#!/usr/bin/env sh

# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

# Print the version tailscale repository corresponding
# to the version listed in go.mod. The version is compatible with
# mkversion.sh.

set -euo pipefail

go_list=$(go list -m tailscale.com)
# go list outputs `tailscale.com <version>`. Extract the version.
mod_version=${go_list##* }

if [ -z "$mod_version" ]; then
	echo "no version reported by go list -m tailscale.com: $go_list"
	exit 1
fi

case "$mod_version" in
	*-*-*)
		# A pseudo-version such as "v1.1.1-0.20201030135043-eab6e9ea4e45"
		# includes the commit hash.
		commit=${mod_version##*-*-}
		version=${mod_version%%-*}
		echo "$version-0-g$commit"
		exit 0
		;;
esac

# Query git repository for the hash matching the tag.
git_ls_remote=$(git ls-remote --exit-code -t https://github.com/tailscale/tailscale "$mod_version^{}")
# Extract the commit. Note that git ls-remote separates fields with tabs.
commit=${git_ls_remote%%$'\t'*}
echo "$mod_version-0-g$commit"
