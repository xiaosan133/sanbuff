package com.vdesk;

import java.util.ArrayList;
import java.util.List;

/** 内置市场的预置条目（骨架阶段为写死的演示数据）。 */
public class MarketRepository {
    private static MarketRepository sInstance;

    public static synchronized MarketRepository get() {
        if (sInstance == null) {
            sInstance = new MarketRepository();
        }
        return sInstance;
    }

    private final List<StoreItem> mItems = new ArrayList<>();

    private MarketRepository() {
        mItems.add(new StoreItem("com.ss.android.ugc.aweme", "抖音", "v23.0.0", "196 MB"));
        mItems.add(new StoreItem("com.tencent.mm", "微信", "v8.0.30", "248 MB"));
        mItems.add(new StoreItem("com.taobao.taobao", "淘宝", "v10.19.0", "167 MB"));
        mItems.add(new StoreItem("com.xingin.xhs", "小红书", "v7.90.0", "145 MB"));
        mItems.add(new StoreItem("com.bilibili.app.blue", "哔哩哔哩", "v7.60.0", "158 MB"));
        mItems.add(new StoreItem("com.pdd.ninja", "拼多多", "v6.40.0", "89 MB"));
    }

    public synchronized List<StoreItem> getItems() {
        return new ArrayList<>(mItems);
    }
}
