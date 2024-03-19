// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;

public class Peer extends Fragment {
    private static native void onActivityResult0(Activity act, int reqCode, int resCode);

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        onActivityResult0(getActivity(), requestCode, resultCode);
    }
}
