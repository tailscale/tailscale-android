#!/bin/sh
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
# docker-build-apt-get.sh runs 'apt'-related commands inside
# the environment that /builds the docker image/
set -x
set -e

apt-get update
apt-get -y upgrade

apt-get -y install \
        \
	libstdc++6 \
	libz1 \
	make \
	unzip \
	zip \
	\
	# end of sort region

apt-get -y --no-install-recommends install \
    \
    ca-certificates \
    curl \
    gcc \
    git \
    libc6-dev \
    \
    # end of sort region

apt-get -y clean

rm -rf \
   /var/cache/debconf \
   /var/lib/apt/lists \
   /var/lib/apt/dpkg
