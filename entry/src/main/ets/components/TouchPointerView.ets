/*
 * HarmonyOS FreeRDP Touch Pointer View Component
 * 
 * Provides a virtual mouse pointer for touch interaction
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import LibFreeRDP, {
  PTR_FLAGS_MOVE,
  PTR_FLAGS_DOWN,
  PTR_FLAGS_BUTTON1,
  PTR_FLAGS_BUTTON2,
  CURSOR_TYPE_DEFAULT,
  CURSOR_TYPE_HAND,
  CURSOR_TYPE_IBEAM,
  CURSOR_TYPE_SIZE_NS,
  CURSOR_TYPE_SIZE_WE,
  CURSOR_TYPE_CROSS,
  CURSOR_TYPE_WAIT
} from '../services/LibFreeRDP';

/**
 * View position interface
 */
interface ViewPosition {
  x: number;
  y: number;
}

/**
 * Touch pointer event listener
 */
export interface TouchPointerListener {
  onPointerMove(x: number, y: number): void;
  onLeftClick(x: number, y: number): void;
  onRightClick(x: number, y: number): void;
  onDragStart(x: number, y: number): void;
  onDragEnd(x: number, y: number): void;
}

/**
 * Touch Pointer View Component
 * Displays a virtual mouse pointer that can be moved by touch
 */
@Component
export struct TouchPointerView {
  // Session instance
  @Prop instance: number = 0;
  
  // Pointer position (in desktop coordinates)
  @State pointerX: number = 100;
  @State pointerY: number = 100;
  
  // Cursor type
  @State cursorType: number = CURSOR_TYPE_DEFAULT;
  
  // View dimensions
  @Prop viewWidth: number = 0;
  @Prop viewHeight: number = 0;
  
  // Desktop dimensions
  @Prop desktopWidth: number = 1920;
  @Prop desktopHeight: number = 1080;
  
  // View scale and offset
  @Prop viewScale: number = 1.0;
  @Prop offsetX: number = 0;
  @Prop offsetY: number = 0;
  
  // Pointer state
  @State isDragging: boolean = false;
  @State isVisible: boolean = true;
  
  // Touch tracking
  private lastTouchX: number = 0;
  private lastTouchY: number = 0;
  
  // Listener
  private listener: TouchPointerListener | null = null;
  
  // Pointer size
  private static readonly POINTER_SIZE = 24;
  private static readonly TOUCH_AREA_SIZE = 48;

  /**
   * Set the event listener
   */
  setListener(listener: TouchPointerListener): void {
    this.listener = listener;
  }

  /**
   * Set cursor type
   */
  setCursorType(type: number): void {
    this.cursorType = type;
  }

  /**
   * Set pointer visibility
   */
  setVisible(visible: boolean): void {
    this.isVisible = visible;
  }

  /**
   * Move pointer to position (desktop coordinates)
   */
  moveTo(x: number, y: number): void {
    this.pointerX = Math.max(0, Math.min(x, this.desktopWidth - 1));
    this.pointerY = Math.max(0, Math.min(y, this.desktopHeight - 1));
    
    // Send mouse move event
    if (this.instance !== 0) {
      LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, PTR_FLAGS_MOVE);
    }
    
    if (this.listener) {
      this.listener.onPointerMove(this.pointerX, this.pointerY);
    }
  }

  /**
   * Get view position of pointer
   */
  private getViewPosition(): ViewPosition {
    const pos: ViewPosition = {
      x: this.pointerX * this.viewScale + this.offsetX,
      y: this.pointerY * this.viewScale + this.offsetY
    };
    return pos;
  }

  /**
   * Handle touch on pointer
   */
  private onPointerTouch(event: TouchEvent): void {
    if (event.touches.length === 0) return;
    
    const touch = event.touches[0];
    
    switch (event.type) {
      case TouchType.Down:
        this.lastTouchX = touch.x;
        this.lastTouchY = touch.y;
        break;
        
      case TouchType.Move:
        const dx = (touch.x - this.lastTouchX) / this.viewScale;
        const dy = (touch.y - this.lastTouchY) / this.viewScale;
        
        this.moveTo(this.pointerX + dx, this.pointerY + dy);
        
        this.lastTouchX = touch.x;
        this.lastTouchY = touch.y;
        break;
        
      case TouchType.Up:
      case TouchType.Cancel:
        break;
    }
  }

  /**
   * Send left click at current position
   */
  leftClick(): void {
    if (this.instance === 0) return;
    
    LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, 
                               PTR_FLAGS_DOWN | PTR_FLAGS_BUTTON1);
    LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, 
                               PTR_FLAGS_BUTTON1);
    
    if (this.listener) {
      this.listener.onLeftClick(this.pointerX, this.pointerY);
    }
  }

  /**
   * Send right click at current position
   */
  rightClick(): void {
    if (this.instance === 0) return;
    
    LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, 
                               PTR_FLAGS_DOWN | PTR_FLAGS_BUTTON2);
    LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, 
                               PTR_FLAGS_BUTTON2);
    
    if (this.listener) {
      this.listener.onRightClick(this.pointerX, this.pointerY);
    }
  }

  /**
   * Start drag operation
   */
  startDrag(): void {
    if (this.instance === 0) return;
    
    this.isDragging = true;
    LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, 
                               PTR_FLAGS_DOWN | PTR_FLAGS_BUTTON1);
    
    if (this.listener) {
      this.listener.onDragStart(this.pointerX, this.pointerY);
    }
  }

  /**
   * End drag operation
   */
  endDrag(): void {
    if (this.instance === 0) return;
    
    this.isDragging = false;
    LibFreeRDP.sendCursorEvent(this.instance, this.pointerX, this.pointerY, 
                               PTR_FLAGS_BUTTON1);
    
    if (this.listener) {
      this.listener.onDragEnd(this.pointerX, this.pointerY);
    }
  }

  /**
   * Get cursor icon based on type
   */
  private getCursorIcon(): ResourceStr {
    // TODO: Add actual cursor icons
    // For now, use a default icon
    return $r('app.media.startIcon');
  }

  /**
   * Get cursor color based on type
   */
  private getCursorColor(): ResourceColor {
    switch (this.cursorType) {
      case CURSOR_TYPE_HAND:
        return '#4CAF50';  // Green for clickable
      case CURSOR_TYPE_IBEAM:
        return '#2196F3';  // Blue for text
      case CURSOR_TYPE_SIZE_NS:
      case CURSOR_TYPE_SIZE_WE:
        return '#FF9800';  // Orange for resize
      case CURSOR_TYPE_CROSS:
        return '#9C27B0';  // Purple for crosshair
      case CURSOR_TYPE_WAIT:
        return '#F44336';  // Red for wait
      default:
        return '#FFFFFF';  // White default
    }
  }

  build() {
    if (this.isVisible) {
      Stack() {
        // Pointer visual
        Column() {
          // Pointer shape
          Polygon({
            width: TouchPointerView.POINTER_SIZE,
            height: TouchPointerView.POINTER_SIZE
          })
            .points([[0, 0], [0, 20], [6, 16], [10, 24], [14, 22], [10, 14], [16, 14]])
            .fill(this.getCursorColor())
            .stroke('#000000')
            .strokeWidth(1)
        }
        .width(TouchPointerView.POINTER_SIZE)
        .height(TouchPointerView.POINTER_SIZE)
        
        // Touch area (larger than visual for easier interaction)
        Column()
          .width(TouchPointerView.TOUCH_AREA_SIZE)
          .height(TouchPointerView.TOUCH_AREA_SIZE)
          .backgroundColor(this.isDragging ? '#30FF0000' : '#00000000')
          .borderRadius(TouchPointerView.TOUCH_AREA_SIZE / 2)
          .position({
            x: -TouchPointerView.TOUCH_AREA_SIZE / 2 + TouchPointerView.POINTER_SIZE / 2,
            y: -TouchPointerView.TOUCH_AREA_SIZE / 2 + TouchPointerView.POINTER_SIZE / 2
          })
          .onTouch((event: TouchEvent) => {
            this.onPointerTouch(event);
          })
      }
      .position({
        x: this.getViewPosition().x,
        y: this.getViewPosition().y
      })
      .gesture(
        LongPressGesture({ repeat: false, duration: 500 })
          .onAction(() => {
            // Long press for right click
            this.rightClick();
          })
      )
      .gesture(
        TapGesture({ count: 1 })
          .onAction(() => {
            this.leftClick();
          })
      )
      .gesture(
        TapGesture({ count: 2 })
          .onAction(() => {
            // Double tap to start/end drag
            if (this.isDragging) {
              this.endDrag();
            } else {
              this.startDrag();
            }
          })
      )
    }
  }
}

export default TouchPointerView;
