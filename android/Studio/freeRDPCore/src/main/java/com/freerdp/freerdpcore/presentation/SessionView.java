/*
   Android Session view

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.presentation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;

import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.utils.DoubleGestureDetector;
import com.freerdp.freerdpcore.utils.GestureDetector;
import com.freerdp.freerdpcore.utils.Mouse;

import java.util.Stack;

public class SessionView extends View
{
	public static final float MAX_SCALE_FACTOR = 4.0f;
	public static final float MIN_SCALE_FACTOR = 1.0f;
	private static final String TAG = "SessionView";
	private static final float SCALE_FACTOR_DELTA = 0.0001f;
	private static final float TOUCH_SCROLL_DELTA = 10.0f;
	private static final int PTR_MOVE = 0x0800;
	private int width;
	private int height;
	private BitmapDrawable surface;
	private Stack<Rect> invalidRegions;
	private int touchPointerPaddingWidth = 0;
	private int touchPointerPaddingHeight = 0;
	private SessionViewListener sessionViewListener = null;
	private TouchPointerView touchPointerView = null;
	// helpers for scaling gesture handling
	private float scaleFactor = 1.0f;
	private Matrix scaleMatrix;
	private Matrix invScaleMatrix;
	private RectF invalidRegionF;
	// ✅ P1 Bug修复: 添加缩放矩阵同步锁，防止触摸事件和缩放操作的竞态条件
	private final Object matrixLock = new Object();
	private GestureDetector gestureDetector;
	private SessionState currentSession;

	// Remote cursor rendering
	private Bitmap remoteCursorBitmap = null;
	private int remoteCursorHotX = 0;
	private int remoteCursorHotY = 0;
	private int remoteCursorX = 0;
	private int remoteCursorY = 0;
	// Track cursor size for reuse optimization
	private int lastCursorWidth = 0;
	private int lastCursorHeight = 0;

	// private static final String TAG = "FreeRDP.SessionView";
	private DoubleGestureDetector doubleGestureDetector;
	public SessionView(Context context)
	{
		super(context);
		initSessionView(context);
	}

	public SessionView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		initSessionView(context);
	}

	public SessionView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context, attrs, defStyle);
		initSessionView(context);
	}

	private void initSessionView(Context context)
	{
		invalidRegions = new Stack<>();
		gestureDetector = new GestureDetector(context, new SessionGestureListener(), null, true);
		doubleGestureDetector =
		    new DoubleGestureDetector(context, null, new SessionDoubleGestureListener());

	scaleFactor = 1.0f;
	scaleMatrix = new Matrix();
	invScaleMatrix = new Matrix();
	invalidRegionF = new RectF();

	// 设置布局延伸到状态栏和导航栏下方，但保持它们始终可见
	setSystemUiVisibility(
	    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
	    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
	    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
	);
}

	/* External Mouse Hover */
	@Override public boolean onHoverEvent(MotionEvent event)
	{
		if (event.getAction() == MotionEvent.ACTION_HOVER_MOVE)
		{
			// ✅ 添加空指针检查，防止NullPointerException导致连接断开
			if (currentSession == null) {
				return true; // 静默失败，避免崩溃
			}
			
			MotionEvent mappedEvent = null;
			try {
				// Handle hover move event
				float x = event.getX();
				float y = event.getY();
				// Perform actions based on the hover position (x, y)
				mappedEvent = mapTouchEvent(event);
				
				// ✅ 添加坐标验证（0-32000，与C代码android_event.c中的检查一致）
				int mappedX = (int)mappedEvent.getX();
				int mappedY = (int)mappedEvent.getY();
				
				if (mappedX < 0 || mappedX > 32000 || mappedY < 0 || mappedY > 32000) {
					// 无效坐标，静默失败，避免发送到服务器导致问题
					return true;
				}
				
				LibFreeRDP.sendCursorEvent(currentSession.getInstance(), mappedX, mappedY, PTR_MOVE);
			} catch (Exception e) {
				// ✅ 捕获异常，防止异常传播导致连接断开
				Log.w(TAG, "Failed to send hover cursor event", e);
			} finally {
				// ✅ Bug Fix: 回收MotionEvent，防止内存泄漏（拖动时高频调用）
				if (mappedEvent != null) {
					mappedEvent.recycle();
				}
			}
		}
		// Return true to indicate that you've handled the event
		return true;
	}

	public void setScaleGestureDetector(ScaleGestureDetector scaleGestureDetector)
	{
		doubleGestureDetector.setScaleGestureDetector(scaleGestureDetector);
	}

	public void setSessionViewListener(SessionViewListener sessionViewListener)
	{
		this.sessionViewListener = sessionViewListener;
	}
	
	public void setTouchPointerView(TouchPointerView touchPointerView)
	{
		this.touchPointerView = touchPointerView;
	}

	public void addInvalidRegion(Rect invalidRegion)
	{
		// correctly transform invalid region depending on current scaling
		invalidRegionF.set(invalidRegion);
		scaleMatrix.mapRect(invalidRegionF);
		invalidRegionF.roundOut(invalidRegion);

		invalidRegions.add(invalidRegion);
	}

	public void invalidateRegion()
	{
		if (invalidRegions == null || invalidRegions.isEmpty())
		{
			// 栈为空时忽略刷新（栈和消息队列不同步时的安全处理）
			// 不执行全屏刷新，避免闪屏
			return;
		}
		invalidate(invalidRegions.pop());
	}

	public void onSurfaceChange(SessionState session)
	{
		surface = session.getSurface();
		Bitmap bitmap = surface.getBitmap();
		width = bitmap.getWidth();
		height = bitmap.getHeight();
		surface.setBounds(0, 0, width, height);

		setMinimumWidth(width);
		setMinimumHeight(height);

		requestLayout();
		currentSession = session;
	}

	public float getZoom()
	{
		return scaleFactor;
	}

	public Matrix getInvScaleMatrix()
	{
		synchronized (matrixLock) {
			return invScaleMatrix;
		}
	}

	public Matrix getScaleMatrix()
	{
		synchronized (matrixLock) {
			return scaleMatrix;
		}
	}

	public void updateRemoteCursor(byte[] bitmapData, int width, int height, int hotX, int hotY)
	{
		if (bitmapData == null || width == 0 || height == 0)
		{
			// Hide cursor
			releaseRemoteCursorBitmap();
			invalidate();
			return;
		}

		// Validate data size
		int expectedSize = width * height * 4; // ARGB = 4 bytes per pixel
		if (bitmapData.length < expectedSize)
		{
			Log.e(TAG, "updateRemoteCursor: Invalid bitmapData size. Expected: " + expectedSize + ", Got: " + bitmapData.length);
			releaseRemoteCursorBitmap();
			invalidate();
			return;
		}

		try
		{
			// ✅ Optimization: Reuse bitmap if same size (avoid allocations)
			if (remoteCursorBitmap != null && 
			    !remoteCursorBitmap.isRecycled() &&
			    lastCursorWidth == width && 
			    lastCursorHeight == height)
			{
				// Reuse existing bitmap, just update pixel data
				remoteCursorBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bitmapData));
				Log.d(TAG, "Reused cursor bitmap " + width + "x" + height);
			}
			else
			{
				// ✅ Release old bitmap before creating new one (fix memory leak)
				releaseRemoteCursorBitmap();
				
				// Create new bitmap
				remoteCursorBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				remoteCursorBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bitmapData));
				
				lastCursorWidth = width;
				lastCursorHeight = height;
				Log.d(TAG, "Created new cursor bitmap " + width + "x" + height);
			}
			
			remoteCursorHotX = hotX;
			remoteCursorHotY = hotY;
			invalidate();
		}
		catch (OutOfMemoryError e)
		{
			Log.e(TAG, "updateRemoteCursor: OutOfMemoryError creating bitmap " + width + "x" + height, e);
			releaseRemoteCursorBitmap();
			invalidate();
		}
		catch (IllegalArgumentException e)
		{
			Log.e(TAG, "updateRemoteCursor: IllegalArgumentException creating bitmap " + width + "x" + height, e);
			releaseRemoteCursorBitmap();
			invalidate();
		}
		catch (Exception e)
		{
			Log.e(TAG, "updateRemoteCursor: Unexpected exception", e);
			releaseRemoteCursorBitmap();
			invalidate();
		}
	}

	public void updateRemoteCursorPosition(int x, int y)
	{
		remoteCursorX = x;
		remoteCursorY = y;
		invalidate();
	}

	/**
	 * Release remote cursor bitmap to free memory (memory optimization)
	 * Safe to call multiple times
	 */
	public void releaseRemoteCursorBitmap()
	{
		if (remoteCursorBitmap != null && !remoteCursorBitmap.isRecycled())
		{
			remoteCursorBitmap.recycle();
			remoteCursorBitmap = null;
			lastCursorWidth = 0;
			lastCursorHeight = 0;
			Log.d(TAG, "Remote cursor bitmap released");
		}
	}

	/**
	 * Show default cursor placeholder when real cursor is not yet available
	 * ✅ 已禁用：不显示任何远程指针，只使用本地 Android 触摸指针
	 */
	public void showDefaultCursor()
	{
		// ✅ Disabled: Do not show any remote cursor, use local Android pointer only
		Log.d(TAG, "Default cursor not shown (remote cursor display disabled)");
	}

	/**
	 * Clear invalid regions stack to free memory (memory optimization)
	 * Safe to call anytime - will be repopulated as needed
	 */
	public void clearInvalidRegions()
	{
		if (invalidRegions != null)
		{
			int size = invalidRegions.size();
			invalidRegions.clear();
			if (size > 0)
			{
				Log.d(TAG, "Cleared " + size + " invalid regions");
			}
		}
	}

	public void setZoom(float factor)
	{
		// ✅ P1 Bug修复: 添加同步保护，防止触摸事件处理时读取到不一致的矩阵
		synchronized (matrixLock) {
			// calc scale matrix and inverse scale matrix (to correctly transform the view and moues
			// coordinates)
			scaleFactor = factor;
			scaleMatrix.setScale(scaleFactor, scaleFactor);
			invScaleMatrix.setScale(1.0f / scaleFactor, 1.0f / scaleFactor);
		}

		// update layout (在锁外执行，避免死锁)
		requestLayout();
	}

	public boolean isAtMaxZoom()
	{
		return (scaleFactor > (MAX_SCALE_FACTOR - SCALE_FACTOR_DELTA));
	}

	public boolean isAtMinZoom()
	{
		return (scaleFactor < (MIN_SCALE_FACTOR + SCALE_FACTOR_DELTA));
	}

	public boolean zoomIn(float factor)
	{
		boolean res = true;
		scaleFactor += factor;
		if (scaleFactor > (MAX_SCALE_FACTOR - SCALE_FACTOR_DELTA))
		{
			scaleFactor = MAX_SCALE_FACTOR;
			res = false;
		}
		setZoom(scaleFactor);
		return res;
	}

	public boolean zoomOut(float factor)
	{
		boolean res = true;
		scaleFactor -= factor;
		if (scaleFactor < (MIN_SCALE_FACTOR + SCALE_FACTOR_DELTA))
		{
			scaleFactor = MIN_SCALE_FACTOR;
			res = false;
		}
		setZoom(scaleFactor);
		return res;
	}

	public void setTouchPointerPadding(int width, int height)
	{
		touchPointerPaddingWidth = width;
		touchPointerPaddingHeight = height;
		requestLayout();
	}

	public int getTouchPointerPaddingWidth()
	{
		return touchPointerPaddingWidth;
	}

	public int getTouchPointerPaddingHeight()
	{
		return touchPointerPaddingHeight;
	}

	@Override public void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		Log.v(TAG, width + "x" + height);
		this.setMeasuredDimension((int)(width * scaleFactor) + touchPointerPaddingWidth,
		                          (int)(height * scaleFactor) + touchPointerPaddingHeight);
	}

	@Override public void onDraw(@NonNull Canvas canvas)
	{
		super.onDraw(canvas);

		canvas.save();
		canvas.concat(scaleMatrix);
		canvas.drawColor(Color.BLACK);
		if (surface != null)
		{
			surface.draw(canvas);
		}
		
	// ✅ 完全禁用远程指针绘制
	// 不绘制远程桌面的光标，只使用本地 Android 触摸指针/TouchPointerView
	// Remote cursor drawing disabled - use local Android pointer only
	// if (remoteCursorBitmap != null && 
	//     (touchPointerPaddingWidth == 0 && touchPointerPaddingHeight == 0))
	// {
	// 	canvas.drawBitmap(remoteCursorBitmap, 
	// 	                  remoteCursorX - remoteCursorHotX, 
	// 	                  remoteCursorY - remoteCursorHotY, 
	// 	                  null);
	// }
		
		canvas.restore();
	}

	// dirty hack: we call back to our activity and call onBackPressed as this doesn't reach us when
	// the soft keyboard is shown ...
	@Override public boolean dispatchKeyEventPreIme(KeyEvent event)
	{
		if (event.getKeyCode() == KeyEvent.KEYCODE_BACK &&
		    event.getAction() == KeyEvent.ACTION_DOWN)
		{
			((SessionActivity)this.getContext()).onBackPressed();
			// 返回true表示事件已处理，阻止系统默认行为
			return true;
		}
		return super.dispatchKeyEventPreIme(event);
	}

	// perform mapping on the touch event's coordinates according to the current scaling
	private MotionEvent mapTouchEvent(MotionEvent event)
	{
		MotionEvent mappedEvent = MotionEvent.obtain(event);
		float[] coordinates = { mappedEvent.getX(), mappedEvent.getY() };
		// ✅ P1 Bug修复: 在同步块中读取矩阵，确保一致性
		synchronized (matrixLock) {
			invScaleMatrix.mapPoints(coordinates);
		}
		mappedEvent.setLocation(coordinates[0], coordinates[1]);
		return mappedEvent;
	}

	// perform mapping on the double touch event's coordinates according to the current scaling
	private MotionEvent mapDoubleTouchEvent(MotionEvent event)
	{
		MotionEvent mappedEvent = MotionEvent.obtain(event);
		float[] coordinates = { (mappedEvent.getX(0) + mappedEvent.getX(1)) / 2,
			                    (mappedEvent.getY(0) + mappedEvent.getY(1)) / 2 };
		// ✅ P1 Bug修复: 在同步块中读取矩阵，确保一致性
		synchronized (matrixLock) {
			invScaleMatrix.mapPoints(coordinates);
		}
		mappedEvent.setLocation(coordinates[0], coordinates[1]);
		return mappedEvent;
	}

	@Override public boolean onTouchEvent(MotionEvent event)
	{
		boolean res = gestureDetector.onTouchEvent(event);
		res |= doubleGestureDetector.onTouchEvent(event);
		return res;
	}

	public interface SessionViewListener {
		void onSessionViewBeginTouch();

		void onSessionViewEndTouch();

		void onSessionViewLeftTouch(int x, int y, boolean down);

		void onSessionViewRightTouch(int x, int y, boolean down);

		void onSessionViewMove(int x, int y);

		void onSessionViewScroll(boolean down);
	}

	private class SessionGestureListener extends GestureDetector.SimpleOnGestureListener
	{
		boolean longPressInProgress = false;

		public boolean onDown(MotionEvent e)
		{
			// ✅ P1 Bug修复: 在每次新的触摸开始时重置状态，防止状态卡住
			longPressInProgress = false;
			
			if (touchPointerView != null)
			{
				touchPointerView.clearDashedLineState();
			}
			return true;
		}

		public boolean onUp(MotionEvent e)
		{
			// ✅ P1 Bug修复: 在手指抬起时重置状态
			longPressInProgress = false;
			sessionViewListener.onSessionViewEndTouch();
			return true;
		}

	public void onLongPress(MotionEvent e)
	{
		MotionEvent mappedEvent = null;
		try {
			mappedEvent = mapTouchEvent(e);
			sessionViewListener.onSessionViewBeginTouch();
			sessionViewListener.onSessionViewLeftTouch((int)mappedEvent.getX(),
			                                           (int)mappedEvent.getY(), true);
			longPressInProgress = true;
		} catch (Exception ex) {
			// ✅ P1 Bug修复: 异常时清理状态
			Log.e(TAG, "Exception in onLongPress", ex);
			longPressInProgress = false;
		} finally {
			// ✅ Bug Fix: 回收MotionEvent，防止内存泄漏
			if (mappedEvent != null) {
				mappedEvent.recycle();
			}
		}
	}

	public void onLongPressUp(MotionEvent e)
	{
		MotionEvent mappedEvent = null;
		try {
			mappedEvent = mapTouchEvent(e);
			sessionViewListener.onSessionViewLeftTouch((int)mappedEvent.getX(),
			                                           (int)mappedEvent.getY(), false);
		} catch (Exception ex) {
			// ✅ P1 Bug修复: 异常时也要清理状态
			Log.e(TAG, "Exception in onLongPressUp", ex);
		} finally {
			// ✅ P1 Bug修复: 确保在finally块中重置状态
			longPressInProgress = false;
			// ✅ Bug Fix: 回收MotionEvent，防止内存泄漏
			if (mappedEvent != null) {
				mappedEvent.recycle();
			}
		}
	}

	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY)
	{
		if (longPressInProgress)
		{
			MotionEvent mappedEvent = null;
			try {
				mappedEvent = mapTouchEvent(e2);
				sessionViewListener.onSessionViewMove((int)mappedEvent.getX(),
				                                      (int)mappedEvent.getY());
				return true;
			} catch (Exception ex) {
				// ✅ P1 Bug修复: 异常时清理状态
				Log.e(TAG, "Exception in onScroll", ex);
				longPressInProgress = false;
			} finally {
				// ✅ Bug Fix: 回收MotionEvent，防止内存泄漏（拖动时高频调用）
				if (mappedEvent != null) {
					mappedEvent.recycle();
				}
			}
		}

		return false;
	}

		public boolean onDoubleTap(MotionEvent e)
		{
		// Disable double-tap - do nothing
			return true;
		}

	public boolean onSingleTapUp(MotionEvent e)
	{
	// Handle single tap immediately without waiting for double-tap confirmation
		MotionEvent mappedEvent = null;
		try {
			mappedEvent = mapTouchEvent(e);
			sessionViewListener.onSessionViewBeginTouch();
					sessionViewListener.onSessionViewLeftTouch((int)mappedEvent.getX(),
					                                           (int)mappedEvent.getY(), true);
					sessionViewListener.onSessionViewLeftTouch((int)mappedEvent.getX(),
					                                           (int)mappedEvent.getY(), false);
			return true;
		} finally {
			// ✅ Bug Fix: 回收MotionEvent，防止内存泄漏
			if (mappedEvent != null) {
				mappedEvent.recycle();
			}
		}
	}
	}

	private class SessionDoubleGestureListener
	    implements DoubleGestureDetector.OnDoubleGestureListener
	{
		private MotionEvent prevEvent = null;

		public boolean onDoubleTouchDown(MotionEvent e)
		{
			sessionViewListener.onSessionViewBeginTouch();
			prevEvent = MotionEvent.obtain(e);
			return true;
		}

		public boolean onDoubleTouchUp(MotionEvent e)
		{
			if (prevEvent != null)
			{
				prevEvent.recycle();
				prevEvent = null;
			}
			sessionViewListener.onSessionViewEndTouch();
			return true;
		}

		public boolean onDoubleTouchScroll(MotionEvent e1, MotionEvent e2)
		{
			// calc if user scrolled up or down (or if any scrolling happened at all)
			float deltaY = e2.getY() - prevEvent.getY();
			if (deltaY > TOUCH_SCROLL_DELTA)
			{
				sessionViewListener.onSessionViewScroll(true);
				prevEvent.recycle();
				prevEvent = MotionEvent.obtain(e2);
			}
			else if (deltaY < -TOUCH_SCROLL_DELTA)
			{
				sessionViewListener.onSessionViewScroll(false);
				prevEvent.recycle();
				prevEvent = MotionEvent.obtain(e2);
			}
			return true;
		}

	public boolean onDoubleTouchSingleTap(MotionEvent e)
	{
		// send single click
		MotionEvent mappedEvent = null;
		try {
			mappedEvent = mapDoubleTouchEvent(e);
			sessionViewListener.onSessionViewRightTouch((int)mappedEvent.getX(),
			                                            (int)mappedEvent.getY(), true);
			sessionViewListener.onSessionViewRightTouch((int)mappedEvent.getX(),
			                                            (int)mappedEvent.getY(), false);
			return true;
		} finally {
			// ✅ Bug Fix: 回收MotionEvent，防止内存泄漏
			if (mappedEvent != null) {
				mappedEvent.recycle();
			}
		}
	}
	}

	@Override public InputConnection onCreateInputConnection(EditorInfo outAttrs)
	{
		super.onCreateInputConnection(outAttrs);
		outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
		return null;
	}
}
