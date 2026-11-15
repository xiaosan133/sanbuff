package com.vdesk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 设备参数档案仓库：SharedPreferences + JSON 持久化。 */
public class DeviceProfileStore {
    private static final String PREFS = "vdesk";
    private static final String KEY_PROFILES = "device_profiles";
    private static final String KEY_ACTIVE = "device_active_index";
    private static final String KEY_LEGACY_MIGRATED = "device_legacy_data_migrated";

    private static DeviceProfileStore sInstance;

    public static synchronized DeviceProfileStore get() {
        if (sInstance == null) {
            sInstance = new DeviceProfileStore();
        }
        sInstance.ensureInit();
        return sInstance;
    }

    private final List<DeviceProfile> mProfiles = new ArrayList<>();
    private DeviceProfile mActive;
    private Context mContext;


    /** 惰性初始化：Context 取自宿主 Application（VApp.onCreate 起手就设好了）。
     *  原先由 VApp.onCreate 直接调 init —— 那是引擎包反向依赖 UI 包；现在翻过来，
     *  UI 包依赖引擎包（自然方向），且任何入口先碰到仓库都会自动补初始化。 */
    private void ensureInit() {
        if (mContext != null) return;
        android.content.Context c = com.minimal.virtual.VApp.getAppContext();
        if (c != null) init(c);
    }

    private DeviceProfileStore() {
    }

    /** 用 app context 加载持久化数据（幂等）。 */
    private synchronized void init(Context ctx) {
        if (mContext != null) {
            return;
        }
        mContext = ctx.getApplicationContext();
        load();
        migrateLegacyDataIfNeeded();
    }

    public synchronized List<DeviceProfile> getProfiles() {
        return new ArrayList<>(mProfiles);
    }

    public synchronized DeviceProfile getActive() {
        return mActive;
    }

    /** 生效档案的 id —— 数据区路径段（虚机初始化时要用，见 VApp.onCreate）。
     *  拿不到档案退回 p0；任何异常返回空串（宁可不带档案段，也不要让进程起不来）。 */
    public static String activeId() {
        try {
            DeviceProfile p = get().getActive();
            if (p == null) p = DeviceProfile.preset(0);
            return p.id != null ? p.id : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public synchronized void setActive(DeviceProfile p) {
        mActive = p;
        save();
    }

    public synchronized void add(DeviceProfile p) {
        if (p.id == null || p.id.length() == 0) {
            p.id = DeviceProfile.allocateId(mProfiles);
        }
        mProfiles.add(p);
        save();
    }

    public synchronized void update(int index, DeviceProfile p) {
        if (index >= 0 && index < mProfiles.size()) {
            DeviceProfile old = mProfiles.get(index);
            if (p.id == null || p.id.length() == 0) {
                p.id = old.id; /* 编辑保留原 id，避免数据区漂移 */
            }
            if (mActive == old) {
                mActive = p;
            }
            mProfiles.set(index, p);
            save();
        }
    }

    private void load() {
        SharedPreferences sp = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_PROFILES, null);
        if (json == null) {
            resetToPresets();
            save();
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                DeviceProfile p = fromJson(arr.getJSONObject(i));
                if (p.id == null || p.id.length() == 0) {
                    p.id = "p" + i; /* 老档案按存储顺序回退，主/桩进程一致 */
                }
                mProfiles.add(p);
            }
            if (mProfiles.isEmpty()) {
                resetToPresets();
            }
            int active = sp.getInt(KEY_ACTIVE, 0);
            mActive = (active >= 0 && active < mProfiles.size())
                    ? mProfiles.get(active) : mProfiles.get(0);
        } catch (JSONException e) {
            resetToPresets();
        }
    }

    private void resetToPresets() {
        mProfiles.clear();
        for (int i = 0; i < DeviceProfile.presetCount(); i++) {
            mProfiles.add(DeviceProfile.preset(i));
        }
        mActive = mProfiles.get(0);
    }

    private void save() {
        if (mContext == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (DeviceProfile p : mProfiles) {
                arr.put(toJson(p));
            }
            SharedPreferences sp = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_PROFILES, arr.toString())
                    .putInt(KEY_ACTIVE, mProfiles.indexOf(mActive))
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    /** 一次性迁移：旧布局 virtual/<pkg>（应用包名含点）→ virtual/<activeId>/<pkg>。
     *  仅主进程执行（桩进程 processName != 包名），用 prefs flag 防重入。 */
    private void migrateLegacyDataIfNeeded() {
        try {
            String pn = mContext.getApplicationInfo().processName;
            if (pn != null && !pn.equals(mContext.getPackageName())) {
                return; /* 仅主进程 */
            }
            SharedPreferences sp = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean(KEY_LEGACY_MIGRATED, false)) {
                return;
            }
            DeviceProfile active = mActive;
            String id = (active != null && active.id != null && active.id.length() > 0)
                    ? active.id : "p0";
            java.io.File[] roots = { mContext.getDataDir() };
            for (java.io.File root : roots) {
                java.io.File virtual = new java.io.File(root, "virtual");
                if (!virtual.isDirectory()) {
                    continue;
                }
                java.io.File[] entries = virtual.listFiles();
                if (entries == null) {
                    continue;
                }
                for (java.io.File e : entries) {
                    if (!e.isDirectory()) {
                        continue;
                    }
                    /* 档案 id（pN）无点，应用包名恒含点：含点的迁入 active 档案 */
                    if (e.getName().indexOf('.') < 0) {
                        continue;
                    }
                    java.io.File dest = new java.io.File(
                            new java.io.File(virtual, id), e.getName());
                    if (dest.exists()) {
                        continue;
                    }
                    dest.getParentFile().mkdirs();
                    if (!e.renameTo(dest)) {
                        android.util.Log.w("VDesk", "migrate skip " + e.getName());
                    }
                }
            }
            sp.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply();
        } catch (Throwable t) {
            android.util.Log.w("VDesk", "migrateLegacyData failed: " + t);
        }
    }

    private JSONObject toJson(DeviceProfile p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", p.id == null ? "" : p.id);
        return o;
    }

    private DeviceProfile fromJson(JSONObject o) throws JSONException {
        DeviceProfile p = new DeviceProfile();
        p.id = o.optString("id", "");
        return p;
    }
}
