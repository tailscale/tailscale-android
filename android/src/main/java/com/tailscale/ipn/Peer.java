// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Fragment;
import android.content.Intent;

public class Peer extends Fragment {

    private static int resultOK = -1;

    public class RequestCodes {
        public static final int requestSignin = 1000;
        public static final int requestPrepareVPN = 1001;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case RequestCodes.requestSignin:
                if (resultCode != resultOK) {
                    // TODO: send null Google token
                    break;
                }
                // TODO: send Google token
            case RequestCodes.requestPrepareVPN:
                if (resultCode == resultOK) {
                    App.getApplication().startVPN();
                } else {
                    App.getApplication().setWantRunning(false);
                    // notify VPN revoked
                }

        }
    }
}
