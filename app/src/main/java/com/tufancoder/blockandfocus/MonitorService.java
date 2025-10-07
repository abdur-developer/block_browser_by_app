package com.tufancoder.blockandfocus;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {

    private static final String TAG = "MonitorService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "monitor_channel";

    private Handler handler;
    private Runnable monitorRunnable;
    private static final long CHECK_INTERVAL = 3000; // 3 seconds

    private SharedPreferences prefs;
    private UsageStatsManager usageStatsManager;
    private List<String> browserPackages;

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences("BrowserMonitorPrefs", MODE_PRIVATE);
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        initializeBrowserPackages();
        startForegroundWithNotification();
        startMonitoring();

        addToLog("🔍 Service started - Monitoring browsers");
        Log.d(TAG, "Service created and monitoring started");
    }

    private void initializeBrowserPackages() {
        browserPackages = Arrays.asList(
                "com.android.chrome",
                "com.android.browser",
                "org.mozilla.firefox",
                "com.opera.browser",
                "com.brave.browser",
                "com.microsoft.emmx",
                "com.sec.android.app.sbrowser",
                "com.google.android.apps.chrome",
                "com.uc.browser",
                "com.duckduckgo.mobile.android",
                "com.vivaldi.browser",
                "com.kiwibrowser.browser"
        );
    }

    private void startForegroundWithNotification() {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🌐 Browser Monitor")
                .setContentText("Monitoring browser usage in real-time")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Browser Monitor",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Monitors browser usage and blocks after time limit");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void startMonitoring() {
        handler = new Handler();
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                checkCurrentApp();
                handler.postDelayed(this, CHECK_INTERVAL);
            }
        };
        handler.post(monitorRunnable);
    }

    private void checkCurrentApp() {
        try {
            String currentApp = getForegroundApp();

            if (currentApp != null && isBrowser(currentApp)) {
                Log.d(TAG, "Browser detected: " + currentApp);
                handleBrowserDetection(currentApp);
            } else {
                // No browser active
                String currentStoredApp = prefs.getString("current_app", "");
                if (!currentStoredApp.isEmpty()) {
                    Log.d(TAG, "Browser closed: " + currentStoredApp);
                    prefs.edit().remove("current_app").remove("app_start_time").apply();
                    addToLog("📱 " + getAppName(currentStoredApp) + " closed");
                }
                updateNotification("No browser active");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in checkCurrentApp: " + e.getMessage());
            addToLog("❌ Error: " + e.getMessage());
        }
    }

    private String getForegroundApp() {
        try {
            long currentTime = System.currentTimeMillis();
            // Get usage stats for last 10 seconds
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    currentTime - TimeUnit.SECONDS.toMillis(10),
                    currentTime
            );

            if (stats == null || stats.isEmpty()) {
                Log.d(TAG, "No usage stats available");
                return null;
            }

            // Find the app with the most recent last used time
            UsageStats mostRecent = null;
            for (UsageStats usageStats : stats) {
                if (usageStats.getLastTimeUsed() > 0) {
                    if (mostRecent == null || usageStats.getLastTimeUsed() > mostRecent.getLastTimeUsed()) {
                        mostRecent = usageStats;
                    }
                }
            }

            if (mostRecent != null) {
                String packageName = mostRecent.getPackageName();
                long lastUsed = mostRecent.getLastTimeUsed();
                long timeSinceLastUsed = currentTime - lastUsed;

                Log.d(TAG, "Found app: " + packageName + ", last used: " + timeSinceLastUsed + "ms ago");

                // Consider app as foreground if used within last 5 seconds
                if (timeSinceLastUsed < 5000) {
                    return packageName;
                } else {
                    Log.d(TAG, "App not in foreground (too old): " + packageName);
                }
            } else {
                Log.d(TAG, "No recent app found");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app: " + e.getMessage());
        }
        return null;
    }

    private boolean isBrowser(String packageName) {
        if (packageName == null) return false;

        for (String browserPkg : browserPackages) {
            if (packageName.contains(browserPkg)) {
                Log.d(TAG, "Identified as browser: " + packageName);
                return true;
            }
        }
        Log.d(TAG, "Not a browser: " + packageName);
        return false;
    }

    private void handleBrowserDetection(String packageName) {
        String currentApp = prefs.getString("current_app", "");
        long startTime = prefs.getLong("app_start_time", 0);
        long currentTime = System.currentTimeMillis();

        Log.d(TAG, "Handling browser: " + packageName + ", current stored: " + currentApp);

        // Check if blocked
        long blockedUntil = prefs.getLong("blocked_until", 0);
        if (blockedUntil > currentTime) {
            long remaining = blockedUntil - currentTime;
            Log.d(TAG, "App is blocked, remaining: " + TimeUnit.MILLISECONDS.toMinutes(remaining) + " minutes");
            addToLog("🚫 " + getAppName(packageName) + " is blocked");
            updateNotification(getAppName(packageName) + " - BLOCKED");
            return;
        }

        if (!packageName.equals(currentApp)) {
            // New browser detected
            Log.d(TAG, "New browser detected: " + packageName);
            prefs.edit()
                    .putString("current_app", packageName)
                    .putLong("app_start_time", currentTime)
                    .apply();

            addToLog("📱 " + getAppName(packageName) + " started");
        }

        // Calculate usage time
        long usageTime = currentTime - startTime;
        String timeText = formatTime(usageTime);

        Log.d(TAG, "Usage time for " + packageName + ": " + timeText);

        // Update notification
        updateNotification(getAppName(packageName) + " - " + timeText);

        // Log every minute
        if (usageTime % 60000 < 3000) {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(usageTime);
            addToLog("⏰ " + getAppName(packageName) + " - " + minutes + " minutes used");
        }

        // Check if usage exceeds 10 minutes
        if (usageTime >= 10 * 60 * 1000) {
            Log.d(TAG, "Time limit reached for: " + packageName);
            blockBrowser(packageName);
        }
    }

    private void blockBrowser(String packageName) {
        long blockTime = System.currentTimeMillis();
        prefs.edit()
                .putLong("blocked_until", blockTime + 60 * 60 * 1000) // 1 hour block
                .remove("current_app")
                .remove("app_start_time")
                .apply();

        String appName = getAppName(packageName);
        addToLog("🚫 " + appName + " BLOCKED for 1 hour");

        Log.d(TAG, "Browser blocked: " + appName);

        // Show block notification
        showBlockNotification(appName);

        // Update service notification
        updateNotification(appName + " - BLOCKED");
    }

    private String getAppName(String packageName) {
        switch (packageName) {
            case "com.android.chrome": return "Chrome";
            case "com.android.browser": return "Android Browser";
            case "org.mozilla.firefox": return "Firefox";
            case "com.opera.browser": return "Opera";
            case "com.brave.browser": return "Brave";
            case "com.microsoft.emmx": return "Edge";
            case "com.sec.android.app.sbrowser": return "Samsung Browser";
            case "com.uc.browser": return "UC Browser";
            case "com.duckduckgo.mobile.android": return "DuckDuckGo";
            case "com.vivaldi.browser": return "Vivaldi";
            case "com.kiwibrowser.browser": return "Kiwi Browser";
            default: return packageName;
        }
    }

    private String formatTime(long milliseconds) {
        long seconds = (milliseconds / 1000) % 60;
        long minutes = (milliseconds / (1000 * 60)) % 60;
        long hours = (milliseconds / (1000 * 60 * 60)) % 24;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void addToLog(String message) {
        String timeStamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date());
        String logEntry = timeStamp + " - " + message;

        String existingLogs = prefs.getString("activity_logs", "");
        String newLogs = logEntry + "\n" + existingLogs;

        // Keep only last 20 logs
        String[] logs = newLogs.split("\n");
        if (logs.length > 20) {
            StringBuilder limitedLogs = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                limitedLogs.append(logs[i]);
                if (i < 19) limitedLogs.append("\n");
            }
            newLogs = limitedLogs.toString();
        }

        prefs.edit().putString("activity_logs", newLogs).apply();
        Log.d(TAG, "Log added: " + message);
    }

    private void showBlockNotification(String appName) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🚫 Browser Blocked")
                    .setContentText(appName + " blocked for 1 hour")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_MAX);

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID + 1, builder.build());
            Log.d(TAG, "Block notification shown for: " + appName);
        } catch (Exception e) {
            Log.e(TAG, "Error showing block notification: " + e.getMessage());
        }
    }

    private void updateNotification(String message) {
        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🌐 Browser Monitor")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && monitorRunnable != null) {
            handler.removeCallbacks(monitorRunnable);
        }
        addToLog("🔴 Service stopped");
        Log.d(TAG, "Service destroyed");
    }
}