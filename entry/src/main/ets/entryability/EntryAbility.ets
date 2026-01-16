import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import RdpSessionManager, { SessionState } from '../services/RdpSessionManager';
import NetworkManager from '../services/NetworkManager';
import AudioFocusManager from '../services/AudioFocusManager';

const DOMAIN = 0x0000;
const TAG = 'EntryAbility';

// Global session manager instance
let sessionManager: RdpSessionManager | null = null;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'Failed to set colorMode. Cause: %{public}s', JSON.stringify(err));
    }
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onCreate');
    
    // Initialize session manager
    this.initializeSessionManager();
  }

  /**
   * Initialize the RDP session manager and all subsystems
   */
  private async initializeSessionManager(): Promise<void> {
    hilog.info(DOMAIN, TAG, 'Initializing session manager...');
    
    try {
      sessionManager = new RdpSessionManager(this.context);
      const success = await sessionManager.initialize();
      
      if (success) {
        hilog.info(DOMAIN, TAG, 'Session manager initialized successfully');
        
        // Set up session state change listener
        RdpSessionManager.setOnSessionStateChangedCallback((state: SessionState) => {
          this.onSessionStateChanged(state);
        });
        
      } else {
        hilog.error(DOMAIN, TAG, 'Failed to initialize session manager');
      }
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Error initializing session manager: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * Handle session state changes
   */
  private onSessionStateChanged(state: SessionState): void {
    const stateNames: Record<number, string> = {
      [SessionState.DISCONNECTED]: 'DISCONNECTED',
      [SessionState.CONNECTING]: 'CONNECTING',
      [SessionState.CONNECTED]: 'CONNECTED',
      [SessionState.RECONNECTING]: 'RECONNECTING',
      [SessionState.BACKGROUND]: 'BACKGROUND',
      [SessionState.LOCKED]: 'LOCKED',
      [SessionState.ERROR]: 'ERROR'
    };
    
    hilog.info(DOMAIN, TAG, 'Session state changed: %{public}s', stateNames[state] || 'UNKNOWN');
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onDestroy');
    
    // Cleanup session manager
    if (sessionManager) {
      sessionManager.cleanup();
      sessionManager = null;
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // Main window is created, set main page for this ability
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onWindowStageCreate');

    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, TAG, 'Succeeded in loading the content.');
    });
  }

  onWindowStageDestroy(): void {
    // Main window is destroyed, release UI related resources
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    // Ability has brought to foreground
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onForeground');
    
    // Notify session manager
    if (sessionManager) {
      sessionManager.onForeground();
    }
  }

  onBackground(): void {
    // Ability has back to background
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onBackground');
    
    // Notify session manager
    if (sessionManager) {
      sessionManager.onBackground();
    }
  }
}

/**
 * Get the global session manager instance
 */
export function getSessionManager(): RdpSessionManager | null {
  return sessionManager;
}