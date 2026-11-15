package com.minimal.virtual;

import android.app.Application;
import android.util.Log;

/** 宿主 Application：持久化仓库初始化 + 引擎初始化，兼 JNI 宿主类（原 NativeBridge）。
 *
 *  native 侧按声明类的 mangle 名解析符号，所以导出的是 Java_com_minimal_virtual_VApp_*；
 *  `seed` / `getAppContext` 这两个（Java 代码，供 native 回调）仍按类名被 FindClass 找，
 *  改名要一起改；art_hook.c 的 JNI 偏移校准也按类名/签名找 nativeExc
 *  （见 native.c 的 native_exc_sig）。 */
public class VApp extends Application {
    static {
        System.loadLibrary("native");
    }

    private static final String TAG = "VApp";
    private static android.content.Context sAppContext;

    public static android.content.Context getAppContext() { return sAppContext; }

    /* 这里原来有个 getPackageName() override（返回 native 的目标包名），已删：
     * rebind 后框架造的是**目标自己的 Application 类**（hbind_exec 把 ApplicationInfo.className
     * 设成 target.appClass），目标代码拿到的 packageName 由它自己的 LoadedApk 给出。VApp
     * 只在 rebind 前作为宿主 Application 存在，而会经过它的只有 DeviceProfileStore 的
     * "是否主进程"判断 —— 那个比的是 processName，与 override 无关。 */

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = this;      /* UI 层仓库靠 getAppContext() 惰性初始化（见 AppRepository/DeviceProfileStore） */

        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                Log.e(TAG, "Uncaught on " + thread.getName(), e));

        /* 引擎初始化整条链都在 native（池判定 / patchSelfView / 目标信息 / engine_init_core /
         * 池收尾）。Java 只交一个 Context。
         * 池序号由 native 自己读 /proc/self/cmdline；档案 id（数据区路径段）**不在这里查** ——
         * 它跟目标信息走同一套通道（act/svc 池走 provider 槽，:stub1 走启动 Intent 的
         * TARGET_PROFILE extra），这样引擎不必反向依赖 UI 层的 DeviceProfileStore。 */
        Log.i(TAG, "nativeExc(APP_INIT) -> " + nativeExc(NC_APP_INIT, this, null, null));
    }

    /* 喂种的**载体方法**（见 jni/art_member.c 的 art_member_seed_all）—— 只留一个空壳，
     * 入口会被 native 换掉。
     *
     * 为什么载体必须留在 Java：jclass 只是 JNI 句柄（本机是 0xc1 这种小整数），没有
     * "解引用成裸 mirror::Class*"这回事；唯一可靠的拿法是让**被调方法的实参寄存器**里带着
     * 裸引用 —— native 从 JNI 边界 CallObjectMethod 调 seed(Object) 时，ART 把句柄解码成
     * 裸引用放进快 ABI 的 x2，而本方法的 qcc 已被换成捕获 handler，于是 x2 就是裸 Class*。
     *
     * 必须是**实例方法**（静态方法有惰性解析问题）。放在 Application 上只是为了少一个类：
     * native 会 new 一个丢弃的实例来调它，原方法体不会被执行。
     * 返回 int 而不是 void：qcc 换掉的入口只做"取值 + 原样返回"，int 返回值最省事
     * （不必牵扯引用返回值的解码），调用点也用 CallIntMethod 干净对上。 */
    public int seed(Object o) {
        return 0;
    }

    /* ================= 载体方法（native 换 qcc 后实现其逻辑） =================
     * 机制同 seed：方法体不会被执行；ART 按快 ABI 调 native 入口，实参在 x2/x3 上是**裸引用**。
     * 与 seed 的差别只是"用实参 + 回返回值"，不再喂 Class 种。转换用到的都放本类里。 */

    /** 裸引用 → 对象：native 把实参寄存器里拿到的裸指针当 long 传进来，入口把它当**返回值**
     *  交回 ART ⇒ JNI 侧收到正常 object（ART 自己造 local ref）。这样 native 就不必懂
     *  mirror::String 的内存布局、也不用 AddLocalReference。 */
    public Object fromPtr(long raw) {
        return null;
    }

    /** act 池槽分配（原为 MainActivity 打 ProcessPoolProvider，provider 已删）：(app 包名, 档案 id)
     *  → 槽号，0 = 没槽（UI 回退 :stub1）。native 侧借 fromPtr 把两个串转回对象读出来，
     *  再调 pool_core_allocate（池状态本来就在 native）。 */
    public int allocateActIdx(String pkg, String profile) {
        return 0;
    }

    /* ================= 以下原是 NativeBridge（JNI 宿主类），已并入本类 ================= */

    /* ---- 唯一的 native 入口：按 code 路由（native 侧见 jni/native.c 的 nativeExc） ----
     *  参数统一 Object（未用的传 null），具体类型由 code 约定；返回 Object（void 的返回 null）。
     *  这样加/改功能只需改这里的常量 + native 的分发，不用再动 Java 声明、也不用再同步
     *  global_refs.c 里缓存的签名（那类漏改是静默失效，踩过）。
     *  ⚠️ 必须与 jni/native.c 的 NC_* 一致；对不上 native 会 LOGE 报未知 code。
     *
     *  **已删的编号**（Java 侧不再有发送方；native 分支保留便于回退/A-B）：
     *    3  原 NC_REBIND（随"抢首条事务"机制一起删）
     *    4  NC_STUB_READY（首屏上报；StubActivity.onCreate 早已不上报）
     *    5  NC_MAIN_TASK（引擎主线程 Handler 载体；唯一任务改由 StubActivity.engineHideLater 完成）
     *    6  NC_STUB_SVC_READY  7  NC_STUB_SVC_BIND（服务池旧上报；目标占用真 token 后框架直调目标，
     *        StubService 的那三个转发已在源码里注释停用）
     *   12  NC_STUB_EVENT（宿主 StubActivity 生命周期桥；改由 ioctl 入站拦截驱动）—— 事件码
     *        STUB_EV_RESUME/PAUSE/STOP/DESTROY 也随之删。 */
    public static final int NC_APP_INIT      = 1;  /* (Context)              -> String 目标包名 */
    /* 13/14：宿主 StubActivity 的两个上报（原先各有一个 native 方法，现合并到 nativeExc，
     * 导出符号只留这一个 JNI 入口）。int 参数由 Java 自动装箱成 Integer。 */
    public static final int NC_STUB_ACTIVITY_RESULT = 13; /* (Integer req, Integer res, Intent data) */
    public static final int NC_STUB_LIFECYCLE       = 14; /* (Integer what: 1=resume 2=pause) */
    /* 2 原 NC_POOL_PROVIDER（ProcessPoolProvider 的槽表操作）—— 已删：provider 整个去掉，
     * 池操作走共享内存（jni/pool_shm.c）。 */
    /* 11（宿主桩 receiver 上报）已撤：StubReceiverBase.onReceive 改成只写静态字段
     * （sLastStub/sLastIntent），引擎在 finishReceiver 拦截点接手，Java 侧不再调 native。 */

    /** 引擎初始化（池进程标记 + patchSelfView + 目标信息 + engine_init_core + 池收尾）、
     *  provider 端槽表操作、OAT fallback 的 rebind+重放、裸指针备份 —— 全走这一个入口。
     *  NC_APP_INIT：主进程不装引擎返回 null；:stub1/adb 直拉时启动期还没目标返回空串，
     *  目标等首个 EXECUTE_TRANSACTION 的 Intent extras 补；档案 id 也走那两个通道。 */
    public static native Object nativeExc(int code, Object a, Object b, Object c);

    /* 池进程标志/伪装名/目标类名（hbind_set_pool_process/ProcName/Target）现在只由
     * nativeExc 内部调用，Java 侧入口已删。
     * 进程级 seccomp profile 同理：DUMPABLE|OPENAT 在 JNI_OnLoad 里装，uid 族由
     * isolated_fake_arm 按需装（见 jni/seccomp_svc.c / env/isolated_fake.c）。
     * patchSelfView 也并进了 nativeExc(NC_APP_INIT)。 */

    /* 引擎自己的主线程 Handler（EngineHandler）已删除：它唯一的任务（首屏转透明）改成
     * StubActivity.engineHideLater —— 主线程那一跳由 View.postDelayed 完成（见
     * jni/hbind_exec.c 的 hbind_stub_post_hide）。首屏过渡本身待定，需要时从 git 历史取回。 */
}
