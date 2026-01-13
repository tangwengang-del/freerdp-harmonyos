/*
 * HarmonyOS FreeRDP Background Service
 * 
 * Foreground service to keep RDP connection alive in background
 * Ensures audio streaming continues even when app is in background or screen is locked
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import wantAgent, { WantAgent } from '@ohos.app.ability.wantAgent';
import notificationManager from '@ohos.notificationManager';
import common from '@ohos.app.ability.common';
import { BusinessError } from '@ohos.base';

const TAG = 'RdpBackgroundService';
const NOTIFICATION_ID = 1001;

// Service state
let isRunning: boolean = false;
let isNormalStop: boolean = false;
let restartCount: number = 0;
let lastRestartTime: number = 0;
const MAX_RESTART_ATTEMPTS = 10;

// Callback for service restart
let onServiceRestartCallback: (() => void) | null = null;

/**
 * RDP Background Service
 * Maintains RDP connection in background for audio streaming
 */
export class RdpBackgroundService {
  private context: common.UIAbilityContext;
  private wantAgentObj: WantAgent | null = null;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  /**
   * Start the background service
   */
  async start(): Promise<boolean> {
    if (isRunning) {
      console.info(`${TAG}: Service already running, skip start`);
      return true;
    }

    console.info(`${TAG}: Starting background service`);

    try {
      // Create WantAgent for notification click action
      const wantAgentInfo: wantAgent.WantAgentInfo = {
        wants: [
          {
            bundleName: this.context.abilityInfo.bundleName,
            abilityName: this.context.abilityInfo.name,
          }
        ],
        operationType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0,
        wantAgentFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
      };

      this.wantAgentObj = await wantAgent.getWantAgent(wantAgentInfo);

      // Start continuous task (long-running background task)
      await backgroundTaskManager.startBackgroundRunning(
        this.context,
        backgroundTaskManager.BackgroundMode.AUDIO_PLAYBACK,
        this.wantAgentObj
      );

      isRunning = true;
      isNormalStop = false;

      // Show notification
      await this.showNotification('远程桌面音频连接', '点击返回远程桌面');

      console.info(`${TAG}: Background service started successfully`);
      return true;

    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to start background service: ${err.code} - ${err.message}`);
      isRunning = false;
      return false;
    }
  }

  /**
   * Stop the background service
   */
  async stop(): Promise<void> {
    if (!isRunning) {
      console.info(`${TAG}: Service not running, skip stop`);
      return;
    }

    console.info(`${TAG}: Stopping background service (normal stop)`);
    isNormalStop = true;

    try {
      await backgroundTaskManager.stopBackgroundRunning(this.context);
      await this.cancelNotification();
      
      isRunning = false;
      console.info(`${TAG}: Background service stopped successfully`);

    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to stop background service: ${err.code} - ${err.message}`);
    }
  }

  /**
   * Check if service is running
   */
  static isServiceRunning(): boolean {
    return isRunning;
  }

  /**
   * Set callback for service restart
   */
  static setOnServiceRestartCallback(callback: () => void): void {
    onServiceRestartCallback = callback;
  }

  /**
   * Schedule service restart (called when service is unexpectedly terminated)
   */
  async scheduleRestart(): Promise<void> {
    if (isNormalStop) {
      console.info(`${TAG}: Normal stop, no restart needed`);
      return;
    }

    const now = Date.now();

    // Reset counter if cooldown period passed (5 minutes)
    if (now - lastRestartTime > 5 * 60 * 1000) {
      restartCount = 0;
      console.info(`${TAG}: Reset restart count (cooldown period passed)`);
    }

    // Check max restart attempts
    if (restartCount >= MAX_RESTART_ATTEMPTS) {
      console.error(`${TAG}: Max restart attempts (${MAX_RESTART_ATTEMPTS}) reached, giving up`);
      await this.showMaxRetriesNotification();
      return;
    }

    // Calculate delay based on retry count
    let delay: number;
    switch (restartCount) {
      case 0: delay = 1000; break;   // 1st: 1s
      case 1: delay = 5000; break;   // 2nd: 5s
      case 2: delay = 10000; break;  // 3rd: 10s
      default: delay = 20000; break; // 4th-10th: 20s
    }

    restartCount++;
    lastRestartTime = now;

    console.info(`${TAG}: Scheduling restart (attempt ${restartCount}/${MAX_RESTART_ATTEMPTS}, delay=${delay}ms)`);

    // Schedule restart
    setTimeout(async () => {
      if (onServiceRestartCallback) {
        onServiceRestartCallback();
      }
    }, delay);
  }

  /**
   * Reset restart counter
   */
  static resetRestartCount(): void {
    restartCount = 0;
    lastRestartTime = 0;
    console.info(`${TAG}: Restart count reset`);
  }

  /**
   * Update notification content
   */
  async updateNotification(contentText: string): Promise<void> {
    if (!isRunning) {
      console.warn(`${TAG}: Service not running, cannot update notification`);
      return;
    }

    await this.showNotification('远程桌面音频连接', contentText);
  }

  /**
   * Show notification
   */
  private async showNotification(title: string, content: string): Promise<void> {
    try {
      const notificationRequest: notificationManager.NotificationRequest = {
        id: NOTIFICATION_ID,
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: {
            title: title,
            text: content,
          }
        },
        notificationSlotType: notificationManager.SlotType.SERVICE_INFORMATION,
        isOngoing: true,
        tapDismissed: false,
      };

      await notificationManager.publish(notificationRequest);
      console.info(`${TAG}: Notification shown: ${content}`);

    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to show notification: ${err.code} - ${err.message}`);
    }
  }

  /**
   * Cancel notification
   */
  private async cancelNotification(): Promise<void> {
    try {
      await notificationManager.cancel(NOTIFICATION_ID);
      console.info(`${TAG}: Notification cancelled`);
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to cancel notification: ${err.code} - ${err.message}`);
    }
  }

  /**
   * Show max retries notification
   */
  private async showMaxRetriesNotification(): Promise<void> {
    try {
      const notificationRequest: notificationManager.NotificationRequest = {
        id: NOTIFICATION_ID + 1,
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: {
            title: '后台服务已停止',
            text: `已尝试${MAX_RESTART_ATTEMPTS}次自动重启，但均失败。请手动重新连接。`,
          }
        },
        notificationSlotType: notificationManager.SlotType.SERVICE_INFORMATION,
      };

      await notificationManager.publish(notificationRequest);
      console.info(`${TAG}: Max retries notification sent`);

    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to show max retries notification: ${err.code} - ${err.message}`);
    }
  }
}

export default RdpBackgroundService;
