/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.wifi;

import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantNetwork;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.SparseArray;

import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hal calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 */
public class SupplicantStaIfaceHal {
    private static final boolean DBG = false;
    private static final String TAG = "SupplicantStaIfaceHal";
    private static final String SERVICE_MANAGER_NAME = "manager";
    private IServiceManager mIServiceManager = null;
    // Supplicant HAL interface objects
    private ISupplicant mISupplicant;
    private ISupplicantStaIface mISupplicantStaIface;
    // Currently configured network in wpa_supplicant
    private SupplicantStaNetworkHal mCurrentNetwork;
    // Currently configured network's framework network Id.
    private int mFrameworkNetworkId;
    private final Object mLock = new Object();
    private final Context mContext;
    private final WifiMonitor mWifiMonitor;

    public SupplicantStaIfaceHal(Context context, WifiMonitor monitor) {
        mContext = context;
        mWifiMonitor = monitor;
    }

    /**
     * Registers a service notification for the ISupplicant service, which triggers intialization of
     * the ISupplicantStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        if (DBG) Log.i(TAG, "Registering ISupplicant service ready callback.");
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!mIServiceManager.linkToDeath(cookie -> {
                    Log.wtf(TAG, "IServiceManager died: cookie=" + cookie);
                    synchronized (mLock) {
                        supplicantServiceDiedHandler();
                        mIServiceManager = null; // Will need to register a new ServiceNotification
                    }
                }, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    supplicantServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
                IServiceNotification serviceNotificationCb = new IServiceNotification.Stub() {
                    public void onRegistration(String fqName, String name, boolean preexisting) {
                        synchronized (mLock) {
                            if (DBG) {
                                Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                                        + ", " + name + " preexisting=" + preexisting);
                            }
                            if (!initSupplicantService() || !initSupplicantStaIface()) {
                                Log.e(TAG, "initalizing ISupplicantIfaces failed.");
                                supplicantServiceDiedHandler();
                            } else {
                                Log.i(TAG, "Completed initialization of ISupplicant interfaces.");
                            }
                        }
                    }
                };
                /* TODO(b/33639391) : Use the new ISupplicant.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(ISupplicant.kInterfaceName,
                        "", serviceNotificationCb)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicant.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for ISupplicant service: "
                        + e);
                supplicantServiceDiedHandler();
            }
            return true;
        }
    }

    private boolean initSupplicantService() {
        synchronized (mLock) {
            try {
                mISupplicant = getSupplicantMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                return false;
            }
            if (mISupplicant == null) {
                Log.e(TAG, "Got null ISupplicant service. Stopping supplicant HIDL startup");
                return false;
            }
        }
        return true;
    }

    private boolean initSupplicantStaIface() {
        synchronized (mLock) {
            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
            try {
                mISupplicant.listInterfaces((SupplicantStatus status,
                        ArrayList<ISupplicant.IfaceInfo> ifaces) -> {
                    if (status.code != SupplicantStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting Supplicant Interfaces failed: " + status.code);
                        return;
                    }
                    supplicantIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.listInterfaces exception: " + e);
                return false;
            }
            if (supplicantIfaces.size() == 0) {
                Log.e(TAG, "Got zero HIDL supplicant ifaces. Stopping supplicant HIDL startup.");
                return false;
            }
            Mutable<ISupplicantIface> supplicantIface = new Mutable<>();
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.STA) {
                    try {
                        mISupplicant.getInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantIface iface) -> {
                                if (status.code != SupplicantStatusCode.SUCCESS) {
                                    Log.e(TAG, "Failed to get ISupplicantIface " + status.code);
                                    return;
                                }
                                supplicantIface.value = iface;
                            });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicant.getInterface exception: " + e);
                        return false;
                    }
                    break;
                }
            }
            if (supplicantIface.value == null) {
                Log.e(TAG, "initSupplicantStaIface got null iface");
                return false;
            }
            mISupplicantStaIface = getStaIfaceMockable(supplicantIface.value);
            return true;
        }
    }

    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
        }
    }

    /**
     * Signals whether Initialization completed successfully. Only necessary for testing, is not
     * needed to guard calls etc.
     */
    public boolean isInitializationComplete() {
        return mISupplicantStaIface != null;
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        return IServiceManager.getService(SERVICE_MANAGER_NAME);
    }

    protected ISupplicant getSupplicantMockable() throws RemoteException {
        return ISupplicant.getService();
    }

    protected ISupplicantStaIface getStaIfaceMockable(ISupplicantIface iface) {
        return ISupplicantStaIface.asInterface(iface.asBinder());
    }

    /**
     * Add a network configuration to wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return SupplicantStaNetwork of the added network in wpa_supplicant.
     */
    private SupplicantStaNetworkHal addNetwork(WifiConfiguration config) {
        logi("addSupplicantStaNetwork via HIDL");
        if (config == null) {
            loge("Cannot add NULL network!");
            return null;
        }
        SupplicantStaNetworkHal network = addNetwork();
        if (network == null) {
            loge("Failed to add a network!");
            return null;
        }
        if (network.saveWifiConfiguration(config)) {
            return network;
        } else {
            loge("Failed to save variables for: " + config.configKey());
            return null;
        }
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. Triggers disconnect command to wpa_supplicant (if |shouldDisconnect| is true).
     * 2. Remove any existing network in wpa_supplicant.
     * 3. Add a new network to wpa_supplicant.
     * 4. Save the provided configuration to wpa_supplicant.
     * 5. Select the new network in wpa_supplicant.
     *
     * @param config WifiConfiguration parameters for the provided network.
     * @param shouldDisconnect whether to trigger a disconnection or not.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(WifiConfiguration config, boolean shouldDisconnect) {
        mFrameworkNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mCurrentNetwork = null;
        logd("connectToNetwork " + config.configKey()
                + " (shouldDisconnect " + shouldDisconnect + ")");
        if (shouldDisconnect && !disconnect()) {
            loge("Failed to trigger disconnect");
            return false;
        }
        if (!removeAllNetworks()) {
            loge("Failed to remove existing networks");
            return false;
        }
        mCurrentNetwork = addNetwork(config);
        if (mCurrentNetwork == null) {
            loge("Failed to add/save network configuration: " + config.configKey());
            return false;
        }
        if (!mCurrentNetwork.select()) {
            loge("Failed to select network configuration: " + config.configKey());
            return false;
        }
        mFrameworkNetworkId = config.networkId;
        return true;
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 2. Set the new bssid for the network in wpa_supplicant.
     * 3. Trigger reassociate command to wpa_supplicant.
     *
     * @param config WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(WifiConfiguration config) {
        if (mFrameworkNetworkId != config.networkId || mCurrentNetwork == null) {
            Log.w(TAG, "Cannot roam to a different network, initiate new connection. "
                    + "Current network ID: " + mFrameworkNetworkId);
            return connectToNetwork(config, false);
        }
        String bssid = config.getNetworkSelectionStatus().getNetworkSelectionBSSID();
        logd("roamToNetwork" + config.configKey() + " (bssid " + bssid + ")");
        if (!mCurrentNetwork.setBssid(bssid)) {
            loge("Failed to set new bssid on network: " + config.configKey());
            return false;
        }
        if (!reassociate()) {
            loge("Failed to trigger reassociate");
            return false;
        }
        return true;
    }

    /**
     * Load all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return true if succeeds, false otherwise.
     */
    public boolean loadNetworks(Map<String, WifiConfiguration> configs,
                                SparseArray<Map<String, String>> networkExtras) {
        List<Integer> networkIds = listNetworks();
        if (networkIds == null) {
            Log.e(TAG, "Failed to list networks");
            return false;
        }
        for (Integer networkId : networkIds) {
            SupplicantStaNetworkHal network = getNetwork(networkId);
            if (network == null) {
                Log.e(TAG, "Failed to get network with ID: " + networkId);
                return false;
            }
            WifiConfiguration config = new WifiConfiguration();
            Map<String, String> networkExtra = new HashMap<>();
            if (!network.loadWifiConfiguration(config, networkExtra)) {
                Log.e(TAG, "Failed to load wifi configuration for network with ID: " + networkId);
                return false;
            }
            // Set the default IP assignments.
            config.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
            config.setProxySettings(IpConfiguration.ProxySettings.NONE);

            networkExtras.put(networkId, networkExtra);
            String configKey = networkExtra.get(SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY);
            final WifiConfiguration duplicateConfig = configs.put(configKey, config);
            if (duplicateConfig != null) {
                // The network is already known. Overwrite the duplicate entry.
                Log.i(TAG, "Replacing duplicate network: " + duplicateConfig.networkId);
                removeNetwork(duplicateConfig.networkId);
                networkExtras.remove(duplicateConfig.networkId);
            }
        }
        return true;
    }

    /**
     * Remove all networks from supplicant
     */
    public boolean removeAllNetworks() {
        synchronized (mLock) {
            ArrayList<Integer> networks = listNetworks();
            if (networks == null) {
                Log.e(TAG, "removeAllNetworks failed, got null networks");
                return false;
            }
            for (int id : networks) {
                if (!removeNetwork(id)) {
                    Log.e(TAG, "removeAllNetworks failed to remove network: " + id);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Gets the interface name.
     *
     * @return returns the name of Iface or null if the call fails
     */
    private String getName() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            final String methodStr = "getName";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            final StringBuilder builder = new StringBuilder();
            try {
                mISupplicantStaIface.getName((SupplicantStatus status, String name) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        builder.append(name);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return builder.toString();
            } else {
                return null;
            }
        }
    }

    /**
     * Adds a new network.
     *
     * @return The ISupplicantNetwork object for the new network, or null if the call fails
     */
    private SupplicantStaNetworkHal addNetwork() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<ISupplicantNetwork> newNetwork = new Mutable<>();
            final String methodStr = "addNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.addNetwork((SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        newNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return getStaNetworkMockable(
                        ISupplicantStaNetwork.asInterface(newNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /**
     * Remove network from supplicant with network Id
     *
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean removeNetwork(int id) {
        synchronized (mLock) {
            final String methodStr = "removeNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeNetwork(id);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Use this to mock the creation of SupplicantStaNetworkHal instance.
     *
     * @param iSupplicantStaNetwork ISupplicantStaNetwork instance retrieved from HIDL.
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    protected SupplicantStaNetworkHal getStaNetworkMockable(
            ISupplicantStaNetwork iSupplicantStaNetwork) {
        return new SupplicantStaNetworkHal(iSupplicantStaNetwork, mContext, mWifiMonitor);
    }

    /**
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    private SupplicantStaNetworkHal getNetwork(int id) {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<ISupplicantNetwork> gotNetwork = new Mutable<>();
            final String methodStr = "getNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.getNetwork(id, (SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        gotNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return getStaNetworkMockable(
                        ISupplicantStaNetwork.asInterface(gotNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /**
     * @return a list of SupplicantNetworkID ints for all networks controlled by supplicant, returns
     * null if the call fails
     */
    private java.util.ArrayList<Integer> listNetworks() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<ArrayList<Integer>> networkIdList = new Mutable<>();
            final String methodStr = "listNetworks";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.listNetworks((SupplicantStatus status,
                        java.util.ArrayList<Integer> networkIds) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        networkIdList.value = networkIds;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return networkIdList.value;
            } else {
                return null;
            }
        }
    }

    /**
     * Trigger a reassociation even if the iface is currently connected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reassociate() {
        synchronized (mLock) {
            final String methodStr = "reassociate";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reassociate();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Trigger a reconnection if the iface is disconnected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reconnect() {
        synchronized (mLock) {
            final String methodStr = "reconnect";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Trigger a disconnection from the currently connected network.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean disconnect() {
        synchronized (mLock) {
            final String methodStr = "disconnect";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.disconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Enable or disable power save mode.
     *
     * @param enable true to enable, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setPowerSave(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setPowerSave";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setPowerSave(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Initiate TDLS discover with the specified AP.
     *
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsDiscover(String macAddress) {
        return initiateTdlsDiscover(NativeUtil.macAddressToByteArray(macAddress));
    }
    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsDiscover(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsDiscover";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsDiscover(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Initiate TDLS setup with the specified AP.
     *
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsSetup(String macAddress) {
        return initiateTdlsSetup(NativeUtil.macAddressToByteArray(macAddress));
    }
    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsSetup(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsSetup";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsSetup(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Initiate TDLS teardown with the specified AP.
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsTeardown(String macAddress) {
        return initiateTdlsTeardown(NativeUtil.macAddressToByteArray(macAddress));
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsTeardown(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsTeardown";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsTeardown(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Request the specified ANQP elements |elements| from the specified AP |bssid|.
     *
     * @param bssid BSSID of the AP
     * @param infoElements ANQP elements to be queried. Refer to ISupplicantStaIface.AnqpInfoId.
     * @param hs20SubTypes HS subtypes to be queried. Refer to ISupplicantStaIface.Hs20AnqpSubTypes.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateAnqpQuery(String bssid, ArrayList<Short> infoElements,
                                     ArrayList<Integer> hs20SubTypes) {
        return initiateAnqpQuery(
                NativeUtil.macAddressToByteArray(bssid), infoElements, hs20SubTypes);
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateAnqpQuery(byte[/* 6 */] macAddress,
            java.util.ArrayList<Short> infoElements, java.util.ArrayList<Integer> subTypes) {
        synchronized (mLock) {
            final String methodStr = "initiateAnqpQuery";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateAnqpQuery(macAddress,
                        infoElements, subTypes);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Request the specified ANQP ICON from the specified AP |bssid|.
     *
     * @param bssid BSSID of the AP
     * @param fileName Name of the file to request.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateHs20IconQuery(String bssid, String fileName) {
        return initiateHs20IconQuery(NativeUtil.macAddressToByteArray(bssid), fileName);
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateHs20IconQuery(byte[/* 6 */] macAddress, String fileName) {
        synchronized (mLock) {
            final String methodStr = "initiateHs20IconQuery";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateHs20IconQuery(macAddress,
                        fileName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     *
     * @return string containing the MAC address, or null on a failed call
     */
    public String getMacAddress() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            final String methodStr = "getMacAddress";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            Mutable<String> gotMac = new Mutable<>();
            try {
                mISupplicantStaIface.getMacAddress((SupplicantStatus status,
                        byte[/* 6 */] macAddr) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        gotMac.value = NativeUtil.macAddressFromByteArray(macAddr);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return gotMac.value;
            } else {
                return null;
            }
        }
    }

    /**
     * Start using the added RX filters.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startRxFilter() {
        synchronized (mLock) {
            final String methodStr = "startRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Stop using the added RX filters.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean stopRxFilter() {
        synchronized (mLock) {
            final String methodStr = "stopRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.stopRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    public static final byte RX_FILTER_TYPE_V4_MULTICAST =
            ISupplicantStaIface.RxFilterType.V6_MULTICAST;
    public static final byte RX_FILTER_TYPE_V6_MULTICAST =
            ISupplicantStaIface.RxFilterType.V6_MULTICAST;
    /**
     * Add an RX filter.
     *
     * @param type one of {@link #RX_FILTER_TYPE_V4_MULTICAST} or
     *        {@link #RX_FILTER_TYPE_V6_MULTICAST} values.
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean addRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "addRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.addRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Remove an RX filter.
     *
     * @param type one of {@link #RX_FILTER_TYPE_V4_MULTICAST} or
     *        {@link #RX_FILTER_TYPE_V6_MULTICAST} values.
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean removeRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "removeRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    public static final byte BT_COEX_MODE_ENABLED = ISupplicantStaIface.BtCoexistenceMode.ENABLED;
    public static final byte BT_COEX_MODE_DISABLED = ISupplicantStaIface.BtCoexistenceMode.DISABLED;
    public static final byte BT_COEX_MODE_SENSE = ISupplicantStaIface.BtCoexistenceMode.SENSE;
    /**
     * Set Bt co existense mode.
     *
     * @param mode one of the above {@link #BT_COEX_MODE_ENABLED}, {@link #BT_COEX_MODE_DISABLED}
     *             or {@link #BT_COEX_MODE_SENSE} values.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setBtCoexistenceMode(byte mode) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceMode";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setBtCoexistenceMode(mode);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /** Enable or disable BT coexistence mode.
     *
     * @param enable true to enable, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setBtCoexistenceScanModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceScanModeEnabled";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaIface.setBtCoexistenceScanModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Enable or disable suspend mode optimizations.
     *
     * @param enable true to enable, false otherwise.
     */
    public boolean setSuspendModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setSuspendModeEnabled";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setSuspendModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Set country code.
     *
     * @param codeStr 2 byte ASCII string. For ex: US, CA.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setCountryCode(String codeStr) {
        return setCountryCode(NativeUtil.stringToByteArray(codeStr));
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean setCountryCode(byte[/* 2 */] code) {
        synchronized (mLock) {
            final String methodStr = "setCountryCode";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setCountryCode(code);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Returns false if SupplicantStaIface is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantStaIfaceAndLogFailure(final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (mISupplicantStaIface == null) {
            Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaIface is null");
            return false;
        }
        return true;
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private static boolean checkStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (status.code != SupplicantStatusCode.SUCCESS) {
            Log.e(TAG, methodStr + " failed: " + supplicantStatusCodeToString(status.code) + ", "
                    + status.debugMessage);
            return false;
        }
        return true;
    }

    /**
     * Converts SupplicantStatus code values to strings for debug logging
     * TODO(b/34811152) Remove this, or make it more break resistance
     */
    public static String supplicantStatusCodeToString(int code) {
        switch (code) {
            case 0:
                return "SUCCESS";
            case 1:
                return "FAILURE_UNKNOWN";
            case 2:
                return "FAILURE_ARGS_INVALID";
            case 3:
                return "FAILURE_IFACE_INVALID";
            case 4:
                return "FAILURE_IFACE_UNKNOWN";
            case 5:
                return "FAILURE_IFACE_EXISTS";
            case 6:
                return "FAILURE_IFACE_DISABLED";
            case 7:
                return "FAILURE_IFACE_NOT_DISCONNECTED";
            case 8:
                return "FAILURE_NETWORK_INVALID";
            case 9:
                return "FAILURE_NETWORK_UNKNOWN";
            default:
                return "??? UNKNOWN_CODE";
        }
    }

    private static class Mutable<E> {
        public E value;

        Mutable() {
            value = null;
        }

        Mutable(E value) {
            this.value = value;
        }
    }

    private void logd(String s) {
        Log.d(TAG, s);
    }

    private void logi(String s) {
        Log.i(TAG, s);
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}