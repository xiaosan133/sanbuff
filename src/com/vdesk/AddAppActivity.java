package com.vdesk;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 添加应用：两种方式（手机已安装应用 / 数据区 APK）。 */
public class AddAppActivity extends Activity {

    private static final String TAG = "VDeskAdd";

    private ListView mList;
    private Button mBtnImport;
    private Button mBtnModePhone;
    private Button mBtnModeData;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // 手机模式数据（包可见，供静态嵌套适配器使用）
    List<ApplicationInfo> mInstalled = new ArrayList<>();
    boolean[] mCheckedPhone;
    /** **两个模式各自的勾选数组**：`mChecked` 只是"当前模式那个"的别名。
     *  只有一份数组时切回来会带着另一个模式的长度（数据区 3 个 APK → 切回手机模式 100 个应用
     *  ⇒ `mChecked[3]` 越界，实测崩在 RowAdapter.getView），而且各模式的勾选也会互相覆盖。 */
    boolean[] mCheckedApk;
    /** 当前模式的勾选数组（适配器与 doImport 都按它取下标）。 */
    boolean[] mChecked;

    // 数据区模式数据
    List<File> mApkFiles = new ArrayList<>();

    boolean mDataMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_app);

        mList = findViewById(R.id.list);
        mBtnImport = findViewById(R.id.btnImport);
        mBtnModePhone = findViewById(R.id.btnModePhone);
        mBtnModeData = findViewById(R.id.btnModeData);

        mBtnModePhone.setOnClickListener(v -> setMode(false));
        mBtnModeData.setOnClickListener(v -> setMode(true));

        mBtnImport.setOnClickListener(v -> doImport());

        loadInstalled();
        setMode(false);
    }

    private void loadInstalled() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> all = pm.getInstalledApplications(0);
        // 只留"用户能启动的应用"：排掉自身、系统应用（含被更新过的系统应用）、以及没有
        // LAUNCHER 入口的包（纯服务/库、框架组件）。光排 FLAG_SYSTEM 不够 —— 华为这类预装
        // vendor 应用 uid≥10000 但仍带这个位，靠"有启动入口"才滤得干净。
        List<ApplicationInfo> apps = new ArrayList<>();
        String self = getPackageName();
        for (ApplicationInfo ai : all) {
            if (ai.packageName.equals(self)) {
                continue;
            }
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                continue;
            }
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                continue;
            }
            apps.add(ai);
        }
        Collections.sort(apps, new AppLabelComparator(pm));
        mInstalled = apps;
        mCheckedPhone = new boolean[apps.size()];
    }

    private void setMode(boolean data) {
        mDataMode = data;
        mBtnModePhone.setEnabled(data);
        mBtnModeData.setEnabled(!data);
        if (data) {
            loadApkFiles();
        } else if (mCheckedPhone == null) {
            loadInstalled();
        }
        /* 切模式只换"当前那份"数组（长度必然与列表一致 ⇒ 适配器取下标不会越界）。 */
        mChecked = data ? mCheckedApk : mCheckedPhone;
        mBtnImport.setText("导入所选 (0)");
        mBtnImport.setEnabled(false);
        mList.setAdapter(new RowAdapter(this, mChecked));
    }

    private void loadApkFiles() {
        mApkFiles.clear();
        collectApks(getFilesDir(), mApkFiles);
        mCheckedApk = new boolean[mApkFiles.size()];
    }

    private void collectApks(File dir, List<File> out) {
        // 跳过已导入的目标布局目录，避免重复列出 base.apk
        String dn = dir.getName();
        if (dn.equals("target_apk") || dn.equals("target_cache")) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectApks(f, out);
            } else if (f.getName().endsWith(".apk")) {
                out.add(f);
            }
        }
    }

    void refreshImportButton() {
        int count = 0;
        for (boolean c : mChecked) {
            if (c) {
                count++;
            }
        }
        mBtnImport.setText("导入所选 (" + count + ")");
        mBtnImport.setEnabled(count > 0);
    }

    private void doImport() {
        final int total = mChecked.length;
        if (mDataMode) {
            // 数据区模式：APK 已在数据区，直接解析目标信息并注册
            for (int i = 0; i < total; i++) {
                if (mChecked[i]) {
                    File f = mApkFiles.get(i);
                    String name = f.getName().replace(".apk", "");
                    AppInfo app = resolveTargetInfo(name, name, f);
                    AppRepository.get().add(app);
                }
            }
            Toast.makeText(this, "已添加到桌面", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 手机模式：后台复制 APK 到 target_apk 布局 + 解压 lib + 解析 launcher
        final List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (mChecked[i]) {
                selected.add(i);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        mBtnImport.setEnabled(false);
        final PackageManager pm = getPackageManager();
        new Thread(() -> {
            int done = 0;
            for (int idx : selected) {
                final ApplicationInfo ai = mInstalled.get(idx);
                final String label = pm.getApplicationLabel(ai).toString();
                final String pkg = ai.packageName;
                AppInfo app = buildTargetApp(pkg, label, new File(ai.sourceDir));
                AppRepository.get().add(app);
                done++;
                final int progress = done;
                mHandler.post(() -> mBtnImport.setText("导入中 " + progress + "/" + selected.size()));
            }
            mHandler.post(() -> {
                Toast.makeText(AddAppActivity.this,
                        "已导入 " + selected.size() + " 个应用", Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }

    /** 手机模式：复制 APK 到 target_apk/<pkg>/base.apk，解压 native lib，解析 launcher。 */
    private AppInfo buildTargetApp(String pkg, String label, File srcApk) {
        File baseApk = new File(getFilesDir(), "target_apk/" + pkg + "/base.apk");
        try {
            if (!baseApk.exists()) {
                baseApk.getParentFile().mkdirs();
                copyFile(srcApk, baseApk);
            }
        } catch (IOException e) {
            android.util.Log.w(TAG, "copy APK failed for " + pkg + ": " + e.getMessage());
        }
        File libDir = new File(baseApk.getParentFile(), "lib");
        extractNativeLibs(baseApk, libDir);
        AppInfo app = resolveTargetInfo(pkg, label, baseApk);
        app.nativeDir = libDir.getAbsolutePath();
        return app;
    }

    /** 从本地 APK 解析 launcher/application 类，构造带目标信息的 AppInfo。 */
    private AppInfo resolveTargetInfo(String pkg, String label, File apk) {
        String[] found = null;
        try {
            found = ManifestFinder.findLauncher(apk.getAbsolutePath());
        } catch (Exception e) {
            android.util.Log.w(TAG, "resolve launcher failed for " + pkg + ": " + e.getMessage());
        }
        AppInfo app = new AppInfo(pkg, label);
        app.apkPath = apk.getAbsolutePath();
        if (found != null) {
            app.launcherClass = found[0];
            app.applicationClass = found[1];
            android.util.Log.i(TAG, pkg + " launcher=" + found[0]
                    + " appClass=" + found[1]);
        }
        app.cacheDir = new File(getFilesDir(), "target_cache/" + pkg).getAbsolutePath();
        new File(app.cacheDir).mkdirs();
        return app;
    }

    /** 从 APK 解压 lib/arm64-v8a/*.so 到 libDir。 */
    private int extractNativeLibs(File apk, File libDir) {
        int count = 0;
        libDir.mkdirs();
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                if (!name.startsWith("lib/arm64-v8a/") || ze.isDirectory()) {
                    continue;
                }
                String soName = name.substring(name.lastIndexOf('/') + 1);
                File out = new File(libDir, soName);
                if (out.exists()) {
                    continue;
                }
                try (InputStream zin = zf.getInputStream(ze);
                     FileOutputStream zout = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zin.read(buf)) >= 0) {
                        zout.write(buf, 0, n);
                    }
                }
                out.setExecutable(true);
                count++;
            }
        } catch (IOException e) {
            android.util.Log.w(TAG, "extract libs failed: " + e.getMessage());
        }
        return count;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /** 行适配器：手机模式显示已安装应用，数据区模式显示 APK 文件。 */
    private static class RowAdapter extends BaseAdapter {
        private final AddAppActivity mOuter;
        private final boolean[] mChecked;

        RowAdapter(AddAppActivity outer, boolean[] checked) {
            mOuter = outer;
            mChecked = checked;
        }

        @Override
        public int getCount() {
            /* 行数 = **构造时接过来的那份勾选数组**的长度：适配器只认它取下标，
             * 取"当前模式的列表长度"会在切模式的瞬间对不上（原来就是这么崩的）。 */
            return mChecked.length;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mOuter.getLayoutInflater()
                        .inflate(R.layout.item_install_app, parent, false);
            }
            final CheckBox check = convertView.findViewById(R.id.check);
            ImageView icon = convertView.findViewById(R.id.icon);
            TextView label = convertView.findViewById(R.id.label);
            TextView status = convertView.findViewById(R.id.status);

            String pkg;
            String name;
            if (mOuter.mDataMode) {
                name = mOuter.mApkFiles.get(position).getName();
                pkg = name;
                icon.setImageDrawable(mOuter.getDrawable(android.R.drawable.sym_def_app_icon));
            } else {
                ApplicationInfo ai = mOuter.mInstalled.get(position);
                pkg = ai.packageName;
                name = mOuter.getPackageManager().getApplicationLabel(ai).toString();
                Drawable d = null;
                try {
                    d = mOuter.getPackageManager().getApplicationIcon(pkg);
                } catch (Exception ignored) {
                }
                icon.setImageDrawable(d != null ? d
                        : mOuter.getDrawable(android.R.drawable.sym_def_app_icon));
            }

            boolean imported = AppRepository.get().contains(pkg);
            label.setText(name);
            status.setText(imported ? "已导入" : "");
            check.setChecked(mChecked[position]);
            check.setEnabled(!imported);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mChecked[position] = isChecked;
                mOuter.refreshImportButton();
            });
            /* CheckBox 本身 clickable/focusable=false（避免抢 ListView 的焦点），
             * 勾选只能由整行来触发；已导入的行不给点。 */
            convertView.setOnClickListener(imported ? null : v -> check.toggle());
            return convertView;
        }
    }

    /** 按应用名排序已安装应用。 */
    private static class AppLabelComparator implements Comparator {
        private final PackageManager mPm;

        AppLabelComparator(PackageManager pm) {
            mPm = pm;
        }

        @Override
        public int compare(Object a, Object b) {
            return mPm.getApplicationLabel((ApplicationInfo) a).toString()
                    .compareTo(mPm.getApplicationLabel((ApplicationInfo) b).toString());
        }
    }
}
