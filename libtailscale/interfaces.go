// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

// Start starts the application, storing state in the given dataDir and using
// the given appCtx.
func Start(dataDir string, appCtx AppContext) Application {
	return start(dataDir, appCtx)
}

// AppContext provides a context within which the Application is running. This
// context is a hook into functionality that's implemented on the Java side.
type AppContext interface {
	// Log logs the given tag and logLine
	Log(tag, logLine string)

	// EncryptToPref stores the given value to an encrypted preference at the
	// given key.
	EncryptToPref(key, value string) error

	// DecryptFromPref retrieves the given value from an encrypted preference
	// at the given key, or returns empty string if unset.
	DecryptFromPref(key string) (string, error)

	// GetOSVersion gets the Android version.
	GetOSVersion() (string, error)

	// GetModelName gets the Android device's model name.
	GetModelName() (string, error)

	// IsPlayVersion reports whether this is the Google Play version of the app
	// (as opposed to F-droid/sideloaded).
	IsPlayVersion() bool

	// IsChromeOS reports whether we're on a ChromeOS device.
	IsChromeOS() (bool, error)

	// GetInterfacesAsString gets a string representation of all network
	// interfaces.
	GetInterfacesAsString() (string, error)

	// GetPlatformDNSConfig gets a string representation of the current DNS
	// configuration.
	GetPlatformDNSConfig() string
}

// IPNService corresponds to our IPNService in Java.
type IPNService interface {
	// ID returns the unique ID of this instance of the IPNService. Every time
	// we start a new IPN service, it should have a new ID.
	ID() string

	// Protect protects socket identified by the given file descriptor from
	// being captured by the VPN. The return value indicates whether or not the
	// socket was successfully protected.
	Protect(fd int32) bool

	// NewBuilder creates a new VPNServiceBuilder in preparation for starting
	// the Android VPN.
	NewBuilder() VPNServiceBuilder
}

// VPNServiceBuilder corresponds to Android's VpnService.Builder.
type VPNServiceBuilder interface {
	SetMTU(int) error
	AddDNSServer(string) error
	AddSearchDomain(string) error
	AddRoute(string, int32) error
	AddAddress(string, int32) error
	Establish() (ParcelFileDescriptor, error)
}

// ParcelFileDescriptor corresponds to Android's ParcelFileDescriptor.
type ParcelFileDescriptor interface {
	Detach() (int32, error)
}

// Application encapsulates the running Tailscale Application. There is only a
// single instance of Application per Android application.
type Application interface {
	// CallLocalAPI provides a mechanism for calling Tailscale's HTTP localapi
	// without having to call over the network.
	CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error)

	// WatchNotifications provides a mechanism for subscribing to ipn.Notify
	// updates. The given NotificationCallback's OnNotify function is invoked
	// on every new ipn.Notify message. The returned NotificationManager
	// allows the watcher to stop watching notifications.
	WatchNotifications(mask int, cb NotificationCallback) NotificationManager
}

// LocalAPIResponse is a response to a localapi call, analogous to an http.Response.
type LocalAPIResponse interface {
	StatusCode() int
	BodyBytes() ([]byte, error)
	BodyInputStream() InputStream
}

// NotificationCallback is callback for receiving ipn.Notify messages.
type NotificationCallback interface {
	OnNotify([]byte) error
}

// NotificationManager provides a mechanism for a notification watcher to stop
// watching notifications.
type NotificationManager interface {
	Stop()
}

// InputStream provides an adapter between Java's InputStream and Go's
// io.Reader.
type InputStream interface {
	Read() ([]byte, error)
	Close() error
}

// The below are global callbacks that allow the Java application to notify Go
// of various state changes.

func OnVPNPrepared() {
	notifyVPNPrepared()
}

func RequestVPN(service IPNService) {
	onVPNRequested <- service
}

func ServiceDisconnect(service IPNService) {
	onDisconnect <- service
}

func OnActivityResult(reqCode, resCode int, idToken string) {
	switch reqCode {
	case requestSignin:
		if resCode != resultOK {
			onGoogleToken <- ""
			break
		}
		onGoogleToken <- idToken
	case requestPrepareVPN:
		if resCode == resultOK {
			notifyVPNPrepared()
		} else {
			notifyVPNClosed()
			notifyVPNRevoked()
		}
	}
}