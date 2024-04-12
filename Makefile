# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

DEBUG_APK=tailscale-debug.apk
RELEASE_AAB=tailscale-release.aab
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
VERSION_LDFLAGS=-X tailscale.com/version.longStamp=$(VERSIONNAME) -X tailscale.com/version.shortStamp=$(VERSIONNAME_SHORT) -X tailscale.com/version.gitCommitStamp=$(TAILSCALE_COMMIT) -X tailscale.com/version.extraGitCommitStamp=$(OUR_VERSION)
FULL_LDFLAGS=$(VERSION_LDFLAGS) -w
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
# If JAVA_HOME is still unset, remove it, because SDK tools go into a CPU spin if it is set and empty.
ifeq ($(JAVA_HOME),)
	unexport JAVA_HOME
endif

# TOOLCHAINDIR is set by fdoid CI and used by tool/* scripts.
TOOLCHAINDIR ?=
export TOOLCHAINDIR

GOBIN ?= $(PWD)/android/build/go/bin
export GOBIN

export PATH := $(PWD)/tool:$(GOBIN):$(JAVA_HOME)/bin:$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$(PATH)
export GOROOT := # Unset

all: tailscale-debug.apk test $(DEBUG_APK) ## Build and test everything

env:
	@echo PATH=$(PATH)
	@echo ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)
	@echo ANDROID_HOME=$(ANDROID_HOME)
	@echo ANDROID_STUDIO_ROOT=$(ANDROID_STUDIO_ROOT)
	@echo JAVA_HOME=$(JAVA_HOME)
	@echo TOOLCHAINDIR=$(TOOLCHAINDIR)

tag_release: ## Tag a release
	sed -i'.bak' 's/versionCode $(VERSIONCODE)/versionCode $(VERSIONCODE_PLUSONE)/' android/build.gradle && rm android/build.gradle.bak
	sed -i'.bak' 's/versionName .*/versionName "$(VERSION_LONG)"/' android/build.gradle && rm android/build.gradle.bak
	git commit -sm "android: bump version code" android/build.gradle
	git tag -a "$(VERSION_LONG)"

bumposs: ## Update the tailscale.com go module
	GOPROXY=direct go get tailscale.com@main
	go run tailscale.com/cmd/printdep --go > go.toolchain.rev
	go mod tidy -compat=1.22

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

androidsdk: $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager ## Install the set of Android SDK packages we need.
	yes | $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --update
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager $(ANDROID_SDK_PACKAGES)

# Normally in make you would simply take a dependency on the task that provides
# the binaries, however users may have a decision to make as to whether they
# want to install an SDK or use the one from an Android Studio installation.
checkandroidsdk: ## Check that Android SDK is installed
	@$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --list_installed | grep -q 'ndk' || (\
		echo -e "\n\tERROR: Android SDK not installed.\n\
		\tANDROID_HOME=$(ANDROID_HOME)\n\
		\tANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)\n\n\
		See README.md for instructions on how to install the prerequisites.\n"; exit 1)

androidpath:
	@echo "export ANDROID_HOME=$(ANDROID_HOME)"
	@echo "export ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)"
	@echo 'export PATH=$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$$PATH'

$(RELEASE_AAB): $(LIBTAILSCALE)
	(cd android && ./gradlew test bundleRelease)
	mv ./android/build/outputs/bundle/release/android-release.aab $@

release: $(RELEASE_AAB) ## Build the release AAB
	jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore $(KEYSTORE) $(RELEASE_AAB) $(KEYSTORE_ALIAS)

apk: $(DEBUG_APK) ## Build the debug APK

LIBTAILSCALE=android/libs/libtailscale.aar
LIBTAILSCALE_SOURCES=$(shell find libtailscale -name *.go) go.mod go.sum

android/libs:
	mkdir -p android/libs

$(GOBIN)/gomobile: $(GOBIN)/gobind go.mod go.sum
	go install golang.org/x/mobile/cmd/gomobile

$(GOBIN)/gobind: go.mod go.sum
	go install golang.org/x/mobile/cmd/gobind

$(LIBTAILSCALE): Makefile android/libs $(LIBTAILSCALE_SOURCES) $(GOBIN)/gomobile
	gomobile bind -target android -androidapi 26 \
		-ldflags "$(FULL_LDFLAGS)" \
		-o $@ ./libtailscale

libtailscale: $(LIBTAILSCALE)

ANDROID_SOURCES=$(shell find android -type f -not -path "android/build/*" -not -path '*/.*')
DEBUG_INTERMEDIARY = android/build/outputs/apk/debug/android-debug.apk

$(DEBUG_INTERMEDIARY): $(ANDROID_SOURCES) $(LIBTAILSCALE)
	cd android && ./gradlew test assembleDebug

$(DEBUG_APK): $(DEBUG_INTERMEDIARY)
	(cd android && ./gradlew test assembleDebug)
	mv $(DEBUG_INTERMEDIARY) $@

tailscale-debug: tailscale-debug.apk ## Build the debug APK

ANDROID_TEST_INTERMEDIARY=./android/build/outputs/apk/androidTest/applicationTest/android-applicationTest-androidTest.apk

$(ANDROID_TEST_INTERMEDIARY): $(ANDROID_SOURCES) $(LIBTAILSCALE)
	cd android && ./gradlew assembleApplicationTestAndroidTest

tailscale-test.apk: $(ANDROID_TEST_INTERMEDIARY)
	mv $(ANDROID_TEST_INTERMEDIARY) $@

test: $(LIBTAILSCALE) ## Run the Android tests
	(cd android && ./gradlew test)

install: tailscale-debug.apk ## Install the debug APK on a connected device
	adb install -r $<

run: install ## Run the debug APK on a connected device
	adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.IPNActivity

dockershell: ## Run a shell in the Docker build container
	docker build -t tailscale-android .
	docker run -v $(CURDIR):/build/tailscale-android -it --rm tailscale-android

clean: ## Remove build artifacts
	-rm -rf android/build $(DEBUG_APK) $(RELEASE_AAB) $(LIBTAILSCALE) android/libs *.apk
	-pkill -f gradle

help: ## Show this help
	@echo "\nSpecify a command. The choices are:\n"
	@grep -hE '^[0-9a-zA-Z_-]+:.*?## .*$$' ${MAKEFILE_LIST} | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[0;36m%-20s\033[m %s\n", $$1, $$2}'
	@echo ""

.PHONY: all clean install android_legacy/lib $(DEBUG_APK) $(RELEASE_AAB) release bump_version dockershell lib tailscale-debug help
.DEFAULT_GOAL := help
