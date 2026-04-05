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
	*)
		usage >&2
		exit 1
		;;
esac

cd "$repo_root"
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

if [ -n "$path" ]; then
	set -- "$@" --es path "$path"
fi

if [ -n "$payload" ]; then
	set -- "$@" --es payload "$payload"
fi

run_adb "$@"
