package com.minimal.stub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import com.minimal.virtual.VApp;

/**
 * 宿主占位 Activity：系统侧只认识它，它同时也是启动首屏。
 *
 * 系统那条 launch 事务原样执行 → 本类真正 onCreate/onResume，此时窗口显示
 * VDeskLaunchTheme 的 windowBackground（深色底 + 居中转环）—— 转环是最先可见的等待指示；
 *  {@link #showSplash()} 再在它上方铺一层目标应用的图标 / 名称（淡入）作为首屏；
 *  引擎在首屏期间完成 rebind，之后由引擎在
 * 进程内把目标 app 的 Activity 拉起来，目标就绪后调 {@link #engineHideLater(long)} —— 延迟
 * 一小段（最小停留）再调 {@link #engineHide()}：淡出内容 + 本窗口变透明且不可触摸
 * （**不 finish** —— 系统侧栈顶要一直是它，否则 AMS 会认为 task 空了）。
 *
 * 主线程那一跳**由本类自己完成**（`View.postDelayed`，从任意线程调用都安全）⇒ 引擎不需要
 * 再投主线程任务（EngineHandler / AMS_ACTION_STUB_HIDE 已停用，见 mini_ams.c）。
 *
 * 系统发来的生命周期都落在本类回调上，再转给引擎驱动虚拟栈顶。
 */
public class StubActivity extends Activity {
    /** 首屏启动 Intent：拉起目标时引擎以它为模板复制（换成目标组件）。
     *
     *  这两个字段是引擎与首屏之间**唯一**的接口 —— onCreate 不再调 native 上报，native 要用
     *  的时候直接读静态字段（生命周期交给 JVM，native 不持 GlobalRef）。
     *
     *  **必须在 StubActivity 上声明、且子类不得重新声明同名字段**：native 是按
     *  `com/minimal/stub/StubActivity` 这个类句柄 + 本字段去读的，而活动池的
     *  StubActivityAct1..10 是空壳子类、继承这里的 onCreate —— 静态字段不按子类各存一份，
     *  大家写的是同一个；一旦某个子类 shadowing 出同名字段就各写各的，池进程会读不到。 */
    public static Intent sLaunchIntent;
    /** 首屏实例：native 读它来判断"首屏已起"；隐藏走 {@link #engineHideLater(long)}
     *  （不 finish，见类注释）。 */
    public static StubActivity sInstance;

    private static final String TAG = "VDeskStub";

    /** 首屏内容（图标 / 名称 / 等待指示的容器 = SplashView）：底色/转环/名字**直接进首帧**，
     *  图标异步画；隐藏时整块淡出。见 SplashView 的类注释。 */
    private SplashView mSplash;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /* 只做三件事：把启动 Intent 和本实例交出去（native 现读）、铺首屏。
         * **不在这里 rebind**、也不在这里调 native —— 等首屏真正可见（系统发来的
         * activityResumed ack）native 自己排"补目标 + rebind + 拉起目标"。 */
        sLaunchIntent = getIntent();
        sInstance = this;
        showSplash();
    }

    /** 三方组件的结果转投入口（走唯一的 native 入口 nativeExc）。
     *
     *  虚拟 Activity 起**三方组件**（HMS core 的更新页等）时，请求透传给真系统带的是宿主真
     *  token ⇒ 结果只会到本宿主 Activity。这里把它报给 native，native 按 requestCode 换回
     *  "哪个虚拟 Activity 在等"，再投一条 ActivityResultItem 给它。
     *  没有对应的等待者时 native 只打日志、不做别的。 */
    public static void nativeStubActivityResult(int requestCode, int resultCode, Intent data) {
        VApp.nativeExc(VApp.NC_STUB_ACTIVITY_RESULT, requestCode, resultCode, data);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            nativeStubActivityResult(requestCode, resultCode, data);
        } catch (Throwable t) {
            Log.w(TAG, "nativeStubActivityResult 失败: " + t);
        }
    }

    /** 铺首屏：目标信息取自启动 Intent 的 extras（宿主 UI 两条路都会带；拿不到就只留转环）。
     *  内容与动效都在 {@link SplashView} 里（那份实现被宿主进程的预览页共用）。
     *  **任何失败都不能影响引擎**：整段包 try —— 首屏只是装饰，引擎要的只有上面两个静态字段。 */
    private void showSplash() {
        try {
            Intent it = sLaunchIntent;
            String pkg   = it != null ? it.getStringExtra("TARGET_PACKAGE") : null;
            String cls   = it != null ? it.getStringExtra("TARGET_CLASS") : null;
            String label = it != null ? it.getStringExtra("TARGET_LABEL") : null;
            SplashView v = SplashView.forTarget(this, pkg, cls, label);
            setContentView(v);
            mSplash = v;
            Log.i(TAG, "首屏已铺上：pkg=" + pkg + " label=" + label);
            v.playEnter();
        } catch (Throwable t) {
            /* 装饰失败就退回"只有主题底色 + 转环"的首屏（等价于改动之前的样子）。
             * **必须打日志**：静默吞掉的话，首屏永远空着却查不出为什么。 */
            Log.e(TAG, "首屏铺失败，退回纯底色", t);
        }
    }

    /** 目标就绪后由引擎（native，从 binder 线程直接 CallStaticVoidMethod）调用：
     *  延迟 delayMs 再在主线程调 {@link #engineHide()}。
     *  主线程那一跳交给 View 自己的消息队列（postDelayed 线程安全；未 attach 时进 RunQueue
     *  等 attach），所以 native 不必再"投主线程任务"，也不需要引擎自己的 Handler。 */
    public static void engineHideLater(long delayMs) {
        StubActivity a = sInstance;
        if (a == null || a.isFinishing()) return;
        a.getWindow().getDecorView().postDelayed(HIDE_TASK, delayMs);
    }

    /** 延迟执行体。必须是 **static 嵌套类**（d8 限制，同 VApp.EngineHandler）：
     *  不能捕获外部实例，所以 run 时现读 sInstance。 */
    private static final class HideTask implements Runnable {
        @Override public void run() {
            StubActivity a = sInstance;
            if (a == null || a.isFinishing()) return;
            a.engineHide();
        }
    }
    private static final HideTask HIDE_TASK = new HideTask();

    /** 目标的**虚拟 Activity 全部销毁**时由引擎调（native，从 binder 线程直接 CallStaticVoidMethod）：
     *  把宿主首屏也 finish 掉 —— 系统才会收掉**宿主的 record** ⇒ **退出进程**，而不是退回这块 stub 页。
     *
     *  ⚠️ 与类顶那句"不 finish"的关系：那是**首屏期间**的要求（系统侧栈顶要一直是它，否则 AMS 会认为
     *  task 空了）；这里是"目标已经全退干净"之后，语义正好相反 —— 该收就收。
     *  主线程那一跳同样交给 View 自己的消息队列（post 线程安全；未 attach 时进 RunQueue）。 */
    public static void finishHost() {
        StubActivity a = sInstance;
        if (a == null || a.isFinishing()) return;
        a.getWindow().getDecorView().post(FINISH_TASK);
    }

    /** finish 执行体。必须是 **static 嵌套类**（d8 限制，同 HideTask）：不捕获外部实例，
     *  run 时现读 sInstance。 */
    private static final class FinishTask implements Runnable {
        @Override public void run() {
            StubActivity a = sInstance;
            if (a == null || a.isFinishing()) return;
            Log.i(TAG, "finishHost: 虚拟 Activity 已全销毁 ⇒ 宿主首屏 finish()"
                    + "（系统收掉宿主 record、退出进程）");
            try { a.finish(); } catch (Throwable t) { Log.w(TAG, "finishHost 失败: " + t); }
        }
    }
    private static final FinishTask FINISH_TASK = new FinishTask();

    /* ---- 宿主首屏的**系统生命周期** → 转发给**虚拟栈顶**（2026-10-05，用户：「回桌面以后子进程没处理
     * pause 消息；以前是 ioctl 入站改写，现在要通过 stub 转发」）----
     * why：系统那边只认识**宿主 StubActivity 的 record**，pause/stop 发给的就是它；虚拟 Activity 是引擎
     * 自造的 record（假 token），系统不会驱动它 ⇒ 不转发的话目标永远以为自己在前台（实测：回桌面后
     * onPause 不触发、音频不停）。原先靠 **ioctl 入站改写**（把 `IApplicationThread.scheduleTransaction`
     * 里的真 token 换成虚拟栈顶的假 token，框架自己驱动那条 record）；`local=1` 没有入站改写 ⇒ 改用
     * 这里的 stub 转发（native 侧**投框架事务**，不裸调 performPause）。 */
    public static final int STUB_EV_RESUME = 1;
    public static final int STUB_EV_PAUSE  = 2;
    /** 生命周期上报：走唯一的 native 入口 nativeExc（14 = NC_STUB_LIFECYCLE）。 */
    public static void nativeStubLifecycle(int what) {
        VApp.nativeExc(VApp.NC_STUB_LIFECYCLE, what, null, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { nativeStubLifecycle(STUB_EV_PAUSE); }
        catch (Throwable t) { Log.w(TAG, "nativeStubLifecycle(pause) 失败: " + t); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { nativeStubLifecycle(STUB_EV_RESUME); }
        catch (Throwable t) { Log.w(TAG, "nativeStubLifecycle(resume) 失败: " + t); }
    }

    /** 首屏窗口转成"透明 + 不可触摸"，目标窗口叠在它之上，视觉上即完成过渡。
     *  刻意不用 setVisibility(GONE)：移掉表面会扰动系统侧的窗口记账/生命周期。
     *  **必须在主线程调**（窗口属性/WindowManager），入口是 {@link #engineHideLater(long)}。
     *
     *  淡出：把**整块内容**（含背景）alpha 拉到 0 —— 窗口背景此刻已置透明，
     *  所以淡完就完全看不见，且过程是往目标首帧的交叉溶解（原来是瞬间切掉，会有一下跳）。
     *  只改 alpha、不碰可见性/视图树，窗口记账不受影响。 */
    public void engineHide() {
        try {
            /* 触摸/焦点标志**立刻**生效（这才是"不挡目标"的实质）。 */
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            if (mSplash == null) {
                getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                return;
            }
            /* 内容淡出；**窗口背景（转环）等淡完再撤** —— 窗口背景换不掉 alpha，提前撤会在
             * 淡出期间露出底下的东西（目标还没画好的话就是一闪）。 */
            mSplash.playExit();
            getWindow().getDecorView().postDelayed(BG_CLEAR, SplashView.FADE_OUT_MS);
        } catch (Throwable ignore) {
            /* 窗口已销毁等异常场景：忽略即可，隐藏失败不影响目标已经起来 */
        }
    }

    /** 淡出结束后把窗口背景撤成透明（见 engineHide）。
     *  必须是 **static 嵌套类**（d8 限制，同 HideTask）：不能捕获外部实例，run 时现读 sInstance。 */
    private static final class BgClear implements Runnable {
        @Override public void run() {
            StubActivity a = sInstance;
            if (a == null || a.isFinishing()) return;
            try {
                a.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            } catch (Throwable ignore) {
            }
        }
    }
    private static final BgClear BG_CLEAR = new BgClear();

    /* ---- 系统生命周期：**不再由本类上报**（2026-09-23 停用）----
     * 这 4 个生命周期已改由引擎在 ioctl **入站**拦截里处理：系统发来的
     * `IApplicationThread.scheduleTransaction` 事务，payload 里的真 token 会被换成"虚拟栈顶"的
     * 假 token ⇒ 框架自己驱动虚拟 Activity 那条 record，目标 app 拿到完整的
     * onPause/onStop/onDestroy/onResume（旧做法只能驱动 pause/resume，且是裸调 performPause）。
     *
     * 原实现（Java 回调 → NC_STUB_EVENT → native 驱动虚拟栈顶）保留在下面注释里，便于回退：
     *
     *   @Override protected void onResume()  { super.onResume();  stubEvent(STUB_EV_RESUME); }
     *   @Override protected void onPause()   { stubEvent(STUB_EV_PAUSE);  super.onPause(); }
     *   @Override protected void onStop()    { stubEvent(STUB_EV_STOP);   super.onStop(); }
     *   @Override protected void onDestroy() { super.onDestroy(); stubEvent(STUB_EV_DESTROY); }
     *   private void stubEvent(int ev) {
     *       VApp.nativeExc(VApp.NC_STUB_EVENT, this, Integer.valueOf(ev), null);
     *   }
     * 注：NC_STUB_EVENT(=12) 与 STUB_EV_* 常量已从 VApp 删掉（native.c 的分支保留），
     *     真要回退得先把常量加回去。
     */
}
