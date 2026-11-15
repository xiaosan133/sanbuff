package com.vdesk;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 虚拟设备列表：点击切换生效设备（多开数据区隔离）。 */
public class DeviceProfileActivity extends Activity {

    private ListView mList;
    private List<DeviceProfile> mProfiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_profile);

        mList = findViewById(R.id.list);
        Button btnNew = findViewById(R.id.btnNew);
        btnNew.setOnClickListener(v -> {
            DeviceProfile p = new DeviceProfile();
            DeviceProfileStore.get().add(p);
            Toast.makeText(this, "已新增设备 " + p.id, Toast.LENGTH_SHORT).show();
            refresh();
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    void refresh() {
        mProfiles = DeviceProfileStore.get().getProfiles();
        mList.setAdapter(new ProfileAdapter(this, mProfiles));
    }

    private static class ProfileAdapter extends BaseAdapter {
        private final DeviceProfileActivity mOuter;
        private final List<DeviceProfile> mProfiles;

        ProfileAdapter(DeviceProfileActivity outer, List<DeviceProfile> profiles) {
            mOuter = outer;
            mProfiles = profiles;
        }

        @Override
        public int getCount() {
            return mProfiles.size();
        }

        @Override
        public Object getItem(int position) {
            return mProfiles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mOuter.getLayoutInflater().inflate(
                        R.layout.item_device_profile, parent, false);
            }
            final DeviceProfile p = mProfiles.get(position);
            ((TextView) convertView.findViewById(R.id.name))
                    .setText("虚拟设备 " + p.id);

            TextView activeMark = convertView.findViewById(R.id.activeMark);
            boolean isActive = DeviceProfileStore.get().getActive() == p;
            activeMark.setVisibility(isActive ? View.VISIBLE : View.GONE);

            ((TextView) convertView.findViewById(R.id.summary))
                    .setText("数据区：/virtual/" + p.id);
            ((TextView) convertView.findViewById(R.id.applied))
                    .setText("点击切换");

            convertView.setOnClickListener(v -> {
                DeviceProfileStore.get().setActive(p);
                Toast.makeText(mOuter, "已切换到设备 " + p.id, Toast.LENGTH_SHORT).show();
                mOuter.refresh();
            });
            return convertView;
        }
    }
}
