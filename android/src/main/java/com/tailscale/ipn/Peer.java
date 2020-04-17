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

/*import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;*/

public class Peer extends Fragment {
	//private final static int REQUEST_SIGNIN = 1001;
	private final static int REQUEST_PREPARE_VPN = 1002;

	@Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		/*case REQUEST_SIGNIN:
			if (resultCode == Activity.RESULT_OK) {
				GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(getActivity());
				android.util.Log.i("gio", "Account: " + acc.getId());
				onSignin();
				return;
			}*/
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
		fragmentCreated();
	}

	@Override public void onDestroy() {
		fragmentDestroyed();
		super.onDestroy();
	}

	/*public void googleSignIn() {
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
			.build();
		GoogleSignInClient client = GoogleSignIn.getClient(getActivity(), gso);
		Intent signInIntent = client.getSignInIntent();
		startActivityForResult(signInIntent, REQUEST_SIGNIN);
	}*/

	public void prepareVPN() {
		Intent intent = VpnService.prepare(getActivity());
		if (intent == null) {
			onVPNPrepared();
		} else {
			startActivityForResult(intent, REQUEST_PREPARE_VPN);
		}
	}

	public void showURLActionView(String url) {
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setData(Uri.parse(url));
		startActivity(i);
	}

	public void showURLCustomTabs(String url) {
		CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
		CustomTabsIntent intent = builder.build();
		intent.launchUrl(getActivity(), Uri.parse(url));
	}

	public void showURLWebView(String url) {
		DialogFragment f = new WebViewFragment();
		Bundle args = new Bundle();
		args.putString("url", url);
		f.setArguments(args);
		f.show(getFragmentManager(), "urldialog");
	}

	private native void fragmentCreated();
	private native void fragmentDestroyed();
	private native void onSignin();
	private native void onVPNPrepared();

	public static class WebViewFragment extends DialogFragment {
		@Override public Dialog onCreateDialog(Bundle savedInstanceState) {
			String url = getArguments().getString("url");
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			WebView wv = new WebView(builder.getContext()) {
				@Override public boolean onCheckIsTextEditor() {
					// Force the soft keyboard to appear when a text
					// input is focused.
					return true;
				}
			};
			wv.setFocusable(true);
			wv.setFocusableInTouchMode(true);
			wv.getSettings().setJavaScriptEnabled(true);
			// Work around Google OAuth refusing to work in embedded
			// browsers.
			final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.13; rv:61.0) Gecko/20100101 Firefox/61.0";
			wv.getSettings().setUserAgentString(USER_AGENT);
			wv.setWebViewClient(new WebViewClient() {
			});
			wv.loadUrl(url);
			return builder.setView(wv).create();
		}
	}
}
