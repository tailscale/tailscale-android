// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;

import java.util.List;

public final class IPNActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        handleIntent();
    }

    @Override
    public void onNewIntent(Intent i) {
        setIntent(i);
        handleIntent();
    }

    private void handleIntent() {
        Intent it = getIntent();
        String act = it.getAction();
        String[] texts;
        Uri[] uris;
        if (Intent.ACTION_SEND.equals(act)) {
            uris = new Uri[]{it.getParcelableExtra(Intent.EXTRA_STREAM)};
            texts = new String[]{it.getStringExtra(Intent.EXTRA_TEXT)};
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            List<Uri> extraUris = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            uris = extraUris.toArray(new Uri[0]);
            texts = new String[uris.length];
        } else {
            return;
        }
        String mime = it.getType();
        int nitems = uris.length;
        String[] items = new String[nitems];
        String[] mimes = new String[nitems];
        int[] types = new int[nitems];
        String[] names = new String[nitems];
        long[] sizes = new long[nitems];
        int nfiles = 0;
        for (int i = 0; i < uris.length; i++) {
            String text = texts[i];
            Uri uri = uris[i];
            if (text != null) {
                types[nfiles] = 1; // FileTypeText
                names[nfiles] = "file.txt";
                mimes[nfiles] = mime;
                items[nfiles] = text;
                // Determined by len(text) in Go to eliminate UTF-8 encoding differences.
                sizes[nfiles] = 0;
                nfiles++;
            } else if (uri != null) {
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c == null) {
                    // Ignore files we have no permission to access.
                    continue;
                }
                int nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeCol = c.getColumnIndex(OpenableColumns.SIZE);
                c.moveToFirst();
                String name = c.getString(nameCol);
                long size = c.getLong(sizeCol);
                types[nfiles] = 2; // FileTypeURI
                mimes[nfiles] = mime;
                items[nfiles] = uri.toString();
                names[nfiles] = name;
                sizes[nfiles] = size;
                nfiles++;
            }
        }
        // TODO(oxtoacart): actually implement this
//        App.onShareIntent(nfiles, types, mimes, items, names, sizes);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
    }
}
