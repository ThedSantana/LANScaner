package com.sonaive.library;

import com.sonaive.library.network.HostBean;

import java.util.List;

/**
 * Created by liutao on 15-7-23.
 */
public interface ScanningCallback {

    void onScanFinished(List<HostBean> result);
    void onFoundNeighbour(HostBean neighbour);

}
