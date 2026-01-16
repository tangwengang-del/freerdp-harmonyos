/*
 * HarmonyOS FreeRDP Session View Component
 * 
 * Custom component for rendering the remote desktop
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import image from '@ohos.multimedia.image';
import LibFreeRDP, { 
  PTR_FLAGS_MOVE, 
  PTR_FLAGS_DOWN, 
  PTR_FLAGS_BUTTON1, 
  PTR_FLAGS_BUTTON2,
  PTR_FLAGS_WHEEL,
  PTR_FLAGS_WHEEL_NEGATIVE
} from '../services/LibFreeRDP';

/**
 * Desktop coordinate point
 */
interface DesktopPoint {
  x: number;
  y: number;
}

/**
 * Session view event listener
 */
export interface SessionViewListener {
  onSessionViewReady(): void;
  onTouchEvent(x: number, y: number, flags: number): void;
  onScaleChanged(scale: number): void;
}

/**
 * Session View Component
 * Displays the remote desktop and handles touch input
 */
@Component
export struct SessionView {
  // Session instance
  @Prop instance: number = 0;
  
  // Display dimensions
  @Prop desktopWidth: number = 1920;
  @Prop desktopHeight: number = 1080;
  
  // Pixel map for rendering
  @Prop @Watch('onPixelMapChanged') pixelMap: image.PixelMap | null = null;
  
  // View state
  @State viewScale: number = 1.0;
  @State offsetX: number = 0;
  @State offsetY: number = 0;
  @State viewWidth: number = 0;
  @State viewHeight: number = 0;
  
  // Touch state
  private lastTouchX: number = 0;
  private lastTouchY: number = 0;
  private isPanning: boolean = false;
  private isScaling: boolean = false;
  private initialPinchDistance: number = 0;
  private initialScale: number = 1.0;
  
  // Listener
  private listener: SessionViewListener | null = null;
  
  // Constants
  private static readonly MIN_SCALE = 0.25;
  private static readonly MAX_SCALE = 4.0;
  private static readonly SCROLL_WHEEL_DELTA = 120;

  /**
   * Set the event listener
   */
  setListener(listener: SessionViewListener): void {
    this.listener = listener;
  }

  /**
   * Handle pixel map changes
   */
  onPixelMapChanged(): void {
    // Force refresh when pixel map changes
  }

  /**
   * Convert view coordinates to desktop coordinates
   */
  private viewToDesktop(viewX: number, viewY: number): DesktopPoint {
    const x = Math.round((viewX - this.offsetX) / this.viewScale);
    const y = Math.round((viewY - this.offsetY) / this.viewScale);
    const result: DesktopPoint = {
      x: Math.max(0, Math.min(x, this.desktopWidth - 1)),
      y: Math.max(0, Math.min(y, this.desktopHeight - 1))
    };
    return result;
  }

  /**
   * Send mouse event to remote desktop
   */
  private sendMouseEvent(x: number, y: number, flags: number): void {
    if (this.instance === 0) return;
    
    LibFreeRDP.sendCursorEvent(this.instance, x, y, flags);
    
    if (this.listener) {
      this.listener.onTouchEvent(x, y, flags);
    }
  }

  /**
   * Handle touch down
   */
  private onTouchDown(event: TouchEvent): void {
    if (event.touches.length === 1) {
      const touch = event.touches[0];
      this.lastTouchX = touch.x;
      this.lastTouchY = touch.y;
      this.isPanning = false;
      this.isScaling = false;
      
      // Send mouse down event
      const pos = this.viewToDesktop(touch.x, touch.y);
      this.sendMouseEvent(pos.x, pos.y, PTR_FLAGS_DOWN | PTR_FLAGS_BUTTON1);
    } else if (event.touches.length === 2) {
      // Start pinch gesture
      this.isScaling = true;
      this.isPanning = false;
      const dx = event.touches[1].x - event.touches[0].x;
      const dy = event.touches[1].y - event.touches[0].y;
      this.initialPinchDistance = Math.sqrt(dx * dx + dy * dy);
      this.initialScale = this.viewScale;
    }
  }

  /**
   * Handle touch move
   */
  private onTouchMove(event: TouchEvent): void {
    if (event.touches.length === 1 && !this.isScaling) {
      const touch = event.touches[0];
      const dx = touch.x - this.lastTouchX;
      const dy = touch.y - this.lastTouchY;
      
      // Check if this is a pan gesture (significant movement)
      if (!this.isPanning && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
        this.isPanning = true;
      }
      
      if (this.isPanning) {
        // Pan the view
        this.offsetX += dx;
        this.offsetY += dy;
        this.constrainOffset();
      } else {
        // Send mouse move event
        const pos = this.viewToDesktop(touch.x, touch.y);
        this.sendMouseEvent(pos.x, pos.y, PTR_FLAGS_MOVE);
      }
      
      this.lastTouchX = touch.x;
      this.lastTouchY = touch.y;
    } else if (event.touches.length === 2 && this.isScaling) {
      // Handle pinch zoom
      const dx = event.touches[1].x - event.touches[0].x;
      const dy = event.touches[1].y - event.touches[0].y;
      const distance = Math.sqrt(dx * dx + dy * dy);
      
      if (this.initialPinchDistance > 0) {
        const scaleRatio = distance / this.initialPinchDistance;
        let newScale = this.initialScale * scaleRatio;
        
        // Clamp scale
        newScale = Math.max(SessionView.MIN_SCALE, Math.min(newScale, SessionView.MAX_SCALE));
        
        // Calculate center point for zoom
        const centerX = (event.touches[0].x + event.touches[1].x) / 2;
        const centerY = (event.touches[0].y + event.touches[1].y) / 2;
        
        // Adjust offset to zoom around center
        const scaleChange = newScale / this.viewScale;
        this.offsetX = centerX - (centerX - this.offsetX) * scaleChange;
        this.offsetY = centerY - (centerY - this.offsetY) * scaleChange;
        
        this.viewScale = newScale;
        this.constrainOffset();
        
        if (this.listener) {
          this.listener.onScaleChanged(this.viewScale);
        }
      }
    }
  }

  /**
   * Handle touch up
   */
  private onTouchUp(event: TouchEvent): void {
    if (!this.isPanning && !this.isScaling && event.touches.length === 0) {
      // Send mouse up event at last position
      const pos = this.viewToDesktop(this.lastTouchX, this.lastTouchY);
      this.sendMouseEvent(pos.x, pos.y, PTR_FLAGS_BUTTON1);
    }
    
    this.isPanning = false;
    this.isScaling = false;
    this.initialPinchDistance = 0;
  }

  /**
   * Constrain offset to keep desktop in view
   */
  private constrainOffset(): void {
    const scaledWidth = this.desktopWidth * this.viewScale;
    const scaledHeight = this.desktopHeight * this.viewScale;
    
    // Allow some margin
    const margin = 50;
    
    if (scaledWidth <= this.viewWidth) {
      // Center horizontally if smaller than view
      this.offsetX = (this.viewWidth - scaledWidth) / 2;
    } else {
      // Constrain to edges
      this.offsetX = Math.min(margin, Math.max(this.viewWidth - scaledWidth - margin, this.offsetX));
    }
    
    if (scaledHeight <= this.viewHeight) {
      // Center vertically if smaller than view
      this.offsetY = (this.viewHeight - scaledHeight) / 2;
    } else {
      // Constrain to edges
      this.offsetY = Math.min(margin, Math.max(this.viewHeight - scaledHeight - margin, this.offsetY));
    }
  }

  /**
   * Set zoom level
   */
  setViewScale(newScale: number): void {
    this.viewScale = Math.max(SessionView.MIN_SCALE, Math.min(newScale, SessionView.MAX_SCALE));
    this.constrainOffset();
  }

  /**
   * Zoom in
   */
  zoomIn(): void {
    this.setViewScale(this.viewScale * 1.25);
  }

  /**
   * Zoom out
   */
  zoomOut(): void {
    this.setViewScale(this.viewScale / 1.25);
  }

  /**
   * Fit to screen
   */
  fitToScreen(): void {
    if (this.viewWidth <= 0 || this.viewHeight <= 0) return;
    
    const scaleX = this.viewWidth / this.desktopWidth;
    const scaleY = this.viewHeight / this.desktopHeight;
    this.viewScale = Math.min(scaleX, scaleY);
    
    // Center the desktop
    this.offsetX = (this.viewWidth - this.desktopWidth * this.viewScale) / 2;
    this.offsetY = (this.viewHeight - this.desktopHeight * this.viewScale) / 2;
  }

  /**
   * Reset to 100% zoom
   */
  resetZoom(): void {
    this.viewScale = 1.0;
    this.offsetX = 0;
    this.offsetY = 0;
    this.constrainOffset();
  }

  /**
   * Scroll the view
   */
  scrollBy(dx: number, dy: number): void {
    this.offsetX += dx;
    this.offsetY += dy;
    this.constrainOffset();
  }

  /**
   * Send scroll wheel event
   */
  sendScrollEvent(x: number, y: number, delta: number): void {
    let flags = PTR_FLAGS_WHEEL;
    if (delta < 0) {
      flags |= PTR_FLAGS_WHEEL_NEGATIVE;
      delta = -delta;
    }
    // Wheel delta is in the lower 9 bits
    flags |= (delta & 0x1FF);
    
    this.sendMouseEvent(x, y, flags);
  }

  /**
   * Send right click
   */
  sendRightClick(x: number, y: number): void {
    this.sendMouseEvent(x, y, PTR_FLAGS_DOWN | PTR_FLAGS_BUTTON2);
    this.sendMouseEvent(x, y, PTR_FLAGS_BUTTON2);
  }

  build() {
    Stack() {
      // Background
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#1a1a1a')
      
      // Remote desktop canvas
      if (this.pixelMap) {
        Image(this.pixelMap)
          .width(this.desktopWidth * this.viewScale)
          .height(this.desktopHeight * this.viewScale)
          .position({ x: this.offsetX, y: this.offsetY })
          .objectFit(ImageFit.Fill)
          .interpolation(ImageInterpolation.Medium)
      } else {
        // Placeholder when no image
        Column() {
          Text('正在连接...')
            .fontSize(24)
            .fontColor('#ffffff')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      }
    }
    .width('100%')
    .height('100%')
    .clip(true)
    .onAreaChange((oldValue: Area, newValue: Area) => {
      this.viewWidth = newValue.width as number;
      this.viewHeight = newValue.height as number;
      
      // Initial fit to screen
      if (this.viewScale === 1.0 && this.offsetX === 0 && this.offsetY === 0) {
        this.fitToScreen();
      } else {
        this.constrainOffset();
      }
      
      if (this.listener) {
        this.listener.onSessionViewReady();
      }
    })
    .onTouch((event: TouchEvent) => {
      switch (event.type) {
        case TouchType.Down:
          this.onTouchDown(event);
          break;
        case TouchType.Move:
          this.onTouchMove(event);
          break;
        case TouchType.Up:
        case TouchType.Cancel:
          this.onTouchUp(event);
          break;
      }
    })
  }
}

export default SessionView;
