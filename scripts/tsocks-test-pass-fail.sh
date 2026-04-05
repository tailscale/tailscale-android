#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
adb_bin=${ADB:-adb}

run_adb() {
	if [ -n "${SERIAL:-}" ]; then
		"$adb_bin" -s "$SERIAL" "$@"
	else
		"$adb_bin" "$@"
	fi
}

tmp_file=$(mktemp)
trap 'rm -f "$tmp_file"' EXIT INT TERM

cd "$repo_root"
run_adb logcat -d -s TSOCKS_TEST > "$tmp_file"

has_fail=0
for scenario in lan-http tailnet-http lan-tcp tailnet-tcp public-http; do
	if grep -q "event=TEST_PASS .*scenario=$scenario" "$tmp_file"; then
		printf 'PASS %s\n' "$scenario"
	else
		printf 'FAIL %s\n' "$scenario"
		has_fail=1
	fi
done

exit "$has_fail"
