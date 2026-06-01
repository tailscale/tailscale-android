#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/scripts/tsocks-test-env.sh"

check_http() {
	name=$1
	url=$2
	if curl -fsS --max-time 2 "$url" >/dev/null; then
		printf 'READY %s\n' "$name"
	else
		printf 'NOT_READY %s\n' "$name" >&2
		return 1
	fi
}

check_tcp() {
	name=$1
	host=$2
	port=$3
	if printf 'PING\n' | nc -w 2 "$host" "$port" | grep -q 'PONG'; then
		printf 'READY %s\n' "$name"
	else
		printf 'NOT_READY %s\n' "$name" >&2
		return 1
	fi
}

check_http lan-http "http://$TSOCKS_TEST_LAN_HOST:$TSOCKS_TEST_LAN_HTTP_PORT/healthz"
check_http tailnet-http "http://$TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_HTTP_PORT/healthz"
check_tcp lan-tcp "$TSOCKS_TEST_LAN_HOST" "$TSOCKS_TEST_LAN_TCP_PORT"
check_tcp tailnet-tcp "$TSOCKS_TEST_TAILNET_HOST" "$TSOCKS_TEST_TAILNET_TCP_PORT"
