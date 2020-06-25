# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

DEBUG_APK=tailscale-debug.apk
RELEASE_AAB=tailscale-release.aab
APPID=com.tailscale.ipn
AAR=android/libs/ipn.aar
KEYSTORE=tailscale.jks
KEYSTORE_ALIAS=tailscale
GIT_DESCRIBE=$(shell git describe --tags --long)
VERSION_LONG=$(shell ./mkversion.sh long $(GIT_DESCRIBE))
VERSION_SHORT=$(shell ./mkversion.sh short $(GIT_DESCRIBE))

all: $(APK)

aar:
	mkdir -p android/libs
	go run gioui.org/cmd/gogio -ldflags "-X tailscale.com/version.LONG=$(VERSION_LONG) -X tailscale.com/version.SHORT=$(VERSION_SHORT)" -tags xversion -buildmode archive -target android -appid $(APPID) -o $(AAR) github.com/tailscale/tailscale-android/cmd/tailscale

$(DEBUG_APK): aar
	(cd android && VERSION=$(VERSION_LONG) ./gradlew assembleDebug)
	mv android/build/outputs/apk/debug/android-debug.apk $@
	
$(RELEASE_AAB): aar
	(cd android && VERSION_LONG=$(VERSION_LONG) ./gradlew bundleRelease)
	mv ./android/build/outputs/bundle/release/android-release.aab $@

release: $(RELEASE_AAB)
	jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore $(KEYSTORE) $(RELEASE_AAB) $(KEYSTORE_ALIAS)

install: $(DEBUG_APK)
	adb install -r $(DEBUG_APK)

dockershell:
	docker build -t tailscale-android .
	docker run -v $(CURDIR):/build/tailscale-android -it --rm tailscale-android

clean:
	rm -rf android/build $(RELEASE_AAB) $(DEBUG_APK) $(AAR)

.PHONY: all clean install aar release dockershell
