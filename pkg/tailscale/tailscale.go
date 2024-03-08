// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"context"
	"log"
	"net/http"
	"path/filepath"
	"time"
	"unsafe"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
	"tailscale.com/logpolicy"
	"tailscale.com/logtail"
	"tailscale.com/logtail/filch"
	"tailscale.com/net/interfaces"
	"tailscale.com/smallzstd"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/clientmetric"
	"tailscale.com/util/must"
)

import "C"

var (
	// googleClass is a global reference to the com.tailscale.ipn.Google class.
	googleClass jnipkg.Class
)

const defaultMTU = 1280 // minimalMTU from wgengine/userspace.go

const (
	logPrefKey               = "privatelogid"
	loginMethodPrefKey       = "loginmethod"
	customLoginServerPrefKey = "customloginserver"
)

type ConnectEvent struct {
	Enable bool
}

func main() {
	a := &App{
		jvm:    (*jnipkg.JVM)(unsafe.Pointer(javaVM())),
		appCtx: jnipkg.Object(appContext()),
	}

	err := a.loadJNIGlobalClassRefs()
	if err != nil {
		fatalErr(err)
	}

	a.store = newStateStore(a.jvm, a.appCtx)
	interfaces.RegisterInterfaceGetter(a.getInterfaces)
	go func() {
		ctx := context.Background()
		if err := a.runBackend(ctx); err != nil {
			fatalErr(err)
		}
	}()
}

func fatalErr(err error) {
	// TODO: expose in UI.
	log.Printf("fatal error: %v", err)
}

func javaVM() uintptr {
	android.mu.Lock()
	defer android.mu.Unlock()
	return uintptr(unsafe.Pointer(android.jvm))
}

func appContext() uintptr {
	android.mu.Lock()
	defer android.mu.Unlock()
	return uintptr(android.appCtx)
}

// osVersion returns android.os.Build.VERSION.RELEASE. " [nogoogle]" is appended
// if Google Play services are not compiled in.
func (a *App) osVersion() string {
	var version string
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "getOSVersion", "()Ljava/lang/String;")
		n, err := jnipkg.CallObjectMethod(env, a.appCtx, m)
		version = jnipkg.GoString(env, jnipkg.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	return version
}

// modelName return the MANUFACTURER + MODEL from
// android.os.Build.
func (a *App) modelName() string {
	var model string
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "getModelName", "()Ljava/lang/String;")
		n, err := jnipkg.CallObjectMethod(env, a.appCtx, m)
		model = jnipkg.GoString(env, jnipkg.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	return model
}

func (a *App) isChromeOS() bool {
	var chromeOS bool
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "isChromeOS", "()Z")
		b, err := jnipkg.CallBooleanMethod(env, a.appCtx, m)
		chromeOS = b
		return err
	})
	if err != nil {
		panic(err)
	}
	return chromeOS
}

func googleSignInEnabled() bool {
	return googleClass != 0
}

// Loads the global JNI class references.  Failures here are fatal if the
// class ref is required for the app to function.
func (a *App) loadJNIGlobalClassRefs() error {
	return jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		loader := jnipkg.ClassLoaderFor(env, a.appCtx)
		cl, err := jnipkg.LoadClass(env, loader, "com.tailscale.ipn.Google")
		if err != nil {
			// Ignore load errors; the Google class is not included in F-Droid builds.
			return nil
		}
		googleClass = jnipkg.Class(jnipkg.NewGlobalRef(env, jnipkg.Object(cl)))
		return nil
	})
}

// SetupLogs sets up remote logging.
func (b *backend) setupLogs(logDir string, logID logid.PrivateID, logf logger.Logf) {
	if b.netMon == nil {
		panic("netMon must be created prior to SetupLogs")
	}
	transport := logpolicy.NewLogtailTransport(logtail.DefaultHost, b.netMon, log.Printf)

	logcfg := logtail.Config{
		Collection:          logtail.CollectionNode,
		PrivateID:           logID,
		Stderr:              log.Writer(),
		MetricsDelta:        clientmetric.EncodeLogTailMetricsDelta,
		IncludeProcID:       true,
		IncludeProcSequence: true,
		NewZstdEncoder: func() logtail.Encoder {
			return must.Get(smallzstd.NewEncoder(nil))
		},
		HTTPC: &http.Client{Transport: transport},
	}
	logcfg.FlushDelayFn = func() time.Duration { return 2 * time.Minute }

	filchOpts := filch.Options{
		ReplaceStderr: true,
	}

	var filchErr error
	if logDir != "" {
		logPath := filepath.Join(logDir, "ipn.log.")
		logcfg.Buffer, filchErr = filch.New(logPath, filchOpts)
	}

	b.logger = logtail.NewLogger(logcfg, logf)

	log.SetFlags(0)
	log.SetOutput(b.logger)

	log.Printf("goSetupLogs: success")

	if logDir == "" {
		log.Printf("SetupLogs: no logDir, storing logs in memory")
	}
	if filchErr != nil {
		log.Printf("SetupLogs: filch setup failed: %v", filchErr)
	}
}
