package com.minimal.stub;

/** 服务池 2 进程（:svcstub2）的静态 StubReceiver，独立类名使广播可路由到指定进程。
 *  本类**会被实例化**（OAT hook 那条改写路径已停用）：系统的 manifest 投递直接进来，
 *  StubReceiverBase.onReceive 只写两个静态字段，引擎在 finishReceiver 拦截点接手。 */
public class StubReceiverSvc2 extends StubReceiverBase {
}
