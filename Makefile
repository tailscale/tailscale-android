# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

DEBUG_APK=tailscale-debug.apk
RELEASE_AAB=tailscale-release.aab
APPID=com.tailscale.ipn
AAR=android/libs/ipn.aar
KEYSTORE=tailscale.jks
KEYSTORE_ALIAS=tailscale
TAILSCALE_VERSION=$(shell ./version/tailscale-version.sh)
TAILSCALE_COMMIT=$(lastword $(subst -g, ,$(TAILSCALE_VERSION)))
GIT_DESCRIBE=$(shell git describe --dirty --exclude "*" --always --abbrev=200)
VERSION_LONG=$(shell ./version/mkversion.sh long $(TAILSCALE_VERSION) $(GIT_DESCRIBE))
VERSION_SHORT=$(shell ./version/mkversion.sh short $(TAILSCALE_VERSION) $(GIT_DESCRIBE))

all: $(APK)

$(DEBUG_APK):
	mkdir -p android/libs
	go run gioui.org/cmd/gogio -buildmode archive -target android -appid $(APPID) -o $(AAR) github.com/tailscale/tailscale-android/cmd/tailscale
	(cd android && ./gradlew assemblePlayDebug)
	mv android/build/outputs/apk/play/debug/android-play-debug.apk $@
	
release_aar:
	mkdir -p android/libs
	go run gioui.org/cmd/gogio -ldflags "-X tailscale.com/version.Long=$(VERSION_LONG) -X tailscale.com/version.Short=$(VERSION_SHORT) -X tailscale.com/version.GitCommit=$(TAILSCALE_COMMIT)" -tags xversion -buildmode archive -target android -appid $(APPID) -o $(AAR) github.com/tailscale/tailscale-android/cmd/tailscale

$(RELEASE_AAB): release_aar
	(cd android && VERSION=$(VERSION_LONG) ./gradlew bundlePlayRelease)
	mv ./android/build/outputs/bundle/playRelease/android-play-release.aab $@

release: $(RELEASE_AAB)
	jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore $(KEYSTORE) $(RELEASE_AAB) $(KEYSTORE_ALIAS)

install: $(DEBUG_APK)
	adb install -r $(DEBUG_APK)

dockershell:
	docker build -t tailscale-android .
	docker run -v $(CURDIR):/build/tailscale-android -it --rm tailscale-android

clean:
	rm -rf android/build $(RELEASE_AAB) $(DEBUG_APK) $(AAR)

.PHONY: all clean install $(DEBUG_APK) $(RELEASE_AAB) release_aar release dockershell
