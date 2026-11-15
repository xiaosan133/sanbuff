package com.vdesk;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.minimal.stub.SplashView;

/**
 * **开发预览页**：在宿主进程里直接看首屏（目标图标 + 名称 + 居中转环 + 入/出场动效）。
 *
 * 为什么要有它：真实首屏跑在 stub 进程里、只活几百毫秒，目标一崩连窗口一起没（很难抓帧）。
 * 把首屏抽成 {@link SplashView} 之后，这里用**同一份实现**就能反复看、反复调 ——
 * 预览看到的就是真实首屏（连窗口背景都一样：本页主题 = VDeskLaunchTheme）。
 *
 * 用法：
 *   adb shell am start -n com.vdesk/com.vdesk.SplashPreviewActivity --es PKG com.xingin.xhs
 *   （不给 PKG 就取桌面上第一个应用；--es LABEL 可覆盖显示名）
 * 点屏幕重放入场动效；1.4s 后自动播出场（模拟"目标就绪 → 首屏淡出"）。
 *
 * **只是调观感用**，引擎路径不经过本页。
 */
public class SplashPreviewActivity extends Activity {

    /** 入场播完停留多久再播出场（模拟目标加载时间）。 */
    private static final long HOLD_MS = 1400;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SplashView mSplash;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String pkg = getIntent().getStringExtra("PKG");
        String label = getIntent().getStringExtra("LABEL");
        if (pkg == null || pkg.trim().isEmpty()) {
            java.util.List<AppInfo> apps = AppRepository.get().getApps();
            if (!apps.isEmpty()) {
                pkg = apps.get(0).packageName;
                if (label == null) {
                    label = apps.get(0).aliasFor(DeviceProfileStore.activeId());
                }
            }
        }

        mSplash = SplashView.forTarget(this, pkg, null, label);

        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(mSplash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("点一下重放 · 返回键退出 · pkg=" + (pkg == null ? "?" : pkg));
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = (int) (32 * getResources().getDisplayMetrics().density);
        wrap.addView(hint, hp);

        wrap.setOnClickListener(v -> replay());
        setContentView(wrap);
        replay();
    }

    /** 重放一遍：入场 → 停留 → 出场。 */
    private void replay() {
        mHandler.removeCallbacksAndMessages(null);
        mSplash.playEnter();
        mHandler.postDelayed(this::playExit, HOLD_MS);
    }

    private void playExit() {
        mSplash.playExit();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }
}
