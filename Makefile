# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

DEBUG_APK=tailscale-debug.apk
RELEASE_AAB=tailscale-release.aab
APPID=com.tailscale.ipn
AAR=android/libs/ipn.aar
KEYSTORE=tailscale.jks
KEYSTORE_ALIAS=tailscale
TAILSCALE_VERSION=$(shell ./version/tailscale-version.sh 200)
OUR_VERSION=$(shell git describe --dirty --exclude "*" --always --abbrev=200)
TAILSCALE_VERSION_ABBREV=$(shell ./version/tailscale-version.sh 11)
OUR_VERSION_ABBREV=$(shell git describe --dirty --exclude "*" --always --abbrev=11)
VERSION_LONG=$(TAILSCALE_VERSION_ABBREV)-g$(OUR_VERSION_ABBREV)
# Extract the long version build.gradle's versionName and strip quotes.
VERSIONNAME=$(patsubst "%",%,$(lastword $(shell grep versionName android/build.gradle)))
# Extract the x.y.z part for the short version.
VERSIONNAME_SHORT=$(shell echo $(VERSIONNAME) | cut -d - -f 1)
TAILSCALE_COMMIT=$(shell echo $(TAILSCALE_VERSION) | cut -d - -f 2 | cut -d t -f 2)
# Extract the version code from build.gradle.
VERSIONCODE=$(lastword $(shell grep versionCode android/build.gradle))
VERSIONCODE_PLUSONE=$(shell expr $(VERSIONCODE) + 1)
ifeq ($(shell uname),Linux)
	ANDROID_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"
	ANDROID_TOOLS_SUM="bd1aa17c7ef10066949c88dc6c9c8d536be27f992a1f3b5a584f9bd2ba5646a0  commandlinetools-linux-9477386_latest.zip"
else
	ANDROID_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-9477386_latest.zip"
	ANDROID_TOOLS_SUM="2072ffce4f54cdc0e6d2074d2f381e7e579b7d63e915c220b96a7db95b2900ee  commandlinetools-mac-9477386_latest.zip"
endif
ANDROID_SDK_PACKAGES='platforms;android-31' 'extras;android;m2repository' 'ndk;23.1.7779620' 'platform-tools' 'build-tools;33.0.2'

# Attempt to find an ANDROID_SDK_ROOT / ANDROID_HOME based either from
# preexisting environment or common locations.
export ANDROID_SDK_ROOT ?= $(shell find $$ANDROID_SDK_ROOT $$ANDROID_HOME $$HOME/Library/Android/sdk $$HOME/Android/Sdk $$HOME/AppData/Local/Android/Sdk /usr/lib/android-sdk -maxdepth 1 -type d 2>/dev/null | head -n 1)

# If ANDROID_SDK_ROOT is still unset, set it to a default location by platform.
ifeq ($(ANDROID_SDK_ROOT),)
	ifeq ($(shell uname),Linux)
		export ANDROID_SDK_ROOT=$(HOME)/Android/Sdk
	else ifeq ($(shell uname),Darwin)
		export ANDROID_SDK_ROOT=$(HOME)/Library/Android/sdk
	else ifneq ($(WINDIR),))
		export ANDROID_SDK_ROOT=$(HOME)/AppData/Local/Android/sdk
	else
		export ANDROID_SDK_ROOT=$(PWD)/android-sdk
	endif
endif
export ANDROID_HOME ?= $(ANDROID_SDK_ROOT)

# Attempt to find Android Studio for Linux configuration, which does not have a
# predetermined location.
ANDROID_STUDIO_ROOT ?= $(shell find ~/android-studio /usr/local/android-studio /opt/android-studio /Applications/Android\ Studio.app $(PROGRAMFILES)/Android/Android\ Studio -type d -maxdepth 1 2>/dev/null | head -n 1)

# Set JAVA_HOME to the Android Studio bundled JDK.
export JAVA_HOME ?= $(shell find "$(ANDROID_STUDIO_ROOT)/jbr" "$(ANDROID_STUDIO_ROOT)/jre" "$(ANDROID_STUDIO_ROOT)/Contents/jbr/Contents/Home" "$(ANDROID_STUDIO_ROOT)/Contents/jre/Contents/Home" -maxdepth 1 -type d 2>/dev/null | head -n 1)

# Go toolchain path, by default pulled from Tailscale prebuilts pinned to the
# version in tailscale.com/cmd/printdep.
TOOLCHAINDIR ?= ${HOME}/.cache/tailscale-android-go-$(shell go run tailscale.com/cmd/printdep --go)

export PATH := $(TOOLCHAINDIR)/bin:$(JAVA_HOME)/bin:$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$(PATH)
export GOROOT := # Unset

all: $(DEBUG_APK) tailscale-fdroid.apk

env:
	@echo PATH=$(PATH)
	@echo ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)
	@echo ANDROID_HOME=$(ANDROID_HOME)
	@echo ANDROID_STUDIO_ROOT=$(ANDROID_STUDIO_ROOT)
	@echo JAVA_HOME=$(JAVA_HOME)
	@echo TOOLCHAINDIR=$(TOOLCHAINDIR)

tag_release:
	sed -i'.bak' 's/versionCode $(VERSIONCODE)/versionCode $(VERSIONCODE_PLUSONE)/' android/build.gradle && rm android/build.gradle.bak
	sed -i'.bak' 's/versionName .*/versionName "$(VERSION_LONG)"/' android/build.gradle && rm android/build.gradle.bak
	git commit -sm "android: bump version code" android/build.gradle
	git tag -a "$(VERSION_LONG)"

bumposs: toolchain
	GOPROXY=direct go get tailscale.com@main
	go mod tidy -compat=1.21

$(TOOLCHAINDIR)/bin/go:
	@if ! echo $(TOOLCHAINDIR) | grep -q 'tailscale-android-go'; then \
		echo "ERROR: TOOLCHAINDIR=$(TOOLCHAINDIR) is missing bin/go and does not appear to be a tailscale managed path"; \
		exit 1; \
	fi
	rm -rf ${HOME}/.cache/tailscale-android-go-*
	mkdir -p $(TOOLCHAINDIR)
	curl --silent -L $(shell go run tailscale.com/cmd/printdep --go-url) | tar --strip-components=1 -C $(TOOLCHAINDIR) -zx

# Get the commandline tools package, this provides (among other things) the sdkmanager binary.
$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager:
	mkdir -p $(ANDROID_HOME)/tmp
	mkdir -p $(ANDROID_HOME)/cmdline-tools
	(cd $(ANDROID_HOME)/tmp && \
		curl --silent -O -L $(ANDROID_TOOLS_URL) && \
		echo $(ANDROID_TOOLS_SUM) | sha256sum -c && \
		unzip $(shell basename $(ANDROID_TOOLS_URL)))
	mv $(ANDROID_HOME)/tmp/cmdline-tools $(ANDROID_HOME)/cmdline-tools/latest
	rm -rf $(ANDROID_HOME)/tmp

# Install the set of Android SDK packages we need.
androidsdk: $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager
	yes | $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --update
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager $(ANDROID_SDK_PACKAGES)

# Normally in make you would simply take a dependency on the task that provides
# the binaries, however users may have a decision to make as to whether they
# want to install an SDK or use the one from an Android Studio installation.
checkandroidsdk:
	@$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --list_installed | grep -q 'ndk' || (\
		echo -e "\n\tERROR: Android SDK not installed.\n\
		\tANDROID_HOME=$(ANDROID_HOME)\n\
		\tANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)\n\n\
		See README.md for instructions on how to install the prerequisites.\n"; exit 1)

androidpath:
	@echo "export ANDROID_HOME=$(ANDROID_HOME)"
	@echo "export ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)"
	@echo 'export PATH=$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$$PATH'

toolchain: $(TOOLCHAINDIR)/bin/go

android/libs:
	mkdir -p android/libs

$(AAR): toolchain checkandroidsdk android/libs
	go run gioui.org/cmd/gogio \
		-ldflags "-X tailscale.com/version.longStamp=$(VERSIONNAME) -X tailscale.com/version.shortStamp=$(VERSIONNAME_SHORT) -X tailscale.com/version.gitCommitStamp=$(TAILSCALE_COMMIT) -X tailscale.com/version.extraGitCommitStamp=$(OUR_VERSION)" \
		-buildmode archive -target android -appid $(APPID) -tags novulkan,tailscale_go -o $@ github.com/tailscale/tailscale-android/cmd/tailscale

# tailscale-debug.apk builds a debuggable APK with the Google Play SDK.
$(DEBUG_APK): $(AAR)
	(cd android && ./gradlew test assemblePlayDebug)
	mv android/build/outputs/apk/play/debug/android-play-debug.apk $@

apk: $(DEBUG_APK)

run: install
	adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.IPNActivity

# tailscale-fdroid.apk builds a non-Google Play SDK, without the Google bits.
# This is effectively what the F-Droid build definition produces.
# This is useful for testing on e.g. Amazon Fire Stick devices.
tailscale-fdroid.apk: $(AAR)
	(cd android && ./gradlew test assembleFdroidDebug)
	mv android/build/outputs/apk/fdroid/debug/android-fdroid-debug.apk $@

$(RELEASE_AAB): $(AAR)
	(cd android && ./gradlew test bundlePlayRelease)
	mv ./android/build/outputs/bundle/playRelease/android-play-release.aab $@

release: $(RELEASE_AAB)
	jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore $(KEYSTORE) $(RELEASE_AAB) $(KEYSTORE_ALIAS)

install: $(DEBUG_APK)
	adb install -r $(DEBUG_APK)

dockershell:
	docker build -t tailscale-android .
	docker run -v $(CURDIR):/build/tailscale-android -it --rm tailscale-android

clean:
	-rm -rf android/build $(DEBUG_APK) $(RELEASE_AAB) $(AAR) tailscale-fdroid.apk
	-pkill -f gradle

.PHONY: all clean install android/lib $(DEBUG_APK) $(RELEASE_AAB) $(AAR) release bump_version dockershell
