// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"context"
	"crypto/tls"
	_ "embed"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"tailscale.com/derp/derpserver"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/stun/stuntest"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/types/nettype"
)

//go:embed testdata/NetprobeReceiver.kt
var netprobeReceiverKotlin []byte

var (
	adbPath       = flag.String("android.adb", "adb", "path to adb")
	apkPath       = flag.String("android.apk", os.Getenv("TAILSCALE_ANDROID_APK"), "path to Tailscale Android APK")
	adbSerial     = flag.String("android.serial", os.Getenv("ANDROID_SERIAL"), "adb device serial")
	androidGOARCH = flag.String("android.goarch", envDefault("TAILSCALE_ANDROID_GOARCH", runtime.GOARCH), "GOARCH for adb-pushed Android test binaries")
	emulatorHost  = flag.String("android.emulator-host", "10.0.2.2", "host address reachable from the Android emulator")
	derpHost      = flag.String("android.derp-host", os.Getenv("TAILSCALE_ANDROID_DERP_HOST"), "DERP/STUN host reachable from both Android and the in-test tsnet peer")
	waitTimeout   = flag.Duration("android.wait", 2*time.Minute, "time to wait for Android to register with testcontrol")
)

const (
	androidPackage    = "com.tailscale.ipn"
	probePackage      = "com.tailscale.ipn.integrationprobe"
	probeReceiver     = probePackage + "/.NetprobeReceiver"
	loginAction       = "com.tailscale.ipn.integration.LOGIN"
	authKey           = "tskey-integration-android"
	debugKeystorePass = "android"
)

func TestAndroidAuthKeyLogin(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("Android integration test currently only runs on Linux")
	}
	if *apkPath == "" {
		t.Skip("set --android.apk or TAILSCALE_ANDROID_APK to run")
	}
	if _, err := exec.LookPath(*adbPath); err != nil {
		t.Skipf("adb not found: %v", err)
	}

	advertisedDERPHost := *derpHost
	if advertisedDERPHost == "" {
		advertisedDERPHost = nonLoopbackIPv4(t)
	}
	t.Logf("test DERP/STUN host: %s", advertisedDERPHost)
	derpMap := runDERPAndSTUN(t, advertisedDERPHost)
	control := &testcontrol.Server{
		Logf:           logger.WithPrefix(t.Logf, "testcontrol: "),
		DERPMap:        derpMap,
		RequireAuthKey: authKey,
		AllOnline:      true,
	}
	controlServer := httptest.NewServer(control)
	t.Cleanup(controlServer.Close)

	controlURL := rewriteHost(t, controlServer.URL, *emulatorHost)
	control.ExplicitBaseURL = controlURL
	t.Logf("test control URL for Android: %s", controlURL)

	const wantBody = "hello-from-tsnet-android-integration"
	peer := &tsnet.Server{
		Dir:        t.TempDir(),
		ControlURL: controlServer.URL,
		AuthKey:    authKey,
		Hostname:   "tsnetpeer",
		Ephemeral:  true,
		Logf:       logger.WithPrefix(t.Logf, "tsnet: "),
		Store:      new(mem.Store),
	}
	t.Cleanup(func() { peer.Close() })

	upCtx, cancelUp := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelUp()
	status, err := peer.Up(upCtx)
	if err != nil {
		t.Fatalf("tsnet peer Up: %v", err)
	}
	if len(status.TailscaleIPs) == 0 {
		t.Fatalf("tsnet peer has no TailscaleIPs")
	}
	peerIP := status.TailscaleIPs[0]
	t.Logf("tsnet peer up at %v", peerIP)

	peerLn, err := peer.Listen("tcp", ":80")
	if err != nil {
		t.Fatalf("tsnet peer Listen :80: %v", err)
	}
	t.Cleanup(func() { peerLn.Close() })
	go http.Serve(peerLn, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, wantBody)
	}))

	adb := adbRunner{path: *adbPath, serial: *adbSerial}
	adb.run(t, "wait-for-device")
	adb.run(t, "install", "-r", "-t", *apkPath)
	probePath := buildAndroidProbe(t)
	probeAPKPath := buildProbeAPK(t, probePath)
	adb.run(t, "install", "-r", "-t", probeAPKPath)
	t.Cleanup(func() { adb.runAllowError(t, "uninstall", probePackage) })
	t.Cleanup(func() { adb.runAllowError(t, "shell", "am", "force-stop", androidPackage) })
	adb.run(t, "shell", "appops", "set", androidPackage, "ACTIVATE_VPN", "allow")
	adb.run(t, "shell", "am", "force-stop", androidPackage)
	adb.run(t, "shell", "pm", "clear", androidPackage)

	adb.run(t,
		"shell", "am", "broadcast",
		"-a", loginAction,
		"-n", androidPackage+"/.IPNReceiver",
		"--include-stopped-packages",
		"--es", "control_url", controlURL,
		"--es", "auth_key", authKey,
	)

	waitForNodeCount(t, control, 2)

	probeResultPath := "files/result"
	adb.run(t, "shell", "run-as", probePackage, "mkdir", "-p", "files")

	peerURL := fmt.Sprintf("http://%s/", peerIP)
	t.Logf("running Android netprobe against tsnet peer URL %s", peerURL)
	adb.runAllowError(t, "shell", "run-as", probePackage, "rm", "-f", probeResultPath)
	adb.run(t,
		"shell", "am", "broadcast",
		"-n", probeReceiver,
		"--es", "url", peerURL,
	)
	got := waitForProbeResult(t, adb, probeResultPath)
	if !strings.Contains(got, "exit=0\n") {
		t.Fatalf("Android probe failed: %q", got)
	}
	if !strings.Contains(got, "status=200\n") {
		t.Fatalf("Android probe status is not OK: %q", got)
	}
	if !strings.Contains(got, "body="+wantBody+"\n") {
		t.Fatalf("Android probe body does not contain %q: %q", wantBody, got)
	}
}

func buildAndroidProbe(t *testing.T) string {
	t.Helper()
	out := t.TempDir() + "/tailscale-android-netprobe"
	root := repoRoot(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, root+"/tool/go", "build", "-buildvcs=false", "-o", out, "./integration/androidvmtest/testnetprobe")
	cmd.Dir = root
	cmd.Env = append(os.Environ(),
		"GOOS=android",
		"GOARCH="+*androidGOARCH,
		"CGO_ENABLED=1",
		"CC="+androidClang(t, *androidGOARCH),
	)
	buildOut, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("building Android netprobe: %v\n%s", err, buildOut)
	}
	return out
}

func buildProbeAPK(t *testing.T, probePath string) string {
	t.Helper()
	root := repoRoot(t)
	dir := t.TempDir()
	manifest := filepath.Join(dir, "AndroidManifest.xml")
	if err := os.WriteFile(manifest, []byte(`<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tailscale.ipn.integrationprobe">
    <uses-permission android:name="android.permission.INTERNET"/>
    <application android:debuggable="true" android:extractNativeLibs="true" android:label="Tailscale Integration Probe">
        <receiver android:name=".NetprobeReceiver" android:exported="true"/>
    </application>
</manifest>
`), 0644); err != nil {
		t.Fatal(err)
	}
	unsignedAPK := filepath.Join(dir, "probe-unsigned.apk")
	alignedAPK := filepath.Join(dir, "probe-aligned.apk")
	signedAPK := filepath.Join(dir, "probe.apk")
	keystore := filepath.Join(dir, "debug.keystore")
	kotlinSrcDir := filepath.Join(dir, "kotlin", "com", "tailscale", "ipn", "integrationprobe")
	classesDir := filepath.Join(dir, "classes")
	dexDir := filepath.Join(dir, "dex")
	libDir := filepath.Join(dir, "apkroot", "lib", androidABI(t, *androidGOARCH))
	if err := os.MkdirAll(kotlinSrcDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(classesDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dexDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(libDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := copyFile(filepath.Join(libDir, "libnetprobe.so"), probePath, 0755); err != nil {
		t.Fatal(err)
	}
	kotlinSrc := filepath.Join(kotlinSrcDir, "NetprobeReceiver.kt")
	if err := os.WriteFile(kotlinSrc, netprobeReceiverKotlin, 0644); err != nil {
		t.Fatal(err)
	}

	runCmd(t, root, "keytool",
		"-genkeypair",
		"-keystore", keystore,
		"-storepass", debugKeystorePass,
		"-keypass", debugKeystorePass,
		"-storetype", "PKCS12",
		"-alias", "androiddebugkey",
		"-keyalg", "RSA",
		"-keysize", "2048",
		"-validity", "10000",
		"-dname", "CN=Android Debug,O=Android,C=US",
	)
	runCmd(t, root, androidBuildTool(t, "aapt2"),
		"link",
		"--manifest", manifest,
		"-I", androidJar(t),
		"--rename-manifest-package", probePackage,
		"--min-sdk-version", "24",
		"--target-sdk-version", "34",
		"-o", unsignedAPK,
	)
	runCmd(t, root, kotlinCompiler(t),
		"-jvm-target", "1.8",
		"-no-reflect",
		"-classpath", androidJar(t),
		"-d", classesDir,
		kotlinSrc,
	)
	d8Inputs := append(classFiles(t, classesDir), kotlinStdlibJar(t))
	d8Args := append([]string{"--min-api", "24", "--lib", androidJar(t), "--output", dexDir}, d8Inputs...)
	runCmd(t, root, androidBuildTool(t, "d8"), d8Args...)
	runCmd(t, root, "zip", "-j", unsignedAPK, filepath.Join(dexDir, "classes.dex"))
	runCmd(t, filepath.Join(dir, "apkroot"), "zip", "-r", unsignedAPK, "lib")
	runCmd(t, root, androidBuildTool(t, "zipalign"), "-f", "4", unsignedAPK, alignedAPK)
	runCmd(t, root, androidBuildTool(t, "apksigner"),
		"sign",
		"--ks", keystore,
		"--ks-pass", "pass:"+debugKeystorePass,
		"--key-pass", "pass:"+debugKeystorePass,
		"--out", signedAPK,
		alignedAPK,
	)
	return signedAPK
}

func runCmd(t *testing.T, dir, name string, args ...string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("%s %v failed: %v\n%s", name, args, err, out)
	}
}

func waitForProbeResult(t *testing.T, adb adbRunner, resultPath string) string {
	t.Helper()
	deadline := time.Now().Add(*waitTimeout)
	for time.Now().Before(deadline) {
		got, err := adb.runOutputAllowError("shell", "run-as", probePackage, "cat", resultPath)
		if err == nil && got != "" {
			t.Logf("Android probe result:\n%s", got)
			return got
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("timed out after %v waiting for Android probe result", *waitTimeout)
	return ""
}

func classFiles(t *testing.T, root string) []string {
	t.Helper()
	var files []string
	if err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && strings.HasSuffix(path, ".class") {
			files = append(files, path)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Fatalf("no .class files found under %s", root)
	}
	return files
}

func copyFile(dst, src string, perm os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, perm)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func androidABI(t *testing.T, goarch string) string {
	t.Helper()
	switch goarch {
	case "amd64":
		return "x86_64"
	case "arm64":
		return "arm64-v8a"
	case "386":
		return "x86"
	case "arm":
		return "armeabi-v7a"
	default:
		t.Fatalf("unsupported Android GOARCH %q", goarch)
		return ""
	}
}

func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		if _, err := os.Stat(dir + "/tool/go"); err == nil {
			if _, err := os.Stat(dir + "/go.mod"); err == nil {
				return dir
			}
		}
		parent := dir[:strings.LastIndex(dir, "/")]
		if parent == "" || parent == dir {
			t.Fatal("could not find repo root containing tool/go and go.mod")
		}
		dir = parent
	}
}

func androidClang(t *testing.T, goarch string) string {
	t.Helper()
	if cc := os.Getenv("ANDROID_CC"); cc != "" {
		return cc
	}
	var triple string
	switch goarch {
	case "amd64":
		triple = "x86_64-linux-android"
	case "arm64":
		triple = "aarch64-linux-android"
	case "386":
		triple = "i686-linux-android"
	case "arm":
		triple = "armv7a-linux-androideabi"
	default:
		t.Fatalf("unsupported Android GOARCH %q", goarch)
	}
	androidHome := androidHome(t)
	ndkRoot := os.Getenv("ANDROID_NDK_HOME")
	if ndkRoot == "" {
		entries, err := os.ReadDir(androidHome + "/ndk")
		if err != nil {
			t.Fatalf("finding Android NDK under %s/ndk: %v", androidHome, err)
		}
		for i := len(entries) - 1; i >= 0; i-- {
			if entries[i].IsDir() {
				ndkRoot = androidHome + "/ndk/" + entries[i].Name()
				break
			}
		}
	}
	if ndkRoot == "" {
		t.Fatalf("no Android NDK found under %s/ndk", androidHome)
	}
	cc := ndkRoot + "/toolchains/llvm/prebuilt/linux-x86_64/bin/" + triple + "24-clang"
	if _, err := os.Stat(cc); err != nil {
		t.Fatalf("Android clang not found at %s: %v", cc, err)
	}
	return cc
}

func androidJar(t *testing.T) string {
	t.Helper()
	return androidHome(t) + "/platforms/android-34/android.jar"
}

func androidBuildTool(t *testing.T, name string) string {
	t.Helper()
	path := androidHome(t) + "/build-tools/34.0.0/" + name
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("Android build tool %s not found at %s: %v", name, path, err)
	}
	return path
}

func kotlinCompiler(t *testing.T) string {
	t.Helper()
	if p := os.Getenv("KOTLINC"); p != "" {
		if _, err := os.Stat(p); err != nil {
			t.Fatalf("KOTLINC points to %s: %v", p, err)
		}
		return p
	}
	p, err := exec.LookPath("kotlinc")
	if err != nil {
		t.Fatal("kotlinc not found; set KOTLINC or put kotlinc on PATH")
	}
	return p
}

func kotlinStdlibJar(t *testing.T) string {
	t.Helper()
	if p := os.Getenv("KOTLIN_STDLIB_JAR"); p != "" {
		if _, err := os.Stat(p); err != nil {
			t.Fatalf("KOTLIN_STDLIB_JAR points to %s: %v", p, err)
		}
		return p
	}
	if home := os.Getenv("KOTLIN_HOME"); home != "" {
		p := filepath.Join(home, "lib", "kotlin-stdlib.jar")
		if _, err := os.Stat(p); err != nil {
			t.Fatalf("KOTLIN_HOME does not contain lib/kotlin-stdlib.jar at %s: %v", p, err)
		}
		return p
	}
	kotlinc, err := filepath.EvalSymlinks(kotlinCompiler(t))
	if err != nil {
		t.Fatalf("resolving kotlinc path: %v", err)
	}
	p := filepath.Join(filepath.Dir(filepath.Dir(kotlinc)), "lib", "kotlin-stdlib.jar")
	if _, err := os.Stat(p); err != nil {
		t.Fatalf("could not infer Kotlin stdlib path from kotlinc at %s; set KOTLIN_HOME or KOTLIN_STDLIB_JAR: %v", kotlinc, err)
	}
	return p
}

func androidHome(t *testing.T) string {
	t.Helper()
	androidHome := os.Getenv("ANDROID_HOME")
	if androidHome == "" {
		androidHome = os.Getenv("ANDROID_SDK_ROOT")
	}
	if androidHome == "" {
		t.Fatal("ANDROID_HOME or ANDROID_SDK_ROOT must be set")
	}
	return androidHome
}

func waitForNodeCount(t *testing.T, control *testcontrol.Server, want int) {
	t.Helper()
	deadline := time.Now().Add(*waitTimeout)
	for time.Now().Before(deadline) {
		if nodes := control.AllNodes(); len(nodes) == want {
			var names []string
			for _, node := range nodes {
				names = append(names, node.ComputedName)
			}
			t.Logf("registered nodes: %v", names)
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("timed out after %v waiting for %d nodes; testcontrol has %d nodes", *waitTimeout, want, control.NumNodes())
}

type adbRunner struct {
	path   string
	serial string
}

func (a adbRunner) args(args ...string) []string {
	if a.serial == "" {
		return args
	}
	return append([]string{"-s", a.serial}, args...)
}

func (a adbRunner) run(t *testing.T, args ...string) {
	t.Helper()
	out := a.runOutput(t, args...)
	t.Logf("adb %v:\n%s", args, out)
}

func (a adbRunner) runOutput(t *testing.T, args ...string) string {
	t.Helper()
	out, err := a.runOutputAllowError(args...)
	if err != nil {
		t.Fatalf("adb %v failed: %v\n%s", args, err, out)
	}
	return out
}

func (a adbRunner) runOutputAllowError(args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	out, err := exec.CommandContext(ctx, a.path, a.args(args...)...).CombinedOutput()
	return string(out), err
}

func (a adbRunner) runAllowError(t *testing.T, args ...string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, a.path, a.args(args...)...).CombinedOutput()
	if err != nil {
		t.Logf("adb %v failed during cleanup: %v\n%s", args, err, out)
	}
}

func rewriteHost(t *testing.T, rawURL, host string) string {
	t.Helper()
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatal(err)
	}
	_, port, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatal(err)
	}
	u.Host = net.JoinHostPort(host, port)
	return u.String()
}

func nonLoopbackIPv4(t *testing.T) string {
	t.Helper()
	ifaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			t.Fatal(err)
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip4 := ip.To4(); ip4 != nil {
				return ip4.String()
			}
		}
	}
	t.Fatal("no non-loopback IPv4 address found; set -android.derp-host")
	return ""
}

func runDERPAndSTUN(t testing.TB, advertisedHost string) *tailcfg.DERPMap {
	t.Helper()

	d := derpserver.New(key.NewNode(), logger.WithPrefix(t.Logf, "derp: "))
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}

	handler := derpserver.AddWebSocketSupport(d, derpserver.Handler(d))
	httpsrv := httptest.NewUnstartedServer(handler)
	if err := httpsrv.Listener.Close(); err != nil {
		t.Fatal(err)
	}
	httpsrv.Listener = ln
	httpsrv.Config.TLSNextProto = make(map[string]func(*http.Server, *tls.Conn, http.Handler))
	httpsrv.StartTLS()

	stunAddr, stunCleanup := stuntest.ServeWithPacketListener(t, nettype.Std{})

	t.Cleanup(func() {
		httpsrv.CloseClientConnections()
		httpsrv.Close()
		d.Close()
		stunCleanup()
	})

	return &tailcfg.DERPMap{
		Regions: map[int]*tailcfg.DERPRegion{
			1: {
				RegionID:   1,
				RegionCode: "test",
				Nodes: []*tailcfg.DERPNode{
					{
						Name:             "t1",
						RegionID:         1,
						HostName:         advertisedHost,
						IPv4:             advertisedHost,
						IPv6:             "none",
						STUNPort:         stunAddr.Port,
						DERPPort:         httpsrv.Listener.Addr().(*net.TCPAddr).Port,
						InsecureForTests: true,
						STUNTestIP:       advertisedHost,
					},
				},
			},
		},
	}
}

func TestRewriteHost(t *testing.T) {
	got := rewriteHost(t, "http://127.0.0.1:12345", "10.0.2.2")
	if want := "http://10.0.2.2:12345"; got != want {
		t.Fatalf("rewriteHost = %q; want %q", got, want)
	}
}

func envDefault(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}
