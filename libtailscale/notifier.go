// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"log"
	"runtime/debug"

	"tailscale.com/ipn"
)

func (app *App) WatchNotifications(mask int, cb NotificationCallback) NotificationManager {
	app.ready.Wait()

	ctx, cancel := context.WithCancel(context.Background())

	// Suspend lagging watchers instead of disconnecting them
	mask |= int(ipn.NotifySuspendWhenBehind)

	var sessionID string
	go app.backend.WatchNotifications(ctx, ipn.NotifyWatchOpt(mask), func() {}, func(notify *ipn.Notify) bool {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in WatchNotifications %s: %s", p, debug.Stack())
				panic(p)
			}
		}()

		if notify.SessionID != "" {
			sessionID = notify.SessionID
		}
		if notify.Suspended {
			if sessionID == "" {
				log.Printf("error: WatchNotifications: suspended without session ID")
				return false
			}
			if err := app.backend.ResumeWatchNotifications(ctx, sessionID); err != nil {
				if ctx.Err() != nil {
					return false
				}
				log.Printf("error: WatchNotifications: resume %v", err)
				return false
			}
			return true
		}

		b, err := json.Marshal(notify)
		if err != nil {
			log.Printf("error: WatchNotifications: marshal notify: %s", err)
			return true
		}
		err = cb.OnNotify(b)
		if err != nil {
			log.Printf("error: WatchNotifications: OnNotify: %s", err)
			return true
		}
		return true
	})
	return &notificationManager{cancel}
}

type notificationManager struct {
	cancel func()
}

func (nm *notificationManager) Stop() {
	nm.cancel()
}
