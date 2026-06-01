#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

adb_bin=${ADB:-adb}

if [ -n "${SERIAL:-}" ]; then
	"$adb_bin" -s "$SERIAL" logcat -d -s TSOCKS_TEST TSOCKS_ROUTE TSOCKS_SOCKS TSOCKS_DATAPATH
else
	"$adb_bin" logcat -d -s TSOCKS_TEST TSOCKS_ROUTE TSOCKS_SOCKS TSOCKS_DATAPATH
fi
