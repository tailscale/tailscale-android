#!/bin/sh

# Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
# Use of this source code is governed by a BSD-style
# license that can be found in the LICENSE file.

set -eu

mode=$1
describe=$2

long() {
    ver="${describe#v}"
    stem="${ver%%-*}"
    case "$stem" in
        *.*.*)
            # Full SemVer, nothing to do.
            semver="${stem}"
        ;;
        *.*)
            # Old style major.minor, add a .0
            semver="${stem}.0"
            ;;
        *)
            echo "Unparseable version $stem" >&2
            exit 1
            ;;
    esac
    suffix="${ver#$stem}"
    case "$suffix" in
        -*-*)
            # Has a change count in addition to the commit hash.
        ;;
        -*)
            # Missing change count, add one.
            suffix="-0${suffix}"
            ;;
        *)
            echo "Unexpected version suffix" >&2
            exit 1
    esac
    echo "${semver}${suffix}"
}

short() {
    ver="$(long)"
    case "$ver" in
        *-*-*)
            echo "${ver%-*}"
            ;;
        *-*)
            echo "$ver"
            ;;
        *)
            echo "Long version in invalid format" >&2
            exit 1
            ;;
    esac
}

case "$mode" in
    long)
        long
    ;;
    short)
        short
    ;;
esac
