#!/usr/bin/env bash
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
# deeplink-probe.sh fires a tailscale://navigate/<path> URI at an attached
# device three ways (BROWSABLE implicit, bare implicit, explicit component)
# and tails the relevant logcat lines so you can confirm DeepLinkNavigator
# saw the intent. Pass --routes to list the URI templates the app handles.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NAV_KT="${SCRIPT_DIR}/../android/src/main/java/com/tailscale/ipn/ui/util/DeepLinkNavigator.kt"

show_routes() {
  if [ ! -f "$NAV_KT" ]; then
    echo "Could not locate DeepLinkNavigator.kt at $NAV_KT" >&2
    return 1
  fi

  # Header comment lists URI templates as `//   tailscale://...`.
  local templates
  templates=$(awk '/^\/\/[[:space:]]+tailscale:\/\//{ sub(/^\/\/[[:space:]]+/, ""); print }' "$NAV_KT")

  # `settingsSubRoutes = setOf("about", "bugReport", ...)` — pull the quoted names.
  local sub_routes
  sub_routes=$(awk '
    /settingsSubRoutes[[:space:]]*=/ { flag=1 }
    flag { print }
    flag && /\)/ { exit }
  ' "$NAV_KT" | grep -oE '"[A-Za-z]+"' | tr -d '"')

  echo "Known DeepLinkNavigator routes (parsed from $(basename "$NAV_KT")):"
  echo
  while IFS= read -r tpl; do
    [ -z "$tpl" ] && continue
    if [[ "$tpl" == *"[/<subRoute>]"* ]]; then
      local base="${tpl%\[/*}"
      echo "  $base"
      while IFS= read -r r; do
        [ -z "$r" ] && continue
        echo "  $base/$r"
      done <<< "$sub_routes"
    else
      echo "  $tpl"
    fi
  done <<< "$templates"

  echo
  echo "Usage: $(basename "$0") [--routes] [path-after-navigate]"
  echo "  default path: main/devices"
}

case "${1:-}" in
  --routes|-l|--list)
    show_routes
    exit 0
    ;;
  -h|--help)
    show_routes
    exit 0
    ;;
esac

PATH_TAIL="${1:-main/devices}"
URI="tailscale://navigate/${PATH_TAIL}"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_FILE="${OUT_DIR}/deeplink-probe.log"

echo "URI: ${URI}"
echo "Logging to ${OUT_FILE}"
echo

adb logcat -c

{
  echo "=== implicit (BROWSABLE) ==="
  adb shell "am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${URI}'"
  echo

  echo "=== implicit (no category) ==="
  adb shell "am start -W -a android.intent.action.VIEW -d '${URI}'"
  echo

  echo "=== explicit component ==="
  adb shell "am start -W -n com.tailscale.ipn/.MainActivity -a android.intent.action.VIEW -d '${URI}'"
  echo
} | tee "${OUT_FILE}"

sleep 1

{
  echo
  echo "=== logcat ==="
  adb logcat -d -v brief | grep -E "Main Activity|DeepLinkNavigator"
} | tee -a "${OUT_FILE}"

echo
echo "Full output: ${OUT_FILE}"
