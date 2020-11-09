// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;

// Google implements helpers for Google services.
public final class Google {
	static String getIdTokenForActivity(Activity act) {
		GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(act);
		return acc.getIdToken();
	}

	static void googleSignIn(Activity act, String serverOAuthID, int reqCode) {
		act.runOnUiThread(new Runnable() {
			@Override public void run() {
				GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
					.requestIdToken(serverOAuthID)
					.requestEmail()
					.build();
				GoogleSignInClient client = GoogleSignIn.getClient(act, gso);
				Intent signInIntent = client.getSignInIntent();
				App.startActivityForResult(act, signInIntent, reqCode);
			}
		});
	}

	static void googleSignOut(Context ctx) {
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
			.build();
		GoogleSignInClient client = GoogleSignIn.getClient(ctx, gso);
		client.signOut();
	}
}
