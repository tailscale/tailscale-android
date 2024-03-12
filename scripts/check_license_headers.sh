#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
# check_license_headers.sh checks that source files in the given
# directory tree have a correct-looking Tailscale license header.

check_file() {
	got=$1

	want=$(
		cat <<EOF
// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
EOF
	)
	if [ "$got" = "$want" ]; then
		return 0
	fi
	return 1
}

if [ $# != 1 ]; then
	echo "Usage: $0 rootdir" >&2
	exit 1
fi

fail=0
for file in $(find $1 \( -name '*.go' -or -name '*.tsx' -or -name '*.ts' -or -name '*.kt' -or -name '*.java' -not -name '*.config.ts' \) -not -path '*/.git/*' -not -path '*/node_modules/*'); do
	case $file in
	*)
		header="$(head -2 $file)"
		;;
	esac
	if [ ! -z "$header" ]; then
		if ! check_file "$header"; then
			fail=1
			echo "${file#$1/} doesn't have the right copyright header:"
			echo "$header" | sed -e 's/^/    /g'
		fi
	fi
done

if [ $fail -ne 0 ]; then
	exit 1
fi
