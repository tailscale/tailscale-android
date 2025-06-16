// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"log"

	_ "golang.org/x/mobile/bind"
)

// Start starts the application, storing state in the given dataDir and using
// the given appCtx.
func Start(dataDir, directFileRoot string, appCtx AppContext) Application {
	return start(dataDir, directFileRoot, appCtx)
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

	// GetStateStoreKeysJson retrieves all keys stored in the encrypted SharedPreferences,
	// strips off the "statestore-" prefix, and returns them as a JSON array.
	GetStateStoreKeysJSON() string

	// GetOSVersion gets the Android version.
	GetOSVersion() (string, error)

	// GetModelName gets the Android device's model name.
	GetModelName() (string, error)

	// GetInstallSource gets information about how the app was installed or updated.
	GetInstallSource() string

	// ShouldUseGoogleDNSFallback reports whether or not to use Google for DNS fallback.
	ShouldUseGoogleDNSFallback() bool

	// IsChromeOS reports whether we're on a ChromeOS device.
	IsChromeOS() (bool, error)

	// GetInterfacesAsString gets a string representation of all network
	// interfaces.
	GetInterfacesAsString() (string, error)

	// GetPlatformDNSConfig gets a string representation of the current DNS
	// configuration.
	GetPlatformDNSConfig() string

	// GetSyspolicyStringValue returns the current string value for the given system policy.
	GetSyspolicyStringValue(key string) (string, error)

	// GetSyspolicyBooleanValue returns whether the given system policy is enabled.
	GetSyspolicyBooleanValue(key string) (bool, error)

	// GetSyspolicyStringArrayValue returns the current string array value for the given system policy,
	// expressed as a JSON string.
	GetSyspolicyStringArrayJSONValue(key string) (string, error)
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

	Close()

	DisconnectVPN()

	UpdateVpnStatus(bool)
}

// VPNServiceBuilder corresponds to Android's VpnService.Builder.
type VPNServiceBuilder interface {
	SetMTU(int32) error
	AddDNSServer(string) error
	AddSearchDomain(string) error
	AddRoute(string, int32) error
	ExcludeRoute(string, int32) error
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

	// CallLocalAPIMultipart is like CallLocalAPI, but instead of a single body,
	// it accepts multiple FileParts that get encoded as multipart/form-data.
	CallLocalAPIMultipart(timeoutMillis int, method, endpoint string, parts FileParts) (LocalAPIResponse, error)

	// NotifyPolicyChanged notifies the backend about a changed MDM policy,
	// so it can re-read it via the [syspolicyHandler].
	NotifyPolicyChanged()

	// WatchNotifications provides a mechanism for subscribing to ipn.Notify
	// updates. The given NotificationCallback's OnNotify function is invoked
	// on every new ipn.Notify message. The returned NotificationManager
	// allows the watcher to stop watching notifications.
	WatchNotifications(mask int, cb NotificationCallback) NotificationManager
}

// FileParts is an array of multiple FileParts.
type FileParts interface {
	Len() int32
	Get(int32) *FilePart
}

// FilePart is a multipart file that can be submitted via CallLocalAPIMultiPart.
type FilePart struct {
	ContentLength int64
	Filename      string
	Body          InputStream
	ContentType   string // optional MIME content type
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

// OutputStream provides an adapter between Java's OutputStream and Go's
// io.WriteCloser.
type OutputStream interface {
	Write([]byte) (int, error)
	Close() error
}

// ShareFileHelper corresponds to the Kotlin ShareFileHelper class
type ShareFileHelper interface {
	// OpenFileWriter creates or truncates a file named fileName
	// and returns an OutputStream for writing to it from the beginning.
	// Returns nil if the file cannot be opened.
	OpenFileWriter(fileName string) OutputStream

	// OpenFileWriterAt opens fileName for writing at a given offset.
	// Returns nil if the file cannot be opened.
	OpenFileWriterAt(fileName string, offset int64) OutputStream

	// OpenFileURI returns the SAF URI string for the file named fileName,
	// or an empty string if the file cannot be resolved.
	OpenFileURI(fileName string) string

	// RenamePartialFile renames the file at oldPath (a SAF URI)
	// into the directory identified by newPath (a tree URI),
	// giving it the new targetName. Returns the SAF URI of the renamed file,
	// or an empty string if the operation failed.
	RenamePartialFile(oldPath string, newPath string, targetName string) string

	// ListPartialFilesJSON returns a JSON-encoded list of partial filenames
	// (e.g., ["foo.partial", "bar.partial"]) that match the given suffix.
	ListPartialFilesJSON(suffix string) string

	// OpenPartialFileReader opens the file with the given name (typically a .partial file)
	// and returns an InputStream for reading its contents.
	// Returns nil if the file cannot be opened.
	OpenPartialFileReader(name string) InputStream

	// DeleteFile deletes the file identified by the given SAF URI string.
	// Returns an error if the file could not be deleted.
	DeleteFile(uriString string) error

	// TreeURI returns the SAF tree URI representing the root directory for Taildrop files.
	// This is typically the URI granted by the user via the Android directory picker.
	TreeURI() string

	// GetFileInfo returns a JSON-encoded string with file metadata for fileName.
	// Returns an empty string if the file does not exist or cannot be accessed.
	GetFileInfo(fileName string) string
}

// The below are global callbacks that allow the Java application to notify Go
// of various state changes.

func RequestVPN(service IPNService) {
	onVPNRequested <- service
}

func ServiceDisconnect(service IPNService) {
	onDisconnect <- service
}

func SendLog(logstr []byte) {
	select {
	case onLog <- string(logstr):
		// Successfully sent log
	default:
		// Channel is full, log not sent
		log.Printf("Log %v not sent", logstr) // missing argument in original code
	}
}

func SetShareFileHelper(fileHelper ShareFileHelper) {
	// Drain the channel if there's an old value.
	select {
	case <-onShareFileHelper:
	default:
		// Channel was already empty.
	}
	select {
	case onShareFileHelper <- fileHelper:
	default:
		// In the unlikely case the channel is still full, drain it and try again.
		<-onShareFileHelper
		onShareFileHelper <- fileHelper
	}
}

func SetDirectFileRoot(filePath string) {
	onFilePath <- filePath
}
