#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#

resolve_default_lan_host() {
	ip -4 -o addr show up scope global | awk '$2 != "tailscale0" { split($4, a, "/"); print a[1]; exit }'
}

resolve_default_tailnet_host() {
	if command -v tailscale >/dev/null 2>&1; then
		tailscale ip -4 2>/dev/null | awk 'NF { print; exit }'
	fi
}

export TSOCKS_TEST_LAN_HOST="${TSOCKS_TEST_LAN_HOST:-$(resolve_default_lan_host)}"
export TSOCKS_TEST_TAILNET_HOST="${TSOCKS_TEST_TAILNET_HOST:-$(resolve_default_tailnet_host)}"

export TSOCKS_TEST_LAN_HTTP_PORT="${TSOCKS_TEST_LAN_HTTP_PORT:-18080}"
export TSOCKS_TEST_LAN_TCP_PORT="${TSOCKS_TEST_LAN_TCP_PORT:-19080}"
export TSOCKS_TEST_TAILNET_HTTP_PORT="${TSOCKS_TEST_TAILNET_HTTP_PORT:-18081}"
export TSOCKS_TEST_TAILNET_TCP_PORT="${TSOCKS_TEST_TAILNET_TCP_PORT:-19081}"
