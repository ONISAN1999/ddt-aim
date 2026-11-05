package com.ddt.aim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class InstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception ignored) { }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "ติดตั้งไม่สำเร็จ" + (msg != null ? ": " + msg : "") + "\nลองกด ‘ดาวน์โหลดผ่านเบราว์เซอร์’ ในหน้าอัปเดต", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ONISAN1999/ddt-aim/releases/latest/download/ddt-aim.apk"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); } catch (Exception ignored) { }
        }
    }
}
