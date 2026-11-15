package com.minimal.virtual;

import android.app.Application;
import android.util.Log;

public class VApp extends Application {
    static {
        System.loadLibrary("native");
    }

    private static final String TAG = "VApp";
    private static android.content.Context sAppContext;

    public static android.content.Context getAppContext() { return sAppContext; }

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = this;

        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                Log.e(TAG, "Uncaught on " + thread.getName(), e));

        Log.i(TAG, "nativeExc(APP_INIT) -> " + nativeExc(NC_APP_INIT, this, null, null));
    }

    public int seed(Object o) {
        return 0;
    }

    public Object fromPtr(long raw) {
        return null;
    }

    public int allocateActIdx(String pkg, String profile) {
        return 0;
    }

    public static final int NC_APP_INIT      = 1;
    public static final int NC_STUB_ACTIVITY_RESULT = 13;
    public static final int NC_STUB_LIFECYCLE       = 14;

    public static native Object nativeExc(int code, Object a, Object b, Object c);
}
