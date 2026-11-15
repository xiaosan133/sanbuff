SDK := $(HOME)/android-sdk
AAPT2 := $(SDK)/build-tools/34.0.0/aapt2
D8 := $(SDK)/build-tools/34.0.0/d8
APKSIGNER := $(SDK)/build-tools/34.0.0/apksigner
ANDROID_JAR := $(SDK)/platforms/android-34/android.jar
KEYSTORE := debug.keystore
KEY_ALIAS := androiddebugkey
ADD2APK := tools/add_to_apk.py

# 本仓库**不含 native 源码**：引擎以预编译 SDK 形式提供。
# 需要重新构建 native 时用 vdesk 主仓库，产物覆盖到这里即可。
PREBUILT_SO := sdk/arm64-v8a/libnative.so

PKG := com.vdesk
APK := vdesk.apk
SIGNED_APK := vdesk-signed.apk
CLASSES_DEX := classes.dex
SRC_FILES := $(shell find src -name '*.java')
RES_FILES := $(shell find res -type f)
COMPILED_RES := compiled_res.zip

.PHONY: all clean install run check-symbols

all: $(SIGNED_APK)

# ---------- 资源 ----------
$(COMPILED_RES): $(RES_FILES)
	rm -f $@
	$(AAPT2) compile --dir res -o $@

# link 生成无 dex 的 resources.apk，同时用 --java gen 产出 R.java
resources.apk: $(COMPILED_RES) AndroidManifest.xml
	rm -rf gen resources.apk
	$(AAPT2) link -o resources.apk -I $(ANDROID_JAR) \
		--manifest AndroidManifest.xml \
		--java gen \
		--min-sdk-version 27 \
		--target-sdk-version 29 \
		$(COMPILED_RES)

# ---------- Java → dex ----------
.classes_built: resources.apk $(SRC_FILES)
	# 必须先清空：javac 只覆盖这次传入的源文件，删掉 .java 时旧 .class 会留下并被打进 dex。
	rm -rf classes
	mkdir -p classes
	javac --release 8 -sourcepath src:gen -cp "$(ANDROID_JAR)" $(SRC_FILES) -d classes/
	@touch $@

$(CLASSES_DEX): .classes_built
	$(D8) --release --lib "$(ANDROID_JAR)" --output . $$(find classes -name '*.class')

# ---------- 预编译 native SDK ----------
$(PREBUILT_SO):
	@echo "★ 缺少预编译 native 库：$@"; \
	 echo "  本仓库不含 native 源码，请从 vdesk 主仓库拷贝 jniLibs/arm64-v8a/libnative.so 到该路径。"; \
	 exit 1

# ---------- 打包 ----------
unsigned.apk: $(CLASSES_DEX) resources.apk $(PREBUILT_SO)
	cp resources.apk $@
	python3 $(ADD2APK) $@ $(CLASSES_DEX)
	python3 $(ADD2APK) $@ $(PREBUILT_SO) --arcname lib/arm64-v8a/libnative.so

# ---------- 签名 ----------
$(KEYSTORE):
	keytool -genkey -v -keystore $@ -alias $(KEY_ALIAS) \
		-keyalg RSA -keysize 2048 -validity 10000 \
		-dname "CN=Android Debug,O=Android,C=US" \
		-storepass android -keypass android

$(SIGNED_APK): unsigned.apk $(KEYSTORE)
	$(APKSIGNER) sign --ks $(KEYSTORE) --ks-pass pass:android \
		--ks-key-alias $(KEY_ALIAS) --out $@ unsigned.apk

install: $(SIGNED_APK)
	adb install -r $(SIGNED_APK)

run: install
	adb shell am start -n $(PKG)/.MainActivity

check-symbols: $(PREBUILT_SO)
	@test -f $< || exit 1; \
	 echo "prebuilt native: $<（$(shell stat -c %s $<) B）"

clean:
	rm -rf classes $(CLASSES_DEX) $(COMPILED_RES) gen resources.apk unsigned.apk $(SIGNED_APK) .classes_built
