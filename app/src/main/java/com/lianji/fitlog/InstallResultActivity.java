package com.lianji.fitlog;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public final class InstallResultActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = getIntent();
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        try {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33 ? result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : result.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) throw new IllegalStateException("Missing install confirmation");
                startActivity(confirm);
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                show(status == PackageInstaller.STATUS_FAILURE_ABORTED ? "已取消安装更新。" : "系统安装失败，请重新检查更新。");
            }
        } catch (RuntimeException error) {
            show("无法打开系统安装界面，请重试。");
        } finally {
            finish();
        }
    }

    private void show(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
