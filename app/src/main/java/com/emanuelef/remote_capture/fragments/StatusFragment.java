/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.activities.InspectorActivity;
import com.emanuelef.remote_capture.adapters.DumpModesAdapter;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.activities.StatsActivity;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.VPNStats;
import com.emanuelef.remote_capture.views.AppsListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class StatusFragment extends Fragment implements AppStateListener, AppsLoadListener {
    private static final String TAG = "StatusFragment";
    private TextView mCollectorInfo;
    private TextView mCaptureStatus;
    private TextView mInspectorLink;
    private View mQuickSettings;
    private MainActivity mActivity;
    private SharedPreferences mPrefs;
    private BroadcastReceiver mReceiver;
    private TextView mFilterDescription;
    private SwitchCompat mAppFilterSwitch;
    private String mAppFilter;
    private boolean mOpenAppsWhenDone;
    private List<AppDescriptor> mInstalledApps;
    AppsListView mOpenAppsList;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (MainActivity) context;
    }

    @Override
    public void onDestroy() {
        mActivity.setAppStateListener(null);
        mActivity = null;
        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status, container, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mCollectorInfo = view.findViewById(R.id.collector_info);
        mCaptureStatus = view.findViewById(R.id.status_view);
        mInspectorLink = view.findViewById(R.id.inspector_link);
        mQuickSettings = view.findViewById(R.id.quick_settings);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mAppFilter = Prefs.getAppFilter(mPrefs);
        mOpenAppsWhenDone = false;

        DumpModesAdapter dumpModeAdapter = new DumpModesAdapter(getContext());
        Spinner dumpMode = view.findViewById(R.id.dump_mode_spinner);
        dumpMode.setAdapter(dumpModeAdapter);
        int curSel = dumpModeAdapter.getModePos(mPrefs.getString(Prefs.PREF_PCAP_DUMP_MODE, Prefs.DEFAULT_DUMP_MODE));
        dumpMode.setSelection(curSel);

        dumpMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                DumpModesAdapter.DumpModeInfo mode = (DumpModesAdapter.DumpModeInfo) dumpModeAdapter.getItem(position);
                SharedPreferences.Editor editor = mPrefs.edit();

                editor.putString(Prefs.PREF_PCAP_DUMP_MODE, mode.key);
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        mAppFilterSwitch = view.findViewById(R.id.app_filter_switch);
        View filterRow = view.findViewById(R.id.app_filter_text);
        TextView filterTitle = filterRow.findViewById(R.id.title);
        mFilterDescription = filterRow.findViewById(R.id.description);

        filterTitle.setText(R.string.app_filter);

        mAppFilterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(isChecked) {
                if((mAppFilter == null) || (mAppFilter.isEmpty()))
                    openAppFilterSelector();
            } else
                setAppFilter(null);
        });

        refreshFilterInfo();

        mCaptureStatus.setOnClickListener(v -> {
            if(mActivity.getState() == AppState.running) {
                Intent intent = new Intent(getActivity(), StatsActivity.class);
                startActivity(intent);
            }
        });

        // Make URLs clickable
        mCollectorInfo.setMovementMethod(LinkMovementMethod.getInstance());

        setupInspectorLinK();

        /* Register for stats update */
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                processStatsUpdateIntent(intent);
            }
        };

        LocalBroadcastManager.getInstance(getContext())
            .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_STATS_DUMP));

        /* Important: call this after all the fields have been initialized */
        mActivity.setAppStateListener(this);

        (new AppsLoader(mActivity))
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if(mReceiver != null) {
            LocalBroadcastManager.getInstance(getContext())
                    .unregisterReceiver(mReceiver);
        }
    }

    private AppDescriptor findAppByPackage(String pkgname) {
        if(mInstalledApps == null)
            return null;

        for(AppDescriptor app : mInstalledApps) {
            if(pkgname.equals(app.getPackageName()))
                return app;
        }

        return null;
    }

    private void refreshFilterInfo() {
        if((mAppFilter == null) || (mAppFilter.isEmpty())) {
            mFilterDescription.setText(R.string.no_app_filter);
            mFilterDescription.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            mAppFilterSwitch.setChecked(false);
            return;
        }

        mAppFilterSwitch.setChecked(true);

        AppDescriptor app = findAppByPackage(mAppFilter);
        String description;

        if(app == null)
            description = mAppFilter;
        else {
            description = app.getName() + " (" + app.getPackageName() + ")";

            if(app.getIcon() != null) {
                int height = mFilterDescription.getMeasuredHeight();
                Drawable drawable = Utils.scaleDrawable(getResources(), app.getIcon(), height, height);

                if(drawable != null)
                    mFilterDescription.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
                else
                    mFilterDescription.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        mFilterDescription.setText(description);
    }

    private void setAppFilter(AppDescriptor filter) {
        SharedPreferences.Editor editor = mPrefs.edit();
        mAppFilter = (filter != null) ? filter.getPackageName() : "";

        editor.putString(Prefs.PREF_APP_FILTER, mAppFilter);
        editor.apply();
        refreshFilterInfo();
    }

    private void setupInspectorLinK() {
        int color = ContextCompat.getColor(mActivity, android.R.color.tab_indicator_text);

        mInspectorLink.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        Drawable []drawables = mInspectorLink.getCompoundDrawables();
        drawables[0].setColorFilter(color, PorterDuff.Mode.SRC_IN);

        mInspectorLink.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), InspectorActivity.class);
            startActivity(intent);
        });
    }

    private void processStatsUpdateIntent(Intent intent) {
        VPNStats stats = (VPNStats) intent.getSerializableExtra("value");

        Log.d("MainReceiver", "Got StatsUpdate: bytes_sent=" + stats.pkts_sent + ", bytes_rcvd=" +
                stats.bytes_rcvd + ", pkts_sent=" + stats.pkts_sent + ", pkts_rcvd=" + stats.pkts_rcvd);

        mCaptureStatus.setText(Utils.formatBytes(stats.bytes_sent + stats.bytes_rcvd));
    }

private void refreshPcapDumpInfo() {
        String info = "";

        Prefs.DumpMode mode = CaptureService.getDumpMode();

        switch (mode) {
        case NONE:
            info = getString(R.string.no_dump_info);
            break;
        case HTTP_SERVER:
            info = String.format(getResources().getString(R.string.http_server_status),
                    Utils.getLocalIPAddress(mActivity), CaptureService.getHTTPServerPort());
            break;
        case PCAP_FILE:
            info = getString(R.string.pcap_file_info);

            if(mActivity != null) {
                String fname = mActivity.getPcapFname();

                if(fname != null)
                    info = fname;
            }
            break;
        case UDP_EXPORTER:
            info = String.format(getResources().getString(R.string.collector_info),
                    CaptureService.getCollectorAddress(), CaptureService.getCollectorPort());
            break;
        }

        mCollectorInfo.setText(info);

        // Check if a filter is set
        if(mAppFilter != null) {
            AppDescriptor app = findAppByPackage(mAppFilter);

            if((app != null) && (app.getIcon() != null)) {
                int height = mCollectorInfo.getMeasuredHeight();
                Drawable drawable = Utils.scaleDrawable(getResources(), app.getIcon(), height, height);

                if(drawable != null)
                    mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
                else
                    mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            } else
                mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        } else
            mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
    }

    @Override
    public void appStateChanged(AppState state) {
        switch(state) {
            case ready:
                mCaptureStatus.setText(R.string.ready);
                mInspectorLink.setVisibility(View.GONE);
                mCollectorInfo.setVisibility(View.GONE);
                mQuickSettings.setVisibility(View.VISIBLE);
                break;
            case running:
                mCaptureStatus.setText(Utils.formatBytes(CaptureService.getBytes()));
                mInspectorLink.setVisibility(View.VISIBLE);
                mCollectorInfo.setVisibility(View.VISIBLE);
                mQuickSettings.setVisibility(View.GONE);
                refreshPcapDumpInfo();
                break;
            default:
                break;
        }
    }

    private void loadInstalledApps(Map<Integer, AppDescriptor> apps, boolean with_icons) {
        mInstalledApps = new ArrayList<>();

        for(Map.Entry<Integer, AppDescriptor> pair : apps.entrySet()) {
            AppDescriptor app = pair.getValue();

            if(!app.isVirtual())
                mInstalledApps.add(app);
        }

        Collections.sort(mInstalledApps);
        refreshFilterInfo();

        if(mOpenAppsWhenDone && mAppFilterSwitch.isChecked())
            openAppFilterSelector();
    }

    private void openAppFilterSelector() {
        if(mInstalledApps == null) {
            // Applications not loaded yet
            mOpenAppsWhenDone = true;
            Utils.showToast(getContext(), R.string.apps_loading_please_wait);
            return;
        }

        mOpenAppsWhenDone = false;

        Dialog dialog = Utils.getAppSelectionDialog(mActivity, mInstalledApps, this::setAppFilter);
        dialog.setOnCancelListener(dialog1 -> {
            setAppFilter(null);
        });
        dialog.setOnDismissListener(dialog1 -> {
            mOpenAppsList = null;
        });

        dialog.show();

        // NOTE: run this after dialog.show
        mOpenAppsList = (AppsListView) dialog.findViewById(R.id.apps_list);
    }

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        loadInstalledApps(apps, false);
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        loadInstalledApps(apps, true);

        // Possibly update the icons
        if(mOpenAppsList != null) {
            Log.d(TAG, "reloading app icons in dialog");
            mOpenAppsList.setApps(mInstalledApps);
        }
    }
}
