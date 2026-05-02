#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
pid_file="$repo_root/.tsocks-test-services.pid"

if [ ! -f "$pid_file" ]; then
	exit 0
fi

pid=$(cat "$pid_file")
if kill -0 "$pid" 2>/dev/null; then
	kill "$pid"
fi
rm -f "$pid_file"
