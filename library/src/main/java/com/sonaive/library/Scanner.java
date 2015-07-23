package com.sonaive.library;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.mcxiaoke.next.task.SimpleTaskCallback;
import com.mcxiaoke.next.task.TaskBuilder;
import com.sonaive.library.network.HardwareAddress;
import com.sonaive.library.network.HostBean;
import com.sonaive.library.network.NetInfo;
import com.sonaive.library.network.RateControl;
import com.sonaive.library.utils.Prefs;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Created by liutao on 15-7-23.
 */
public class Scanner {
    private final String TAG = Scanner.class.getSimpleName();

    private final static int[] PORTS = { 139, 445, 22, 80 };
    private final static int TIMEOUT_SCAN = 3600; // seconds
    private final static int TIMEOUT_SHUTDOWN = 10; // seconds
    private final static int THREADS = 10; //FIXME: Test, plz set in options again ?

    private int hostsDone = 0;
    private long mIp;
    private long mStart = 0;
    private long mEnd = 0;
    private long mSize = 0;

    private final int mRateMult = 5; // Number of alive hosts between Rate
    private int mDirection = 2; // 1 backward, 2 forward
    private ExecutorService mPool;
    private boolean doRateControl;
    private RateControl mRateControl;

    private List<HostBean> mHosts;

    private static Scanner mInstance;

    private ScanningCallback mCallback;
    private static Handler mHandler = new Handler(Looper.getMainLooper());

    private ConnectivityManager connMgr;

    protected Context mContext;
    protected SharedPreferences prefs;
    protected NetInfo net;
    private int currentNetwork = 0;

    protected String ipInfo = "";
    protected String ssidInfo = "";
    protected String modeInfo = "";

    private Scanner(Context context) {
        mRateControl = new RateControl();
        mHosts = new ArrayList<HostBean>();
        mContext = context.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        connMgr = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        net = new NetInfo(mContext);
    }

    public static Scanner getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new Scanner(context);
        }
        return mInstance;
    }

    public void registerReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        context.registerReceiver(receiver, filter);
    }

    public void unregisterReceiver(Context context) {
        context.unregisterReceiver(receiver);
    }


    public void setCallback(ScanningCallback callback) {
        mCallback = callback;
    }

    public void scan() {
        TaskBuilder.create(new Callable<List<HostBean>>() {
            @Override
            public List<HostBean> call() throws Exception {
                Log.v(TAG, "start=" + NetInfo.getIpFromLongUnsigned(mStart) + " (" + mStart
                        + "), end=" + NetInfo.getIpFromLongUnsigned(mEnd) + " (" + mEnd
                        + "), length=" + mSize);
                mPool = Executors.newFixedThreadPool(THREADS);
                if (mIp <= mEnd && mIp >= mStart) {
                    Log.i(TAG, "Back and forth scanning");
                    // gateway
                    launch(mStart);

                    // hosts
                    long pt_backward = mIp;
                    long pt_forward = mIp + 1;
                    long size_hosts = mSize - 1;

                    for (int i = 0; i < size_hosts; i++) {
                        // Set pointer if of limits
                        if (pt_backward <= mStart) {
                            mDirection = 2;
                        } else if (pt_forward > mEnd) {
                            mDirection = 1;
                        }
                        // Move back and forth
                        if (mDirection == 1) {
                            launch(pt_backward);
                            pt_backward--;
                            mDirection = 2;
                        } else if (mDirection == 2) {
                            launch(pt_forward);
                            pt_forward++;
                            mDirection = 1;
                        }
                    }
                } else {
                    Log.i(TAG, "Sequence scanning");
                    for (long i = mStart; i <= mEnd; i++) {
                        launch(i);
                    }
                }
                mPool.shutdown();
                try {
                    if(!mPool.awaitTermination(TIMEOUT_SCAN, TimeUnit.SECONDS)) {
                        mPool.shutdownNow();
                        Log.e(TAG, "Shutting down pool");
                        if(!mPool.awaitTermination(TIMEOUT_SHUTDOWN, TimeUnit.SECONDS)){
                            Log.e(TAG, "Pool did not terminate");
                        }
                    }
                } catch (InterruptedException e){
                    Log.e(TAG, e.getMessage());
                    mPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                return mHosts;
            }
        }).callback(new SimpleTaskCallback<List<HostBean>>() {

            @Override
            public void onTaskSuccess(final List<HostBean> list, Bundle extras) {
                Log.d(TAG, "Machine total count is: " + list.size());
                if (mCallback != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCallback.onScanFinished(list);
                        }
                    });
                }
            }

            @Override
            public void onTaskFailure(Throwable ex, Bundle extras) {
                super.onTaskFailure(ex, extras);
            }
        }).with(this).serial(true).start();
    }

    private void launch(long i) {
        if(!mPool.isShutdown()) {
            mPool.execute(new CheckRunnable(NetInfo.getIpFromLongUnsigned(i)));
        }
    }

    private int getRate() {
        if (doRateControl) {
            return mRateControl.rate;
        }

        return Integer.parseInt(Prefs.DEFAULT_TIMEOUT_DISCOVER);
    }

    private void publish(final HostBean host) {
        hostsDone++;
        if(host == null){
            return;
        }

        // Mac address not already detected
        if(NetInfo.NOMAC.equals(host.hardwareAddress)){
            host.hardwareAddress = HardwareAddress.getHardwareAddress(host.ipAddress);
        }
        mHosts.add(host);
        if (mCallback != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onFoundNeighbour(host);
                }
            });
        }
    }

    private class CheckRunnable implements Runnable {
        private String addr;

        CheckRunnable(String addr) {
            this.addr = addr;
        }

        public void run() {
//            if(isCancelled()) {
//                publish(null);
//            }
            Log.d(TAG, "run = " + addr);
            // Create host object
            final HostBean host = new HostBean();
            host.responseTime = getRate();
            host.ipAddress = addr;
            try {
                InetAddress h = InetAddress.getByName(addr);
                // Rate control check
                if (doRateControl && mRateControl.indicator != null && hostsDone % mRateMult == 0) {
                    mRateControl.adaptRate();
                }
                // Arp Check #1
                host.hardwareAddress = HardwareAddress.getHardwareAddress(addr);
                if(!NetInfo.NOMAC.equals(host.hardwareAddress)){
                    Log.e(TAG, "found using arp #1 " + addr);
                    publish(host);
                    return;
                }
                // Native InetAddress check
                if (h.isReachable(getRate())) {
                    Log.e(TAG, "found using InetAddress ping " + addr);
                    publish(host);
                    // Set indicator and get a rate
                    if (doRateControl && mRateControl.indicator == null) {
                        mRateControl.indicator = addr;
                        mRateControl.adaptRate();
                    }
                    return;
                }
                // Arp Check #2
                host.hardwareAddress = HardwareAddress.getHardwareAddress(addr);
                if(!NetInfo.NOMAC.equals(host.hardwareAddress)){
                    Log.e(TAG, "found using arp #2 " + addr);
                    publish(host);
                    return;
                }
                // Custom check
                int port;
                // TODO: Get ports from options
                Socket s = new Socket();
                for (int PORT : PORTS) {
                    try {
                        s.bind(null);
                        s.connect(new InetSocketAddress(addr, PORT), getRate());
                        Log.v(TAG, "found using TCP connect " + addr + " on port=" + PORT);
                    } catch (IOException e) {
                    } catch (IllegalArgumentException e) {
                    } finally {
                        try {
                            s.close();
                        } catch (Exception e) {
                        }
                    }
                }

                // Arp Check #3
                host.hardwareAddress = HardwareAddress.getHardwareAddress(addr);
                if(!NetInfo.NOMAC.equals(host.hardwareAddress)){
                    Log.e(TAG, "found using arp #3 " + addr);
                    publish(host);
                    return;
                }
                publish(null);

            } catch (IOException e) {
                publish(null);
                Log.e(TAG, e.getMessage());
            }
        }
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {

            // Wifi state
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    int WifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                    //Log.d(TAG, "WifiState=" + WifiState);
                    switch (WifiState) {
                        case WifiManager.WIFI_STATE_ENABLING:
                            ssidInfo = context.getString(R.string.wifi_enabling);
                            break;
                        case WifiManager.WIFI_STATE_ENABLED:
                            ssidInfo = context.getString(R.string.wifi_enabled);
                            break;
                        case WifiManager.WIFI_STATE_DISABLING:
                            ssidInfo = context.getString(R.string.wifi_disabling);
                            break;
                        case WifiManager.WIFI_STATE_DISABLED:
                            ssidInfo = context.getString(R.string.wifi_disabled);
                            break;
                        default:
                            ssidInfo = context.getString(R.string.wifi_unknown);
                    }
                }

                if (action.equals(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) && net.getWifiInfo()) {
                    SupplicantState sstate = net.getSupplicantState();
                    if (sstate == SupplicantState.SCANNING) {
                        ssidInfo = context.getString(R.string.wifi_scanning);
                    } else if (sstate == SupplicantState.ASSOCIATING) {
                        ssidInfo = context.getString(R.string.wifi_associating,
                                (net.ssid != null ? net.ssid : (net.bssid != null ? net.bssid
                                        : net.macAddress)));
                    } else if (sstate == SupplicantState.COMPLETED) {
                        ssidInfo = context.getString(R.string.wifi_dhcp, net.ssid);
                    }
                }
            }

            // 3G(connected) -> Wifi(connected)
            // Support Ethernet, with ConnectivityManager.TYPE_ETHER=3
            final NetworkInfo ni = connMgr.getActiveNetworkInfo();
            if (ni != null) {
                //Log.i(TAG, "NetworkState="+ni.getDetailedState());
                if (ni.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
                    int type = ni.getType();
                    //Log.i(TAG, "NetworkType="+type);
                    if (type == ConnectivityManager.TYPE_WIFI) { // WIFI
                        net.getWifiInfo();
                        if (net.ssid != null) {
                            net.getIp();
                            ipInfo = context.getString(R.string.net_ip, net.ip, net.cidr, net.intf);
                            ssidInfo = context.getString(R.string.net_ssid, net.ssid);
                            modeInfo = context.getString(R.string.net_mode, context.getString(
                                    R.string.net_mode_wifi, net.speed, WifiInfo.LINK_SPEED_UNITS));
                        }
                    } else if (type == ConnectivityManager.TYPE_MOBILE) { // 3G
                        if (prefs.getBoolean(Prefs.KEY_MOBILE, Prefs.DEFAULT_MOBILE)
                                || prefs.getString(Prefs.KEY_INTF, Prefs.DEFAULT_INTF) != null) {
                            net.getMobileInfo();
                            if (net.carrier != null) {
                                net.getIp();
                                ipInfo = context.getString(R.string.net_ip, net.ip, net.cidr, net.intf);
                                ssidInfo = context.getString(R.string.net_carrier, net.carrier);
                                modeInfo = context.getString(R.string.net_mode,
                                        context.getString(R.string.net_mode_mobile));
                            }
                        }
                    } else if (type == 3 || type == 9) { // ETH
                        net.getIp();
                        ipInfo = context.getString(R.string.net_ip, net.ip, net.cidr, net.intf);
                        ssidInfo = "";
                        modeInfo = context.getString(R.string.net_mode) + context.getString(R.string.net_mode_eth);
                        Log.i(TAG, "Ethernet connectivity detected!");
                    } else {
                        Log.i(TAG, "Connectivity unknown!");
                        modeInfo = context.getString(R.string.net_mode)
                                + context.getString(R.string.net_mode_unknown);
                    }
                } else {
//                    cancelTasks();
                }
            } else {
//                cancelTasks();
            }

            // Always update network info
            setInfo();
        }

        protected void setInfo() {


            if (currentNetwork != net.hashCode()) {
                Log.i(TAG, "Network info has changed");
                currentNetwork = net.hashCode();

                // Cancel running tasks
//                cancelTasks();
            } else {
                return;
            }

            // Get ip information
            mIp = NetInfo.getUnsignedLongFromIp(net.ip);
            if (prefs.getBoolean(Prefs.KEY_IP_CUSTOM, Prefs.DEFAULT_IP_CUSTOM)) {
                // Custom IP
                mStart = NetInfo.getUnsignedLongFromIp(prefs.getString(Prefs.KEY_IP_START,
                        Prefs.DEFAULT_IP_START));
                mEnd = NetInfo.getUnsignedLongFromIp(prefs.getString(Prefs.KEY_IP_END,
                        Prefs.DEFAULT_IP_END));
            } else {
                // Custom CIDR
                if (prefs.getBoolean(Prefs.KEY_CIDR_CUSTOM, Prefs.DEFAULT_CIDR_CUSTOM)) {
                    net.cidr = Integer.parseInt(prefs.getString(Prefs.KEY_CIDR, Prefs.DEFAULT_CIDR));
                }
                // Detected IP
                int shift = (32 - net.cidr);
                if (net.cidr < 31) {
                    mStart = (mIp >> shift << shift) + 1;
                    mEnd = (mStart | ((1 << shift) - 1)) - 1;
                } else {
                    mStart = (mIp >> shift << shift);
                    mEnd = (mStart | ((1 << shift) - 1));
                }
                // Reset ip start-end (is it really convenient ?)
                SharedPreferences.Editor edit = prefs.edit();
                edit.putString(Prefs.KEY_IP_START, NetInfo.getIpFromLongUnsigned(mStart));
                edit.putString(Prefs.KEY_IP_END, NetInfo.getIpFromLongUnsigned(mEnd));
                edit.commit();
            }
            mSize = (int) (mEnd - mStart + 1);
        }
    };
}
