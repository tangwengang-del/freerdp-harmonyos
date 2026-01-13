/*
 * HarmonyOS FreeRDP Virtual Keyboard Component
 * 
 * Provides a virtual keyboard for sending key events to remote desktop
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import LibFreeRDP from '../services/LibFreeRDP';

// Virtual key codes (Windows VK codes)
const VK_ESCAPE = 0x1B;
const VK_TAB = 0x09;
const VK_RETURN = 0x0D;
const VK_BACK = 0x08;
const VK_DELETE = 0x2E;
const VK_INSERT = 0x2D;
const VK_HOME = 0x24;
const VK_END = 0x23;
const VK_PRIOR = 0x21;  // Page Up
const VK_NEXT = 0x22;   // Page Down
const VK_LEFT = 0x25;
const VK_UP = 0x26;
const VK_RIGHT = 0x27;
const VK_DOWN = 0x28;
const VK_CONTROL = 0x11;
const VK_MENU = 0x12;   // Alt
const VK_SHIFT = 0x10;
const VK_LWIN = 0x5B;   // Windows key
const VK_F1 = 0x70;
const VK_F2 = 0x71;
const VK_F3 = 0x72;
const VK_F4 = 0x73;
const VK_F5 = 0x74;
const VK_F6 = 0x75;
const VK_F7 = 0x76;
const VK_F8 = 0x77;
const VK_F9 = 0x78;
const VK_F10 = 0x79;
const VK_F11 = 0x7A;
const VK_F12 = 0x7B;
const VK_NUMLOCK = 0x90;
const VK_SCROLL = 0x91;
const VK_PRINT = 0x2C;
const VK_PAUSE = 0x13;

/**
 * Key definition
 */
interface KeyDef {
  label: string;
  vk: number;
  width?: number;
  isModifier?: boolean;
  isToggle?: boolean;
}

/**
 * Keyboard layout
 */
type KeyboardRow = KeyDef[];
type KeyboardLayout = KeyboardRow[];

/**
 * Special keys keyboard layout
 */
const SPECIAL_KEYS_LAYOUT: KeyboardLayout = [
  // Row 1: Escape, F1-F4
  [
    { label: 'Esc', vk: VK_ESCAPE },
    { label: 'F1', vk: VK_F1 },
    { label: 'F2', vk: VK_F2 },
    { label: 'F3', vk: VK_F3 },
    { label: 'F4', vk: VK_F4 },
  ],
  // Row 2: F5-F8
  [
    { label: 'F5', vk: VK_F5 },
    { label: 'F6', vk: VK_F6 },
    { label: 'F7', vk: VK_F7 },
    { label: 'F8', vk: VK_F8 },
  ],
  // Row 3: F9-F12
  [
    { label: 'F9', vk: VK_F9 },
    { label: 'F10', vk: VK_F10 },
    { label: 'F11', vk: VK_F11 },
    { label: 'F12', vk: VK_F12 },
  ],
  // Row 4: Navigation
  [
    { label: 'Ins', vk: VK_INSERT },
    { label: 'Del', vk: VK_DELETE },
    { label: 'Home', vk: VK_HOME },
    { label: 'End', vk: VK_END },
  ],
  // Row 5: Page navigation
  [
    { label: 'PgUp', vk: VK_PRIOR },
    { label: 'PgDn', vk: VK_NEXT },
    { label: 'PrtSc', vk: VK_PRINT },
    { label: 'Pause', vk: VK_PAUSE },
  ],
];

/**
 * Cursor keys layout
 */
const CURSOR_KEYS_LAYOUT: KeyboardLayout = [
  [
    { label: '', vk: 0, width: 1 },
    { label: '↑', vk: VK_UP },
    { label: '', vk: 0, width: 1 },
  ],
  [
    { label: '←', vk: VK_LEFT },
    { label: '↓', vk: VK_DOWN },
    { label: '→', vk: VK_RIGHT },
  ],
];

/**
 * Modifier keys layout
 */
const MODIFIER_KEYS_LAYOUT: KeyboardLayout = [
  [
    { label: 'Ctrl', vk: VK_CONTROL, isModifier: true },
    { label: 'Alt', vk: VK_MENU, isModifier: true },
    { label: 'Shift', vk: VK_SHIFT, isModifier: true },
    { label: 'Win', vk: VK_LWIN, isModifier: true },
  ],
];

/**
 * Virtual Keyboard listener
 */
export interface VirtualKeyboardListener {
  onKeyPressed(vk: number): void;
  onModifierChanged(vk: number, active: boolean): void;
}

/**
 * Virtual Keyboard Component
 */
@Component
export struct VirtualKeyboard {
  // Session instance
  @Prop instance: number = 0;
  
  // Keyboard type
  @State keyboardType: string = 'special';
  
  // Modifier states
  @State ctrlActive: boolean = false;
  @State altActive: boolean = false;
  @State shiftActive: boolean = false;
  @State winActive: boolean = false;
  
  // Visibility
  @State isVisible: boolean = false;
  
  // Listener
  private listener: VirtualKeyboardListener | null = null;
  
  // Key size
  private static readonly KEY_SIZE = 48;
  private static readonly KEY_MARGIN = 4;

  /**
   * Set the event listener
   */
  setListener(listener: VirtualKeyboardListener): void {
    this.listener = listener;
  }

  /**
   * Show the keyboard
   */
  show(type: string = 'special'): void {
    this.keyboardType = type;
    this.isVisible = true;
  }

  /**
   * Hide the keyboard
   */
  hide(): void {
    this.isVisible = false;
    // Release all modifiers
    this.releaseAllModifiers();
  }

  /**
   * Toggle visibility
   */
  toggle(type: string = 'special'): void {
    if (this.isVisible && this.keyboardType === type) {
      this.hide();
    } else {
      this.show(type);
    }
  }

  /**
   * Send key event
   */
  private sendKey(vk: number, isModifier: boolean = false): void {
    if (this.instance === 0 || vk === 0) return;
    
    if (isModifier) {
      // Toggle modifier state
      let active = false;
      switch (vk) {
        case VK_CONTROL:
          this.ctrlActive = !this.ctrlActive;
          active = this.ctrlActive;
          break;
        case VK_MENU:
          this.altActive = !this.altActive;
          active = this.altActive;
          break;
        case VK_SHIFT:
          this.shiftActive = !this.shiftActive;
          active = this.shiftActive;
          break;
        case VK_LWIN:
          this.winActive = !this.winActive;
          active = this.winActive;
          break;
      }
      
      // Send key down or up based on state
      LibFreeRDP.sendKeyEvent(this.instance, vk, active);
      
      if (this.listener) {
        this.listener.onModifierChanged(vk, active);
      }
    } else {
      // Regular key - send down and up
      LibFreeRDP.sendKeyEvent(this.instance, vk, true);
      LibFreeRDP.sendKeyEvent(this.instance, vk, false);
      
      if (this.listener) {
        this.listener.onKeyPressed(vk);
      }
      
      // Release non-sticky modifiers after key press
      this.releaseNonStickyModifiers();
    }
  }

  /**
   * Release all modifiers
   */
  private releaseAllModifiers(): void {
    if (this.ctrlActive) {
      LibFreeRDP.sendKeyEvent(this.instance, VK_CONTROL, false);
      this.ctrlActive = false;
    }
    if (this.altActive) {
      LibFreeRDP.sendKeyEvent(this.instance, VK_MENU, false);
      this.altActive = false;
    }
    if (this.shiftActive) {
      LibFreeRDP.sendKeyEvent(this.instance, VK_SHIFT, false);
      this.shiftActive = false;
    }
    if (this.winActive) {
      LibFreeRDP.sendKeyEvent(this.instance, VK_LWIN, false);
      this.winActive = false;
    }
  }

  /**
   * Release non-sticky modifiers (Shift only for now)
   */
  private releaseNonStickyModifiers(): void {
    if (this.shiftActive) {
      LibFreeRDP.sendKeyEvent(this.instance, VK_SHIFT, false);
      this.shiftActive = false;
    }
  }

  /**
   * Check if a modifier is active
   */
  private isModifierActive(vk: number): boolean {
    switch (vk) {
      case VK_CONTROL: return this.ctrlActive;
      case VK_MENU: return this.altActive;
      case VK_SHIFT: return this.shiftActive;
      case VK_LWIN: return this.winActive;
      default: return false;
    }
  }

  /**
   * Get current keyboard layout
   */
  private getLayout(): KeyboardLayout {
    switch (this.keyboardType) {
      case 'cursor':
        return CURSOR_KEYS_LAYOUT;
      case 'modifiers':
        return MODIFIER_KEYS_LAYOUT;
      case 'special':
      default:
        return SPECIAL_KEYS_LAYOUT;
    }
  }

  /**
   * Build a single key button
   */
  @Builder
  KeyButton(key: KeyDef) {
    if (key.vk === 0) {
      // Empty space
      Column()
        .width(VirtualKeyboard.KEY_SIZE * (key.width || 1))
        .height(VirtualKeyboard.KEY_SIZE)
    } else {
      Button(key.label)
        .width(VirtualKeyboard.KEY_SIZE * (key.width || 1))
        .height(VirtualKeyboard.KEY_SIZE)
        .fontSize(14)
        .fontColor(key.isModifier && this.isModifierActive(key.vk) ? '#000000' : '#FFFFFF')
        .backgroundColor(key.isModifier && this.isModifierActive(key.vk) ? '#4CAF50' : '#424242')
        .borderRadius(8)
        .margin(VirtualKeyboard.KEY_MARGIN)
        .onClick(() => {
          this.sendKey(key.vk, key.isModifier);
        })
    }
  }

  /**
   * Build a row of keys
   */
  @Builder
  KeyRow(row: KeyboardRow) {
    Row() {
      ForEach(row, (key: KeyDef) => {
        this.KeyButton(key)
      })
    }
    .justifyContent(FlexAlign.Center)
  }

  build() {
    if (this.isVisible) {
      Column() {
        // Keyboard type selector
        Row() {
          Button('特殊键')
            .fontSize(12)
            .height(32)
            .backgroundColor(this.keyboardType === 'special' ? '#2196F3' : '#616161')
            .margin(4)
            .onClick(() => { this.keyboardType = 'special'; })
          
          Button('方向键')
            .fontSize(12)
            .height(32)
            .backgroundColor(this.keyboardType === 'cursor' ? '#2196F3' : '#616161')
            .margin(4)
            .onClick(() => { this.keyboardType = 'cursor'; })
          
          Button('修饰键')
            .fontSize(12)
            .height(32)
            .backgroundColor(this.keyboardType === 'modifiers' ? '#2196F3' : '#616161')
            .margin(4)
            .onClick(() => { this.keyboardType = 'modifiers'; })
          
          Button('关闭')
            .fontSize(12)
            .height(32)
            .backgroundColor('#F44336')
            .margin(4)
            .onClick(() => { this.hide(); })
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .padding(8)
        
        // Modifier status bar
        Row() {
          if (this.ctrlActive) {
            Text('Ctrl')
              .fontSize(12)
              .fontColor('#4CAF50')
              .margin(4)
          }
          if (this.altActive) {
            Text('Alt')
              .fontSize(12)
              .fontColor('#4CAF50')
              .margin(4)
          }
          if (this.shiftActive) {
            Text('Shift')
              .fontSize(12)
              .fontColor('#4CAF50')
              .margin(4)
          }
          if (this.winActive) {
            Text('Win')
              .fontSize(12)
              .fontColor('#4CAF50')
              .margin(4)
          }
        }
        .width('100%')
        .height(24)
        .justifyContent(FlexAlign.Center)
        
        // Keyboard layout
        Column() {
          ForEach(this.getLayout(), (row: KeyboardRow) => {
            this.KeyRow(row)
          })
        }
        .padding(8)
      }
      .width('100%')
      .backgroundColor('#303030')
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
  }
}

export default VirtualKeyboard;
