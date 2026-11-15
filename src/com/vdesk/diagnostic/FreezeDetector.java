package com.vdesk.diagnostic;

import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Waits for trigger file, then dumps all thread stacks to file 3 times.
 * First immediately, then at 5s intervals.
 *
 * 移植自 minimal-apk，DUMP_DIR 改为 com.vdesk。
 * 触发方式：adb shell run-as com.vdesk touch files/trigger_dump
 */
public class FreezeDetector {
    private static final String TAG = "FreezeDetect";
    private static final String DUMP_DIR = "/data/data/com.vdesk/files/";

    public static void start() {
        final File triggerFile = new File(DUMP_DIR, "trigger_dump");

        Thread watcher = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (true) {
                try {
                    if (triggerFile.exists()) {
                        triggerFile.delete();
                        Log.i(TAG, "trigger detected at +" + (System.currentTimeMillis()-t0) + "ms");
                        break;
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) { return; }
                  catch (Exception e) { Log.e(TAG, "check err: " + e.getMessage()); }
            }

            for (int i = 1; i <= 3; i++) {
                if (i > 1) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                }
                dumpAll("trigger #" + i);
            }
        }, "FreezeDetect");
        watcher.setDaemon(true);
        watcher.start();
        Log.i(TAG, "started");
    }

    private static void dumpAll(String reason) {
        long t0 = System.currentTimeMillis();
        String ts = new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date());
        String dumpFile = DUMP_DIR + "freeze_" + ts + ".txt";

        Log.i(TAG, reason + " starting dump...");

        // Step 1: main thread — 立即写文件，即使后续全量收集卡住也保住主线程栈
        StringBuilder sb = new StringBuilder(8192);
        sb.append("=== FREEZE DUMP ").append(reason).append(" ").append(ts).append(" ===\n");
        long t1 = System.currentTimeMillis();
        try {
            Thread mainThread = Looper.getMainLooper().getThread();
            sb.append("main [").append(mainThread.getState()).append("]:\n");
            for (StackTraceElement ste : mainThread.getStackTrace()) {
                sb.append("  at ").append(ste.toString()).append("\n");
            }
            Log.i(TAG, reason + " main thread done (" + (System.currentTimeMillis()-t1) + "ms)");
        } catch (Throwable e) {
            sb.append("main ERROR: ").append(e.getMessage()).append("\n");
            Log.e(TAG, reason + " main thread FAIL: " + e.getMessage());
        }

        // 立即写主线程部分（若全量收集卡住，至少保留这部分）
        writeFile(dumpFile, sb.toString());

        // Step 2: all threads (may hang if native is stuck)
        long t2 = System.currentTimeMillis();
        try {
            Log.i(TAG, reason + " calling getAllStackTraces...");
            java.util.Map<String, StringBuilder> tidMap = buildTidMap();
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                StackTraceElement[] trace = e.getValue();
                try {
                    sb.append("\n--- ").append(t.getName())
                            .append(" (id=").append(t.getId())
                            .append(" tid=").append(lookupTid(tidMap, t.getName()))
                            .append(" state=").append(t.getState()).append(") ---\n");
                    if (trace != null) {
                        for (StackTraceElement ste : trace) {
                            sb.append("  at ").append(ste.toString()).append("\n");
                        }
                    }
                } catch (Exception ignored) {}
            }
            Log.i(TAG, reason + " all threads done (" + (System.currentTimeMillis()-t2) + "ms)");
        } catch (Throwable e) {
            sb.append("\nERROR collecting thread stacks: ").append(e.getMessage()).append("\n");
            Log.e(TAG, reason + " all threads FAIL: " + e.getMessage() + " at +" + (System.currentTimeMillis()-t2) + "ms");
        }

        // Step 3: 追加全量（若全量收集卡住则此步不可达，但主线程部分已写入）
        try {
            FileOutputStream fos = new FileOutputStream(dumpFile, true);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Log.w(TAG, reason + " written " + dumpFile + " (" + sb.length() + " bytes, " + (System.currentTimeMillis()-t0) + "ms total)");
        } catch (Exception e) {
            Log.e(TAG, reason + " write FAILED: " + e.getMessage());
        }
    }

    /** 扫 /proc/self/task 建 comm→tid 列表映射（comm 被截断到 15 字节）。 */
    private static java.util.Map<String, StringBuilder> buildTidMap() {
        java.util.Map<String, StringBuilder> map = new java.util.HashMap<>();
        java.io.File taskDir = new java.io.File("/proc/self/task");
        java.io.File[] tasks = taskDir.listFiles();
        if (tasks != null) {
            for (java.io.File t : tasks) {
                String comm = readProcFile(new java.io.File(t, "comm"));
                if (comm == null || comm.length() == 0) continue;
                StringBuilder list = map.get(comm);
                if (list == null) { list = new StringBuilder(); map.put(comm, list); }
                else list.append(",");
                list.append(t.getName());
            }
        }
        return map;
    }

    /** 按 Java 线程名匹配 native tid：comm 是线程名截断到 15 字节的前缀。 */
    private static String lookupTid(java.util.Map<String, StringBuilder> map, String name) {
        if (name == null || map == null) return "?";
        StringBuilder sb = new StringBuilder();
        String prefix = name.length() > 15 ? name.substring(0, 15) : name;
        StringBuilder list = map.get(prefix);
        if (list != null) sb.append(list);
        /* 兜底：名字本身就 ≤15，可能没截断，直接全名匹配 */
        if (sb.length() == 0) {
            StringBuilder full = map.get(name);
            if (full != null) sb.append(full);
        }
        return sb.length() > 0 ? sb.toString() : "?";
    }

    private static String readProcFile(java.io.File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[64];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8").trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(String path, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(path);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            Log.w(TAG, "partial main dump written " + path);
        } catch (Exception e) {
            Log.e(TAG, "partial write FAILED: " + e.getMessage());
        }
    }
}
