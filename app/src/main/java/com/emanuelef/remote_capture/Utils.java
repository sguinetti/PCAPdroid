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

package com.emanuelef.remote_capture;

import android.app.Activity;
import android.app.Dialog;
import android.app.Service;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ScaleDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;

import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.views.AppsListView;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class Utils {
    public static final String PCAP_HEADER = "d4c3b2a1020004000000000000000000ffff000065000000";

    public static String formatBytes(long bytes) {
        long divisor;
        String suffix;
        if(bytes < 1024) return bytes + " B";

        if(bytes < 1024*1024)               { divisor = 1024;           suffix = "KB"; }
        else if(bytes < 1024*1024*1024)     { divisor = 1024*1024;      suffix = "MB"; }
        else                                { divisor = 1024*1024*1024; suffix = "GB"; }

        return String.format("%.1f %s", ((float)bytes) / divisor, suffix);
    }

    public static String formatPkts(long pkts) {
        long divisor;
        String suffix;
        if(pkts < 1000) return Long.toString(pkts);

        if(pkts < 1000*1000)               { divisor = 1000;           suffix = "K"; }
        else if(pkts < 1000*1000*1000)     { divisor = 1000*1000;      suffix = "M"; }
        else                               { divisor = 1000*1000*1000; suffix = "G"; }

        return String.format("%.1f %s", ((float)pkts) / divisor, suffix);
    }

    public static String formatNumber(Context context, long num) {
        Locale locale = context.getResources().getConfiguration().locale;
        return String.format(locale, "%,d", num);
    }

    public static String formatDuration(long seconds) {
        if(seconds == 0)
            return "< 1 s";
        else if(seconds < 60)
            return String.format("%d s", seconds);
        else if(seconds < 3600)
            return String.format("> %d m", seconds / 60);
        else
            return String.format("> %d h", seconds / 3600);
    }

    public static String proto2str(int proto) {
        switch(proto) {
            case 6:     return "TCP";
            case 17:    return "UDP";
            case 1:     return "ICMP";
            default:    return(Integer.toString(proto));
        }
    }

    public static String getDnsServer(Context context) {
        ConnectivityManager conn = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);

        if(conn != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Network net = conn.getActiveNetwork();

                if (net != null) {
                    LinkProperties props = conn.getLinkProperties(net);

                    if(props != null) {
                        List<InetAddress> dns_servers = props.getDnsServers();

                        for(InetAddress addr : dns_servers) {
                            // Get the first IPv4 DNS server
                            if(addr instanceof Inet4Address) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            }
        }

        // Fallback
        return "8.8.8.8";
    }

    // https://gist.github.com/mathieugerard/0de2b6f5852b6b0b37ed106cab41eba1
    public static String getLocalWifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo connInfo = wifiManager.getConnectionInfo();

        if(connInfo != null) {
            int ipAddress = connInfo.getIpAddress();

            if(ipAddress == 0)
                return(null);

            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                ipAddress = Integer.reverseBytes(ipAddress);
            }

            byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

            String ipAddressString;
            try {
                ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
            } catch (UnknownHostException ex) {
                return(null);
            }

            return ipAddressString;
        }

        return(null);
    }

    public static String getLocalIPAddress(Context context) {
        InetAddress vpn_ip;

        try {
            vpn_ip = InetAddress.getByName(CaptureService.VPN_IP_ADDRESS);
        } catch (UnknownHostException e) {
            return "";
        }

        // try to get the WiFi IP address first
        String wifi_ip = getLocalWifiIpAddress(context);

        if((wifi_ip != null) && (!wifi_ip.equals("0.0.0.0"))) {
            Log.d("getLocalIPAddress", "Using WiFi IP: " + wifi_ip);
            return wifi_ip;
        }

        // otherwise search for other network interfaces
        // https://stackoverflow.com/questions/6064510/how-to-get-ip-address-of-the-device-from-code
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if(!intf.isVirtual()) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress()
                                && addr.isSiteLocalAddress() /* Exclude public IPs */
                                && !addr.equals(vpn_ip)) {
                            String sAddr = addr.getHostAddress();

                            if ((addr instanceof Inet4Address) && !sAddr.equals("0.0.0.0")) {
                                Log.d("getLocalIPAddress", "Using interface '" + intf.getName() + "' IP: " + sAddr);
                                return sAddr;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        // Fallback
        Log.d("getLocalIPAddress", "Using fallback IP");
        return "127.0.0.1";
    }

    // returns current timestamp in seconds
    public static long now() {
        Calendar calendar = Calendar.getInstance();
        return(calendar.getTimeInMillis() / 1000);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static boolean hasVPNRunning(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if(cm != null) {
            Network[] networks = cm.getAllNetworks();

            for(Network net : networks) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(net);

                if ((cap != null) && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    Log.d("hasVPNRunning", "detected VPN connection: " + net.toString());
                    return true;
                }
            }
        }

        return false;
    }

    public static void showToast(Context context, int id) {
        String msg = context.getResources().getString(id);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    public static Dialog getAppSelectionDialog(Activity activity, List<AppDescriptor> appsData, AppsListView.OnSelectedAppListener listener) {
        View dialogLayout = activity.getLayoutInflater().inflate(R.layout.apps_selector, null);
        SearchView searchView = dialogLayout.findViewById(R.id.apps_search);
        AppsListView apps = dialogLayout.findViewById(R.id.apps_list);
        TextView emptyText = dialogLayout.findViewById(R.id.no_apps);

        apps.setApps(appsData);
        apps.setEmptyView(emptyText);
        searchView.setOnQueryTextListener(apps);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.app_filter);
        builder.setView(dialogLayout);

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);

        apps.setSelectedAppListener(app -> {
            listener.onSelectedApp(app);

            // dismiss the dialog
            alert.dismiss();
        });

        return alert;
    }

    public static String getUniquePcapFileName(Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        final DateFormat fmt = new SimpleDateFormat("dd_MMM_HH_mm_ss", locale);
        return  "PCAPdroid_" + fmt.format(new Date()) + ".pcap";
    }

    public static Drawable scaleDrawable(Resources res, Drawable drawable, int new_x, int new_y) {
        Bitmap bitmap = Bitmap.createBitmap(new_x, new_y, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return new BitmapDrawable(res, bitmap);
    }
}
