// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.app.Fragment;
import android.content.Intent;

public class Peer extends Fragment {
	@Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
		App.onActivityResult(getActivity(), requestCode, resultCode, data);
	}
}
