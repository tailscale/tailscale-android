// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Fragment;
import android.content.Intent;

public class Peer extends Fragment {

    private static int resultOK = -1;

    public class RequestCodes {
        public static final int requestPrepareVPN = 1001;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RequestCodes.requestPrepareVPN) {
            if (resultCode == resultOK) {
                App.getApplication().startVPN();
            } else {
                App.getApplication().setWantRunning(false);
                // notify VPN revoked
            }
        }
    }
}
