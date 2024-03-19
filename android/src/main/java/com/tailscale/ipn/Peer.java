// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Fragment;
import android.content.Intent;

import libtailscale.Libtailscale;

public class Peer extends Fragment {
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Libtailscale.onActivityResult(requestCode, resultCode, MaybeGoogle.getIdTokenForActivity(getActivity()));
    }
}
