/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.util;

import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.farmerbb.taskbar.BuildConfig;
import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.activity.DummyActivity;
import com.farmerbb.taskbar.activity.InvisibleActivityFreeform;
import com.farmerbb.taskbar.receiver.LockDeviceReceiver;

import java.util.ArrayList;
import java.util.List;

public class U {

    private U() {}

    private static SharedPreferences pref;
    private static Toast toast;
    private static Integer cachedRotation;

    private static final int FULLSCREEN = 0;
    private static final int LEFT = -1;
    private static final int RIGHT = 1;

    public static final int HIDDEN = 0;
    public static final int TOP_APPS = 1;

    public static SharedPreferences getSharedPreferences(Context context) {
        if(pref == null) pref = context.getSharedPreferences(BuildConfig.APPLICATION_ID + "_preferences", Context.MODE_PRIVATE);
        return pref;
    }

    @TargetApi(Build.VERSION_CODES.M)
    public static void showPermissionDialog(final Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.permission_dialog_title)
                .setMessage(R.string.permission_dialog_message)
                .setPositiveButton(R.string.action_grant_permission, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                        } catch (ActivityNotFoundException e) {
                            showErrorDialog(context, "SYSTEM_ALERT_WINDOW");
                        }
                    }
                });

        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.setCancelable(false);
    }

    public static void showErrorDialog(final Context context, String appopCmd) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.error_dialog_title)
                .setMessage(context.getString(R.string.error_dialog_message, BuildConfig.APPLICATION_ID, appopCmd))
                .setPositiveButton(R.string.action_ok, null);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public static void lockDevice(Context context) {
        ComponentName component = new ComponentName(BuildConfig.APPLICATION_ID, LockDeviceReceiver.class.getName());
        context.getPackageManager().setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        DevicePolicyManager mDevicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if(mDevicePolicyManager.isAdminActive(component))
            mDevicePolicyManager.lockNow();
        else {
            Intent intent = new Intent(context, DummyActivity.class);
            intent.putExtra("device_admin", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public static void showToast(Context context, int message) {
        showToast(context, context.getString(message), Toast.LENGTH_SHORT);
    }

    public static void showToastLong(Context context, int message) {
        showToast(context, context.getString(message), Toast.LENGTH_LONG);
    }

    public static void showToast(Context context, String message, int length) {
        if(toast != null) toast.cancel();

        toast = Toast.makeText(context, message, length);
        toast.show();
    }

    public static void launchApp(final Context context,
                                 final String packageName,
                                 final String componentName,
                                 final boolean launchedFromTaskbar,
                                 final boolean padStatusBar,
                                 final boolean openInNewWindow) {
        boolean shouldDelay = false;

        SharedPreferences pref = getSharedPreferences(context);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && pref.getBoolean("freeform_hack", false)
                && !FreeformHackHelper.getInstance().isInFreeformWorkspace()) {
            shouldDelay = true;

            int msToWait = 0;
            if(!FreeformHackHelper.getInstance().isFreeformHackActive()) {
                float factor = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1);
                if(factor < 0.334)
                    factor = 0.334f;

                msToWait = (int) (900 * factor);

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startFreeformHack(context, launchedFromTaskbar);
                    }
                }, msToWait);
            } else
                startFreeformHack(context, launchedFromTaskbar);

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    continueLaunchingApp(context, packageName, componentName, true, padStatusBar, false);
                }
            }, msToWait + 100);
        }

        if(!FreeformHackHelper.getInstance().isFreeformHackActive()) {
            if(shouldDelay) {
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        continueLaunchingApp(context, packageName, componentName, launchedFromTaskbar, padStatusBar, openInNewWindow);
                    }
                }, 100);
            } else
                continueLaunchingApp(context, packageName, componentName, launchedFromTaskbar, padStatusBar, openInNewWindow);
        } else if(FreeformHackHelper.getInstance().isInFreeformWorkspace())
            continueLaunchingApp(context, packageName, componentName, launchedFromTaskbar, padStatusBar, openInNewWindow);
    }

    private static void startFreeformHack(Context context, boolean launchedFromTaskbar) {
        Intent freeformHackIntent = new Intent(context, InvisibleActivityFreeform.class);
        freeformHackIntent.putExtra("check_multiwindow", true);
        freeformHackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if(launchedFromTaskbar) {
            SharedPreferences pref = getSharedPreferences(context);
            if(pref.getBoolean("disable_animations", false))
                freeformHackIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }

        context.startActivity(freeformHackIntent);
    }

    @TargetApi(Build.VERSION_CODES.N)
    private static void continueLaunchingApp(Context context,
                                             String packageName,
                                             String componentName,
                                             boolean launchedFromTaskbar,
                                             boolean padStatusBar,
                                             boolean openInNewWindow) {
        SharedPreferences pref = getSharedPreferences(context);
        Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(componentName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        if(launchedFromTaskbar) {
            if(pref.getBoolean("disable_animations", false))
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }

        if(openInNewWindow) {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

            switch(intent.resolveActivityInfo(context.getPackageManager(), 0).launchMode) {
                case ActivityInfo.LAUNCH_SINGLE_TASK:
                case ActivityInfo.LAUNCH_SINGLE_INSTANCE:
                    intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                    break;
            }
        }

        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !pref.getBoolean("freeform_hack", false)) {
            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException | IllegalArgumentException e) { /* Gracefully fail */ }
        } else switch(SavedWindowSizes.getInstance(context).getWindowSize(context, packageName)) {
            case "standard":
                if(FreeformHackHelper.getInstance().isInFreeformWorkspace())
                    try {
                        context.startActivity(intent);
                    } catch (ActivityNotFoundException | IllegalArgumentException e) { /* Gracefully fail */ }
                else
                    launchMode1(context, intent, 1);
                break;
            case "large":
                launchMode1(context, intent, 2);
                break;
            case "fullscreen":
                launchMode2(context, intent, padStatusBar, FULLSCREEN);
                break;
            case "half_left":
                launchMode2(context, intent, padStatusBar, LEFT);
                break;
            case "half_right":
                launchMode2(context, intent, padStatusBar, RIGHT);
                break;
            case "phone_size":
                launchMode3(context, intent);
                break;
        }

        if(pref.getBoolean("hide_taskbar", true) && !FreeformHackHelper.getInstance().isInFreeformWorkspace())
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_TASKBAR"));
        else
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU"));
    }
    
    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.N)
    private static void launchMode1(Context context, Intent intent, int factor) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

        int width1 = display.getWidth() / (4 * factor);
        int width2 = display.getWidth() - width1;
        int height1 = display.getHeight() / (4 * factor);
        int height2 = display.getHeight() - height1;

        try {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchBounds(new Rect(
                    width1,
                    height1,
                    width2,
                    height2
            )).toBundle());
        } catch (ActivityNotFoundException | IllegalArgumentException e) { /* Gracefully fail */ }
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.N)
    private static void launchMode2(Context context, Intent intent, boolean padStatusBar, int launchType) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

        int statusBarHeight = getStatusBarHeight(context);

        String position = getTaskbarPosition(context);
        boolean overridePad = position.equals("top_left") || position.equals("top_right");

        boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean isLandscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        int left = launchType == RIGHT && isLandscape
                ? display.getWidth() / 2
                : 0;

        int top;
        if(launchType == RIGHT && isPortrait) {
            top = (display.getHeight() / 2)
                    + (!padStatusBar && overridePad ? statusBarHeight / 2 : 0)
                    + (!padStatusBar && !overridePad ? statusBarHeight / 2 : 0);
        } else {
            top = padStatusBar || overridePad ? statusBarHeight : 0;
        }

        int right = launchType == LEFT && isLandscape
                ? display.getWidth() / 2
                : display.getWidth();

        int bottom;
        if(launchType == LEFT && isPortrait) {
            bottom = display.getHeight() / 2
                    + (!padStatusBar && overridePad ? statusBarHeight / 2 : 0)
                    - (!padStatusBar && !overridePad ? statusBarHeight / 2 : 0);
        } else {
            bottom = display.getHeight()
                    + ((!padStatusBar && overridePad) || (!padStatusBar && launchType == RIGHT && isPortrait)
                    ? statusBarHeight
                    : 0);
        }

        int iconSize = context.getResources().getDimensionPixelSize(R.dimen.icon_size);

        if(position.contains("vertical_left")) {
            if(launchType != RIGHT || isPortrait) left = left + iconSize;
        } else if(position.contains("vertical_right")) {
            if(launchType != LEFT || isPortrait) right = right - iconSize;
        } else if(position.contains("bottom")) {
            if(isLandscape || (launchType != LEFT && isPortrait))
                bottom = bottom - iconSize;
        } else if(isLandscape || (launchType != RIGHT && isPortrait))
            top = top + iconSize;

        try {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchBounds(new Rect(
                    left,
                    top,
                    right,
                    bottom
            )).toBundle());
        } catch (ActivityNotFoundException | IllegalArgumentException e) { /* Gracefully fail */ }
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.N)
    private static void launchMode3(Context context, Intent intent) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

        int width1 = display.getWidth() / 2;
        int width2 = context.getResources().getDimensionPixelSize(R.dimen.phone_size_width) / 2;
        int height1 = display.getHeight() / 2;
        int height2 = context.getResources().getDimensionPixelSize(R.dimen.phone_size_height) / 2;

        try {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchBounds(new Rect(
                    width1 - width2,
                    height1 - height2,
                    width1 + width2,
                    height1 + height2
            )).toBundle());
        } catch (ActivityNotFoundException | IllegalArgumentException e) { /* Gracefully fail */ }
    }

    public static void checkForUpdates(Context context) {
        String url;
        try {
            context.getPackageManager().getPackageInfo("com.android.vending", 0);
            url = "https://play.google.com/store/apps/details?id=" + BuildConfig.APPLICATION_ID;
        } catch (PackageManager.NameNotFoundException e) {
            url = "https://f-droid.org/repository/browse/?fdid=" + BuildConfig.BASE_APPLICATION_ID;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) { /* Gracefully fail */ }
    }

    public static boolean bootToFreeformActive(Context context) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = context.getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);

        return defaultLauncher.activityInfo.packageName.equals(BuildConfig.APPLICATION_ID);
    }

    public static void setCachedRotation(int cachedRotation) {
        U.cachedRotation = cachedRotation;
    }

    public static String getTaskbarPosition(Context context) {
        SharedPreferences pref = getSharedPreferences(context);
        String position = pref.getString("position", "bottom_left");

        if(pref.getBoolean("anchor", false)) {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            int rotation = cachedRotation != null ? cachedRotation : windowManager.getDefaultDisplay().getRotation();

            switch(position) {
                case "bottom_left":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "bottom_left";
                        case Surface.ROTATION_90:
                            return "bottom_vertical_right";
                        case Surface.ROTATION_180:
                            return "top_right";
                        case Surface.ROTATION_270:
                            return "top_vertical_left";
                    }
                    break;
                case "bottom_vertical_left":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "bottom_vertical_left";
                        case Surface.ROTATION_90:
                            return "bottom_right";
                        case Surface.ROTATION_180:
                            return "top_vertical_right";
                        case Surface.ROTATION_270:
                            return "top_left";
                    }
                    break;
                case "bottom_right":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "bottom_right";
                        case Surface.ROTATION_90:
                            return "top_vertical_right";
                        case Surface.ROTATION_180:
                            return "top_left";
                        case Surface.ROTATION_270:
                            return "bottom_vertical_left";
                    }
                    break;
                case "bottom_vertical_right":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "bottom_vertical_right";
                        case Surface.ROTATION_90:
                            return "top_right";
                        case Surface.ROTATION_180:
                            return "top_vertical_left";
                        case Surface.ROTATION_270:
                            return "bottom_left";
                    }
                    break;
                case "top_left":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "top_left";
                        case Surface.ROTATION_90:
                            return "bottom_vertical_left";
                        case Surface.ROTATION_180:
                            return "bottom_right";
                        case Surface.ROTATION_270:
                            return "top_vertical_right";
                    }
                    break;
                case "top_vertical_left":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "top_vertical_left";
                        case Surface.ROTATION_90:
                            return "bottom_left";
                        case Surface.ROTATION_180:
                            return "bottom_vertical_right";
                        case Surface.ROTATION_270:
                            return "top_right";
                    }
                    break;
                case "top_right":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "top_right";
                        case Surface.ROTATION_90:
                            return "top_vertical_left";
                        case Surface.ROTATION_180:
                            return "bottom_left";
                        case Surface.ROTATION_270:
                            return "bottom_vertical_right";
                    }
                    break;
                case "top_vertical_right":
                    switch(rotation) {
                        case Surface.ROTATION_0:
                            return "top_vertical_right";
                        case Surface.ROTATION_90:
                            return "top_left";
                        case Surface.ROTATION_180:
                            return "bottom_vertical_left";
                        case Surface.ROTATION_270:
                            return "bottom_right";
                    }
                    break;
            }
        }

        return position;
    }

    public static int getMaxNumOfColumns(Context context) {
        // The base Taskbar size without any recent apps added.
        // Someday this might be automatically calculated, but today is not that day.
        float baseTaskbarSize = 92;
        int numOfColumns = 0;

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float maxScreenSize = getTaskbarPosition(context).contains("vertical")
                ? (metrics.heightPixels - getStatusBarHeight(context)) / metrics.density
                : metrics.widthPixels / metrics.density;

        float iconSize = context.getResources().getDimension(R.dimen.icon_size) / metrics.density;

        SharedPreferences pref = getSharedPreferences(context);
        int userMaxNumOfColumns = Integer.valueOf(pref.getString("max_num_of_recents", "10"));

        while(baseTaskbarSize + iconSize < maxScreenSize
                && numOfColumns < userMaxNumOfColumns) {
            baseTaskbarSize = baseTaskbarSize + iconSize;
            numOfColumns++;
        }

        return numOfColumns;
    }

    private static int getStatusBarHeight(Context context) {
        int statusBarHeight = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if(resourceId > 0)
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);

        return statusBarHeight;
    }

    public static void refreshPinnedIcons(Context context) {
        IconCache.getInstance(context).clearCache();

        PinnedBlockedApps pba = PinnedBlockedApps.getInstance(context);
        List<AppEntry> pinnedAppsList = new ArrayList<>(pba.getPinnedApps());
        List<AppEntry> blockedAppsList = new ArrayList<>(pba.getBlockedApps());
        PackageManager pm = context.getPackageManager();

        pba.clear(context);

        for(AppEntry entry : pinnedAppsList) {
            Intent throwaway = new Intent();
            throwaway.setComponent(ComponentName.unflattenFromString(entry.getComponentName()));

            AppEntry newEntry = new AppEntry(
                    entry.getPackageName(),
                    entry.getComponentName(),
                    entry.getLabel(),
                    IconCache.getInstance(context).getIcon(context, pm, throwaway.resolveActivityInfo(pm, 0)),
                    true);

            pba.addPinnedApp(context, newEntry);
        }

        for(AppEntry entry : blockedAppsList) {
            pba.addBlockedApp(context, entry);
        }
    }
}
