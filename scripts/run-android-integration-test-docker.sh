#!/usr/bin/env bash
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause

set -euo pipefail

AVD="${AVD:-tailscale-integration}"
AVD_IMAGE="${AVD_IMAGE:-system-images;android-33;google_apis;x86_64}"
KEYCODE_MENU=82
APK="${1:-/workspace/tailscale-debug.apk}"
shift || true

export GOPATH="${GOPATH:-${HOME}/.cache/go}"
export GOMODCACHE="${GOMODCACHE:-${GOPATH}/pkg/mod}"

begin_section() {
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        echo "::group::$1"
    fi
}

end_section() {
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        echo "::endgroup::"
    fi
}

if [[ ! -f "${APK}" ]]; then
    echo "APK not found: ${APK}" >&2
    exit 1
fi

if [[ ! -e /dev/kvm ]]; then
    echo "/dev/kvm is not available. Run this container with --device /dev/kvm." >&2
    exit 1
fi

begin_section "Start emulator"
mkdir -p "${HOME}/.android"
touch "${HOME}/.android/repositories.cfg"

if ! avdmanager list avd | grep -q "Name: ${AVD}$"; then
    echo "Creating AVD ${AVD} (${AVD_IMAGE})"
    echo "no" | avdmanager create avd -n "${AVD}" -k "${AVD_IMAGE}" --device pixel
fi

emulator -avd "${AVD}" \
    -no-window \
    -no-audio \
    -no-snapshot \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -netdelay none \
    -netspeed full &
emulator_pid=$!

cleanup() {
    adb emu kill >/dev/null 2>&1 || true
    wait "${emulator_pid}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb wait-for-device

deadline=$((SECONDS + 180))
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
    if (( SECONDS > deadline )); then
        end_section
        echo "Timed out waiting for emulator boot" >&2
        adb devices -l >&2 || true
        exit 1
    fi
    sleep 2
done

adb shell input keyevent "${KEYCODE_MENU}" >/dev/null 2>&1 || true
end_section

./tool/go run tailscale.com/cmd/testwrapper ./integration/androidvmtest \
    -count=1 \
    -v \
    -timeout=30m \
    -android.apk="${APK}" \
    "$@"
