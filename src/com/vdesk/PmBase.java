package com.vdesk;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/**
 * PM **基础模板** —— 所有目标应用共用一份，由主进程首次启动时生成。
 *
 * 为什么查 vdesk 自己、而不是目标应用：引擎自造档案时缺的那几个字段
 * （storageUuid / seInfoUser / privateFlags / owns / targetSandboxVersion）**不是目标应用特有的**，
 * 而是平台/ROM 给"装在这台设备上的普通应用"填的 —— 拿 vdesk 自己的 PackageInfo 当模板就够，
 * 不必去解析目标 APK（于是也完全不用管目标有没有装进系统）。
 *
 * 为什么不用 Parcel 序列化整个 PackageInfo：实测 `marshall()`→`unmarshall()` 的往返会坏
 * （`Bad magic number for Bundle: 0x0`，Java 侧自检也复现），因为对象图里有没法进字节流的
 * 东西。这里只取 5 个**标量**，写成纯文本，简单、可读、跨版本稳。
 *
 * 文本格式（每行 `key value`，缺失用 `-`）：
 *   vdesk-pmbase 1
 *   storageUuid <uuid>
 *   seInfoUser <string>
 *   privateFlags <int>
 *   owns <int>
 *   targetSandboxVersion <int>
 * 位置：files/target_apk/pm_base.bin（固定公共区域；native 从目标 APK 路径推出）。
 */
final class PmBase {

    private static final String TAG = "VDeskPmBase";
    private static final int FLAGS = 0x80010FB;      /* 与 native CACHE_PM_FLAGS 一致 */

    private PmBase() {}

    static File fileFor(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "target_apk"), "pm_base.bin");
    }

    /** 主进程调用；已存在且不旧于本 APK 时只做一次 stat。失败只跳过，不抛。 */
    static void ensure(Context ctx) {
        File src = new File(ctx.getApplicationInfo().sourceDir);
        File out = fileFor(ctx);
        if (out.exists() && src.exists() && out.lastModified() >= src.lastModified()) return;
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), FLAGS);
            if (ai == null) {
                android.util.Log.w(TAG, "getApplicationInfo(自己) 返回 null，不写模板");
                return;
            }
            StringBuilder sb = new StringBuilder("vdesk-pmbase 1\n");
            sb.append("storageUuid ").append(str(ai, "storageUuid")).append('\n');
            sb.append("seInfoUser ").append(str(ai, "seInfoUser")).append('\n');
            sb.append("privateFlags ").append(num(ai, "privateFlags")).append('\n');
            sb.append("owns ").append(num(ai, "owns")).append('\n');
            sb.append("targetSandboxVersion ").append(num(ai, "targetSandboxVersion")).append('\n');

            out.getParentFile().mkdirs();
            File tmp = new File(out.getAbsolutePath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out); }
            android.util.Log.i(TAG, "已写基础模板 " + out + ":\n" + sb);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "写基础模板失败: " + t);
        }
    }

    /** 取字段的字符串值；拿不到/为 null 记 "-"。字段可能是 @hide ⇒ 反射，失败就算了。 */
    private static String str(ApplicationInfo ai, String name) {
        try {
            Field f = ApplicationInfo.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(ai);
            return v == null ? "-" : v.toString();
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String num(ApplicationInfo ai, String name) {
        try {
            Field f = ApplicationInfo.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(ai);
            return v == null ? "0" : v.toString();
        } catch (Throwable t) {
            return "0";
        }
    }
}
