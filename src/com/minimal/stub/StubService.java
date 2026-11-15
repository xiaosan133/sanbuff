package com.minimal.stub;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.minimal.virtual.VApp;

/**
 * 宿主占位 Service：系统侧只认识它，它也是服务池进程里的"宿主服务"。
 *
 * 本类现在**只剩"让系统肯为我们起这个池进程"的作用**，不参与任何转发也不上报任何事件：
 * 系统那条 CREATE_SERVICE 原样执行 → 系统在 handleCreateService 结尾发
 * `serviceDoneExecuting(真 token)` —— 引擎在 ioctl 层拦住那一步（全 native，见 hbind_exec.c
 * 的 engine_pool_service_on_stub_create_done），在同一进程里自建**目标 Service** 并让它
 * **占用系统给本类的那张真 token**。于是 `mServices[token]` 从本类换成目标，系统随后的
 * onBind/onStartCommand/onUnbind/onDestroy 全部直接落到**目标**身上。
 *
 * 这样系统侧全程只看到宿主服务（进程 spawn 与 binder 投递都走系统正常路径），引擎不必改写
 * 系统的 CreateServiceData，也不必依赖 Java 侧上报。
 */
public class StubService extends Service {
    private static final String TAG = "StubService";

    /* onCreate 也不上报：触发点是 native 侧拦的 serviceDoneExecuting。
     * 旧实现（NC_STUB_SVC_READY）保留在 native 的 engine_stub_service_ready 里，已置空。 */

    @Override
    public void onCreate() {
        super.onCreate();
    }

    /** Service 唯一的抽象方法，必须实现。**转发已停用**（2026-09-23）：目标占用了本类的真
     *  token，系统的 handleBindService 直接取到目标实例并调它的 onBind，走不到这里。
     *  原转发实现见下方注释段（回退时恢复它 + 让引擎退回自造 token）。 */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---- 以下三个已停用（2026-09-23）：同上，框架直接调目标 ----
     * 保留原实现便于回退/A-B。

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Object r = VApp.nativeExc(VApp.NC_STUB_SVC_ARGS, this, intent,
                    Integer.valueOf(startId));
            if (r instanceof Integer) return ((Integer) r).intValue();
        } catch (Throwable t) {
            Log.w(TAG, "stub svc args failed: " + t);
        }
        return START_NOT_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            Object r = VApp.nativeExc(VApp.NC_STUB_SVC_UNBIND, this, intent, null);
            if (r instanceof Boolean) return ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            Log.w(TAG, "stub svc unbind failed: " + t);
        }
        return false;
    }

    @Override
    public void onDestroy() {
        try {
            VApp.nativeExc(VApp.NC_STUB_SVC_DESTROY, this, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "stub svc destroy failed: " + t);
        }
        super.onDestroy();
    }

    ---- 停用段结束 ---- */
}
