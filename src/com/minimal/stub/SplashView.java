package com.minimal.stub;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 首屏内容：目标应用图标 + 名称 + 等待指示。**只在这里画一次**，两个使用方共用：
 *   ① {@link StubActivity} —— 真实首屏（跑在 stub 进程里）
 *   ② {@code com.vdesk.SplashPreviewActivity} —— 宿主进程里的预览页（调观感用）
 * 同一份实现 ⇒ 预览看到的就等于真实首屏。
 *
 * 底色与等待指示**都在这里**（窗口背景只给同色纯色）：
 *   · 底色放 view（而不是只靠窗口背景）是为了**出场能整块淡出**（窗口背景换不掉 alpha）——
 *     淡出过程就是往目标首帧的交叉溶解；淡完再由 StubActivity 把窗口背景撤成透明。
 *   · 等待指示用框架 ProgressBar（白 tint）。原来它在窗口背景里（launch_bg 的 layer-list +
 *     animated-vector），**实测画出来只有几个 dp、基本看不见**（缩放没生效），所以挪进来。
 *
 * 【2026-10-04 改】内容**整块进首帧**，不再 `alpha=0` + 等 attach 淡入：
 *   · 原来 `setAlpha(0f)` + `playEnter()` 里 `post` 到 attach 才淡入 ⇒ 视图 attach/动画推进之前
 *     屏幕上只有窗口背景（`vdesk_launch_bg` = #161823 近黑）。目标初始化慢时（抖音 ~18s）看上去
 *     就是"黑屏"—— 可这几样**本来就是 stub 页自己的内容**，没有理由晚出来。
 *   · 图标**同步**取：`pm.getActivityIcon` 实测 ~57ms（binder 进 PM 节点 + 建目标 APK 的 Resources +
 *     解码图），相对目标那十几秒可以忽略；同步取 ⇒ 图标和名字一起进首帧。（一度改成后台线程取，
 *     结果图标总是比名字晚冒出来 —— 那 57ms 只是换个地方花，观感反而差。）
 *   · 时间顺序天然满足"先拿到图标再做重活"：`首屏已铺上`（本构造函数）**早于** `engine_apply_target`
 *     / rebind（实测 03.846 vs 03.881）。
 *
 * 引擎包 `com.minimal.*` **不许依赖 UI 包 `com.vdesk.*`**（方向是 UI → 引擎），
 * 而 `R` 是 UI 包里的生成类 ⇒ 这里一个 `R.xxx` 都没有，界面全代码搭。
 */
public class SplashView extends FrameLayout {

    /** 图标缩放入场时长。 */
    public static final long FADE_IN_MS = 240;
    /** 出场时长（整块淡出）。 */
    public static final long FADE_OUT_MS = 180;

    /** 首屏底色：= res/values/colors.xml 的 vdesk_launch_bg（与窗口背景同色 ⇒ 淡入淡出无缝）。
     *  引擎包不能引 UI 包的 `R`，所以这里写字面量；两处颜色要一起改。 */
    private static final int SPLASH_BG = 0xFF161823;

    private ImageView mIcon;

    /** 按目标包/类解析图标与名称后建首屏。
     *  `label` 由调用方优先给（宿主桌面上的名字，可能被改过别名）——空则问 PackageManager。
     *  解析失败的部分直接省略（不画假图标、不写假名字）。 */
    public static SplashView forTarget(Context ctx, String pkg, String cls, String label) {
        if (label == null || label.trim().isEmpty()) {
            label = labelOf(ctx, pkg);
        }
        return new SplashView(ctx, iconOf(ctx, pkg, cls), label);
    }

    public SplashView(Context ctx, Drawable icon, String label) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        setBackgroundColor(SPLASH_BG);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams center = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                               ViewGroup.LayoutParams.WRAP_CONTENT);
        center.gravity = Gravity.CENTER;
        addView(box, center);

        if (icon != null) {
            mIcon = new ImageView(ctx);
            mIcon.setImageDrawable(icon);
            box.addView(mIcon, new LinearLayout.LayoutParams((int) (76 * d), (int) (76 * d)));
        }
        if (label != null && !label.trim().isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setTextSize(17);
            tv.setTextColor(0xFFE9EBF3);            /* = colors.xml 的 vdesk_text */
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setMaxWidth((int) (240 * d));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = (int) (12 * d);
            box.addView(tv, p);
        }
        /* 等待指示：框架的转圈（不定进度）。tint 成白色 —— 主题是 Light，默认取的是浅色强调色。 */
        ProgressBar wait = new ProgressBar(ctx, null, android.R.attr.progressBarStyle);
        wait.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d));
        wp.topMargin = (int) (24 * d);
        box.addView(wait, wp);

        /* 不再 setAlpha(0f)：底色/图标/名字/转环直接进第一次绘制。 */
    }

    /** 图标的 0.88→1 缩放入场（可选动效；视图可见性不依赖它 —— 没调也照样显示）。
     *  **必须 attach 之后再 start**：onCreate 里视图还没挂窗口，那时 start() 有掉的风险。 */
    public void playEnter() {
        if (mIcon == null) return;
        mIcon.setScaleX(0.88f);
        mIcon.setScaleY(0.88f);
        mIcon.animate().scaleX(1f).scaleY(1f)
                .setDuration(FADE_IN_MS + 80)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 出场：整块淡出。窗口背景要等这之后再由调用方撤，见类注释。 */
    public void playExit() {
        animate().alpha(0f).setDuration(FADE_OUT_MS).start();
    }

    /** 目标图标：优先 launcher Activity 的图标（部分应用两者不同），退回应用图标，
     *  再退回桌面用的那张默认图（与桌面保持一致，不出现两套占位图）。 */
    private static Drawable iconOf(Context ctx, String pkg, String cls) {
        if (ctx == null || pkg == null || pkg.trim().isEmpty()) return null;
        PackageManager pm = ctx.getPackageManager();
        try {
            if (cls != null && !cls.trim().isEmpty()) {
                return pm.getActivityIcon(new ComponentName(pkg, cls));
            }
        } catch (Throwable ignore) {
        }
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Throwable ignore) {
        }
        try {
            return ctx.getDrawable(android.R.drawable.sym_def_app_icon);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String labelOf(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (Throwable ignore) {
            return null;
        }
    }
}
