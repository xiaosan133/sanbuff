package com.vdesk;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 内置应用市场：模拟下载 + 安装到虚拟桌面。 */
public class MarketActivity extends Activity {

    private static final int STATE_DOWNLOAD = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_INSTALL = 2;
    private static final int STATE_INSTALLED = 3;

    private ListView mList;
    private EditText mSearch;
    private List<StoreItem> mItems = new ArrayList<>();
    private int[] mState;
    private int[] mProgress;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** 图标缓存（键 = 包名）：装上/取下后取值不变，一次进程内有效。 */
    private final java.util.HashMap<String, android.graphics.drawable.Drawable> mIcons
            = new java.util.HashMap<>();

    /** 条目图标：真装到手机上的用真图标，否则用占位图（原来一律占位图，和桌面不一致）。 */
    android.graphics.drawable.Drawable icon(String pkg) {
        android.graphics.drawable.Drawable d = mIcons.get(pkg);
        if (d != null) {
            return d;
        }
        try {
            d = getPackageManager().getApplicationIcon(pkg);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            d = getDrawable(android.R.drawable.sym_def_app_icon);
        }
        if (d != null) {
            mIcons.put(pkg, d);
        }
        return d;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_market);

        mList = findViewById(R.id.list);
        mSearch = findViewById(R.id.search);

        mSearch.addTextChangedListener(new SearchWatcher(this));

        refreshItems("");
    }

    void refreshItems(String query) {
        List<StoreItem> all = MarketRepository.get().getItems();
        mItems = new ArrayList<>();
        for (StoreItem item : all) {
            if (query.isEmpty() || item.name.contains(query) || item.packageName.contains(query)) {
                mItems.add(item);
            }
        }
        mState = new int[mItems.size()];
        mProgress = new int[mItems.size()];
        for (int i = 0; i < mItems.size(); i++) {
            mState[i] = AppRepository.get().contains(mItems.get(i).packageName)
                    ? STATE_INSTALLED : STATE_DOWNLOAD;
        }
        mList.setAdapter(new MarketAdapter(this, mItems, mState, mProgress));
    }

    void onActionClicked(final int position) {
        switch (mState[position]) {
            case STATE_DOWNLOAD:
                mState[position] = STATE_DOWNLOADING;
                mProgress[position] = 0;
                tickDownload(position);
                break;
            case STATE_INSTALL:
                AppRepository.get().add(new AppInfo(
                        mItems.get(position).packageName, mItems.get(position).name));
                mState[position] = STATE_INSTALLED;
                ((MarketAdapter) mList.getAdapter()).notifyDataSetChanged();
                break;
            case STATE_INSTALLED:
                break;
            default:
                break;
        }
    }

    private void tickDownload(final int position) {
        if (mState[position] != STATE_DOWNLOADING) {
            return;
        }
        mProgress[position] += 5;
        ((MarketAdapter) mList.getAdapter()).notifyDataSetChanged();
        if (mProgress[position] >= 100) {
            mState[position] = STATE_INSTALL;
        } else {
            mHandler.postDelayed(() -> tickDownload(position), 150);
        }
    }

    private static class MarketAdapter extends BaseAdapter {
        private final MarketActivity mOuter;
        private final List<StoreItem> mItems;
        private final int[] mState;
        private final int[] mProgress;

        MarketAdapter(MarketActivity outer, List<StoreItem> items,
                      int[] state, int[] progress) {
            mOuter = outer;
            mItems = items;
            mState = state;
            mProgress = progress;
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mOuter.getLayoutInflater().inflate(R.layout.item_market, parent, false);
            }
            StoreItem item = mItems.get(position);
            ((TextView) convertView.findViewById(R.id.name)).setText(item.name);
            ((TextView) convertView.findViewById(R.id.meta)).setText(item.meta());
            ((ImageView) convertView.findViewById(R.id.icon)).setImageDrawable(mOuter.icon(item.packageName));

            Button action = convertView.findViewById(R.id.btnAction);
            switch (mState[position]) {
                case STATE_DOWNLOAD:
                    action.setText("下载");
                    action.setEnabled(true);
                    break;
                case STATE_DOWNLOADING:
                    action.setText("下载中 " + mProgress[position] + "%");
                    action.setEnabled(false);
                    break;
                case STATE_INSTALL:
                    action.setText("安装");
                    action.setEnabled(true);
                    break;
                case STATE_INSTALLED:
                    action.setText("已安装");
                    action.setEnabled(false);
                    break;
                default:
                    break;
            }
            action.setOnClickListener(v -> mOuter.onActionClicked(position));
            return convertView;
        }
    }

    /** 搜索框文本监听。 */
    private static class SearchWatcher implements android.text.TextWatcher {
        private final MarketActivity mOuter;

        SearchWatcher(MarketActivity outer) {
            mOuter = outer;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            mOuter.refreshItems(s.toString());
        }
    }
}
