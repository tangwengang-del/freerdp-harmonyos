package com.freerdp.freerdpcore.services;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.ServiceRestartReceiver;

/**
 * Foreground service to keep RDP connection alive in background
 * Ensures audio streaming continues even when app is in background or screen is locked
 */
public class RdpAudioService extends Service {
    private static final String TAG = "RdpAudioService";
    private static final String CHANNEL_ID = "rdp_audio_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private static RdpAudioService instance;
    private static boolean isRunning = false;
    private static boolean isNormalStop = false; // ✅ 标记是否为正常停止

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "RdpAudioService created");
        instance = this;
        isNormalStop = false; // ✅ 重置标志
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ✅ CRITICAL: Set isRunning IMMEDIATELY to avoid race condition
        isRunning = true;
        
        Log.i(TAG, "RdpAudioService started (flags=" + flags + ", startId=" + startId + ")");
        
        // Initialize foreground service immediately
        initializeForegroundService();
        
        // ✅ Return START_NOT_STICKY: we handle restart manually via ServiceRestartReceiver
        // No system auto-restart needed
        return START_NOT_STICKY;
    }
    
    /**
     * Initialize foreground service
     */
    private void initializeForegroundService() {
        // Create notification channel (Android 8.0+)
        createNotificationChannel();
        
        // Build notification
        Notification notification = buildNotification();
        
        // Start as foreground service with exception handling
        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.i(TAG, "✓ Foreground service started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
            isRunning = false;
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "RdpAudioService destroyed (normal_stop=" + isNormalStop + ")");
        isRunning = false;
        
        // ✅ 只在"非正常停止"且"有活跃会话"时才安排重启
        if (!isNormalStop) {
            SharedPreferences prefs = getSharedPreferences("rdp_state", MODE_PRIVATE);
            boolean hasActiveSession = prefs.getBoolean("has_active_session", false);
            
            if (hasActiveSession) {
                Log.w(TAG, "⚠️ Service terminated unexpectedly (system killed), scheduling auto restart");
                scheduleServiceRestart();
            } else {
                Log.d(TAG, "No active session, normal shutdown");
            }
        } else {
            Log.i(TAG, "✓ Normal stop requested, no restart needed");
            isNormalStop = false; // 重置标志
        }
        
        instance = null;
        super.onDestroy();
    }
    
    /**
     * ✅ 安排服务自动重启（只调度，不增加计数）
     * 计数由 ServiceRestartReceiver 统一管理
     * 重试策略：10次重启 (1s, 5s, 10s, 然后7次20s)
     */
    private void scheduleServiceRestart() {
        try {
            SharedPreferences prefs = getSharedPreferences("service_restart", MODE_PRIVATE);
            int restartCount = prefs.getInt("restart_count", 0);
            long lastRestartTime = prefs.getLong("last_restart_time", 0);
            long now = System.currentTimeMillis();
            
            // 如果距离上次重启超过5分钟，重置计数器
            if (now - lastRestartTime > 5 * 60 * 1000) {
                restartCount = 0;
                Log.i(TAG, "Reset restart count (cooldown period passed)");
            }
            
            // ✅ 最多重启10次
            if (restartCount >= 10) {
                Log.e(TAG, "❌ Max restart attempts (10) reached, giving up");
                // 清除活跃会话标记，避免无限重启
                getSharedPreferences("rdp_state", MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_active_session", false)
                    .apply();
                // ✅ 重置计数，为下次启动作准备
                com.freerdp.freerdpcore.application.ServiceRestartReceiver.resetRestartCount(this);
                showMaxRetriesNotification();
                return;
            }
            
            // ✅ 重试延迟策略：1s, 5s, 10s, 然后7次20s
            long delay;
            switch (restartCount) {
                case 0:  
                    delay = 1000;   // 第1次：1秒
                    break;
                case 1:  
                    delay = 5000;   // 第2次：5秒
                    break;
                case 2:  
                    delay = 10000;  // 第3次：10秒
                    break;
                default:  
                    delay = 20000;  // 第4-10次：20秒
                    break;
            }
            
            // ✅ 不在这里增加计数！由 ServiceRestartReceiver 统一管理
            
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            
            Intent restartIntent = new Intent(this, ServiceRestartReceiver.class);
            restartIntent.setAction("com.freerdp.RESTART_SERVICE");
            
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                restartIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );
            
            if (alarmManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        now + delay,
                        pendingIntent
                    );
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        now + delay,
                        pendingIntent
                    );
                }
                Log.i(TAG, "✓ Auto restart scheduled (current attempt: " + restartCount + "/10" + 
                           ", delay=" + (delay/1000) + "s, count will be incremented by Receiver)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule service restart", e);
        }
    }
    
    /**
     * 显示最大重试失败通知
     */
    private void showMaxRetriesNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_launcher_freerdp)
                .setContentTitle("后台服务已停止")
                .setContentText("已尝试10次自动重启，但均失败。请手动重新连接。")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
            
            nm.notify((int) System.currentTimeMillis(), builder.build());
            Log.i(TAG, "Max retries notification sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show notification", e);
        }
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "Task removed - service may be killed soon");
        // Don't stop service when task is removed - let it continue running
        // The service will be stopped explicitly when needed
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "远程桌面音频",
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound/vibration
            );
            channel.setDescription("保持远程桌面连接以接收音频");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        // Intent to return to existing SessionActivity (not create new one)
        Intent notificationIntent = new Intent(this, com.freerdp.freerdpcore.presentation.SessionActivity.class);
        // Critical: bring existing instance to front, don't create new one
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M 
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程桌面音频连接")
            .setContentText("点击返回远程桌面")
            .setSmallIcon(R.drawable.icon_launcher_freerdp) // Use FreeRDP app icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority = minimal intrusion
            .setOngoing(true) // Cannot be dismissed by user (keeps connection alive)
            .setShowWhen(false)
            .setAutoCancel(false); // Don't auto-cancel when clicked

        return builder.build();
    }

    /**
     * Start the foreground service
     */
    public static void start(Context context) {
        if (isRunning) {
            Log.d(TAG, "Service already running, skip start");
            return;
        }
        
        Intent intent = new Intent(context, RdpAudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        Log.i(TAG, "Service start requested");
    }

    /**
     * Stop the foreground service
     */
    public static void stop(Context context) {
        if (!isRunning) {
            Log.d(TAG, "Service not running, skip stop");
            return;
        }
        
        // ✅ 标记为正常停止，避免触发重启逻辑
        isNormalStop = true;
        
        Intent intent = new Intent(context, RdpAudioService.class);
        context.stopService(intent);
        Log.i(TAG, "Service stop requested (normal stop)");
    }

    /**
     * Check if service is running
     */
    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Update notification content (e.g., to show connection status)
     */
    public static void updateNotification(Context context, String contentText) {
        if (!isRunning || instance == null) {
            Log.d(TAG, "Service not running, cannot update notification");
            return;
        }
        
        instance.updateNotificationContent(contentText);
    }

    private void updateNotificationContent(String contentText) {
        Notification notification = buildNotificationWithText(contentText);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
            Log.d(TAG, "Notification updated: " + contentText);
        }
    }

    private Notification buildNotificationWithText(String contentText) {
        Intent notificationIntent = new Intent(this, com.freerdp.freerdpcore.presentation.SessionActivity.class);
        // Critical: bring existing instance to front, don't create new one
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M 
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程桌面音频连接")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.icon_launcher_freerdp) // Use FreeRDP app icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setAutoCancel(false);

        return builder.build();
    }
}

