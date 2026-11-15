package com.vdesk;

/** 一个虚拟设备（多开隔离沙箱）。只含 id，无设备参数、无名称（已移除身份伪造与名称）。 */
public class DeviceProfile {
    /** 稳定 id（p0/p1/...）：数据区隔离的路径段，编辑不改变。 */
    public String id;

    public DeviceProfile() {
    }

    /** 预置虚拟设备数量。 */
    private static final int PRESET_COUNT = 10;

    /** 返回第 i 个预置设备（i 越界则返回空档案）。 */
    public static DeviceProfile preset(int i) {
        DeviceProfile p = new DeviceProfile();
        if (i >= 0 && i < PRESET_COUNT) {
            p.id = "p" + i;
        }
        return p;
    }

    /** 预置设备数量。 */
    public static int presetCount() {
        return PRESET_COUNT;
    }

    /** 分配新的唯一 id（"pN"，取现有最大数字后缀+1，删除不复用旧 id）。 */
    public static String allocateId(java.util.List<DeviceProfile> existing) {
        int max = -1;
        if (existing != null) {
            for (DeviceProfile p : existing) {
                if (p.id == null || p.id.length() <= 1 || !p.id.startsWith("p")) {
                    continue;
                }
                try {
                    int n = Integer.parseInt(p.id.substring(1));
                    if (n > max) {
                        max = n;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "p" + (max + 1);
    }
}
