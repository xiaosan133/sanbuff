package com.vdesk;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * AXML Manifest 解析器 — 从 APK 的 AndroidManifest.xml 中查找 MAIN/LAUNCHER Activity。
 * 支持 android:enabled="false" 检查、activity-alias、包名前缀补全。
 *
 * @return [launcherClass, appClass] or null
 */
public class ManifestFinder {
    static final int CHUNK_AXML = 0x00080003;
    static final int CHUNK_STRING_POOL = 0x001C0001;
    static final int CHUNK_XML_START_TAG = 0x00100102;
    static final int CHUNK_XML_END_TAG = 0x00100103;

    public static String[] findLauncher(String apkPath) throws Exception {
        byte[] xmlData;
        try (ZipFile zf = new ZipFile(apkPath)) {
            ZipEntry entry = zf.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            InputStream is = zf.getInputStream(entry);
            int n; while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            xmlData = baos.toByteArray();
        }
        Parser p = new Parser(xmlData);
        String launcher = p.find();
        return launcher != null ? new String[]{launcher, p.mAppClass} : null;
    }

    static class Parser {
        final ByteBuffer buf;
        int[] m_stringOffsets, m_strings;
        String mPackageName, mActName, mLauncherAct, mAppClass;
        boolean mHasMain, mHasLauncher, mEnabled = true;
        final List<String> tagStack = new ArrayList<>();

        Parser(byte[] data) {
            buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        String find() {
            int magic = buf.getInt(); buf.getInt();
            if (magic != CHUNK_AXML) return null;
            while (buf.remaining() >= 8 && mLauncherAct == null) {
                int pos = buf.position();
                int type = buf.getInt(); int size = buf.getInt();
                if (size <= 0) break;
                if (type == CHUNK_STRING_POOL) readPool(pos, size);
                else if (type == CHUNK_XML_START_TAG) handleStart(pos);
                else if (type == CHUNK_XML_END_TAG) handleEnd();
                buf.position(pos + size);
            }
            return mLauncherAct;
        }

        void readPool(int pos, int size) {
            buf.position(pos + 8);
            int cnt = buf.getInt(); buf.getInt(); buf.getInt();
            int so = buf.getInt(); buf.getInt();
            m_stringOffsets = new int[cnt];
            for (int i = 0; i < cnt; i++) m_stringOffsets[i] = buf.getInt();
            buf.position(pos + so);
            int wc = (pos + size - (pos + so)) / 4;
            m_strings = new int[wc];
            for (int i = 0; i < wc; i++) m_strings[i] = buf.getInt();
        }

        String str(int idx) {
            if (idx < 0 || m_stringOffsets == null || idx >= m_stringOffsets.length) return null;
            int o = m_stringOffsets[idx];
            int len = sht(m_strings, o);
            char[] c = new char[len];
            for (int i = 0; i < len; i++) { o += 2; c[i] = (char) sht(m_strings, o); }
            return new String(c);
        }

        static int sht(int[] d, int o) {
            int v = d[o / 4];
            return ((o % 4) / 2 == 0) ? (v & 0xFFFF) : (v >>> 16);
        }

        void handleStart(int pos) {
            buf.position(pos + 8);
            buf.getInt(); buf.getInt(); // line, comment
            buf.getInt(); // nsIdx
            int ni = buf.getInt(); // nameIdx
            buf.getInt(); // flags
            int acFull = buf.getInt();
            int ac = acFull & 0xFFFF;
            buf.getInt(); // classAttribute
            String tag = str(ni);
            if (tag == null) return;
            tagStack.add(tag);

            for (int i = 0; i < ac; i++) {
                buf.getInt(); int an = buf.getInt(); String anS = str(an);
                int ar = buf.getInt(); buf.getInt(); int ad = buf.getInt();
                String av = ar >= 0 ? str(ar) : null;

                if ("manifest".equals(tag) && "package".equals(anS))
                    mPackageName = av;
                else if (("activity".equals(tag) || "activity-alias".equals(tag)) && "name".equals(anS)) {
                    mActName = av;
                    if (mActName != null && mActName.startsWith(".") && mPackageName != null)
                        mActName = mPackageName + mActName;
                    mHasMain = false; mHasLauncher = false; mEnabled = true;
                } else if (("activity".equals(tag) || "activity-alias".equals(tag)) && "enabled".equals(anS)) {
                    if (ar < 0) mEnabled = (ad != 0);
                } else if ("activity-alias".equals(tag) && "targetActivity".equals(anS)) {
                    // skip — use alias name, not target
                } else if ("application".equals(tag) && "name".equals(anS)) {
                    mAppClass = av;
                    if (mAppClass != null && mAppClass.startsWith(".") && mPackageName != null)
                        mAppClass = mPackageName + mAppClass;
                } else if ("action".equals(tag) && "name".equals(anS))
                    mHasMain |= "android.intent.action.MAIN".equals(av);
                else if ("category".equals(tag) && "name".equals(anS))
                    mHasLauncher |= "android.intent.category.LAUNCHER".equals(av);
            }
        }

        void handleEnd() {
            if (tagStack.isEmpty()) return;
            String tag = tagStack.remove(tagStack.size() - 1);
            if (("activity".equals(tag) || "activity-alias".equals(tag)) && mActName != null) {
                if (mEnabled && mHasMain && mHasLauncher && mLauncherAct == null)
                    mLauncherAct = mActName;
                mActName = null;
            }
        }
    }
}
