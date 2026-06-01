// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

object AdbTcpHttpTestContract {
  const val ACTION_RUN_TEST = "com.tailscale.ipn.RUN_NETWORK_TEST"
  const val WORK_RUN_TEST = "ipn-run-network-test"

  const val EXTRA_SCENARIO = "scenario"
  const val EXTRA_REQUEST_ID = "requestId"
  const val EXTRA_HOST = "host"
  const val EXTRA_PORT = "port"
  const val EXTRA_PROTOCOL = "protocol"
  const val EXTRA_PATH = "path"
  const val EXTRA_PAYLOAD = "payload"
  const val EXTRA_HOST_HEADER = "hostHeader"
  const val EXTRA_TIMEOUT_MS = "timeoutMs"
  const val EXTRA_SOCKS_ENABLED = "socksEnabled"
  const val EXTRA_PREVIEW_ONLY = "previewOnly"
  const val EXTRA_URL = "url"

  const val TAG_TEST = "TSOCKS_TEST"
  const val TAG_ROUTE = "TSOCKS_ROUTE"
  const val TAG_SOCKS = "TSOCKS_SOCKS"
  const val TAG_DATAPATH = "TSOCKS_DATAPATH"

  const val DEFAULT_PROTOCOL = "tcp"
  const val DEFAULT_PATH = "/"
  const val DEFAULT_TIMEOUT_MS = 5_000L
  const val DEFAULT_SOCKS_ENABLED = true

  const val LAN_HOST = "192.168.31.101"
  const val TAILNET_LAB_HOST = "100.109.193.113"
  const val TAILNET_DOMAIN_HOST = "wide-ts-wu"
  const val SOCKS_SERVER_HOST = "100.78.63.77"
  const val SOCKS_SERVER_PORT = 1080
  const val PUBLIC_ALLOWLIST_HOST = "example.com"
  const val PUBLIC_ALLOWLIST_PORT = 80
}
