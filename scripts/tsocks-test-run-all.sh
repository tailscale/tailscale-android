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

wait_for_http() {
	scenario=$1
	url=$2
	attempts=${3:-10}
	count=1
	while [ "$count" -le "$attempts" ]; do
		if run_adb shell "curl --max-time 3 -fsS '$url' >/dev/null" >/dev/null 2>&1; then
			printf 'READY %s\n' "$scenario"
			return 0
		fi
		sleep 1
		count=$((count + 1))
	done
	printf 'ENV_NOT_READY %s\n' "$scenario" >&2
	return 1
}

wait_for_tcp() {
	scenario=$1
	host=$2
	port=$3
	attempts=${4:-10}
	count=1
	while [ "$count" -le "$attempts" ]; do
		if run_adb shell "printf 'PING\\n' | nc -w 3 '$host' '$port' >/dev/null" >/dev/null 2>&1; then
			printf 'READY %s\n' "$scenario"
			return 0
		fi
		sleep 1
		count=$((count + 1))
	done
	printf 'ENV_NOT_READY %s\n' "$scenario" >&2
	return 1
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

wait_for_http lan-http http://192.168.31.101:18080/healthz
wait_for_http tailnet-http http://100.109.193.113:18081/healthz
wait_for_tcp lan-tcp 192.168.31.101 19080
wait_for_tcp tailnet-tcp 100.109.193.113 19081

run_adb logcat -c

for scenario in lan-http tailnet-http lan-tcp tailnet-tcp public-http phase3-public-http-a phase3-public-http-b phase3-public-no-match phase3-wrong-port-entered-tun phase3-recursion-guard; do
	REQUEST_ID="$(date +%Y%m%d%H%M%S)-$scenario" SERIAL="${SERIAL:-}" sh scripts/tsocks-test-trigger.sh "$scenario"
	sleep "$sleep_seconds"
done

echo "=== TSOCKS route/test logs ==="
SERIAL="${SERIAL:-}" sh scripts/tsocks-test-logs.sh

echo "=== PASS/FAIL summary ==="
SERIAL="${SERIAL:-}" sh scripts/tsocks-test-pass-fail.sh
