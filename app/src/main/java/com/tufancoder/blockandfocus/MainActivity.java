package com.tufancoder.blockandfocus;

import android.app.Activity;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView statusText, currentAppText, timerText;
    private Button startBtn, stopBtn, resetBtn, permissionBtn;
    private RecyclerView logRecyclerView;
    private LogAdapter logAdapter;
    private List<String> logList;
    private Handler handler;
    private Runnable updateRunnable;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "BrowserMonitorPrefs";
    private static final int REQUEST_USAGE_STATS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerView();
        setupClickListeners();
        checkAndRequestPermissions();
        startUpdateHandler();
    }

    private void initViews() {
        statusText = findViewById(R.id.status_text);
        currentAppText = findViewById(R.id.current_app_text);
        timerText = findViewById(R.id.timer_text);
        startBtn = findViewById(R.id.start_btn);
        stopBtn = findViewById(R.id.stop_btn);
        resetBtn = findViewById(R.id.reset_btn);
        permissionBtn = findViewById(R.id.permission_btn);
        logRecyclerView = findViewById(R.id.log_recycler_view);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        logList = new ArrayList<>();
        handler = new Handler();
    }

    private void setupRecyclerView() {
        logAdapter = new LogAdapter(logList);
        logRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        logRecyclerView.setAdapter(logAdapter);
    }

    private void setupClickListeners() {
        startBtn.setOnClickListener(v -> startMonitoring());
        stopBtn.setOnClickListener(v -> stopMonitoring());
        resetBtn.setOnClickListener(v -> resetMonitoring());
        permissionBtn.setOnClickListener(v -> requestUsageStatsPermission());
    }

    private void checkAndRequestPermissions() {
        if (!hasUsageStatsPermission()) {
            statusText.setText("⚠️ Permission Needed");
            addLog("Usage stats permission required");
            Toast.makeText(this, "Please grant usage stats permission", Toast.LENGTH_LONG).show();
        } else {
            statusText.setText("✅ Ready");
            addLog("All permissions granted");
        }
    }

    private boolean hasUsageStatsPermission() {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long currentTime = System.currentTimeMillis();
        try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, currentTime - 1000 * 1000, currentTime);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void requestUsageStatsPermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivityForResult(intent, REQUEST_USAGE_STATS);
    }

    private void startMonitoring() {
        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "Please grant usage stats permission first", Toast.LENGTH_LONG).show();
            requestUsageStatsPermission();
            return;
        }

        Intent serviceIntent = new Intent(this, MonitorService.class);
        startService(serviceIntent);

        statusText.setText("🟢 Monitoring Active");
        statusText.setTextColor(getColor(android.R.color.holo_green_dark));

        addLog("Monitoring started successfully");
        Toast.makeText(this, "Browser monitoring started", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        stopService(serviceIntent);

        statusText.setText("🔴 Monitoring Stopped");
        statusText.setTextColor(getColor(android.R.color.holo_red_dark));

        addLog("Monitoring stopped");
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    private void resetMonitoring() {
        prefs.edit().clear().apply();
        logList.clear();
        logAdapter.notifyDataSetChanged();

        currentAppText.setText("No app running");
        timerText.setText("00:00:00");

        addLog("All data reset");
        Toast.makeText(this, "All data reset", Toast.LENGTH_SHORT).show();
    }

    private void startUpdateHandler() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(updateRunnable);
    }

    private void updateUI() {
        String currentApp = prefs.getString("current_app", "");
        long startTime = prefs.getLong("app_start_time", 0);
        long blockedUntil = prefs.getLong("blocked_until", 0);
        long currentTime = System.currentTimeMillis();

        if (blockedUntil > currentTime) {
            long remainingTime = blockedUntil - currentTime;
            long remainingMinutes = remainingTime / (60 * 1000);

            statusText.setText("🚫 BLOCKED");
            currentAppText.setText("Browser is blocked");
            timerText.setText(remainingMinutes + " min remaining");
        }
        else if (!currentApp.isEmpty() && startTime > 0) {
            long usageTime = currentTime - startTime;

            currentAppText.setText("Current: " + getAppName(currentApp));
            timerText.setText(formatTime(usageTime));

            if (usageTime >= 10 * 60 * 1000) {
                statusText.setText("⏰ Time Limit Reached");
            } else {
                statusText.setText("🟢 Monitoring");
            }
        } else {
            currentAppText.setText("No browser running");
            timerText.setText("00:00:00");
        }

        updateLogsFromService();
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
            default: return packageName;
        }
    }

    private String formatTime(long milliseconds) {
        long seconds = (milliseconds / 1000) % 60;
        long minutes = (milliseconds / (1000 * 60)) % 60;
        long hours = (milliseconds / (1000 * 60 * 60)) % 24;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void updateLogsFromService() {
        String logs = prefs.getString("activity_logs", "");
        if (!logs.isEmpty()) {
            String[] logArray = logs.split("\n");
            if (logList.size() != logArray.length) {
                logList.clear();
                for (String log : logArray) {
                    if (!log.trim().isEmpty()) {
                        logList.add(0, log); // Add to beginning for latest first
                    }
                }
                logAdapter.notifyDataSetChanged();
            }
        }
    }

    private void addLog(String message) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = timeStamp + " - " + message;

        logList.add(0, logEntry);
        if (logList.size() > 50) {
            logList.remove(logList.size() - 1);
        }
        logAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_USAGE_STATS) {
            if (hasUsageStatsPermission()) {
                statusText.setText("✅ Permission Granted");
                addLog("Usage stats permission granted");
                Toast.makeText(this, "Permission granted! You can start monitoring now.", Toast.LENGTH_LONG).show();
            } else {
                statusText.setText("❌ Permission Denied");
                addLog("Usage stats permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }
}