#!/bin/bash

if [[ -z "$TOOLCHAIN_DIR" ]]; then
    # By default, if TOOLCHAIN_DIR is unset, we assume we're
    # using the Tailscale Go toolchain (github.com/tailscale/go)
    # at the revision specified by go.toolchain.rev. If so,
    # we tell our caller to use the "tailscale_go" build tag.
    echo "tailscale_go"
else
    # Otherwise, if TOOLCHAIN_DIR is specified, we assume
    # we're F-Droid or something using a stock Go toolchain.
    # That's fine. But we don't set the tailscale_go build tag.
    # Return some no-op build tag that's non-empty for clarity
    # when debugging.
    echo "not_tailscale_go"
fi
