/*
 * Copyright (C) 2009-2010 Aubort Jean-Baptiste (Rorist)
 * Licensed under GNU's GPL 2, see README
 */
package com.sonaive.library;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.sonaive.library.network.HostBean;
import com.sonaive.library.network.NetInfo;

import java.util.ArrayList;
import java.util.List;

public class ActivityDiscovery extends Activity {

    private final String TAG = "ActivityDiscovery";
    private static LayoutInflater mInflater;
    private List<HostBean> hosts = null;
    private HostsAdapter adapter;
    private Button btn_discover;
    private Scanner mScanner;
    protected SharedPreferences prefs;

    private ListView mListView;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.discovery);
        mInflater = LayoutInflater.from(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Discover
        btn_discover = (Button) findViewById(R.id.btn_discover);
        btn_discover.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startDiscovering();
            }
        });

        // Hosts list
        adapter = new HostsAdapter(this);
        mListView = (ListView) findViewById(R.id.output);
        mListView.setAdapter(adapter);
        mListView.setItemsCanFocus(false);
        mListView.setEmptyView(findViewById(R.id.list_empty));
        mScanner = Scanner.getInstance(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        mScanner.registerReceiver(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mScanner.unregisterReceiver(this);
    }

    static class ViewHolder {
        TextView host;
        TextView mac;
        TextView vendor;
    }

    // Custom ArrayAdapter
    private class HostsAdapter extends ArrayAdapter<Void> {
        public HostsAdapter(Context mContext) {
            super(mContext, R.layout.list_host, R.id.list);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.list_host, null);
                holder = new ViewHolder();
                holder.host = (TextView) convertView.findViewById(R.id.list);
                holder.mac = (TextView) convertView.findViewById(R.id.mac);
                holder.vendor = (TextView) convertView.findViewById(R.id.vendor);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            final HostBean host = hosts.get(position);

            if (host.hostname != null && !host.hostname.equals(host.ipAddress)) {
                holder.host.setText(host.hostname + " (" + host.ipAddress + ")");
            } else {
                holder.host.setText(host.ipAddress);
            }
            if (!host.hardwareAddress.equals(NetInfo.NOMAC)) {
                holder.mac.setText(host.hardwareAddress);
                if(host.nicVendor != null){
                    holder.vendor.setText(host.nicVendor);
                } else {
                    holder.vendor.setText(R.string.info_unknown);
                }
                holder.mac.setVisibility(View.VISIBLE);
                holder.vendor.setVisibility(View.VISIBLE);
            } else {
                holder.mac.setVisibility(View.GONE);
                holder.vendor.setVisibility(View.GONE);
            }
            return convertView;
        }
    }

    /**
     * Discover hosts
     */
    private void startDiscovering() {
        mScanner.setCallback(new ScanningCallback() {
            @Override
            public void onScanFinished(List<HostBean> result) {

            }

            @Override
            public void onFoundNeighbour(HostBean neighbour) {
                addHost(neighbour);
            }
        });
        mScanner.scan();
        btn_discover.setText(R.string.btn_discover_cancel);
        btn_discover.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopDiscovering();
            }
        });
        makeToast(R.string.discover_start);
        initList();
    }

    public void stopDiscovering() {
        Log.e(TAG, "stopDiscovering()");
        mScanner.cancel();
        btn_discover.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startDiscovering();
            }
        });
        btn_discover.setText(R.string.btn_discover);
    }

    private void initList() {
        adapter.clear();
        hosts = new ArrayList<HostBean>();
    }

    public void addHost(HostBean host) {
        host.position = hosts.size();
        hosts.add(host);
        adapter.add(null);
        mListView.setSelection(adapter.getCount());
    }

    public void makeToast(int msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
