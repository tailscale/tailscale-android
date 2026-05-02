#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/scripts/tsocks-test-env.sh"
adb_bin=${ADB:-adb}
concurrency=${CONCURRENCY:-10}
sleep_seconds=${SLEEP_SECONDS:-2}
build_first=${BUILD_FIRST:-true}
install_first=${INSTALL_FIRST:-true}

run_adb() {
	if [ -n "${SERIAL:-}" ]; then
		"$adb_bin" -s "$SERIAL" "$@"
	else
		"$adb_bin" "$@"
	fi
}

wait_for_device_http() {
	name=$1
	url=$2
	attempts=${3:-10}
	count=1
	while [ "$count" -le "$attempts" ]; do
		if run_adb shell "curl --max-time 3 -fsS '$url' >/dev/null" >/dev/null 2>&1; then
			printf 'READY %s\n' "$name"
			return 0
		fi
		sleep 1
		count=$((count + 1))
	done
	printf 'NOT_READY %s\n' "$name" >&2
	return 1
}

wait_for_device_tcp() {
	name=$1
	host=$2
	port=$3
	attempts=${4:-10}
	count=1
	while [ "$count" -le "$attempts" ]; do
		if run_adb shell "printf 'PING\\n' | nc -w 3 '$host' '$port' >/dev/null" >/dev/null 2>&1; then
			printf 'READY %s\n' "$name"
			return 0
		fi
		sleep 1
		count=$((count + 1))
	done
	printf 'NOT_READY %s\n' "$name" >&2
	return 1
}

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT INT TERM

assert_contains() {
	label=$1
	pattern=$2
	file=$3
	if grep -Eq "$pattern" "$file"; then
		printf 'PASS %s\n' "$label"
	else
		printf 'FAIL %s\n' "$label"
		return 1
	fi
}

assert_not_contains() {
	label=$1
	pattern=$2
	file=$3
	if grep -Eq "$pattern" "$file"; then
		printf 'FAIL %s\n' "$label"
		return 1
	fi
	printf 'PASS %s\n' "$label"
}

collect_logs() {
	out=$1
	run_adb logcat -d -s TSOCKS_TEST TSOCKS_ROUTE TSOCKS_SOCKS TSOCKS_DATAPATH >"$out"
}

prepare_device() {
	run_adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity >/dev/null
	run_adb shell am broadcast \
		-n com.tailscale.ipn/com.tailscale.ipn.IPNReceiver \
		-a com.tailscale.ipn.CONNECT_VPN >/dev/null
	sleep "$sleep_seconds"
	wait_for_device_http lan-http "http://$TSOCKS_TEST_LAN_HOST:$TSOCKS_TEST_LAN_HTTP_PORT/healthz"
	wait_for_device_http tailnet-http "http://$TSOCKS_TEST_TAILNET_HOST:$TSOCKS_TEST_TAILNET_HTTP_PORT/healthz"
	wait_for_device_tcp lan-tcp "$TSOCKS_TEST_LAN_HOST" "$TSOCKS_TEST_LAN_TCP_PORT"
	wait_for_device_tcp tailnet-tcp "$TSOCKS_TEST_TAILNET_HOST" "$TSOCKS_TEST_TAILNET_TCP_PORT"
}

run_baseline() {
	printf '== baseline ==\n'
	sh "$repo_root/scripts/tsocks-test-services-start.sh" >/dev/null
	run_adb logcat -c
	for scenario in lan-http lan-tcp tailnet-http; do
		REQUEST_ID="phase32-$scenario-$(date +%s)" SERIAL="${SERIAL:-}" sh "$repo_root/scripts/tsocks-test-trigger.sh" "$scenario"
		sleep "$sleep_seconds"
	done
	log_file="$tmp_dir/baseline.log"
	collect_logs "$log_file"
	assert_contains "baseline-lan-http" "event=TEST_PASS .*scenario=lan-http" "$log_file"
	assert_contains "baseline-lan-tcp" "event=TEST_PASS .*scenario=lan-tcp" "$log_file"
	assert_contains "baseline-tailnet-http" "event=TEST_PASS .*scenario=tailnet-http" "$log_file"
	assert_contains "baseline-native-path" "event=route_decision .*offloadReason=BASELINE_NATIVE_PATH_OK" "$log_file"
}

run_concurrent_socks() {
	printf '== concurrent-socks ==\n'
	run_adb logcat -c
	i=1
	while [ "$i" -le "$concurrency" ]; do
		scenario=phase3-public-http-a
		if [ $((i % 2)) -eq 0 ]; then
			scenario=phase3-public-http-b
		fi
		REQUEST_ID="phase32-socks-$i-$(date +%s)" SERIAL="${SERIAL:-}" TIMEOUT_MS=8000 sh "$repo_root/scripts/tsocks-test-trigger.sh" "$scenario" &
		i=$((i + 1))
	done
	wait
	sleep 8
	log_file="$tmp_dir/concurrent-socks.log"
	collect_logs "$log_file"
	assert_contains "socks-pass-count" "event=TEST_PASS .*scenario=phase3-public-http-[ab]" "$log_file"
	assert_contains "socks-flow-identified" "event=flow_identified .*offloadReason=RULE_MATCHED_AND_SOCKS_OFFLOADED" "$log_file"
	assert_contains "socks-relay-start" "event=relay_start .*activeRelays=" "$log_file"
	assert_contains "socks-relay-end" "event=relay_end .*activeRelays=0" "$log_file"
	assert_contains "socks-close" "event=conn_close .*closeReason=" "$log_file"
	assert_contains "socks-connect" "event=socks_connect .*stage=connect_success" "$log_file"
	assert_not_contains "socks-test-fail" "event=TEST_FAIL" "$log_file"
	assert_not_contains "socks-cross-target" "flow_id=.*dst=104\.18\.26\.120:80.*\n.*flow_id=.*dst=104\.18\.27\.120:80" "$log_file" || true
}

run_concurrent_direct() {
	printf '== concurrent-direct ==\n'
	run_adb logcat -c
	i=1
	while [ "$i" -le "$concurrency" ]; do
		REQUEST_ID="phase32-direct-$i-$(date +%s)" SERIAL="${SERIAL:-}" sh "$repo_root/scripts/tsocks-test-trigger.sh" phase3-public-no-match &
		i=$((i + 1))
	done
	wait
	sleep 5
	log_file="$tmp_dir/concurrent-direct.log"
	collect_logs "$log_file"
	assert_contains "direct-pass" "event=TEST_PASS .*scenario=phase3-public-no-match .*route=DIRECT" "$log_file"
	assert_contains "direct-route" "event=route_decision .*target=104.18.4.106:80 .*selectedRoute=DIRECT .*offloadReason=BASELINE_NATIVE_PATH_OK" "$log_file"
	assert_not_contains "direct-socks-leak" "TSOCKS_SOCKS: .*104\.18\.4\.106:80" "$log_file"
	assert_not_contains "direct-test-fail" "event=TEST_FAIL" "$log_file"
}

run_concurrent_mixed() {
	printf '== concurrent-mixed ==\n'
	run_adb logcat -c
	i=1
	while [ "$i" -le "$concurrency" ]; do
		REQUEST_ID="phase32-mixed-socks-$i-$(date +%s)" SERIAL="${SERIAL:-}" TIMEOUT_MS=8000 sh "$repo_root/scripts/tsocks-test-trigger.sh" phase3-public-http-a &
		REQUEST_ID="phase32-mixed-direct-$i-$(date +%s)" SERIAL="${SERIAL:-}" sh "$repo_root/scripts/tsocks-test-trigger.sh" phase3-public-no-match &
		i=$((i + 1))
	done
	wait
	sleep 8
	log_file="$tmp_dir/concurrent-mixed.log"
	collect_logs "$log_file"
	assert_contains "mixed-socks-pass" "event=TEST_PASS .*scenario=phase3-public-http-a .*route=TAILNET_SOCKS" "$log_file"
	assert_contains "mixed-direct-pass" "event=TEST_PASS .*scenario=phase3-public-no-match .*route=DIRECT" "$log_file"
	assert_contains "mixed-relay-end" "event=relay_end .*activeRelays=0" "$log_file"
	assert_not_contains "mixed-direct-socks-leak" "TSOCKS_SOCKS: .*104\.18\.4\.106:80" "$log_file"
	assert_not_contains "mixed-test-fail" "event=TEST_FAIL" "$log_file"
}

run_wrong_port() {
	printf '== wrong-port ==\n'
	run_adb logcat -c
	REQUEST_ID="phase32-wrong-port-$(date +%s)" SERIAL="${SERIAL:-}" sh "$repo_root/scripts/tsocks-test-trigger.sh" phase3-wrong-port-entered-tun
	sleep 5
	log_file="$tmp_dir/wrong-port.log"
	collect_logs "$log_file"
	assert_contains "wrong-port-trigger" "event=TEST_PASS .*scenario=phase3-wrong-port-entered-tun" "$log_file"
	assert_contains "wrong-port-expected" "event=route_decision .*dst=104.18.26.120:81 .*selectedRoute=DIRECT .*entered_tun_due_to_/32=true .*offloadDecision=bypass .*offloadReason=RULE_NOT_MATCHED_BUT_ENTERED_TUN_DUE_TO_/32 .*expectedBehavior=true" "$log_file"
}

run_lifecycle() {
	printf '== lifecycle ==\n'
	run_adb logcat -c
	REQUEST_ID="phase32-normal-close-$(date +%s)" SERIAL="${SERIAL:-}" TIMEOUT_MS=8000 sh "$repo_root/scripts/tsocks-test-trigger.sh" phase3-public-http-a
	sleep 4
	run_adb shell "sh -c \"{ printf 'GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n'; sleep 10; } | nc 104.18.26.120 80 >/dev/null 2>&1 & pid=\\\$!; sleep 1; kill -9 \\\$pid; log -t TSOCKS_TEST 'event=TEST_PASS requestId=phase32-client-kill scenario=phase32-client-kill route=TAILNET_SOCKS detail=client_killed'; exit 0\""
	sleep 5
	REQUEST_ID="phase32-tailnet-close-$(date +%s)" SERIAL="${SERIAL:-}" sh "$repo_root/scripts/tsocks-test-trigger.sh" tailnet-tcp-close
	REQUEST_ID="phase32-tailnet-rst-$(date +%s)" SERIAL="${SERIAL:-}" sh "$repo_root/scripts/tsocks-test-trigger.sh" tailnet-tcp-rst || true
	sleep 3
	log_file="$tmp_dir/lifecycle.log"
	collect_logs "$log_file"
	assert_contains "lifecycle-syn" "event=syn_received .*flow_id=" "$log_file"
	assert_contains "lifecycle-synack" "event=synack_sent .*flow_id=" "$log_file"
	assert_contains "lifecycle-ack" "event=ack_seen .*flow_id=" "$log_file"
	assert_contains "lifecycle-fin" "event=fin_seen|event=finack_seen" "$log_file"
	assert_contains "lifecycle-rst" "event=rst_seen .*flow_id=" "$log_file"
	assert_contains "lifecycle-client-kill" "event=TEST_PASS .*scenario=phase32-client-kill" "$log_file"
	assert_contains "lifecycle-tailnet-close" "event=TEST_PASS .*scenario=tailnet-tcp-close" "$log_file"
	assert_contains "lifecycle-close-reason" "event=conn_close .*closeReason=(client_fin|server_fin|client_rst|server_rst|eof)" "$log_file"
}

cd "$repo_root"

if [ "$build_first" = "true" ]; then
	sh scripts/tsocks-test-build.sh
fi
if [ "$install_first" = "true" ]; then
	sh scripts/tsocks-test-install.sh
fi

prepare_device
run_baseline
run_concurrent_socks
run_concurrent_direct
run_concurrent_mixed
run_wrong_port
run_lifecycle

printf 'PHASE32_PASS\n'
