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
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'platforms;android-31'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'extras;android;m2repository'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'ndk;23.1.7779620'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'platform-tools'
RUN echo y | $ANDROID_HOME/tools/bin/sdkmanager 'build-tools;28.0.3'

ENV PATH $PATH:$HOME/bin:$ANDROID_HOME/platform-tools
ENV ANDROID_SDK_ROOT /build/android-sdk

# We need some version of Go new enough to support the "embed" package
# to run "go run tailscale.com/cmd/printdep" to figure out which Tailscale Go
# version we need later, but otherwise this toolchain isn't used:
RUN curl -L https://go.dev/dl/go1.17.5.linux-amd64.tar.gz | tar -C /usr/local -zxv
RUN ln -s /usr/local/go/bin/go /usr/bin

RUN mkdir -p $HOME/tailscale-android
WORKDIR $HOME/tailscale-android

# Preload Gradle
COPY android/gradlew android/gradlew
COPY android/gradle android/gradle
RUN ./android/gradlew

CMD /bin/bash
