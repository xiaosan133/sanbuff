package com.minimal.stub;

/** 纯 receiver 池 1 进程（:rcvstub1）的静态 StubReceiver（该池进程**只**声明 receiver，
 *  不夹带宿主 stub service —— 对应目标 manifest 里那个进程只有 receiver、没有 service 的情形）。
 *  与 StubReceiverSvcN 同构：StubReceiverBase.onReceive 只写 this/intent，引擎在那次
 *  finishReceiver 的拦截点造投递，框架按目标 classloader 实例化真正的目标 receiver。 */
public class StubReceiverRcv1 extends StubReceiverBase {
}
