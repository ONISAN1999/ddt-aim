package com.ddt.aim;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible activity that asks for screen-capture permission and hands the result to BubbleService. */
public class ScreenCapActivity extends Activity {
    private static final int REQ = 7777;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(m.createScreenCaptureIntent(), REQ);
        } catch (Exception e) {
            send(RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ) send(res, data);
        else super.onActivityResult(req, res, data);
    }

    private void send(int res, Intent data) {
        Intent i = new Intent(this, BubbleService.class);
        if (res == RESULT_OK && data != null) {
            i.setAction("cap");
            i.putExtra("code", res);
            i.putExtra("data", data);
        } else {
            i.setAction("capfail");
        }
        startService(i);
        finish();
        overridePendingTransition(0, 0);
    }
}
