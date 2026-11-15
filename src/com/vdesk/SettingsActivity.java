package com.vdesk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页（骨架阶段以提示为主）。 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        TextView rowSpace = findViewById(R.id.rowSpace);
        rowSpace.setText("虚拟空间容量：" + AppRepository.get().size() + " 个应用");

        findViewById(R.id.rowClean).setOnClickListener(v ->
                Toast.makeText(this, "缓存已清理（骨架占位）", Toast.LENGTH_SHORT).show());
        findViewById(R.id.rowStorage).setOnClickListener(v ->
                Toast.makeText(this, "数据区：" + getFilesDir(), Toast.LENGTH_LONG).show());
        findViewById(R.id.rowPerm).setOnClickListener(v -> requestMissingPermissions());
        findViewById(R.id.rowAbout).setOnClickListener(v ->
                Toast.makeText(this, "虚拟桌面 v0.1 骨架", Toast.LENGTH_SHORT).show());
    }

    /** 只请求未授予的权限，避免对已授权权限重复弹窗。 */
    private void requestMissingPermissions() {
        String[] missing = MainActivity.missingPermissions(this);
        if (missing.length == 0) {
            Toast.makeText(this, "已全部授权", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(missing, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            int granted = 0;
            for (int r : grantResults) {
                if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) granted++;
            }
            Toast.makeText(this, "授权完成：" + granted + "/" + grantResults.length
                    + "（已授予的权限对所有虚拟应用生效）", Toast.LENGTH_LONG).show();
        }
    }
}
