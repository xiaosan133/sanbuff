package com.minimal.stub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 宿主 StubReceiver 基类：只做**一次静态字段写**——不调 native、不碰目标代码。
 *
 *  引擎在 `finishReceiver`（框架"这条投递处理完了"的回执）的拦截点读这两个字段，在那里给
 *  **目标** Receiver 造投递（框架按目标 info 的 classloader 实例化，PendingResult/有序结果
 *  仍由框架管）。为什么不在 onReceive 里直接上报引擎：那一刻框架还在 handleReceiver 的栈上、
 *  还握着这次投递的状态；finishReceiver 时框架已完全退出 onReceive，改框架状态最安全。
 *  为什么走静态字段而不是调 native：Java 侧只留"记账"，机制全在 native。
 *
 *  volatile：写在前台，读可能在 QueuedWork 的线程上（finish 有可能被排队）。
 *  单槽语义：一条投递一条槽，native 读完即置空（它据此判断"有没有待处理的桩投递"）。 */
public class StubReceiverBase extends BroadcastReceiver {
    public static volatile StubReceiverBase sLastStub;
    public static volatile Intent sLastIntent;

    @Override
    public void onReceive(Context context, Intent intent) {
        sLastStub = this;
        sLastIntent = intent;
    }
}
