#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/scripts/tsocks-test-env.sh"
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
run_adb logcat -d -s TSOCKS_TEST TSOCKS_ROUTE TSOCKS_SOCKS TSOCKS_DATAPATH > "$tmp_file"

has_fail=0
for scenario in lan-http tailnet-http lan-tcp tailnet-tcp public-http phase3-public-http-a phase3-public-http-b phase3-public-no-match phase3-wrong-port-entered-tun phase3-recursion-guard; do
	if grep -q "event=TEST_PASS .*scenario=$scenario" "$tmp_file"; then
		printf 'PASS %s\n' "$scenario"
	else
		printf 'FAIL %s\n' "$scenario"
		has_fail=1
	fi
done

check_line() {
	label=$1
	pattern=$2
	if grep -Eq "$pattern" "$tmp_file"; then
		printf 'PASS %s\n' "$label"
	else
		printf 'FAIL %s\n' "$label"
		has_fail=1
	fi
}

for target in 104.18.26.120:80 104.18.27.120:80; do
	check_line "phase3-flow-$target" "TSOCKS_DATAPATH: event=flow_identified .*dst=$target .*selectedRoute=TAILNET_SOCKS .*injectedRoute=true"
	check_line "phase3-socks-$target" "TSOCKS_SOCKS: event=socks_connect_success flow=datapath .*target=$target"
	check_line "phase3-target-$target" "TSOCKS_DATAPATH: event=target_connect_success .*dst=$target .*selectedRoute=TAILNET_SOCKS .*injectedRoute=true"
	check_line "phase3-bytes-$target" "TSOCKS_DATAPATH: event=conn_close .*dst=$target .*selectedRoute=TAILNET_SOCKS .*bytes_up=[1-9][0-9]* .*bytes_down=[1-9][0-9]* .*closeReason="
done

check_line "RULE_MATCHED_AND_SOCKS_OFFLOADED" "TSOCKS_DATAPATH: event=flow_identified .*offloadDecision=offloaded .*offloadReason=RULE_MATCHED_AND_SOCKS_OFFLOADED .*recursionGuard=false"

check_line "phase3-public-no-match-route" "TSOCKS_ROUTE: event=route_decision .*target=104.18.4.106:80 .*matchedRule=default_direct .*selectedRoute=DIRECT .*injectedRoute=false"
check_line "phase3-public-no-match-no-socks" "event=TEST_PASS .*scenario=phase3-public-no-match .*route=DIRECT"
if grep -Eq 'TSOCKS_SOCKS: event=socks_connect_success .*target=104.18.4.106:80' "$tmp_file"; then
	printf 'FAIL phase3-public-no-match-socks-leak\n'
	has_fail=1
else
	printf 'PASS phase3-public-no-match-socks-leak\n'
fi
baseline_pattern=$(printf '%s:%s|%s:%s|%s:%s|%s:%s' \
	"$TSOCKS_TEST_LAN_HOST" "$TSOCKS_TEST_LAN_HTTP_PORT" \
	"$TSOCKS_TEST_LAN_HOST" "$TSOCKS_TEST_LAN_TCP_PORT" \
	"$TSOCKS_TEST_TAILNET_HOST" "$TSOCKS_TEST_TAILNET_HTTP_PORT" \
	"$TSOCKS_TEST_TAILNET_HOST" "$TSOCKS_TEST_TAILNET_TCP_PORT")
check_line "BASELINE_NATIVE_PATH_OK" "TSOCKS_ROUTE: event=route_decision .*target=($baseline_pattern) .*offloadDecision=bypass .*offloadReason=BASELINE_NATIVE_PATH_OK"
check_line "RULE_NOT_MATCHED_BUT_ENTERED_TUN_DUE_TO_/32" "TSOCKS_DATAPATH: event=route_decision .*dst=104.18.26.120:81 .*matchedRule=default_direct .*selectedRoute=DIRECT .*injectedRoute=true .*entered_tun_due_to_/32=true .*offloadDecision=bypass .*offloadReason=RULE_NOT_MATCHED_BUT_ENTERED_TUN_DUE_TO_/32 .*expectedBehavior=true .*recursionGuard=false"

check_line "phase3-recursion-guard-route" "TSOCKS_ROUTE: event=route_decision .*target=100.78.63.77:1080 .*matchedRule=socks_server_self .*selectedRoute=DIRECT .*injectedRoute=false .*offloadDecision=bypass .*offloadReason=RECURSION_GUARD_BYPASS .*recursionGuard=true"
check_line "phase3-recursion-guard-pass" "event=TEST_PASS .*scenario=phase3-recursion-guard .*detail=preview_only"
check_line "RECURSION_GUARD_BYPASS" "TSOCKS_ROUTE: event=route_decision .*target=100.78.63.77:1080 .*offloadDecision=bypass .*offloadReason=RECURSION_GUARD_BYPASS .*recursionGuard=true"

exit "$has_fail"
