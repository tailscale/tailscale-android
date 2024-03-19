package com.tailscale.ipn;

import android.app.Activity;

import java.lang.reflect.Method;

public class MaybeGoogle {
    static boolean isGoogle() {
        return getGoogle() != null;
    }

    static String getIdTokenForActivity(Activity act) {
        Class<?> google = getGoogle();
        if (google == null) {
            return "";
        }
        try {
            Method method = google.getMethod("getIdTokenForActivity", Activity.class);
            return (String) method.invoke(null, act);
        } catch (Exception e) {
            return "";
        }
    }

    private static Class getGoogle() {
        try {
            return Class.forName("com.tailscale.ipn.Google");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
