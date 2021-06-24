# This is a Dockerfile for creating a build environment for
# tailscale-android.

FROM openjdk:8-jdk

# To enable running android tools such as aapt
RUN apt-get update && apt-get -y upgrade
RUN apt-get install -y lib32z1 lib32stdc++6
# For Go:
RUN apt-get -y --no-install-recommends install curl gcc
RUN apt-get -y --no-install-recommends install ca-certificates libc6-dev git

RUN apt-get -y install make

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
ARG GO_VERSION=1.16.5
RUN curl -O https://storage.googleapis.com/golang/go${GO_VERSION}.linux-amd64.tar.gz
RUN echo "b12c23023b68de22f74c0524f10b753e7b08b1504cb7e417eccebdd3fae49061  go${GO_VERSION}.linux-amd64.tar.gz" | sha256sum -c
RUN tar -xzf go${GO_VERSION}.linux-amd64.tar.gz && mv go goroot
ENV GOROOT $HOME/goroot
ENV PATH $PATH:$GOROOT/bin:$HOME/bin:$ANDROID_HOME/platform-tools
ENV ANDROID_SDK_ROOT /build/android-sdk

RUN mkdir -p $HOME/tailscale-android
WORKDIR $HOME/tailscale-android

ADD go.mod .
ADD go.sum .
RUN go mod download

# Preload Gradle
COPY android/gradlew android/gradlew
COPY android/gradle android/gradle
RUN ./android/gradlew

CMD /bin/bash
