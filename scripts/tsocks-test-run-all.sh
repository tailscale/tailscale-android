#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
adb_bin=${ADB:-adb}
sleep_seconds=${SLEEP_SECONDS:-2}
build_first=${BUILD_FIRST:-true}
install_first=${INSTALL_FIRST:-true}
connect_vpn_first=${CONNECT_VPN_FIRST:-true}

run_adb() {
	if [ -n "${SERIAL:-}" ]; then
		"$adb_bin" -s "$SERIAL" "$@"
	else
		"$adb_bin" "$@"
	fi
}

cd "$repo_root"

if [ "$build_first" = "true" ]; then
	sh scripts/tsocks-test-build.sh
fi

if [ "$install_first" = "true" ]; then
	sh scripts/tsocks-test-install.sh
fi

if [ "$connect_vpn_first" = "true" ]; then
	run_adb shell am broadcast \
		-n com.tailscale.ipn/com.tailscale.ipn.IPNReceiver \
		-a com.tailscale.ipn.CONNECT_VPN
	sleep "$sleep_seconds"
fi

run_adb logcat -c

for scenario in lan-http tailnet-http lan-tcp tailnet-tcp public-http; do
	REQUEST_ID="$(date +%Y%m%d%H%M%S)-$scenario" SERIAL="${SERIAL:-}" sh scripts/tsocks-test-trigger.sh "$scenario"
	sleep "$sleep_seconds"
done

echo "=== TSOCKS route/test logs ==="
SERIAL="${SERIAL:-}" sh scripts/tsocks-test-logs.sh

echo "=== PASS/FAIL summary ==="
SERIAL="${SERIAL:-}" sh scripts/tsocks-test-pass-fail.sh
