# miniVDesk

Android 应用容器（虚拟化）引擎。让未安装的第三方应用运行在宿主提供的隔离环境里：
per-app 沙盒、binder 虚拟化、seccomp 路径重定向与 /proc 视图伪装。

> 本仓库只包含 **Java 层 + 预编译 native SDK**，不包含 native 源码。

## 目录

```
src/                     Java 层（UI + 引擎宿主类 VApp + stub Activity/Service/Receiver）
res/                     资源
sdk/arm64-v8a/libnative.so   预编译 native 引擎（release 加固版）
tools/add_to_apk.py      往 zip 里加条目的打包脚本
Makefile                 aapt2 → javac → d8 → 打包 → 签名
```

## 构建

依赖：

- Android SDK：`build-tools;34.0.0`、`platforms;android-34`
- JDK（`javac --release 8`）、`python3`、`keytool`/`apksigner`
- 默认路径 `$HOME/android-sdk`，不同则改 `Makefile` 顶部 `SDK`

```sh
make                 # 产出 vdesk-signed.apk
make install         # adb install -r
make run             # 安装并启动主界面
make clean
```

构建**不编译 native**：打包时把 `sdk/arm64-v8a/libnative.so` 放进 APK 的
`lib/arm64-v8a/`。需要重编引擎时，在上游 vdesk 仓库构建后覆盖该文件。

## 使用

首次需先在 UI 里「添加应用」导入目标 APK（落到 `files/target_apk/<pkg>/`）。
之后可以直接点击图标，或在已导入的情况下无头拉起：

```sh
adb shell am start -n com.vdesk/com.minimal.stub.StubActivity \
  --es TARGET_PACKAGE    <pkg> \
  --es TARGET_APK        /data/user/0/com.vdesk/files/target_apk/<pkg>/base.apk \
  --es TARGET_APP_CLASS  <applicationClass> \
  --es TARGET_NATIVE_DIR /data/user/0/com.vdesk/files/target_apk/<pkg>/lib
```

## 说明

- 仅提供 `arm64-v8a`；应用包名为 `com.vdesk`。
- `libnative.so` 为 release 加固产物：只导出 `JNI_OnLoad` 与
  `Java_com_minimal_virtual_VApp_nativeExc`，无符号表/DWARF。
- 仅供学习与研究，请在合法授权范围内使用。
