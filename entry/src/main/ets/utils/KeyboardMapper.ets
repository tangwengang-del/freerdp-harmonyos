/*
 * HarmonyOS FreeRDP Keyboard Mapper
 * 
 * Maps HarmonyOS key codes to Windows virtual key codes
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

// Windows Virtual Key Codes
export const VK_BACK = 0x08;
export const VK_TAB = 0x09;
export const VK_RETURN = 0x0D;
export const VK_SHIFT = 0x10;
export const VK_CONTROL = 0x11;
export const VK_MENU = 0x12;  // Alt
export const VK_PAUSE = 0x13;
export const VK_CAPITAL = 0x14;
export const VK_ESCAPE = 0x1B;
export const VK_SPACE = 0x20;
export const VK_PRIOR = 0x21;  // Page Up
export const VK_NEXT = 0x22;   // Page Down
export const VK_END = 0x23;
export const VK_HOME = 0x24;
export const VK_LEFT = 0x25;
export const VK_UP = 0x26;
export const VK_RIGHT = 0x27;
export const VK_DOWN = 0x28;
export const VK_PRINT = 0x2C;
export const VK_INSERT = 0x2D;
export const VK_DELETE = 0x2E;

// Number keys 0-9
export const VK_0 = 0x30;
export const VK_1 = 0x31;
export const VK_2 = 0x32;
export const VK_3 = 0x33;
export const VK_4 = 0x34;
export const VK_5 = 0x35;
export const VK_6 = 0x36;
export const VK_7 = 0x37;
export const VK_8 = 0x38;
export const VK_9 = 0x39;

// Letter keys A-Z
export const VK_A = 0x41;
export const VK_B = 0x42;
export const VK_C = 0x43;
export const VK_D = 0x44;
export const VK_E = 0x45;
export const VK_F = 0x46;
export const VK_G = 0x47;
export const VK_H = 0x48;
export const VK_I = 0x49;
export const VK_J = 0x4A;
export const VK_K = 0x4B;
export const VK_L = 0x4C;
export const VK_M = 0x4D;
export const VK_N = 0x4E;
export const VK_O = 0x4F;
export const VK_P = 0x50;
export const VK_Q = 0x51;
export const VK_R = 0x52;
export const VK_S = 0x53;
export const VK_T = 0x54;
export const VK_U = 0x55;
export const VK_V = 0x56;
export const VK_W = 0x57;
export const VK_X = 0x58;
export const VK_Y = 0x59;
export const VK_Z = 0x5A;

// Windows keys
export const VK_LWIN = 0x5B;
export const VK_RWIN = 0x5C;
export const VK_APPS = 0x5D;

// Numpad keys
export const VK_NUMPAD0 = 0x60;
export const VK_NUMPAD1 = 0x61;
export const VK_NUMPAD2 = 0x62;
export const VK_NUMPAD3 = 0x63;
export const VK_NUMPAD4 = 0x64;
export const VK_NUMPAD5 = 0x65;
export const VK_NUMPAD6 = 0x66;
export const VK_NUMPAD7 = 0x67;
export const VK_NUMPAD8 = 0x68;
export const VK_NUMPAD9 = 0x69;
export const VK_MULTIPLY = 0x6A;
export const VK_ADD = 0x6B;
export const VK_SEPARATOR = 0x6C;
export const VK_SUBTRACT = 0x6D;
export const VK_DECIMAL = 0x6E;
export const VK_DIVIDE = 0x6F;

// Function keys
export const VK_F1 = 0x70;
export const VK_F2 = 0x71;
export const VK_F3 = 0x72;
export const VK_F4 = 0x73;
export const VK_F5 = 0x74;
export const VK_F6 = 0x75;
export const VK_F7 = 0x76;
export const VK_F8 = 0x77;
export const VK_F9 = 0x78;
export const VK_F10 = 0x79;
export const VK_F11 = 0x7A;
export const VK_F12 = 0x7B;

// Lock keys
export const VK_NUMLOCK = 0x90;
export const VK_SCROLL = 0x91;

// OEM keys
export const VK_OEM_1 = 0xBA;      // ;:
export const VK_OEM_PLUS = 0xBB;   // =+
export const VK_OEM_COMMA = 0xBC;  // ,<
export const VK_OEM_MINUS = 0xBD;  // -_
export const VK_OEM_PERIOD = 0xBE; // .>
export const VK_OEM_2 = 0xBF;      // /?
export const VK_OEM_3 = 0xC0;      // `~
export const VK_OEM_4 = 0xDB;      // [{
export const VK_OEM_5 = 0xDC;      // \|
export const VK_OEM_6 = 0xDD;      // ]}
export const VK_OEM_7 = 0xDE;      // '"

/**
 * Key processing listener interface
 */
export interface KeyProcessingListener {
  onKeyEvent(keyCode: number, isDown: boolean): void;
  onUnicodeKeyEvent(unicodeChar: number, isDown: boolean): void;
}

/**
 * Keyboard Mapper
 * Handles key code translation and modifier state
 */
export class KeyboardMapper {
  private listener: KeyProcessingListener | null = null;
  
  // Modifier states
  private shiftPressed: boolean = false;
  private ctrlPressed: boolean = false;
  private altPressed: boolean = false;
  private metaPressed: boolean = false;
  
  // Key mapping table
  private static readonly KEY_MAP: Map<number, number> = new Map([
    // HarmonyOS KeyCode -> Windows VK
    [2, VK_0], [3, VK_1], [4, VK_2], [5, VK_3], [6, VK_4],
    [7, VK_5], [8, VK_6], [9, VK_7], [10, VK_8], [11, VK_9],
    
    [29, VK_A], [30, VK_B], [31, VK_C], [32, VK_D], [33, VK_E],
    [34, VK_F], [35, VK_G], [36, VK_H], [37, VK_I], [38, VK_J],
    [39, VK_K], [40, VK_L], [41, VK_M], [42, VK_N], [43, VK_O],
    [44, VK_P], [45, VK_Q], [46, VK_R], [47, VK_S], [48, VK_T],
    [49, VK_U], [50, VK_V], [51, VK_W], [52, VK_X], [53, VK_Y],
    [54, VK_Z],
    
    [61, VK_TAB],
    [62, VK_SPACE],
    [66, VK_RETURN],
    [67, VK_BACK],
    [111, VK_ESCAPE],
    [112, VK_DELETE],
    
    [19, VK_UP],
    [20, VK_DOWN],
    [21, VK_LEFT],
    [22, VK_RIGHT],
    
    [92, VK_PRIOR],  // Page Up
    [93, VK_NEXT],   // Page Down
    [122, VK_HOME],
    [123, VK_END],
    [124, VK_INSERT],
    
    [131, VK_F1], [132, VK_F2], [133, VK_F3], [134, VK_F4],
    [135, VK_F5], [136, VK_F6], [137, VK_F7], [138, VK_F8],
    [139, VK_F9], [140, VK_F10], [141, VK_F11], [142, VK_F12],
    
    [59, VK_SHIFT],
    [113, VK_CONTROL],
    [57, VK_MENU],  // Alt
    [117, VK_LWIN],
  ]);

  /**
   * Set the key processing listener
   */
  setListener(listener: KeyProcessingListener): void {
    this.listener = listener;
  }

  /**
   * Process a key event from HarmonyOS
   */
  processKeyEvent(keyCode: number, isDown: boolean): boolean {
    // Update modifier states
    switch (keyCode) {
      case 59: // Shift
        this.shiftPressed = isDown;
        break;
      case 113: // Ctrl
        this.ctrlPressed = isDown;
        break;
      case 57: // Alt
        this.altPressed = isDown;
        break;
      case 117: // Meta/Windows
        this.metaPressed = isDown;
        break;
    }
    
    // Map to Windows VK
    const vk = KeyboardMapper.KEY_MAP.get(keyCode);
    if (vk !== undefined) {
      if (this.listener) {
        this.listener.onKeyEvent(vk, isDown);
      }
      return true;
    }
    
    return false;
  }

  /**
   * Process a Unicode character input
   */
  processUnicodeInput(char: string): void {
    if (char.length === 0) return;
    
    const charCode = char.charCodeAt(0);
    
    if (this.listener) {
      this.listener.onUnicodeKeyEvent(charCode, true);
      this.listener.onUnicodeKeyEvent(charCode, false);
    }
  }

  /**
   * Check if Shift is pressed
   */
  isShiftPressed(): boolean {
    return this.shiftPressed;
  }

  /**
   * Check if Ctrl is pressed
   */
  isCtrlPressed(): boolean {
    return this.ctrlPressed;
  }

  /**
   * Check if Alt is pressed
   */
  isAltPressed(): boolean {
    return this.altPressed;
  }

  /**
   * Check if Meta/Windows is pressed
   */
  isMetaPressed(): boolean {
    return this.metaPressed;
  }

  /**
   * Reset all modifier states
   */
  resetModifiers(): void {
    this.shiftPressed = false;
    this.ctrlPressed = false;
    this.altPressed = false;
    this.metaPressed = false;
  }

  /**
   * Send Ctrl+Alt+Delete sequence
   */
  sendCtrlAltDelete(): void {
    if (this.listener) {
      // Press Ctrl
      this.listener.onKeyEvent(VK_CONTROL, true);
      // Press Alt
      this.listener.onKeyEvent(VK_MENU, true);
      // Press Delete
      this.listener.onKeyEvent(VK_DELETE, true);
      this.listener.onKeyEvent(VK_DELETE, false);
      // Release Alt
      this.listener.onKeyEvent(VK_MENU, false);
      // Release Ctrl
      this.listener.onKeyEvent(VK_CONTROL, false);
    }
  }

  /**
   * Send Alt+Tab sequence
   */
  sendAltTab(): void {
    if (this.listener) {
      this.listener.onKeyEvent(VK_MENU, true);
      this.listener.onKeyEvent(VK_TAB, true);
      this.listener.onKeyEvent(VK_TAB, false);
      this.listener.onKeyEvent(VK_MENU, false);
    }
  }

  /**
   * Send Windows key
   */
  sendWindowsKey(): void {
    if (this.listener) {
      this.listener.onKeyEvent(VK_LWIN, true);
      this.listener.onKeyEvent(VK_LWIN, false);
    }
  }
}

export default KeyboardMapper;
