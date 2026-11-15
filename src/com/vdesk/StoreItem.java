package com.vdesk;

/** 内置市场中的一个应用条目。 */
public class StoreItem {
    public final String packageName;
    public final String name;
    public final String version;
    public final String size;

    public StoreItem(String packageName, String name, String version, String size) {
        this.packageName = packageName;
        this.name = name;
        this.version = version;
        this.size = size;
    }

    public String meta() {
        return version + " · " + size;
    }
}
