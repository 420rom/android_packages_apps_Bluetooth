/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.hid;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppConfiguration;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHidDevice;
import android.bluetooth.IBluetoothHidDeviceCallback;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/** @hide */
public class HidDeviceService extends ProfileService {
    private static final boolean DBG = false;

    private static final String TAG = HidDeviceService.class.getSimpleName();

    private static final int MESSAGE_APPLICATION_STATE_CHANGED = 1;
    private static final int MESSAGE_CONNECT_STATE_CHANGED = 2;
    private static final int MESSAGE_GET_REPORT = 3;
    private static final int MESSAGE_SET_REPORT = 4;
    private static final int MESSAGE_SET_PROTOCOL = 5;
    private static final int MESSAGE_INTR_DATA = 6;
    private static final int MESSAGE_VC_UNPLUG = 7;

    private boolean mNativeAvailable = false;

    private BluetoothDevice mHidDevice = null;

    private int mHidDeviceState = BluetoothHidDevice.STATE_DISCONNECTED;

    private BluetoothHidDeviceAppConfiguration mAppConfig = null;

    private IBluetoothHidDeviceCallback mCallback = null;

    private BluetoothHidDeviceDeathRecipient mDeathRcpt;

    static {
        classInitNative();
    }

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (DBG) {
                Log.d(TAG, "handleMessage(): msg.what=" + msg.what);
            }

            switch (msg.what) {
                case MESSAGE_APPLICATION_STATE_CHANGED: {
                    BluetoothDevice device = msg.obj != null ? getDevice((byte[]) msg.obj) : null;
                    boolean success = (msg.arg1 != 0);

                    if (success) {
                        mHidDevice = device;
                    } else {
                        mHidDevice = null;
                    }

                    try {
                        if (mCallback != null) {
                            mCallback.onAppStatusChanged(device, mAppConfig, success);
                        } else {
                            break;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "e=" + e.toString());
                        e.printStackTrace();
                    }

                    if (success) {
                        mDeathRcpt = new BluetoothHidDeviceDeathRecipient(HidDeviceService.this,
                                mAppConfig);
                        if (mCallback != null) {
                            IBinder binder = mCallback.asBinder();
                            try {
                                binder.linkToDeath(mDeathRcpt, 0);
                                Log.i(TAG, "IBinder.linkToDeath() ok");
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                    } else if (mDeathRcpt != null) {
                        if (mCallback != null) {
                            IBinder binder = mCallback.asBinder();
                            try {
                                binder.unlinkToDeath(mDeathRcpt, 0);
                                Log.i(TAG, "IBinder.unlinkToDeath() ok");
                            } catch (NoSuchElementException e) {
                                e.printStackTrace();
                            }
                            mDeathRcpt.cleanup();
                            mDeathRcpt = null;
                        }
                    }

                    if (!success) {
                        mAppConfig = null;
                        mCallback = null;
                    }

                    break;
                }

                case MESSAGE_CONNECT_STATE_CHANGED: {
                    BluetoothDevice device = getDevice((byte[]) msg.obj);
                    int halState = msg.arg1;
                    int state = convertHalState(halState);

                    if (state != BluetoothHidDevice.STATE_DISCONNECTED) {
                        mHidDevice = device;
                    }

                    broadcastConnectionState(device, state);

                    try {
                        if (mCallback != null) {
                            mCallback.onConnectionStateChanged(device, state);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case MESSAGE_GET_REPORT:
                    byte type = (byte) msg.arg1;
                    byte id = (byte) msg.arg2;
                    int bufferSize = msg.obj == null ? 0 : ((Integer) msg.obj).intValue();

                    try {
                        if (mCallback != null) {
                            mCallback.onGetReport(mHidDevice, type, id, bufferSize);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;

                case MESSAGE_SET_REPORT: {
                    byte reportType = (byte) msg.arg1;
                    byte reportId = (byte) msg.arg2;
                    byte[] data = ((ByteBuffer) msg.obj).array();

                    try {
                        if (mCallback != null) {
                            mCallback.onSetReport(mHidDevice, reportType, reportId, data);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case MESSAGE_SET_PROTOCOL:
                    byte protocol = (byte) msg.arg1;

                    try {
                        if (mCallback != null) {
                            mCallback.onSetProtocol(mHidDevice, protocol);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;

                case MESSAGE_INTR_DATA:
                    byte reportId = (byte) msg.arg1;
                    byte[] data = ((ByteBuffer) msg.obj).array();

                    try {
                        if (mCallback != null) {
                            mCallback.onIntrData(mHidDevice, reportId, data);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;

                case MESSAGE_VC_UNPLUG:
                    try {
                        if (mCallback != null) {
                            mCallback.onVirtualCableUnplug(mHidDevice);
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    mHidDevice = null;
                    break;
            }
        }
    };

    private static class BluetoothHidDeviceDeathRecipient implements IBinder.DeathRecipient {
        private HidDeviceService mService;
        private BluetoothHidDeviceAppConfiguration mAppConfig;

        BluetoothHidDeviceDeathRecipient(HidDeviceService service,
                BluetoothHidDeviceAppConfiguration config) {
            mService = service;
            mAppConfig = config;
        }

        @Override
        public void binderDied() {
            Log.w(TAG, "Binder died, need to unregister app :(");
            mService.unregisterApp(mAppConfig);
        }

        public void cleanup() {
            mService = null;
            mAppConfig = null;
        }
    }

    private static class BluetoothHidDeviceBinder extends IBluetoothHidDevice.Stub
            implements IProfileServiceBinder {

        private static final String TAG = BluetoothHidDeviceBinder.class.getSimpleName();

        private HidDeviceService mService;

        BluetoothHidDeviceBinder(HidDeviceService service) {
            mService = service;
        }

        @Override
        public boolean cleanup() {
            mService = null;
            return true;
        }

        private HidDeviceService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "HidDevice call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }

            return null;
        }

        @Override
        public boolean registerApp(BluetoothHidDeviceAppConfiguration config,
                BluetoothHidDeviceAppSdpSettings sdp, BluetoothHidDeviceAppQosSettings inQos,
                BluetoothHidDeviceAppQosSettings outQos, IBluetoothHidDeviceCallback callback) {
            if (DBG) {
                Log.d(TAG, "registerApp()");
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.registerApp(config, sdp, inQos, outQos, callback);
        }

        @Override
        public boolean unregisterApp(BluetoothHidDeviceAppConfiguration config) {
            if (DBG) {
                Log.d(TAG, "unregisterApp()");
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.unregisterApp(config);
        }

        @Override
        public boolean sendReport(BluetoothDevice device, int id, byte[] data) {
            if (DBG) {
                Log.d(TAG, "sendReport(): device=" + device + "  id=" + id);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.sendReport(device, id, data);
        }

        @Override
        public boolean replyReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (DBG) {
                Log.d(TAG, "replyReport(): device=" + device + " type=" + type + " id=" + id);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.replyReport(device, type, id, data);
        }

        @Override
        public boolean unplug(BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "unplug(): device=" + device);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.unplug(device);
        }

        @Override
        public boolean connect(BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "connect(): device=" + device);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "disconnect(): device=" + device);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public boolean reportError(BluetoothDevice device, byte error) {
            if (DBG) {
                Log.d(TAG, "reportError(): device=" + device + " error=" + error);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return false;
            }

            return service.reportError(device, error);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            if (DBG) {
                Log.d(TAG, "getConnectionState(): device=" + device);
            }

            HidDeviceService service = getService();
            if (service == null) {
                return BluetoothHidDevice.STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            if (DBG) {
                Log.d(TAG, "getConnectedDevices()");
            }

            return getDevicesMatchingConnectionStates(new int[]{BluetoothProfile.STATE_CONNECTED});
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            if (DBG) {
                Log.d(TAG,
                        "getDevicesMatchingConnectionStates(): states=" + Arrays.toString(states));
            }

            HidDeviceService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }

            return service.getDevicesMatchingConnectionStates(states);
        }
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothHidDeviceBinder(this);
    }

    private boolean checkDevice(BluetoothDevice device) {
        if (mHidDevice == null || !mHidDevice.equals(device)) {
            Log.w(TAG, "Unknown device: " + device);
            return false;
        }
        return true;
    }

    synchronized boolean registerApp(BluetoothHidDeviceAppConfiguration config,
            BluetoothHidDeviceAppSdpSettings sdp, BluetoothHidDeviceAppQosSettings inQos,
            BluetoothHidDeviceAppQosSettings outQos, IBluetoothHidDeviceCallback callback) {
        if (DBG) {
            Log.d(TAG, "registerApp()");
        }

        if (mAppConfig != null) {
            Log.w(TAG, "registerApp: failed because app configuration already exists. ");
            return false;
        }

        mAppConfig = config;
        mCallback = callback;

        return registerAppNative(sdp.name, sdp.description, sdp.provider, sdp.subclass,
                sdp.descriptors, inQos == null ? null : inQos.toArray(),
                outQos == null ? null : outQos.toArray());
    }

    synchronized boolean unregisterApp(BluetoothHidDeviceAppConfiguration config) {
        if (DBG) {
            Log.d(TAG, "unregisterApp()");
        }
        if (config == null) {
            Log.w(TAG, "unregisterApp: failed. The config in parameter should not be null");
            return false;
        }

        if (mAppConfig == null ||  !config.equals(mAppConfig)) {
            Log.w(TAG, "unregisterApp: failed. The app configuration does not match the record");
            return false;
        }

        return unregisterAppNative();
    }

    synchronized boolean sendReport(BluetoothDevice device, int id, byte[] data) {
        if (DBG) {
            Log.d(TAG, "sendReport(): device=" + device + " id=" + id);
        }

        if (!checkDevice(device)) {
            return false;
        }

        return sendReportNative(id, data);
    }

    synchronized boolean replyReport(BluetoothDevice device, byte type, byte id, byte[] data) {
        if (DBG) {
            Log.d(TAG, "replyReport(): device=" + device + " type=" + type + " id=" + id);
        }

        if (!checkDevice(device)) {
            return false;
        }

        return replyReportNative(type, id, data);
    }

    synchronized boolean unplug(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "unplug(): device=" + device);
        }

        if (!checkDevice(device)) {
            return false;
        }

        return unplugNative();
    }

    synchronized boolean connect(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "connect(): device=" + device);
        }

        return connectNative(Utils.getByteAddress(device));
    }

    synchronized boolean disconnect(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "disconnect(): device=" + device);
        }

        if (!checkDevice(device)) {
            return false;
        }

        return disconnectNative();
    }

    synchronized boolean reportError(BluetoothDevice device, byte error) {
        if (DBG) {
            Log.d(TAG, "reportError(): device=" + device + " error=" + error);
        }

        if (!checkDevice(device)) {
            return false;
        }

        return reportErrorNative(error);
    }

    @Override
    protected boolean start() {
        if (DBG) {
            Log.d(TAG, "start()");
        }

        initNative();
        mNativeAvailable = true;

        return true;
    }

    @Override
    protected boolean stop() {
        if (DBG) {
            Log.d(TAG, "stop()");
        }

        return true;
    }

    @Override
    protected boolean cleanup() {
        if (DBG) {
            Log.d(TAG, "cleanup()");
        }

        if (mNativeAvailable) {
            cleanupNative();
            mNativeAvailable = false;
        }

        return true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Need to unregister app");
        unregisterApp(mAppConfig);
        return super.onUnbind(intent);
    }

    int getConnectionState(BluetoothDevice device) {
        if (mHidDevice != null && mHidDevice.equals(device)) {
            return mHidDeviceState;
        }
        return BluetoothHidDevice.STATE_DISCONNECTED;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        List<BluetoothDevice> inputDevices = new ArrayList<BluetoothDevice>();

        if (mHidDevice != null) {
            for (int state : states) {
                if (state == mHidDeviceState) {
                    inputDevices.add(mHidDevice);
                    break;
                }
            }
        }
        return inputDevices;
    }

    private synchronized void onApplicationStateChanged(byte[] address, boolean registered) {
        if (DBG) {
            Log.d(TAG, "onApplicationStateChanged(): registered=" + registered);
        }

        Message msg = mHandler.obtainMessage(MESSAGE_APPLICATION_STATE_CHANGED);
        msg.obj = address;
        msg.arg1 = registered ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    private synchronized void onConnectStateChanged(byte[] address, int state) {
        if (DBG) {
            Log.d(TAG, "onConnectStateChanged(): address=" + Arrays.toString(address) + " state="
                    + state);
        }

        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_STATE_CHANGED);
        msg.obj = address;
        msg.arg1 = state;
        mHandler.sendMessage(msg);
    }

    private synchronized void onGetReport(byte type, byte id, short bufferSize) {
        if (DBG) {
            Log.d(TAG, "onGetReport(): type=" + type + " id=" + id + " bufferSize=" + bufferSize);
        }

        Message msg = mHandler.obtainMessage(MESSAGE_GET_REPORT);
        msg.obj = bufferSize > 0 ? new Integer(bufferSize) : null;
        msg.arg1 = type;
        msg.arg2 = id;
        mHandler.sendMessage(msg);
    }

    private synchronized void onSetReport(byte reportType, byte reportId, byte[] data) {
        if (DBG) {
            Log.d(TAG, "onSetReport(): reportType=" + reportType + " reportId=" + reportId);
        }

        ByteBuffer bb = ByteBuffer.wrap(data);

        Message msg = mHandler.obtainMessage(MESSAGE_SET_REPORT);
        msg.arg1 = reportType;
        msg.arg2 = reportId;
        msg.obj = bb;
        mHandler.sendMessage(msg);
    }

    private synchronized void onSetProtocol(byte protocol) {
        if (DBG) {
            Log.d(TAG, "onSetProtocol(): protocol=" + protocol);
        }

        Message msg = mHandler.obtainMessage(MESSAGE_SET_PROTOCOL);
        msg.arg1 = protocol;
        mHandler.sendMessage(msg);
    }

    private synchronized void onIntrData(byte reportId, byte[] data) {
        if (DBG) {
            Log.d(TAG, "onIntrData(): reportId=" + reportId);
        }

        ByteBuffer bb = ByteBuffer.wrap(data);

        Message msg = mHandler.obtainMessage(MESSAGE_INTR_DATA);
        msg.arg1 = reportId;
        msg.obj = bb;
        mHandler.sendMessage(msg);
    }

    private synchronized void onVirtualCableUnplug() {
        if (DBG) {
            Log.d(TAG, "onVirtualCableUnplug()");
        }

        Message msg = mHandler.obtainMessage(MESSAGE_VC_UNPLUG);
        mHandler.sendMessage(msg);
    }

    private void broadcastConnectionState(BluetoothDevice device, int newState) {
        if (DBG) {
            Log.d(TAG, "broadcastConnectionState(): device=" + device.getAddress() + " newState="
                    + newState);
        }

        if (mHidDevice != null && !mHidDevice.equals(device)) {
            Log.w(TAG, "Connection state changed for unknown device, ignoring");
            return;
        }

        int prevState = mHidDeviceState;
        mHidDeviceState = newState;

        Log.i(TAG, "connection state for " + device.getAddress() + ": " + prevState + " -> "
                + newState);

        if (prevState == newState) {
            return;
        }

        Intent intent = new Intent(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_PERM);
    }

    private static int convertHalState(int halState) {
        switch (halState) {
            case CONN_STATE_CONNECTED:
                return BluetoothProfile.STATE_CONNECTED;
            case CONN_STATE_CONNECTING:
                return BluetoothProfile.STATE_CONNECTING;
            case CONN_STATE_DISCONNECTED:
                return BluetoothProfile.STATE_DISCONNECTED;
            case CONN_STATE_DISCONNECTING:
                return BluetoothProfile.STATE_DISCONNECTING;
            default:
                return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    private static final int CONN_STATE_CONNECTED = 0;
    private static final int CONN_STATE_CONNECTING = 1;
    private static final int CONN_STATE_DISCONNECTED = 2;
    private static final int CONN_STATE_DISCONNECTING = 3;

    private static native void classInitNative();

    private native void initNative();

    private native void cleanupNative();

    private native boolean registerAppNative(String name, String description, String provider,
            byte subclass, byte[] descriptors, int[] inQos, int[] outQos);

    private native boolean unregisterAppNative();

    private native boolean sendReportNative(int id, byte[] data);

    private native boolean replyReportNative(byte type, byte id, byte[] data);

    private native boolean unplugNative();

    private native boolean connectNative(byte[] btAddress);

    private native boolean disconnectNative();

    private native boolean reportErrorNative(byte error);
}
