# This is the start of a Dockerfile to build tailscale-android.
# It works, but it's not very efficient yet; see TODOs below.

FROM openjdk:8-jdk

# To enable running android tools such as aapt
RUN apt-get update && apt-get -y upgrade
RUN apt-get install -y lib32z1 lib32stdc++6
# For Go:
RUN apt-get -y --no-install-recommends install curl gcc
RUN apt-get -y --no-install-recommends install ca-certificates libc6-dev git

RUN mkdir -p BUILD
ENV HOME /build

# Get android sdk, ndk, and rest of the stuff needed to build the android app.
WORKDIR $HOME
RUN mkdir android-sdk
ENV ANDROID_HOME $HOME/android-sdk
WORKDIR $ANDROID_HOME
RUN curl -O https://dl.google.com/android/repository/sdk-tools-linux-3859397.zip
RUN echo '444e22ce8ca0f67353bda4b85175ed3731cae3ffa695ca18119cbacef1c1bea0  sdk-tools-linux-3859397.zip' | sha256sum -c
RUN unzip sdk-tools-linux-3859397.zip
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager --update
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'platforms;android-29'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'extras;android;m2repository'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'ndk;20.0.5594570'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'platform-tools'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'build-tools;28.0.3'

# Get Go stable release
WORKDIR $HOME
ARG GO_VERSION=1.14.3
RUN curl -O https://storage.googleapis.com/golang/go${GO_VERSION}.linux-amd64.tar.gz
RUN echo "1c39eac4ae95781b066c144c58e45d6859652247f7515f0d2cba7be7d57d2226  go${GO_VERSION}.linux-amd64.tar.gz" | sha256sum -c
RUN tar -xzf go${GO_VERSION}.linux-amd64.tar.gz && mv go goroot
ENV GOROOT $HOME/goroot
ENV PATH $PATH:$GOROOT/bin:$HOME/bin

# TODO: pre-install Grade 6.3 so gogio doesn't download it later at runtime at the build.sh step.
# TODO: ... likewise, all this:
# Checking the license for package Android SDK Build-Tools 28.0.3 in /build/android-sdk/licenses
# License for package Android SDK Build-Tools 28.0.3 accepted.
# Preparing "Install Android SDK Build-Tools 28.0.3 (revision: 28.0.3)".
# "Install Android SDK Build-Tools 28.0.3 (revision: 28.0.3)" ready.
# Installing Android SDK Build-Tools 28.0.3 in /build/android-sdk/build-tools/28.0.3
# "Install Android SDK Build-Tools 28.0.3 (revision: 28.0.3)" complete.
# "Install Android SDK Build-Tools 28.0.3 (revision: 28.0.3)" finished.
# Checking the license for package Android SDK Platform-Tools in /build/android-sdk/licenses
# License for package Android SDK Platform-Tools accepted.
# Preparing "Install Android SDK Platform-Tools (revision: 30.0.1)".
# "Install Android SDK Platform-Tools (revision: 30.0.1)" ready.
# Installing Android SDK Platform-Tools in /build/android-sdk/platform-tools
# "Install Android SDK Platform-Tools (revision: 30.0.1)" complete.
# "Install Android SDK Platform-Tools (revision: 30.0.1)" finished.

RUN mkdir -p $HOME/tailscale-android
WORKDIR $HOME/tailscale-android

ADD go.mod .
ADD go.sum .
RUN go mod download
ADD . .
RUN ./build.sh

