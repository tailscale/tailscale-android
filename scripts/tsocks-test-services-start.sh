#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/scripts/tsocks-test-env.sh"

pid_file="$repo_root/.tsocks-test-services.pid"
log_file="$repo_root/.tsocks-test-services.log"

if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
	printf 'TSOCKS_TEST_SERVICES already_running pid=%s\n' "$(cat "$pid_file")"
	exit 0
fi

if [ -z "$TSOCKS_TEST_LAN_HOST" ] || [ -z "$TSOCKS_TEST_TAILNET_HOST" ]; then
	printf 'missing_test_hosts lan=%s tailnet=%s\n' "$TSOCKS_TEST_LAN_HOST" "$TSOCKS_TEST_TAILNET_HOST" >&2
	exit 1
fi

cd "$repo_root"
setsid python3 scripts/tsocks_test_server.py \
	--lan-host "$TSOCKS_TEST_LAN_HOST" \
	--tailnet-host "$TSOCKS_TEST_TAILNET_HOST" \
	--lan-http-port "$TSOCKS_TEST_LAN_HTTP_PORT" \
	--lan-tcp-port "$TSOCKS_TEST_LAN_TCP_PORT" \
	--tailnet-http-port "$TSOCKS_TEST_TAILNET_HTTP_PORT" \
	--tailnet-tcp-port "$TSOCKS_TEST_TAILNET_TCP_PORT" \
	< /dev/null >"$log_file" 2>&1 &
echo $! >"$pid_file"
sleep 1
sh scripts/tsocks-test-services-health.sh
