package com.vdesk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** 修改应用别名：直接应用到当前生效档案。 */
public class AliasEditActivity extends Activity {

    private String mPkg;
    private EditText mAlias;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alias_edit);

        mPkg = getIntent().getStringExtra("packageName");
        String label = getIntent().getStringExtra("label");

        final AppInfo app = findApp();
        final DeviceProfile active = DeviceProfileStore.get().getActive();

        ((TextView) findViewById(R.id.appName)).setText("修改别名 - " + label);
        ((TextView) findViewById(R.id.hint))
                .setText("应用到当前生效设备「" + active.id + "」（留空 = 使用原名）");

        mAlias = findViewById(R.id.alias);
        mAlias.setHint("原名：" + label);
        if (app != null) {
            String current = app.aliasFor(active.id);
            if (!current.equals(label)) {
                mAlias.setText(current);
            }
        }

        Button save = findViewById(R.id.btnSave);
        save.setOnClickListener(v -> {
            if (app != null) {
                app.setAlias(active.id, mAlias.getText().toString());
                AppRepository.get().persist();
            }
            finish();
        });
    }

    private AppInfo findApp() {
        for (AppInfo a : AppRepository.get().getApps()) {
            if (a.packageName.equals(mPkg)) {
                return a;
            }
        }
        return null;
    }
}
