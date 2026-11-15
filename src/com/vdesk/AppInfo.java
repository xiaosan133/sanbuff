package com.vdesk;

import java.util.HashMap;
import java.util.Map;

/** 虚拟桌面上的一个应用条目。别名按虚拟设备（id）分别存储。 */
public class AppInfo {
    public final String packageName;
    public final String label;   // 应用原名
    private final Map<String, String> mAliases = new HashMap<>();

    // 目标可启动信息（与 minimal-apk TargetInfo 对齐）
    public String apkPath;           // 目标 APK：target_apk/<pkg>/base.apk
    public String launcherClass;     // MAIN/LAUNCHER Activity
    public String applicationClass;  // Application 类
    public String cacheDir;          // target_cache/<pkg>
    public String nativeDir;         // 解压 native lib 目录

    public AppInfo(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }

    /** 指定虚拟设备（id）下的别名；未设置时返回原名。 */
    public String aliasFor(String profileId) {
        String a = mAliases.get(profileId);
        return (a != null && !a.trim().isEmpty()) ? a : label;
    }

    /** 设置指定设备（id）的别名；空值表示恢复原名。 */
    public void setAlias(String profileId, String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            mAliases.remove(profileId);
        } else {
            mAliases.put(profileId, alias.trim());
        }
    }

    public Map<String, String> getAliases() {
        return mAliases;
    }
}
