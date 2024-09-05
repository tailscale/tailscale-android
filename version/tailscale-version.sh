#!/usr/bin/env bash

# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

# Print the version tailscale repository corresponding
# to the version listed in go.mod.

set -euo pipefail

# use the go toolchain from the tailscale.com
export TS_USE_TOOLCHAIN=1

go_list=$(go list -m tailscale.com)
# go list outputs `tailscale.com <version>`. Extract the version.
mod_version=${go_list#tailscale.com}

if [ -z "$mod_version" ]; then
	echo >&2 "no version reported by go list -m tailscale.com: $go_list"
	exit 1
fi

case "$mod_version" in
	*-*-*)
		# A pseudo-version such as "v1.1.1-0.20201030135043-eab6e9ea4e45"
		# includes the commit hash.
		mod_version=${mod_version##*-*-}
		;;
esac

tailscale_clone=$(mktemp -d -t tailscale-clone-XXXXXXXXXX)
git clone -q https://github.com/tailscale/tailscale.git "$tailscale_clone"

cd $tailscale_clone
git reset --hard -q
git clean -d -x -f
git fetch -q --all --tags
git checkout -q ${mod_version}

eval $(./build_dist.sh shellvars)
git_hash=$(git rev-parse HEAD)
short_hash=$(echo "$git_hash" | cut -c1-9)
echo ${VERSION_SHORT}-t${short_hash}
cd /tmp
rm -rf "$tailscale_clone"
