# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

## For signed release build JKS_PASSWORD must be set to the password for the jks keystore
## and JKS_PATH must be set to the path to the jks keystore.

# The docker image to use for the build environment.  Changing this
# will force a rebuild of the docker image.  If there is an existing image
# with this name, it will be used.
DOCKER_IMAGE=tailscale-android-build-amd64-go1.23
export TS_USE_TOOLCHAIN=1

DEBUG_APK=tailscale-debug.apk
RELEASE_AAB=tailscale-release.aab
RELEASE_TV_AAB=tailscale-tv-release.aab
LIBTAILSCALE=android/libs/libtailscale.aar
TAILSCALE_VERSION=$(shell ./version/tailscale-version.sh 200)
OUR_VERSION=$(shell git describe --dirty --exclude "*" --always --abbrev=200)
TAILSCALE_VERSION_ABBREV=$(shell ./version/tailscale-version.sh 11)
OUR_VERSION_ABBREV=$(shell git describe --exclude "*" --always --abbrev=11)
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
else
	export PATH := $(JAVA_HOME)/bin:$(PATH)
endif

# TOOLCHAINDIR is set by fdoid CI and used by tool/* scripts.
TOOLCHAINDIR ?=
export TOOLCHAINDIR

GOBIN ?= $(PWD)/android/build/go/bin
export GOBIN

export PATH := $(PWD)/tool:$(GOBIN):$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$(PATH)
export GOROOT := # Unset

#
# Android Builds:
#

.PHONY: apk
apk: $(DEBUG_APK) ## Build the debug APK

.PHONY: tailscale-debug
tailscale-debug: $(DEBUG_APK) ## Build the debug APK

# Builds the release AAB and signs it (phone/tablet/chromeOS variant)
.PHONY: release
release: update-version jarsign-env $(RELEASE_AAB) ## Build the release AAB
	@jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore $(JKS_PATH) -storepass $(JKS_PASSWORD) $(RELEASE_AAB) tailscale

# Builds the release AAB and signs it (androidTV variant)
.PHONY: release-tv
release-tv: update-version jarsign-env $(RELEASE_TV_AAB) ## Build the release AAB
	@jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore $(JKS_PATH) -storepass $(JKS_PASSWORD) $(RELEASE_TV_AAB) tailscale

# gradle-dependencies groups together the android sources and libtailscale needed to assemble tests/debug/release builds.
.PHONY: gradle-dependencies
gradle-dependencies: $(shell find android -type f -not -path "android/build/*" -not -path '*/.*') $(LIBTAILSCALE)

$(DEBUG_APK): gradle-dependencies
	(cd android && ./gradlew test assembleDebug)
	install -C android/build/outputs/apk/debug/android-debug.apk $@

$(RELEASE_AAB): gradle-dependencies
	@echo "Building release AAB"
	(cd android && ./gradlew test bundleRelease)
	install -C ./android/build/outputs/bundle/release/android-release.aab $@

$(RELEASE_TV_AAB): gradle-dependencies
	@echo "Building TV release AAB"
	(cd android && ./gradlew test bundleRelease_tv)
	install -C ./android/build/outputs/bundle/release_tv/android-release_tv.aab $@

tailscale-test.apk: gradle-dependencies
	(cd android && ./gradlew assembleApplicationTestAndroidTest)
	install -C ./android/build/outputs/apk/androidTest/applicationTest/android-applicationTest-androidTest.apk $@

#
# Go Builds:
#

android/libs:
	mkdir -p android/libs

$(GOBIN)/gomobile: $(GOBIN)/gobind go.mod go.sum
	go install golang.org/x/mobile/cmd/gomobile

$(GOBIN)/gobind: go.mod go.sum
	go install golang.org/x/mobile/cmd/gobind

$(LIBTAILSCALE): Makefile android/libs $(shell find libtailscale -name *.go) go.mod go.sum $(GOBIN)/gomobile
	gomobile bind -target android -androidapi 26 \
		-ldflags "$(FULL_LDFLAGS)" \
		-o $@ ./libtailscale

.PHONY: libtailscale
libtailscale: $(LIBTAILSCALE) ## Build the libtailscale AAR

#
# Utility tasks:
#

.PHONY: all
all: test $(DEBUG_APK) ## Build and test everything

.PHONY: env
env:
	@echo PATH=$(PATH)
	@echo ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)
	@echo ANDROID_HOME=$(ANDROID_HOME)
	@echo ANDROID_STUDIO_ROOT=$(ANDROID_STUDIO_ROOT)
	@echo JAVA_HOME=$(JAVA_HOME)
	@echo TOOLCHAINDIR=$(TOOLCHAINDIR)

# Ensure that JKS_PATH and JKS_PASSWORD are set before we attempt a build
# that requires signing.
.PHONY: jarsign-env
jarsign-env:
ifeq ($(JKS_PATH),)
	$(error JKS_PATH is not set.  export JKS_PATH=/path/to/tailcale.jks)
endif
ifeq ($(JKS_PASSWORD),)
	$(error JKS_PASSWORD is not set.  export JKS_PASSWORD=passwordForTailcale.jks)
endif
ifeq ($(wildcard $(JKS_PATH)),)
	$(error JKS_PATH does not point to a file)
endif
	@echo "keystore path set to $(JKS_PATH)"

.PHONY: androidpath
androidpath:
	@echo "export ANDROID_HOME=$(ANDROID_HOME)"
	@echo "export ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)"
	@echo 'export PATH=$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$$PATH'

.PHONY: tag_release
tag_release: ## Tag the current commit with the current version
	git tag -a "$(VERSION_LONG)" -m "OSS and Version updated to ${VERSION_LONG}"


.PHONY: bumposs ## Bump to the latest oss and update teh versions.
bumposs: update-oss update-version
	git commit -sm "android: bumping OSS" -m "OSS and Version updated to ${VERSION_LONG}" android/build.gradle go.mod go.sum
	git tag -a "$(VERSION_LONG)" -m "OSS and Version updated to ${VERSION_LONG}"

.PHONY: bump_version_code
bump_version_code: ## Bump the version code in build.gradle
	sed -i'.bak' 's/versionCode .*/versionCode $(VERSIONCODE_PLUSONE)/' android/build.gradle && rm android/build.gradle.bak

.PHONY: update-version
update-version: ## Update the version in build.gradle
	sed -i'.bak' 's/versionName .*/versionName "$(VERSION_LONG)"/' android/build.gradle && rm android/build.gradle.bak

.PHONY: update-oss
update-oss: ## Update the tailscale.com go module and update the version in build.gradle
	GOPROXY=direct go get tailscale.com@main
	go run tailscale.com/cmd/printdep --go > go.toolchain.rev
	go mod tidy -compat=1.23

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

.PHONY: androidsdk
androidsdk: $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager ## Install the set of Android SDK packages we need.
	yes | $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --update
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager $(ANDROID_SDK_PACKAGES)

# Normally in make you would simply take a dependency on the task that provides
# the binaries, however users may have a decision to make as to whether they
# want to install an SDK or use the one from an Android Studio installation.
.PHONY: checkandroidsdk
checkandroidsdk: ## Check that Android SDK is installed
	@$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager --list_installed | grep -q 'ndk' || (\
		echo -e "\n\tERROR: Android SDK not installed.\n\
		\tANDROID_HOME=$(ANDROID_HOME)\n\
		\tANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT)\n\n\
		See README.md for instructions on how to install the prerequisites.\n"; exit 1)

.PHONY: test
test: gradle-dependencies ## Run the Android tests
	(cd android && ./gradlew test)

.PHONY: install
install: $(DEBUG_APK) ## Install the debug APK on a connected device
	adb install -r $<

.PHONY: run
run: install ## Run the debug APK on a connected device
	adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity

.PHONY: docker-build-image
docker-build-image: ## Builds the docker image for the android build environment if it does not exist
	@echo "Checking if docker image $(DOCKER_IMAGE) already exists..."
	@if ! docker images $(DOCKER_IMAGE) -q | grep -q . ; then \
		echo "Image does not exist. Building..."; \
		docker build -f docker/DockerFile.amd64-build -t $(DOCKER_IMAGE) .; \
	fi

.PHONY: docker-run-build
docker-run-build: clean jarsign-env docker-build-image  ## Runs the docker image for the android build environment and builds release
	@docker run --rm -v $(CURDIR):/build/tailscale-android --env JKS_PASSWORD=$(JKS_PASSWORD) --env JKS_PATH=$(JKS_PATH) $(DOCKER_IMAGE)

.PHONY: docker-remove-build-image
docker-remove-build-image: ## Removes the current docker build image
	docker rmi --force $(DOCKER_IMAGE)

.PHONY: docker-all ## Makes a fresh docker environment, builds docker and cleans up.  For CI.
docker-all: docker-build-image docker-run-build $(DOCKER_IMAGE)

.PHONY: docker-shell
docker-shell: ## Builds a docker image with the android build env and opens a shell
	docker build  -f docker/DockerFile.amd64-shell -t tailscale-android-shell-amd64 .
	docker run --rm -v $(CURDIR):/build/tailscale-android -it tailscale-android-shell-amd64

.PHONY: docker-remove-shell-image
docker-remove-shell-image: ## Removes all docker shell image
	docker rmi --force tailscale-android-shell-amd64

.PHONY: clean
clean: ## Remove build artifacts.  Does not purge docker build envs.  Use dockerRemoveEnv for that.
	-rm -rf android/build $(DEBUG_APK) $(RELEASE_AAB) $(RELEASE_TV_AAB) $(LIBTAILSCALE) android/libs *.apk *.aab
	-pkill -f gradle

.PHONY: help
help: ## Show this help
	@echo "\nSpecify a command. The choices are:\n"
	@grep -hE '^[0-9a-zA-Z_-]+:.*?## .*$$' ${MAKEFILE_LIST} | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[0;36m%-20s\033[m %s\n", $$1, $$2}'
	@echo ""

.DEFAULT_GOAL := help
