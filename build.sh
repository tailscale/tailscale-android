#!/bin/sh
# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

set -e

mkdir -p android/libs
go run gioui.org/cmd/gogio -buildmode archive -target android -appid com.tailscale.ipn -o android/libs/ipn.aar tailscale.com/tailscale-android/cmd/tailscale
(cd android && ./gradlew assembleDebug)
