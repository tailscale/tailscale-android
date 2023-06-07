# This is a Dockerfile for creating a build environment for
# tailscale-android.

FROM --platform=linux/amd64 eclipse-temurin:11-jdk

# To enable running android tools such as aapt
RUN apt-get update && apt-get -y upgrade
RUN apt-get install -y libz1 libstdc++6 unzip
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
RUN curl -O https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
RUN echo 'bd1aa17c7ef10066949c88dc6c9c8d536be27f992a1f3b5a584f9bd2ba5646a0  commandlinetools-linux-9477386_latest.zip' | sha256sum -c
RUN mkdir cmdline-tools && unzip -d cmdline-tools/latest commandlinetools-linux-9477386_latest.zip && mv cmdline-tools/latest/cmdline-tools/* cmdline-tools/latest/
RUN echo y | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --update
RUN echo y | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'platforms;android-31'
RUN echo y | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'extras;android;m2repository'
RUN echo y | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'ndk;23.1.7779620'
RUN echo y | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'platform-tools'
RUN echo y | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'build-tools;33.0.2'

ENV PATH $PATH:$HOME/bin:$ANDROID_HOME/platform-tools
ENV ANDROID_SDK_ROOT /build/android-sdk

# We need some version of Go new enough to support the "embed" package
# to run "go run tailscale.com/cmd/printdep" to figure out which Tailscale Go
# version we need later, but otherwise this toolchain isn't used:
RUN curl -L https://go.dev/dl/go1.20.5.linux-amd64.tar.gz | tar -C /usr/local -zxv
RUN ln -s /usr/local/go/bin/go /usr/bin

RUN mkdir -p $HOME/tailscale-android
RUN git config --global --add safe.directory $HOME/tailscale-android
WORKDIR $HOME/tailscale-android

# Preload Gradle
COPY android/gradlew android/gradlew
COPY android/gradle android/gradle
RUN ./android/gradlew

CMD /bin/bash
