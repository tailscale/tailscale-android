// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.app.DialogFragment;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;

public class Peer extends Fragment {
	private final static int REQUEST_SIGNIN = 1001;
	private final static int REQUEST_PREPARE_VPN = 1002;

	@Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_SIGNIN:
			if (resultCode == Activity.RESULT_OK) {
				GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(getActivity());
				onSignin(acc.getIdToken());
				return;
			} else {
				onSignin(null);
			}
		case REQUEST_PREPARE_VPN:
			if (resultCode == Activity.RESULT_OK) {
				onVPNPrepared();
				return;
			}
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override public void onCreate(Bundle b) {
		super.onCreate(b);
		setRetainInstance(true);
		fragmentCreated();
	}

	@Override public void onDestroy() {
		fragmentDestroyed();
		super.onDestroy();
	}

	public void googleSignIn(String serverOAuthID) {
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
			.requestIdToken(serverOAuthID)
			.requestEmail()
			.build();
		GoogleSignInClient client = GoogleSignIn.getClient(getActivity(), gso);
		Intent signInIntent = client.getSignInIntent();
		startActivityForResult(signInIntent, REQUEST_SIGNIN);
	}

	public void prepareVPN() {
		Intent intent = VpnService.prepare(getActivity());
		if (intent == null) {
			onVPNPrepared();
		} else {
			startActivityForResult(intent, REQUEST_PREPARE_VPN);
		}
	}

	void showURL(String url) {
		CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
		int headerColor = 0xff496495;
		builder.setToolbarColor(headerColor);
		CustomTabsIntent intent = builder.build();
		intent.launchUrl(getActivity(), Uri.parse(url));
	}

	private native void fragmentCreated();
	private native void fragmentDestroyed();
	private native void onSignin(String idToken);
	private native void onVPNPrepared();
}
