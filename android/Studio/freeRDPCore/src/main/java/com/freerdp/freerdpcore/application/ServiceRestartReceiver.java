/*
   Service Restart Receiver - Production Grade Version

   Copyright 2024 FreeRDP Android

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.application;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.services.RdpAudioService;

import java.util.List;

/**
 * Service restart receiver - Production grade optimized version (launch Activity on demand)
 * 
 * Features:
 * 1. Restart service only when killed
 * 2. Delayed Activity state detection (avoid race conditions)
 * 3. Launch Activity only when truly killed
 * 4. Detect native connection state, decide reuse or reconnect
 * 5. Support Android 12+ background launch restrictions (FullScreenIntent)
 * 6. Add retry mechanism and permission checks
 * 7. Thread-safe and exception handling
 */
public class ServiceRestartReceiver extends BroadcastReceiver {
    private static final String TAG = "ServiceRestartReceiver";
    private static final String CHANNEL_ID = "service_auto_restart";
    private static final String CHANNEL_ID_RECOVERY = "service_recovery";
    private static final String PREFS_NAME = "service_restart";
    
    // Detection parameters
    private static final long ACTIVITY_CHECK_DELAY_MS = 2000; // Delay 2 seconds to check Activity
    private static final int MAX_RETRY_CHECKS = 3; // Max 3 retry checks
    private static final long RETRY_INTERVAL_MS = 1000; // 1 second retry interval
    // ✅ 新增：总超时时间，防止检查过程卡死
    private static final long MAX_TOTAL_CHECK_TIME_MS = 10000; // 10 seconds total timeout
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.freerdp.RESTART_SERVICE".equals(intent.getAction())) {
            return;
        }
        
        Log.i(TAG, "========== Service Restart Request Received ==========");
        
        // Check if there's an active session
        SharedPreferences rdpPrefs = context.getSharedPreferences("rdp_state", Context.MODE_PRIVATE);
        if (!rdpPrefs.getBoolean("has_active_session", false)) {
            Log.d(TAG, "No active session, skip restart");
            return;
        }
        
        // ✅ Check if this is a manual disconnect - do NOT restart
        if (rdpPrefs.getBoolean("manual_disconnect", false)) {
            Log.i(TAG, "❌ Manual disconnect detected, skip restart (user initiated disconnect)");
            // Clear the flag for next session
            rdpPrefs.edit().putBoolean("manual_disconnect", false).apply();
            return;
        }
        
        // ✅ Check immediately: no system auto-restart (START_NOT_STICKY)
        if (RdpAudioService.isRunning()) {
            Log.i(TAG, "✓ Service already running, no restart needed");
            // ✅ Don't schedule Activity check - service is already running means everything is fine
            // No need for unnecessary checks
            return;
        }
        
        // Service is stopped, proceed with manual restart
        Log.w(TAG, "⚠️ Service stopped unexpectedly, proceeding with manual restart");
        performManualRestart(context, rdpPrefs);
    }
    
    /**
     * ✅ Perform manual restart (separated for clarity)
     * NOTE: No longer maintains independent restart_count, uses SessionActivity's reconnect_attempts
     */
    private void performManualRestart(Context context, SharedPreferences rdpPrefs) {
        // ✅ Check if reconnection is already in progress
        boolean reconnectInProgress = rdpPrefs.getBoolean("reconnect_in_progress", false);
        long reconnectLockTime = rdpPrefs.getLong("reconnect_lock_time", 0);
        long now = System.currentTimeMillis();
        
        if (reconnectInProgress && (now - reconnectLockTime) < 60000) {
            String reconnectSource = rdpPrefs.getString("reconnect_source", "unknown");
            Log.i(TAG, "✓ Reconnection already in progress (source: " + reconnectSource + 
                       "), Service restart will only restore environment");
        }
        
        // ✅ Increment restart_count here (single source of truth)
        // RdpAudioService only schedules alarms based on this counter.
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            int restartCount = prefs.getInt("restart_count", 0);
            long lastRestartTime = prefs.getLong("last_restart_time", 0);

            // Cooldown: if last restart was long ago, reset counter
            if (now - lastRestartTime > 5 * 60 * 1000) {
                restartCount = 0;
            }

            // Increment and persist synchronously so subsequent reads are consistent
            boolean committed = prefs.edit()
                .putInt("restart_count", restartCount + 1)
                .putLong("last_restart_time", now)
                .commit();
            if (!committed) {
                Log.w(TAG, "Failed to commit restart_count increment");
            } else {
                Log.i(TAG, "Restart counter incremented: " + (restartCount + 1) + "/10");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update restart counter", e);
        }
        
        // Execute restart
        try {
            RdpAudioService.start(context);
            Log.i(TAG, "✓ Service restart requested (manual)");
            
            // ✅ 关键修复：验证服务真正启动后才继续
            // 延迟500ms验证服务是否成功启动，防止启动失败仍触发Activity检查
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (RdpAudioService.isRunning()) {
                        Log.i(TAG, "✓ Service confirmed running, proceeding with Activity check");
                        scheduleActivityCheck(context, rdpPrefs, 0, 0);
                        showRestartNotification(context, now);
                    } else {
                        Log.e(TAG, "❌ Service failed to start within 500ms, aborting recovery");
                        cleanupFailedRestart(context);
                    }
                }
            }, 500);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart service", e);
            cleanupFailedRestart(context);
        }
    }
    
    /**
     * ✅ Clean up state after failed restart
     * Prevents stale state from causing issues
     */
    private void cleanupFailedRestart(Context context) {
        try {
            SharedPreferences rdpPrefs = context.getSharedPreferences("rdp_state", Context.MODE_PRIVATE);
            rdpPrefs.edit()
                .putBoolean("has_active_session", false)
                .putBoolean("reconnect_in_progress", false)
                .remove("reconnect_source")
                .remove("reconnect_lock_time")
                .remove("activity_launching")
                .remove("activity_launch_time")
                .apply();
            
            Log.w(TAG, "✓ Cleaned up state after failed restart");
            showErrorNotification(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to cleanup after restart failure", e);
        }
    }
    
    /**
     * Schedule Activity check (with retry support)
     */
    private void scheduleActivityCheck(Context context, SharedPreferences rdpPrefs, 
                                      int unusedParam, int retryCount) {
        long delay = (retryCount == 0) ? ACTIVITY_CHECK_DELAY_MS : RETRY_INTERVAL_MS;
        
        Log.d(TAG, "Scheduling activity check (retry: " + retryCount + ", delay: " + delay + "ms)");
        
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkAndRecoverIfNeeded(context, rdpPrefs, unusedParam, retryCount);
            }
        }, delay);
    }
    
    /**
     * Check if Activity recovery is needed (with retry mechanism)
     */
    private void checkAndRecoverIfNeeded(Context context, SharedPreferences rdpPrefs, 
                                        int unusedParam, int retryCount) {
        Log.i(TAG, "Checking Activity status (retry: " + retryCount + "/" + MAX_RETRY_CHECKS + ")");
        
        long now = System.currentTimeMillis();
        
        // ✅ 关键修复：记录检查开始时间（仅第一次）
        if (retryCount == 0) {
            rdpPrefs.edit().putLong("activity_check_start_time", now).apply();
        }
        
        // ✅ 关键修复：检查总时长，防止检查过程卡死
        long checkStartTime = rdpPrefs.getLong("activity_check_start_time", now);
        long totalCheckTime = now - checkStartTime;
        if (totalCheckTime > MAX_TOTAL_CHECK_TIME_MS) {
            Log.e(TAG, "❌ Activity check timeout after " + totalCheckTime + "ms (max: " + 
                       MAX_TOTAL_CHECK_TIME_MS + "ms), giving up");
            rdpPrefs.edit().remove("activity_check_start_time").apply();
            cleanupFailedRestart(context);
            return;
        }
        
        // ✅ 关键修复：检查是否正在启动Activity，防止重复启动
        boolean isLaunching = rdpPrefs.getBoolean("activity_launching", false);
        long launchTime = rdpPrefs.getLong("activity_launch_time", 0);
        
        if (isLaunching && (now - launchTime) < 5000) {
            Log.d(TAG, "⚠️ Activity is currently launching (started " + 
                       (now - launchTime) + "ms ago), skip duplicate launch check");
            // 仍然安排重试，等待Activity完成启动
            if (retryCount < MAX_RETRY_CHECKS) {
                scheduleActivityCheck(context, rdpPrefs, unusedParam, retryCount + 1);
            }
            return;
        }
        
        // 如果标记超过5秒还在，说明可能启动失败，清除标记
        if (isLaunching && (now - launchTime) >= 5000) {
            Log.w(TAG, "Activity launch marker stale (age: " + (now - launchTime) + "ms), clearing");
            rdpPrefs.edit()
                .remove("activity_launching")
                .remove("activity_launch_time")
                .apply();
        }
        
        // Check if Activity is running
        boolean activityRunning = isActivityRunning(context);
        
        if (activityRunning) {
        // ✅ Enhanced: Check if Activity has FULLY recovered (not just created)
        String activityState = rdpPrefs.getString("activity_state", "unknown");
        long lastHeartbeat = rdpPrefs.getLong("activity_last_heartbeat", 0);
        // ✅ 修复：缩短心跳判断窗口从10秒到5秒，更快检测Activity状态
        boolean heartbeatRecent = (now - lastHeartbeat) < 5000; // Within 5 seconds
            
            Log.d(TAG, "Activity state: " + activityState + ", heartbeat age: " + 
                       (now - lastHeartbeat) + "ms");
            
            // Only consider as "perfect recovery" if:
            // 1. Activity is in "ready" state, OR
            // 2. Activity has recent heartbeat (within 5 seconds)
            if ("ready".equals(activityState) || heartbeatRecent) {
                Log.i(TAG, "✓ Activity fully recovered, perfect recovery!");
                // ✅ 清除检查开始时间
                rdpPrefs.edit().remove("activity_check_start_time").apply();
                markRestartSuccessful(context, rdpPrefs);
                return;
            } else if ("creating".equals(activityState) || "resuming".equals(activityState)) {
                // Activity is in recovery process, need to wait
                Log.w(TAG, "⚠️ Activity is recovering (state: " + activityState + "), waiting...");
                
                if (retryCount < MAX_RETRY_CHECKS) {
                    Log.d(TAG, "Retrying check to confirm Activity completes recovery");
                    scheduleActivityCheck(context, rdpPrefs, unusedParam, retryCount + 1);
                    return;
                } else {
                    // Retry exhausted, but Activity process exists
                    // Assume it will complete recovery, don't launch duplicate
                    Log.w(TAG, "Activity recovery timeout, but process exists - assuming it will complete");
                    markRestartSuccessful(context, rdpPrefs);
                    return;
                }
            } else {
                // Activity detected but state unclear, retry
                Log.w(TAG, "⚠️ Activity detected but state unclear (state: " + activityState + ")");
                
                if (retryCount < MAX_RETRY_CHECKS) {
                    scheduleActivityCheck(context, rdpPrefs, unusedParam, retryCount + 1);
                    return;
                }
                // Retry exhausted, assume Activity is OK to avoid duplicate launch
                Log.w(TAG, "Activity state unclear after retries, assuming OK to prevent duplicate launch");
                markRestartSuccessful(context, rdpPrefs);
                return;
            }
        }
        
        // Activity doesn't exist
        Log.w(TAG, "⚠️ Activity not running");
        
        // Check if retry is needed
        if (retryCount < MAX_RETRY_CHECKS) {
            Log.d(TAG, "Retrying activity check in " + RETRY_INTERVAL_MS + "ms");
            scheduleActivityCheck(context, rdpPrefs, unusedParam, retryCount + 1);
            return;
        }
        
        // Confirmed Activity is truly not running - need to launch
        Log.w(TAG, "Activity confirmed not running after " + MAX_RETRY_CHECKS + " checks");
        
        // Get session instance ID
        long sessionInstance = rdpPrefs.getLong("session_instance", -1);
        
        // Detect native connection state
        boolean nativeAlive = checkNativeConnection(sessionInstance);
        
        Log.i(TAG, "Connection status: instance=" + sessionInstance + ", native_alive=" + nativeAlive);
        
        // Launch Activity
        launchSessionActivity(context, rdpPrefs, sessionInstance, nativeAlive);
    }
    
    /**
     * Mark restart as successful
     */
    private void markRestartSuccessful(Context context, SharedPreferences rdpPrefs) {
        rdpPrefs.edit()
            .putBoolean("restart_successful", true)
            .putLong("last_successful_restart", System.currentTimeMillis())
            .apply();
        
        resetRestartCount(context);
        
        Log.i(TAG, "✓ Service restart marked as successful");
    }
    
    /**
     * Check native connection state
     */
    private boolean checkNativeConnection(long instanceId) {
        if (instanceId <= 0) {
            Log.d(TAG, "Invalid instance ID: " + instanceId);
            return false;
        }
        
        try {
            // Use LibFreeRDP public method
            boolean isAlive = LibFreeRDP.isInstanceConnected(instanceId);
            Log.d(TAG, "Native connection check: instance=" + instanceId + ", alive=" + isAlive);
            return isAlive;
        } catch (Exception e) {
            Log.e(TAG, "Failed to check native connection", e);
            return false;
        }
    }
    
    /**
     * Check if Activity is running (multiple detection methods + thread-safe)
     */
    private boolean isActivityRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) 
                context.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (am == null) {
                Log.w(TAG, "ActivityManager is null");
                return false;
            }
            
            String targetActivity = "com.freerdp.freerdpcore.presentation.SessionActivity";
            String packageName = context.getPackageName();
            
            // Method 1: Use AppTask (Android 5.0+, recommended)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    List<ActivityManager.AppTask> appTasks = am.getAppTasks();
                    if (appTasks != null && !appTasks.isEmpty()) {
                        for (ActivityManager.AppTask task : appTasks) {
                            ActivityManager.RecentTaskInfo taskInfo = task.getTaskInfo();
                            if (taskInfo != null && taskInfo.topActivity != null) {
                                String className = taskInfo.topActivity.getClassName();
                                if (targetActivity.equals(className)) {
                                    Log.d(TAG, "✓ SessionActivity found via AppTask");
                                    return true;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "AppTask check failed", e);
                }
            }
            
            // Method 2: Use RunningTasks (fallback, Android 5.1+ restricted)
            try {
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
                if (tasks != null) {
                    for (ActivityManager.RunningTaskInfo task : tasks) {
                        if (task.topActivity != null &&
                            packageName.equals(task.topActivity.getPackageName()) &&
                            targetActivity.equals(task.topActivity.getClassName())) {
                            Log.d(TAG, "✓ SessionActivity found via RunningTasks");
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "RunningTasks check failed", e);
            }
            
            // Method 3: Check if active connections exist (fallback)
            try {
                if (LibFreeRDP.getActiveConnectionCount() > 0) {
                    Log.d(TAG, "Active connections exist, Activity likely running");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Connection count check failed", e);
            }
            
            Log.d(TAG, "SessionActivity not found by any method");
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to check activity state", e);
            return false; // Assume not running on error, safer to launch
        }
    }
    
    /**
     * Launch Activity (support Android 12+ background launch restrictions)
     */
	private void launchSessionActivity(Context context, SharedPreferences rdpPrefs,
	                                   long sessionInstance, boolean nativeAlive) {
		// ✅ Bug修复 #4: 使用文件锁实现跨进程原子操作，替代SharedPreferences
		com.freerdp.freerdpcore.utils.ProcessLock launchLock = 
			new com.freerdp.freerdpcore.utils.ProcessLock(context, "activity_launch");
		
		try {
			// Try to acquire lock with 100ms timeout
			if (!launchLock.tryLock(100)) {
				Log.w(TAG, "❌ Failed to acquire launch lock, another process is launching Activity");
				return;
			}
			
			Log.i(TAG, "✓ Launch lock acquired, proceeding with Activity launch");
			
			// Check if Activity was recently launched (within 5 seconds)
			long now = System.currentTimeMillis();
			long lastLaunchTime = rdpPrefs.getLong("activity_launch_time", 0);
			if (lastLaunchTime > 0 && (now - lastLaunchTime) < 5000) {
				Log.w(TAG, "❌ Activity was launched recently (age: " + (now - lastLaunchTime) + "ms), skip");
				return;
			}
			
			// Record launch time
			rdpPrefs.edit()
				.putLong("activity_launch_time", now)
				.apply();
			
			Log.i(TAG, "✓ Launching SessionActivity (API " + Build.VERSION.SDK_INT + "), launch time recorded");
            
            // Android 12+: Use FullScreenIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Check permission
                if (hasFullScreenIntentPermission(context)) {
                    Log.i(TAG, "Android 12+: Using FullScreenIntent (permission granted)");
                    launchViaFullScreenIntent(context, sessionInstance, nativeAlive);
                } else {
                    Log.w(TAG, "Android 12+: FullScreenIntent permission not granted, using fallback");
                    launchViaNotification(context, sessionInstance, nativeAlive);
                }
            }
            // Android 10-11: Try direct launch
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.i(TAG, "Android 10-11: Trying direct launch");
                try {
                    launchDirectly(context, sessionInstance, nativeAlive);
                } catch (Exception e) {
                    Log.w(TAG, "Direct launch failed, using notification", e);
                    launchViaNotification(context, sessionInstance, nativeAlive);
                }
            }
            // Android 9 and below: Direct launch (no restrictions)
            else {
                Log.i(TAG, "Android 9-: Direct launch (no restrictions)");
                launchDirectly(context, sessionInstance, nativeAlive);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "All launch methods failed", e);
            showManualRecoveryNotification(context);
        } finally {
            // ✅ Bug修复 #4: 确保锁总是被释放
            launchLock.unlock();
        }
    }
    
    /**
     * Check FullScreenIntent permission
     */
    private boolean hasFullScreenIntentPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                NotificationManager nm = context.getSystemService(NotificationManager.class);
                if (nm != null) {
                    // Check notification permission
                    boolean notificationsEnabled = nm.areNotificationsEnabled();
                    Log.d(TAG, "Notifications enabled: " + notificationsEnabled);
                    return notificationsEnabled;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to check FullScreenIntent permission", e);
            }
            return false;
        }
        return true; // Android 11 and below don't need special permission
    }
    
    /**
     * Launch Activity via FullScreenIntent
     */
    private void launchViaFullScreenIntent(Context context, long sessionInstance, 
                                           boolean nativeAlive) {
        try {
            Intent activityIntent = createActivityIntent(context, sessionInstance, 
                                                        nativeAlive);
            
            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, 0, activityIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );
            
            NotificationManager nm = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (nm != null) {
                // Create notification channel
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID_RECOVERY,
                        "连接恢复",
                        NotificationManager.IMPORTANCE_HIGH
                    );
                    channel.setDescription("远程桌面连接恢复通知");
                    channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                    nm.createNotificationChannel(channel);
                }
                
                // Build FullScreen notification
                NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    context, CHANNEL_ID_RECOVERY)
                    .setSmallIcon(R.drawable.icon_launcher_freerdp)
                    .setContentTitle("正在恢复远程桌面连接")
                    .setContentText("点击返回远程桌面")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(fullScreenPendingIntent, true) // Key
                    .setAutoCancel(true)
                    .setOngoing(false);
                
                // Show notification (will automatically launch Activity)
                nm.notify(10000, builder.build());
                
                Log.i(TAG, "✓ FullScreenIntent notification sent");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch via FullScreenIntent", e);
            throw e;
        }
    }
    
    /**
     * Launch Activity via normal notification (fallback)
     */
    private void launchViaNotification(Context context, long sessionInstance, 
                                       boolean nativeAlive) {
        try {
            Intent activityIntent = createActivityIntent(context, sessionInstance, 
                                                        nativeAlive);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, activityIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );
            
            NotificationManager nm = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID_RECOVERY,
                        "连接恢复",
                        NotificationManager.IMPORTANCE_HIGH
                    );
                    nm.createNotificationChannel(channel);
                }
                
                NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    context, CHANNEL_ID_RECOVERY)
                    .setSmallIcon(R.drawable.icon_launcher_freerdp)
                    .setContentTitle("远程桌面连接已恢复")
                    .setContentText("点击返回远程桌面")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL); // Vibration + sound
                
                nm.notify(20000, builder.build());
                
                Log.i(TAG, "✓ Recovery notification sent");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to send recovery notification", e);
        }
    }
    
    /**
     * Launch Activity directly
     */
    private void launchDirectly(Context context, long sessionInstance, 
                               boolean nativeAlive) {
        Intent activityIntent = createActivityIntent(context, sessionInstance, 
                                                    nativeAlive);
        
        context.startActivity(activityIntent);
        
        Log.i(TAG, "✓ SessionActivity launched directly");
    }
    
    /**
     * Create Activity Intent
     */
    private Intent createActivityIntent(Context context, long sessionInstance, 
                                        boolean nativeAlive) {
            Intent activityIntent = new Intent(context, 
                com.freerdp.freerdpcore.presentation.SessionActivity.class);
            
            activityIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | 
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            
        // Fix type conversion issue: use long consistently
        if (sessionInstance > 0) {
            // PARAM_INSTANCE needs int, but we saved long
            // Safe conversion: don't pass if beyond int range
            if (sessionInstance <= Integer.MAX_VALUE) {
            activityIntent.putExtra(
                com.freerdp.freerdpcore.presentation.SessionActivity.PARAM_INSTANCE, 
                    (int)sessionInstance
                );
            } else {
                Log.w(TAG, "Session instance ID too large for int: " + sessionInstance);
            }
        }
        
        activityIntent.putExtra("auto_recovery", true);
        activityIntent.putExtra("service_restart_recovery", true);
        activityIntent.putExtra("native_connection_alive", nativeAlive);
        activityIntent.putExtra("session_instance_long", sessionInstance); // Pass complete long value
        
        return activityIntent;
    }
    
    /**
     * Show manual recovery notification (final fallback)
     */
    private void showManualRecoveryNotification(Context context) {
        try {
            Intent activityIntent = new Intent(context, 
                com.freerdp.freerdpcore.presentation.SessionActivity.class);
            activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                   Intent.FLAG_ACTIVITY_SINGLE_TOP);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, activityIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );
            
            NotificationManager nm = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "服务恢复",
                        NotificationManager.IMPORTANCE_HIGH
                    );
                    nm.createNotificationChannel(channel);
                }
                
                NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.icon_launcher_freerdp)
                    .setContentTitle("远程桌面服务已恢复")
                    .setContentText("点击返回远程桌面")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL);
                
                nm.notify(99999, builder.build());
                
                Log.i(TAG, "Manual recovery notification sent");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to show manual recovery notification", e);
        }
    }
    
    /**
     * Show service restart notification
     */
    private void showRestartNotification(Context context, long restartTime) {
        try {
        NotificationManager nm = (NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "后台服务自动恢复",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("后台服务被系统终止后自动恢复的通知");
            nm.createNotificationChannel(channel);
        }
        
        Intent notificationIntent = new Intent(context, 
            com.freerdp.freerdpcore.presentation.SessionActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                                       Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, notificationIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Simplified notification (no count tracking)
        String title = "✓ 后台服务已自动恢复";
        String content = "检测到服务异常终止，已自动恢复";
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_launcher_freerdp)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);
        
        nm.notify((int) (restartTime % Integer.MAX_VALUE), builder.build());
        
        } catch (Exception e) {
            Log.w(TAG, "Failed to show restart notification", e);
        }
    }
    
    /**
     * Show restart failure notification
     */
    private void showErrorNotification(Context context) {
        try {
        NotificationManager nm = (NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "后台服务自动恢复",
                NotificationManager.IMPORTANCE_HIGH
            );
            nm.createNotificationChannel(channel);
        }
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_launcher_freerdp)
            .setContentTitle("❌ 服务重启失败")
            .setContentText("无法自动恢复后台服务，请手动重新连接")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true);
        
        nm.notify((int) System.currentTimeMillis(), builder.build());
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to show error notification", e);
        }
    }
    
    /**
     * Reset restart count
     */
    public static void resetRestartCount(Context context) {
        try {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putInt("restart_count", 0)
            .putLong("last_restart_time", 0)
            .apply();
            Log.d(TAG, "Service restart count reset");
        } catch (Exception e) {
            Log.w(TAG, "Failed to reset restart count", e);
        }
    }
}
