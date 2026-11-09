// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"log"
	"runtime/debug"
	"time"

	"tailscale.com/ipn"
)

func (app *App) WatchNotifications(mask int, cb NotificationCallback) NotificationManager {
	app.ready.Wait()

	ctx, cancel := context.WithCancel(context.Background())

	// Suspend lagging watchers instead of disconnecting them
	opts := ipn.NotifyWatchOpt(mask) | ipn.NotifyInitialState | ipn.NotifySuspendWhenBehind
	if err := ipn.ValidateNotifyWatchOpt(opts); err != nil {
		log.Printf("error: WatchNotifications: invalid options: %v", err)
		return &notificationManager{cancel}
	}

	go func() {
		for ctx.Err() == nil {
			var sessionID string

			app.backend.WatchNotifications(ctx, opts, func() {}, func(notify *ipn.Notify) bool {
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
						log.Printf("error: WatchNotifications: resume: %v", err)
						return false
					}
					return true
				}

				b, err := json.Marshal(notify)
				if err != nil {
					log.Printf("error: WatchNotifications: marshal notify: %s", err)
					return true
				}
				if err := cb.OnNotify(b); err != nil {
					log.Printf("error: WatchNotifications: OnNotify: %s", err)
					return true
				}
				return true
			})

			if ctx.Err() != nil {
				return
			}

			log.Printf("WatchNotifications: watch ended unexpectedly; restarting")

			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Second):
			}
		}
	}()

	return &notificationManager{cancel}
}

type notificationManager struct {
	cancel func()
}

func (nm *notificationManager) Stop() {
	nm.cancel()
}
