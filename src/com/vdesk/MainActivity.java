package com.vdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.content.pm.PackageManager;


import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 虚拟桌面主界面：垂直滚动的图标网格 + 底部 Dock。 */
public class MainActivity extends Activity {

    /** 危险权限：授予 com.vdesk（host）后所有虚拟化子程序共享（同一 uid/进程）。 */
    public static final String[] PERMISSIONS = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_CALENDAR,
    };
    private static final int REQ_LAUNCH_PERM = 100;

    /** 未授予的权限列表（避免对已授权权限重复弹窗）。 */
    public static String[] missingPermissions(android.app.Activity a) {
        java.util.List<String> m = new java.util.ArrayList<>();
        for (String p : PERMISSIONS) {
            if (a.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                m.add(p);
            }
        }
        return m.toArray(new String[0]);
    }

    private GridView mGrid;
    private View mEmptyHint;
    private TextView mDeviceBadge;
    private AppInfo mPendingLaunch;
    /** 应用图标缓存（键 = 包名）。 */
    private final LruCache<String, Drawable> mIconCache = new LruCache<>(64);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /* 主进程首次启动时生成 PM 基础模板（供所有目标应用共用；子进程只读）。
         * 参考 jni/common/mkarchive.c 与 PmBase 的说明。已生成且不过期时只做一次 stat。 */
        PmBase.ensure(this);

        mGrid = findViewById(R.id.grid);
        mEmptyHint = findViewById(R.id.emptyHint);
        mDeviceBadge = findViewById(R.id.deviceBadge);

        mDeviceBadge.setOnClickListener(v ->
                startActivity(new Intent(this, DeviceProfileActivity.class)));

        findViewById(R.id.btnEmptyAdd).setOnClickListener(v ->
                startActivity(new Intent(this, AddAppActivity.class)));
        findViewById(R.id.btnAdd).setOnClickListener(v ->
                startActivity(new Intent(this, AddAppActivity.class)));
        findViewById(R.id.btnMarket).setOnClickListener(v ->
                startActivity(new Intent(this, MarketActivity.class)));
        findViewById(R.id.btnProfile).setOnClickListener(v ->
                startActivity(new Intent(this, DeviceProfileActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mDeviceBadge.setText("设备 " + DeviceProfileStore.get().getActive().id);
        buildGrid();
    }

    /** 点击图标：写入目标信息并启动 stub 进程运行虚拟应用。 */
    void launchApp(AppInfo app) {
        if (app.apkPath == null || app.launcherClass == null) {
            Toast.makeText(this, "该应用无目标信息，请重新导入", Toast.LENGTH_SHORT).show();
            return;
        }
        /* 启动前补授权：部分授权可能仅本次有效/被撤销。缺则先请求，结果回调后再启动。 */
        String[] missing = missingPermissions(this);
        if (missing.length > 0) {
            mPendingLaunch = app;
            requestPermissions(missing, REQ_LAUNCH_PERM);
            return;
        }
        doLaunch(app);
    }

    private void doLaunch(AppInfo app) {
        /* 多 app 并存：从闲置 actstub 池分配一个进程作为该 app 的虚拟主进程
         * （每 app 一进程，可同时运行多个；act 池只按包名单实例复用）。
         * 分配失败回退 :stub1。 */
        /* 生效档案 id：池分配与启动 Intent 都要带 —— 档案 id 跟目标信息走同一套通道
         * （act 池走 provider 槽，:stub1 走 Intent extras）。 */
        String profile = com.vdesk.DeviceProfileStore.activeId();
        /* 槽分配走**载体方法**：VApp.allocateActIdx 的 qcc 已被 native 换掉，逻辑在
         * native 里直接调 pool_core_allocate ⇒ 不再打 ProcessPoolProvider（池状态本来就在
         * native）。返回 0 = 没槽 → 回退 :stub1。 */
        /* **必须反射调**：唯一改 qcc 的载体抓不到"解释器链内互调"（引擎 invoke_qcc.c 记过、
         * 本次实测确认）—— 本进程是 debuggable、无 AOT、全解释执行，直接 invoke 根本不走 qcc；
         * 反射（同 JNI）经 invoke 路径会走 qcc。宿主 UI 侧没有检测面，反射代价可忽略。 */
        int idx = 0;
        try {
            java.lang.reflect.Method m = com.minimal.virtual.VApp.class
                    .getMethod("allocateActIdx", String.class, String.class);
            idx = (Integer) m.invoke(getApplication(), app.packageName, profile);
        } catch (Throwable t) {
            android.util.Log.w("VDesk", "act 槽分配载体调用失败: " + t);
        }
        String stubActivity = (idx > 0)
                ? "com.minimal.stub.StubActivityAct" + idx
                : "com.minimal.stub.StubActivity";
        /* 目标信息就靠这几个 extras 传进去（不再写 files/target.info）：
         * 池进程走 provider 槽，拿不到槽时（回退到 :stub1）由引擎从首个
         * EXECUTE_TRANSACTION 的 Intent 里读 —— 见 hbind_exec.c。 */
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.vdesk", stubActivity));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("TARGET_CLASS", app.launcherClass);
        /* 首屏（StubActivity 的过渡页）要显示目标应用名 —— 给成**桌面显示的那个名字**
         * （含别名），不然改过别名的应用在首屏会突然变回原名。 */
        intent.putExtra("TARGET_LABEL", app.aliasFor(profile));
        intent.putExtra("TARGET_APK", app.apkPath);
        intent.putExtra("TARGET_PACKAGE", app.packageName);
        intent.putExtra("TARGET_APP_CLASS", app.applicationClass);
        intent.putExtra("TARGET_NATIVE_DIR", app.nativeDir);
        intent.putExtra("TARGET_PROFILE", profile);
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LAUNCH_PERM && mPendingLaunch != null) {
            AppInfo app = mPendingLaunch;
            mPendingLaunch = null;
            doLaunch(app);
        }
    }

    /** 长按图标弹出菜单：修改别名 / 卸载。 */
    void showAppMenu(View anchor, AppInfo app) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "修改别名");
        menu.getMenu().add(0, 2, 0, "卸载");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Intent i = new Intent(this, AliasEditActivity.class);
                i.putExtra("packageName", app.packageName);
                i.putExtra("label", app.label);
                startActivity(i);
            } else {
                removeApp(app);
            }
            return true;
        });
        menu.show();
    }

    /** 卸载：确认后从桌面移除（持久化）。 */
    void removeApp(AppInfo app) {
        new AlertDialog.Builder(this)
                .setTitle("卸载应用")
                .setMessage("从虚拟桌面移除「" + app.label + "」？")
                .setPositiveButton("移除", (dialog, which) -> {
                    AppRepository.get().remove(app.packageName);
                    buildGrid();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 网格内容：空则显示引导块，否则铺图标（GridView 自己垂直滚动）。 */
    private void buildGrid() {
        List<AppInfo> apps = AppRepository.get().getApps();
        boolean empty = apps.isEmpty();
        mEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        mGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            mGrid.setAdapter(new AppGridAdapter(this, apps));
        }
    }

    /** 网格适配器。图标按包名缓存：原来每格 getApplicationIcon 都过一次 PackageManager。 */
    private static class AppGridAdapter extends BaseAdapter {
        private final MainActivity mActivity;
        private final List<AppInfo> mApps;

        AppGridAdapter(MainActivity activity, List<AppInfo> apps) {
            mActivity = activity;
            mApps = apps;
        }

        @Override
        public int getCount() {
            return mApps.size();
        }

        @Override
        public Object getItem(int position) {
            return mApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View itemView = convertView != null ? convertView
                    : mActivity.getLayoutInflater().inflate(R.layout.item_app, parent, false);
            AppInfo app = (AppInfo) getItem(position);
            String shown = app.aliasFor(DeviceProfileStore.get().getActive().id);
            ((TextView) itemView.findViewById(R.id.appLabel)).setText(shown);
            ((ImageView) itemView.findViewById(R.id.appIcon)).setImageDrawable(loadIcon(app));
            itemView.setOnClickListener(v -> mActivity.launchApp(app));
            itemView.setOnLongClickListener(v -> {
                mActivity.showAppMenu(itemView, app);
                return true;
            });
            return itemView;
        }

        private Drawable loadIcon(AppInfo app) {
            Drawable cached = mActivity.mIconCache.get(app.packageName);
            if (cached != null) {
                return cached;
            }
            Drawable d;
            try {
                d = mActivity.getPackageManager().getApplicationIcon(app.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                d = mActivity.getDrawable(android.R.drawable.sym_def_app_icon);
            }
            if (d != null) {
                mActivity.mIconCache.put(app.packageName, d);
            }
            return d;
        }
    }
}
