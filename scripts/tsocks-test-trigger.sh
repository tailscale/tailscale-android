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
  lan-http      -> $TSOCKS_TEST_LAN_HOST:$TSOCKS_TEST_LAN_HTTP_PORT/healthz
  tailnet-http  -> $TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_HTTP_PORT/healthz
  lan-tcp       -> $TSOCKS_TEST_LAN_HOST:$TSOCKS_TEST_LAN_TCP_PORT
  tailnet-tcp   -> $TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_TCP_PORT
  lan-tcp-close -> $TSOCKS_TEST_LAN_HOST:$TSOCKS_TEST_LAN_TCP_PORT payload CLOSE
  tailnet-tcp-close -> $TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_TCP_PORT payload CLOSE
  tailnet-tcp-rst -> $TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_TCP_PORT payload RST
  public-http   -> example.com:80/
  datapath-public-http -> Activity GET http://example.com/
  datapath-direct-http -> Activity GET http://$TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_HTTP_PORT/healthz
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
. "$repo_root/scripts/tsocks-test-env.sh"
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
		host=$TSOCKS_TEST_LAN_HOST
		port=$TSOCKS_TEST_LAN_HTTP_PORT
		protocol=http
		path=/healthz
		;;
	tailnet-http)
		host=$TSOCKS_TEST_TAILNET_HOST
		port=$TSOCKS_TEST_TAILNET_HTTP_PORT
		protocol=http
		path=/healthz
		;;
	lan-tcp)
		host=$TSOCKS_TEST_LAN_HOST
		port=$TSOCKS_TEST_LAN_TCP_PORT
		protocol=tcp
		payload="PING"
		;;
	lan-tcp-close)
		host=$TSOCKS_TEST_LAN_HOST
		port=$TSOCKS_TEST_LAN_TCP_PORT
		protocol=tcp
		payload="CLOSE"
		;;
	tailnet-tcp)
		host=$TSOCKS_TEST_TAILNET_HOST
		port=$TSOCKS_TEST_TAILNET_TCP_PORT
		protocol=tcp
		payload="PING"
		;;
	tailnet-tcp-close)
		host=$TSOCKS_TEST_TAILNET_HOST
		port=$TSOCKS_TEST_TAILNET_TCP_PORT
		protocol=tcp
		payload="CLOSE"
		;;
	tailnet-tcp-rst)
		host=$TSOCKS_TEST_TAILNET_HOST
		port=$TSOCKS_TEST_TAILNET_TCP_PORT
		protocol=tcp
		payload="RST"
		;;
	public-http)
		host=example.com
		port=80
		protocol=http
		path=/
		;;
	phase3-public-http-a)
		run_adb shell "curl --max-time ${timeout_ms} -H 'Host: example.com' http://104.18.26.120/ >/dev/null 2>&1; rc=\$?; if [ \$rc -eq 0 ]; then log -t TSOCKS_TEST 'event=TEST_PASS requestId=${request_id} scenario=phase3-public-http-a route=TAILNET_SOCKS detail=curl_exit_0'; else log -t TSOCKS_TEST 'event=TEST_FAIL requestId=${request_id} scenario=phase3-public-http-a route=TAILNET_SOCKS reason=curl_exit_'\$rc; fi; exit \$rc"
		exit 0
		;;
	phase3-public-http-b)
		run_adb shell "curl --max-time ${timeout_ms} -H 'Host: example.com' http://104.18.27.120/ >/dev/null 2>&1; rc=\$?; if [ \$rc -eq 0 ]; then log -t TSOCKS_TEST 'event=TEST_PASS requestId=${request_id} scenario=phase3-public-http-b route=TAILNET_SOCKS detail=curl_exit_0'; else log -t TSOCKS_TEST 'event=TEST_FAIL requestId=${request_id} scenario=phase3-public-http-b route=TAILNET_SOCKS reason=curl_exit_'\$rc; fi; exit \$rc"
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
		run_adb shell "curl --max-time ${timeout_ms} http://104.18.26.120:81/ >/dev/null 2>&1; log -t TSOCKS_TEST 'event=TEST_PASS requestId=${request_id} scenario=phase3-wrong-port-entered-tun route=DIRECT detail=trigger_sent'; exit 0"
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
		url=http://$TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_HTTP_PORT/healthz
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
