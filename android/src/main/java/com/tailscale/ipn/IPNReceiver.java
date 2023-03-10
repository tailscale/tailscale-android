package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class IPNReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == "com.tailscale.ipn.CONNECT_VPN") {
            connect();
        } else if (intent.getAction() == "com.tailscale.ipn.DISCONNECT_VPN") {
            disconnect();
        }
    }

    private native void connect();
    private native void disconnect();
}
