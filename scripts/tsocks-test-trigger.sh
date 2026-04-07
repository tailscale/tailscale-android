#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

usage() {
	cat <<'EOF'
Usage: scripts/tsocks-test-trigger.sh <scenario>

Scenarios:
  lan-http      -> 192.168.31.101:18080/healthz
  tailnet-http  -> 100.109.193.113:18081/healthz
  lan-tcp       -> 192.168.31.101:19080
  tailnet-tcp   -> 100.109.193.113:19081
  public-http   -> example.com:80/
  datapath-public-http -> Activity GET http://example.com/
  datapath-direct-http -> Activity GET http://100.109.193.113:18081/healthz
  phase3-public-http-a -> shell curl http://104.18.26.120/ with Host: example.com
  phase3-public-http-b -> shell curl http://104.18.27.120/ with Host: example.com
  phase3-public-no-match -> direct probe http://104.18.4.106/ with Host: example.net
  phase3-wrong-port-entered-tun -> shell curl http://104.18.26.120:81/ to observe /32 boundary
  phase3-recursion-guard -> preview-only probe for 100.78.63.77:1080

Optional env:
  SERIAL=<adb serial>
  TIMEOUT_MS=<timeout, default 5000>
  REQUEST_ID=<custom request id>
  SOCKS_ENABLED=true|false (default true)
EOF
}

scenario=${1-}
if [ -z "$scenario" ]; then
	usage >&2
	exit 1
fi

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
adb_bin=${ADB:-adb}
timeout_ms=${TIMEOUT_MS:-5000}
request_id=${REQUEST_ID:-$(date +%Y%m%d%H%M%S)-$scenario}
socks_enabled=${SOCKS_ENABLED:-true}

run_adb() {
	if [ -n "${SERIAL:-}" ]; then
		"$adb_bin" -s "$SERIAL" "$@"
	else
		"$adb_bin" "$@"
	fi
}

host=
port=
protocol=
path=
payload=
url=

case "$scenario" in
	lan-http)
		host=192.168.31.101
		port=18080
		protocol=http
		path=/healthz
		;;
	tailnet-http)
		host=100.109.193.113
		port=18081
		protocol=http
		path=/healthz
		;;
	lan-tcp)
		host=192.168.31.101
		port=19080
		protocol=tcp
		payload="PING"
		;;
	tailnet-tcp)
		host=100.109.193.113
		port=19081
		protocol=tcp
		payload="PING"
		;;
	public-http)
		host=example.com
		port=80
		protocol=http
		path=/
		;;
	phase3-public-http-a)
		run_adb shell "curl --max-time ${timeout_ms} -H 'Host: example.com' http://104.18.26.120/ >/dev/null 2>&1; rc=\$?; if [ \$rc -eq 0 ]; then log -t TSOCKS_TEST 'event=TEST_PASS scenario=phase3-public-http-a route=TAILNET_SOCKS detail=curl_exit_0'; else log -t TSOCKS_TEST 'event=TEST_FAIL scenario=phase3-public-http-a route=TAILNET_SOCKS reason=curl_exit_'\$rc; fi; exit \$rc"
		exit 0
		;;
	phase3-public-http-b)
		run_adb shell "curl --max-time ${timeout_ms} -H 'Host: example.com' http://104.18.27.120/ >/dev/null 2>&1; rc=\$?; if [ \$rc -eq 0 ]; then log -t TSOCKS_TEST 'event=TEST_PASS scenario=phase3-public-http-b route=TAILNET_SOCKS detail=curl_exit_0'; else log -t TSOCKS_TEST 'event=TEST_FAIL scenario=phase3-public-http-b route=TAILNET_SOCKS reason=curl_exit_'\$rc; fi; exit \$rc"
		exit 0
		;;
	phase3-public-no-match)
		host=104.18.4.106
		port=80
		protocol=http
		path=/
		payload=
		host_header=example.net
		;;
	phase3-wrong-port-entered-tun)
		run_adb shell "curl --max-time ${timeout_ms} http://104.18.26.120:81/ >/dev/null 2>&1; log -t TSOCKS_TEST 'event=TEST_PASS scenario=phase3-wrong-port-entered-tun route=DIRECT detail=trigger_sent'; exit 0"
		exit 0
		;;
	phase3-recursion-guard)
		host=100.78.63.77
		port=1080
		protocol=tcp
		preview_only=true
		;;
	datapath-public-http)
		url=http://example.com/
		;;
	datapath-direct-http)
		url=http://100.109.193.113:18081/healthz
		;;
	*)
		usage >&2
		exit 1
		;;
esac

cd "$repo_root"
if [ -n "$url" ]; then
	set -- shell am start -W \
		-n com.tailscale.ipn/com.tailscale.ipn.DatapathTestActivity \
		--es scenario "$scenario" \
		--es requestId "$request_id" \
		--es url "$url" \
		--el timeoutMs "$timeout_ms"
	run_adb "$@"
	exit 0
fi

set -- shell am broadcast \
	-n com.tailscale.ipn/com.tailscale.ipn.IPNReceiver \
	-a com.tailscale.ipn.RUN_NETWORK_TEST \
	--es scenario "$scenario" \
	--es requestId "$request_id" \
	--es host "$host" \
	--ei port "$port" \
	--es protocol "$protocol" \
	--ez socksEnabled "$socks_enabled" \
	--el timeoutMs "$timeout_ms"

if [ -n "${host_header:-}" ]; then
	set -- "$@" --es hostHeader "$host_header"
fi

if [ "${preview_only:-false}" = "true" ]; then
	set -- "$@" --ez previewOnly true
fi

if [ -n "$path" ]; then
	set -- "$@" --es path "$path"
fi

if [ -n "$payload" ]; then
	set -- "$@" --es payload "$payload"
fi

run_adb "$@"
