package com.vdesk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 虚拟桌面应用仓库：SharedPreferences + JSON 持久化。 */
public class AppRepository {
    private static final String PREFS = "vdesk";
    private static final String KEY_APPS = "apps";

    private static AppRepository sInstance;

    public static synchronized AppRepository get() {
        if (sInstance == null) {
            sInstance = new AppRepository();
        }
        sInstance.ensureInit();
        return sInstance;
    }

    private final List<AppInfo> mApps = new ArrayList<>();
    private Context mContext;


    /** 惰性初始化：Context 取自宿主 Application（VApp.onCreate 起手就设好了）。
     *  原先由 VApp.onCreate 直接调 init —— 那是引擎包反向依赖 UI 包；现在翻过来，
     *  UI 包依赖引擎包（自然方向），且任何入口先碰到仓库都会自动补初始化。 */
    private void ensureInit() {
        if (mContext != null) return;
        android.content.Context c = com.minimal.virtual.VApp.getAppContext();
        if (c != null) init(c);
    }

    private AppRepository() {
    }

    /** 用 app context 加载持久化数据（幂等）。 */
    private synchronized void init(Context ctx) {
        if (mContext != null) {
            return;
        }
        mContext = ctx.getApplicationContext();
        load();
    }

    public synchronized List<AppInfo> getApps() {
        return new ArrayList<>(mApps);
    }

    public synchronized boolean contains(String packageName) {
        for (AppInfo a : mApps) {
            if (a.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void add(AppInfo app) {
        if (!contains(app.packageName)) {
            mApps.add(app);
            save();
        }
    }

    public synchronized void remove(String packageName) {
        for (int i = mApps.size() - 1; i >= 0; i--) {
            if (mApps.get(i).packageName.equals(packageName)) {
                mApps.remove(i);
                save();
            }
        }
    }

    public synchronized int size() {
        return mApps.size();
    }

    /** 就地修改 AppInfo（如别名）后落盘。 */
    public synchronized void persist() {
        save();
    }

    private void load() {
        SharedPreferences sp = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_APPS, null);
        if (json == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AppInfo app = new AppInfo(o.getString("packageName"), o.getString("label"));
                app.apkPath = nn(o.optString("apkPath"));
                app.launcherClass = nn(o.optString("launcherClass"));
                app.applicationClass = nn(o.optString("applicationClass"));
                app.cacheDir = nn(o.optString("cacheDir"));
                app.nativeDir = nn(o.optString("nativeDir"));
                if (o.has("aliases")) {
                    JSONObject aliases = o.getJSONObject("aliases");
                    Iterator<String> keys = aliases.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        app.setAlias(k, aliases.getString(k));
                    }
                }
                mApps.add(app);
            }
        } catch (JSONException e) {
            mApps.clear();
        }
    }

    private void save() {
        if (mContext == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (AppInfo a : mApps) {
                JSONObject o = new JSONObject();
                o.put("packageName", a.packageName);
                o.put("label", a.label);
                o.put("apkPath", nz(a.apkPath));
                o.put("launcherClass", nz(a.launcherClass));
                o.put("applicationClass", nz(a.applicationClass));
                o.put("cacheDir", nz(a.cacheDir));
                o.put("nativeDir", nz(a.nativeDir));
                JSONObject aliases = new JSONObject();
                for (Map.Entry<String, String> e : a.getAliases().entrySet()) {
                    aliases.put(e.getKey(), e.getValue());
                }
                o.put("aliases", aliases);
                arr.put(o);
            }
            SharedPreferences sp = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_APPS, arr.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** null → 空串（写入 JSON 时 org.json 不允许 null）。 */
    private static String nz(String s) {
        return s != null ? s : "";
    }

    /** 空串 → null（读取时还原）。 */
    private static String nn(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
