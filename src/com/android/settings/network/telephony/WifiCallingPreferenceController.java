/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.network.telephony;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.ims.ImsManager;
import com.android.settings.R;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import java.util.List;

/**
 * Preference controller for "Wifi Calling"
 */
public class WifiCallingPreferenceController extends TelephonyBasePreferenceController implements
        LifecycleObserver, OnStart, OnStop {

    private TelephonyManager mTelephonyManager;
    @VisibleForTesting
    CarrierConfigManager mCarrierConfigManager;
    @VisibleForTesting
    ImsManager mImsManager;
    private ImsMmTelManager mImsMmTelManager;
    @VisibleForTesting
    PhoneAccountHandle mSimCallManager;
    private PhoneCallStateListener mPhoneStateListener;
    private Preference mPreference;

    public WifiCallingPreferenceController(Context context, String key) {
        super(context, key);
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mPhoneStateListener = new PhoneCallStateListener(Looper.getMainLooper());
    }

    @Override
    public int getAvailabilityStatus(int subId) {
        return subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                && MobileNetworkUtils.isWifiCallingEnabled(mContext, subId)
                ? AVAILABLE
                : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public void onStart() {
        mPhoneStateListener.register(mSubId);
    }

    @Override
    public void onStop() {
        mPhoneStateListener.unregister();
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
        final Intent intent = mPreference.getIntent();
        if (intent != null) {
            intent.putExtra(Settings.EXTRA_SUB_ID, mSubId);
        }
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        if (mSimCallManager != null) {
            final Intent intent = MobileNetworkUtils.buildPhoneAccountConfigureIntent(mContext,
                    mSimCallManager);
            if (intent == null) {
                // Do nothing in this case since preference is invisible
                return;
            }
            final PackageManager pm = mContext.getPackageManager();
            final List<ResolveInfo> resolutions = pm.queryIntentActivities(intent, 0);
            preference.setTitle(resolutions.get(0).loadLabel(pm));
            preference.setSummary(null);
            preference.setIntent(intent);
        } else {
            final String title = SubscriptionManager.getResourcesForSubId(mContext, mSubId)
                    .getString(R.string.wifi_calling_settings_title);
            preference.setTitle(title);
            int resId = com.android.internal.R.string.wifi_calling_off_summary;
            if (mImsManager.isWfcEnabledByUser()) {
                boolean useWfcHomeModeForRoaming = false;
                if (mCarrierConfigManager != null) {
                    final PersistableBundle carrierConfig =
                            mCarrierConfigManager.getConfigForSubId(mSubId);
                    if (carrierConfig != null) {
                        useWfcHomeModeForRoaming = carrierConfig.getBoolean(
                                CarrierConfigManager
                                        .KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL);
                    }
                }
                final boolean isRoaming = mTelephonyManager.isNetworkRoaming();
                final int wfcMode = (isRoaming && !useWfcHomeModeForRoaming)
                        ? mImsMmTelManager.getVoWiFiRoamingModeSetting() :
                        mImsMmTelManager.getVoWiFiModeSetting();
                switch (wfcMode) {
                    case ImsMmTelManager.WIFI_MODE_WIFI_ONLY:
                        resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                        break;
                    case ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED:
                        resId = com.android.internal.R.string
                                .wfc_mode_cellular_preferred_summary;
                        break;
                    case ImsMmTelManager.WIFI_MODE_WIFI_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                        break;
                    default:
                        break;
                }
            }
            preference.setSummary(resId);
        }
        preference.setEnabled(
                mTelephonyManager.getCallState(mSubId) == TelephonyManager.CALL_STATE_IDLE);
    }

    public WifiCallingPreferenceController init(int subId) {
        mSubId = subId;
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId);
        mImsManager = ImsManager.getInstance(mContext, SubscriptionManager.getPhoneId(mSubId));
        mImsMmTelManager = getImsMmTelManager(mSubId);
        mSimCallManager = mContext.getSystemService(TelecomManager.class)
                .getSimCallManagerForSubscription(mSubId);

        return this;
    }

    protected ImsMmTelManager getImsMmTelManager(int subId) {
        return ImsMmTelManager.createForSubscriptionId(subId);
    }

    private class PhoneCallStateListener extends PhoneStateListener {

        public PhoneCallStateListener(Looper looper) {
            super(looper);
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            updateState(mPreference);
        }

        public void register(int subId) {
            mSubId = subId;
            mTelephonyManager.listen(this, PhoneStateListener.LISTEN_CALL_STATE);
        }

        public void unregister() {
            mTelephonyManager.listen(this, PhoneStateListener.LISTEN_NONE);
        }
    }
}
