// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"log"
	"net/http"
	"path/filepath"
	"runtime/debug"
	"time"

	"tailscale.com/logpolicy"
	"tailscale.com/logtail"
	"tailscale.com/logtail/filch"
	"tailscale.com/net/interfaces"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/clientmetric"
	"tailscale.com/util/syspolicy"
)

const defaultMTU = 1280 // minimalMTU from wgengine/userspace.go

const (
	logPrefKey               = "privatelogid"
	loginMethodPrefKey       = "loginmethod"
	customLoginServerPrefKey = "customloginserver"
)

func newApp(dataDir, directFileRoot string, appCtx AppContext) Application {
	a := &App{
		directFileRoot: directFileRoot,
		dataDir:        dataDir,
		appCtx:         appCtx,
	}
	a.ready.Add(1)

	a.store = newStateStore(a.appCtx)
	interfaces.RegisterInterfaceGetter(a.getInterfaces)
	syspolicy.RegisterHandler(androidHandler{a: a})
	go func() {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in runBackend %s: %s", p, debug.Stack())
				panic(p)
			}
		}()

		ctx := context.Background()
		if err := a.runBackend(ctx); err != nil {
			fatalErr(err)
		}
	}()

	return a
}

func fatalErr(err error) {
	// TODO: expose in UI.
	log.Printf("fatal error: %v", err)
}

// osVersion returns android.os.Build.VERSION.RELEASE. " [nogoogle]" is appended
// if Google Play services are not compiled in.
func (a *App) osVersion() string {
	version, err := a.appCtx.GetOSVersion()
	if err != nil {
		panic(err)
	}
	return version
}

// modelName return the MANUFACTURER + MODEL from
// android.os.Build.
func (a *App) modelName() string {
	model, err := a.appCtx.GetModelName()
	if err != nil {
		panic(err)
	}
	return model
}

func (a *App) isChromeOS() bool {
	isChromeOS, err := a.appCtx.IsChromeOS()
	if err != nil {
		panic(err)
	}
	return isChromeOS
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
		HTTPC:               &http.Client{Transport: transport},
		CompressLogs:        true,
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
