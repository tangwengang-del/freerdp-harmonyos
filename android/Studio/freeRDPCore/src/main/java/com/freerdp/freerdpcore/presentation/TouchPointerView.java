/*
   Android Touch Pointer view - Simplified 3-Zone Version
   
   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
   Modified 2024: Simplified to 3-zone layout

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.presentation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.utils.GestureDetector;

import java.util.ArrayList;
import java.util.List;

public class TouchPointerView extends ImageView
{
	private static final String TAG = "TouchPointerView";
	private static final boolean DEBUG = false;

	// Simplified zones: 0 = left button, 1 = move (center), 2 = right button
	private static final int POINTER_ACTION_LCLICK = 0;
	private static final int POINTER_ACTION_MOVE = 1;
	private static final int POINTER_ACTION_RCLICK = 2;

	private static final int DEFAULT_TOUCH_POINTER_RESTORE_DELAY = 150;

	private RectF pointerRect;
	private final RectF[] pointerAreaRects = new RectF[3];
	private Matrix translationMatrix;
	private boolean pointerMoving = false;
	private boolean leftButtonDragging = false;
	private TouchPointerListener listener = null;
	private SessionView sessionView = null;
	private final UIHandler uiHandler = new UIHandler();
	private GestureDetector gestureDetector;
	
	private int currentCursorType = 0;
	private int currentBaseDrawable = R.drawable.touch_pointer_simple_default;

	// === 虚实线检测和自动对准功能 ===
	private boolean isOnDashedLine = false;  // 当前是否在虚实线上（红色状态）
	private int dashedLineY = -1;  // 记录虚实线的Y坐标
	private SessionActivity sessionActivity = null;  // 用于获取Bitmap
	private boolean isAutoMoving = false;  // 是否正在自动移动
	
	// ✅ P0 Bug修复: 缓存上次有效坐标，防止异常时返回(0,0)导致点击飘到左上角
	private Point lastValidRemoteCoordinate = null;
	
	// ✅ P1 Bug修复: 添加虚实线检测节流，防止过于频繁的检测导致内存压力和OOM
	private long lastDetectTime = 0;
	private static final long DETECT_INTERVAL_MS = 150;  // 150ms节流间隔（已从300ms优化）

	public TouchPointerView(Context context)
	{
		super(context);
		initTouchPointer(context);
	}

	public TouchPointerView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		initTouchPointer(context);
	}

	public TouchPointerView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context, attrs, defStyle);
		initTouchPointer(context);
	}

	private void initTouchPointer(Context context)
	{
		gestureDetector =
		    new GestureDetector(context, new TouchPointerGestureListener(), null, true);
		gestureDetector.setLongPressTimeout(500);
		translationMatrix = new Matrix();
		setScaleType(ScaleType.MATRIX);
		setImageMatrix(translationMatrix);

	// 动态获取图像尺寸
	final float width = (float)getDrawable().getIntrinsicWidth();
	final float height = (float)getDrawable().getIntrinsicHeight();

	// 新的对称布局（基于150×140标准比例，左键右移10px与右键对称）
	// 左键：10-50（40px，右移10px），中央圆：50-110（60px），右键：110-150（40px）
	// 按钮Y范围：80-140（箭头区域0-80不可触摸）
	float leftStart = width * (10f / 150f);    // 左键区域：10 ~ 50
	float leftEnd = width * (50f / 150f);
	float centerStart = width * (50f / 150f);  // 中央区域：50 ~ 110
	float centerEnd = width * (110f / 150f);
	float rightStart = width * (110f / 150f);  // 右键区域：110 ~ 150
	float buttonTop = height * (80f / 140f);   // 按钮顶部：80
	float buttonBottom = height;                // 按钮底部：140

	pointerAreaRects[POINTER_ACTION_LCLICK] = new RectF(leftStart, buttonTop, leftEnd, buttonBottom);
	pointerAreaRects[POINTER_ACTION_MOVE] = new RectF(centerStart, buttonTop, centerEnd, buttonBottom);
	pointerAreaRects[POINTER_ACTION_RCLICK] = new RectF(rightStart, buttonTop, width, buttonBottom);

	pointerRect = new RectF(0, 0, width, height);

	if (DEBUG) {
		Log.d(TAG, "Init: Image size=" + width + "x" + height + " (150×140 symmetric layout, L 10-50, M 50-110, R 110-150)");
		Log.d(TAG, "Left zone (10-50, 80-140): " + pointerAreaRects[POINTER_ACTION_LCLICK]);
		Log.d(TAG, "Move zone (50-110, 80-140): " + pointerAreaRects[POINTER_ACTION_MOVE]);
		Log.d(TAG, "Right zone (110-150, 80-140): " + pointerAreaRects[POINTER_ACTION_RCLICK]);
	}
	}

	public void setTouchPointerListener(TouchPointerListener listener)
	{
		this.listener = listener;
	}

	public void setSessionView(SessionView sessionView)
	{
		this.sessionView = sessionView;
	}

	public void setSessionActivity(SessionActivity activity)
	{
		this.sessionActivity = activity;
	}

	public void setPosition(float x, float y)
	{
		translationMatrix.setTranslate(x, y);
		setImageMatrix(translationMatrix);
		if (DEBUG) Log.d(TAG, "Position set to: (" + x + "," + y + ")");
	}

	public int getPointerWidth()
	{
		return getDrawable().getIntrinsicWidth();
	}

	public int getPointerHeight()
	{
		return getDrawable().getIntrinsicHeight();
	}

	public float[] getPointerPosition()
	{
		float[] curPos = new float[2];
		translationMatrix.mapPoints(curPos);
		return curPos;
	}

	private void movePointer(float deltaX, float deltaY)
	{
		translationMatrix.postTranslate(deltaX, deltaY);
		setImageMatrix(translationMatrix);
		
		if (listener != null)
		{
			Point remoteCoord = getRemoteCoordinate();
			listener.onTouchPointerMove(remoteCoord);
			if (DEBUG) Log.v(TAG, "Pointer moved, remote coord: (" + remoteCoord.x + "," + remoteCoord.y + ")");
		}
	}

	private void ensureVisibility(int screen_width, int screen_height)
	{
		float[] curPos = new float[2];
		translationMatrix.mapPoints(curPos);

		if (curPos[0] > (screen_width - pointerRect.width()))
			curPos[0] = screen_width - pointerRect.width();
		if (curPos[0] < 0)
			curPos[0] = 0;
		if (curPos[1] > (screen_height - pointerRect.height()))
			curPos[1] = screen_height - pointerRect.height();
		if (curPos[1] < 0)
			curPos[1] = 0;

		translationMatrix.setTranslate(curPos[0], curPos[1]);
		setImageMatrix(translationMatrix);
	}

	private void displayPointerImageAction(int resId)
	{
		// 取消之前的延迟恢复消息，避免状态不同步
		uiHandler.removeMessages(0);
		setPointerImage(resId);
		uiHandler.sendEmptyMessageDelayed(0, DEFAULT_TOUCH_POINTER_RESTORE_DELAY);
	}

	private void setPointerImage(int resId)
	{
		setImageResource(resId);
	}

	private RectF getCurrentPointerArea(int area)
	{
		RectF transRect = new RectF(pointerAreaRects[area]);
		translationMatrix.mapRect(transRect);
		return transRect;
	}

	// 获取箭头尖端对应的远程桌面坐标
	// 这是终极方案：在TouchPointerView内部完成所有坐标转换
	// ✅ P0 Bug修复: 异常时返回上次有效坐标而非(0,0)，防止点击飘到左上角
	public Point getRemoteCoordinate()
	{
		try {
			if (sessionView == null)
			{
				Log.w(TAG, "SessionView not set, returning last valid coordinate");
				return getLastValidCoordinate();
			}
			
			// 检查 Drawable 是否有效
			android.graphics.drawable.Drawable drawable = getDrawable();
			if (drawable == null) {
				Log.w(TAG, "Drawable is null, returning last valid coordinate");
				return getLastValidCoordinate();
			}
			
		// 1. 获取箭头尖端相对于TouchPointerView的坐标
		// Drawable定义：150dp × 140dp，箭头尖端在 (20dp, 25dp) - 已右移20px下移25px
		// 需要根据实际显示尺寸动态计算
		float[] pointerPos = getPointerPosition();
		float drawableWidth = (float)drawable.getIntrinsicWidth();
		float drawableHeight = (float)drawable.getIntrinsicHeight();
		
		// 验证尺寸有效性
		if (drawableWidth <= 0 || drawableHeight <= 0) {
			Log.w(TAG, "Invalid drawable size: " + drawableWidth + "x" + drawableHeight);
			return getLastValidCoordinate();
		}
		
		// 箭头尖端在drawable中的相对位置（比例）
		float tipXRatio = 20f / 150f;  // 20dp / 150dp (右移20px)
		float tipYRatio = 25f / 140f;  // 25dp / 140dp (下移25px)
			
			// 根据实际显示尺寸计算箭头尖端的绝对位置
			float tipX = pointerPos[0] + (drawableWidth * tipXRatio);
			float tipY = pointerPos[1] + (drawableHeight * tipYRatio);
			
			// 2. 获取两个View在屏幕上的位置
			int[] touchPointerLocation = new int[2];
			getLocationOnScreen(touchPointerLocation);
			
			int[] sessionViewLocation = new int[2];
			sessionView.getLocationOnScreen(sessionViewLocation);
			
			// 3. 计算相对于SessionView的坐标
			float viewX = tipX + (touchPointerLocation[0] - sessionViewLocation[0]);
			float viewY = tipY + (touchPointerLocation[1] - sessionViewLocation[1]);
			
			// 4. 使用逆缩放矩阵转换为远程桌面坐标
			android.graphics.Matrix invMatrix = sessionView.getInvScaleMatrix();
			if (invMatrix == null) {
				Log.w(TAG, "Inverse scale matrix is null, returning last valid coordinate");
				return getLastValidCoordinate();
			}
			
			float[] coordinates = { viewX, viewY };
			invMatrix.mapPoints(coordinates);
			
			// 验证坐标有效性 (防止 NaN, Infinity)
			if (!Float.isFinite(coordinates[0]) || !Float.isFinite(coordinates[1])) {
				Log.w(TAG, "Invalid coordinates: " + coordinates[0] + "," + coordinates[1]);
				return getLastValidCoordinate();
			}
		
		if (DEBUG) Log.d(TAG, String.format("Coordinate calc: drawable=%.1fx%.1f, tipRatio=(%.3f,%.3f), " +
		                         "pointerPos=(%.1f,%.1f), tip=(%.1f,%.1f), " +
			                         "viewCoord=(%.1f,%.1f), remoteCoord=(%d,%d)",
			                         drawableWidth, drawableHeight, tipXRatio, tipYRatio,
			                         pointerPos[0], pointerPos[1], tipX, tipY,
			                         viewX, viewY, (int)coordinates[0], (int)coordinates[1]));
			
			// ✅ 缓存有效坐标
			Point result = new Point((int)coordinates[0], (int)coordinates[1]);
			lastValidRemoteCoordinate = result;
			return result;
			
		} catch (Exception e) {
			Log.e(TAG, "Exception in getRemoteCoordinate", e);
			return getLastValidCoordinate();
		}
	}
	
	// ✅ P0 Bug修复: 获取上次有效坐标，防止返回(0,0)导致点击飘移
	private Point getLastValidCoordinate()
	{
		if (lastValidRemoteCoordinate != null) {
			if (DEBUG) Log.d(TAG, "Returning last valid coordinate: " + lastValidRemoteCoordinate);
			return new Point(lastValidRemoteCoordinate.x, lastValidRemoteCoordinate.y);
		}
		// 如果从未有过有效坐标，返回屏幕中心作为安全值
		if (sessionActivity != null) {
			android.graphics.Bitmap bitmap = sessionActivity.getBitmap();
			if (bitmap != null && !bitmap.isRecycled()) {
				int centerX = bitmap.getWidth() / 2;
				int centerY = bitmap.getHeight() / 2;
				if (DEBUG) Log.d(TAG, "No last valid coordinate, returning screen center: " + centerX + "," + centerY);
				return new Point(centerX, centerY);
			}
		}
		// 最后的fallback
		if (DEBUG) Log.w(TAG, "No valid coordinate available, returning (0,0) as last resort");
		return new Point(0, 0);
	}

	private boolean pointerAreaTouched(MotionEvent event, int area)
	{
		RectF transRect = new RectF(pointerAreaRects[area]);
		translationMatrix.mapRect(transRect);
	boolean touched = transRect.contains(event.getX(), event.getY());
	if (DEBUG) Log.d(TAG, "Area " + area + " touched=" + touched + " at (" + event.getX() + "," +
	       event.getY() + ") rect=" + transRect);
		return touched;
	}

	private boolean pointerTouched(MotionEvent event)
	{
		RectF transRect = new RectF(pointerRect);
		translationMatrix.mapRect(transRect);
		return transRect.contains(event.getX(), event.getY());
	}

	private boolean isInButtonArea(MotionEvent event)
	{
		RectF transRect = new RectF(pointerRect);
		translationMatrix.mapRect(transRect);
		
		float localX = event.getX() - transRect.left;
		float localY = event.getY() - transRect.top;
		
		float width = transRect.width();
		float height = transRect.height();
		
		float buttonTop = height * (80f / 140f);
		float buttonBottom = height;
		float leftBound = width * (10f / 150f);
		float rightBound = width;
		
		return (localY >= buttonTop && localY <= buttonBottom && 
		        localX >= leftBound && localX <= rightBound);
	}

	private boolean touchSequenceStarted = false;

	@Override
	public boolean dispatchTouchEvent(MotionEvent event)
	{
		switch (event.getAction())
		{
			case MotionEvent.ACTION_DOWN:
				if (!isInButtonArea(event))
				{
					touchSequenceStarted = false;
					return false;
				}
				touchSequenceStarted = true;
				break;
				
			case MotionEvent.ACTION_MOVE:
				if (!touchSequenceStarted && !pointerMoving)
				{
					return false;
				}
				break;
				
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				touchSequenceStarted = false;
				break;
		}
		
		return super.dispatchTouchEvent(event);
	}

	@Override public boolean onTouchEvent(MotionEvent event)
	{
		try {
			if (DEBUG) Log.d(TAG, "onTouchEvent: action=" + event.getAction() + " at (" + event.getX() + "," +
			               event.getY() + ")");

			return gestureDetector.onTouchEvent(event);
		} catch (Exception e) {
			Log.e(TAG, "Exception in onTouchEvent", e);
			// 清理所有状态，确保不会卡在中间状态
			pointerMoving = false;
			leftButtonDragging = false;
			touchSequenceStarted = false;
			// 注意：这里不能直接访问内部类的 leftButtonPressed 和 rightButtonPressed
			// 它们会在下次 onDown 时被重置
			return false;
		}
	}

	@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom)
	{
		if (changed)
			ensureVisibility(right - left, bottom - top);
	}

	// touch pointer listener - keep same callbacks for compatibility
	public interface TouchPointerListener {
		void onTouchPointerClose();

		void onTouchPointerLeftClick(Point remoteCoord, boolean down);

		void onTouchPointerRightClick(Point remoteCoord, boolean down);

		void onTouchPointerMove(Point remoteCoord);

		void onTouchPointerScroll(boolean down);

		void onTouchPointerToggleKeyboard();

		void onTouchPointerToggleExtKeyboard();

		void onTouchPointerResetScrollZoom();
	}

	private class UIHandler extends Handler
	{

		UIHandler()
		{
			super();
		}

		@Override public void handleMessage(Message msg)
		{
			// 恢复时也要检查直接触控状态
			updatePointerDrawable();
		if (DEBUG) Log.d(TAG, "UIHandler: restored drawable via updatePointerDrawable()");
	}
	}

	private class TouchPointerGestureListener extends GestureDetector.SimpleOnGestureListener
	{

		private MotionEvent prevEvent = null;
		private boolean leftButtonPressed = false;
		private boolean rightButtonPressed = false;
		private boolean hasMoved = false;

		public boolean onDown(MotionEvent e)
		{
			if (DEBUG) Log.d(TAG, "onDown at (" + e.getX() + "," + e.getY() + ")");

		if (pointerAreaTouched(e, POINTER_ACTION_LCLICK))
		{
		prevEvent = MotionEvent.obtain(e);
		leftButtonPressed = true;
		rightButtonPressed = false;
		hasMoved = false;
		setPointerImage(R.drawable.touch_pointer_simple_lclick);
		if (DEBUG) Log.d(TAG, "Left button pressed (waiting for drag or click)");
		}
		else if (pointerAreaTouched(e, POINTER_ACTION_RCLICK))
		{
			prevEvent = MotionEvent.obtain(e);
			rightButtonPressed = true;
			leftButtonPressed = false;
			hasMoved = false;
			setPointerImage(R.drawable.touch_pointer_simple_rclick);
			if (DEBUG) Log.d(TAG, "Right button pressed (waiting for click)");
		}
			else if (pointerAreaTouched(e, POINTER_ACTION_MOVE))
			{
				prevEvent = MotionEvent.obtain(e);
			pointerMoving = true;
			leftButtonDragging = false;
			leftButtonPressed = false;
			rightButtonPressed = false;
			setPointerImage(R.drawable.touch_pointer_simple_active);
			if (DEBUG) Log.d(TAG, "Move zone activated");
			}
			return true;
		}

public boolean onUp(MotionEvent e)
{
	if (DEBUG) Log.d(TAG, "onUp - leftButtonPressed=" + leftButtonPressed + ", rightButtonPressed=" + rightButtonPressed + ", hasMoved=" + hasMoved + ", pointerMoving=" + pointerMoving);

	if (prevEvent != null)
	{
		prevEvent.recycle();
		prevEvent = null;
	}

	try {
		if (leftButtonPressed && !hasMoved)
		{
			if (listener != null)
			{
				Point remoteCoord = getRemoteCoordinate();
				listener.onTouchPointerLeftClick(remoteCoord, true);
				listener.onTouchPointerLeftClick(remoteCoord, false);
				if (DEBUG) Log.d(TAG, "Left button CLICK (down+up), remote: (" + remoteCoord.x + "," + remoteCoord.y + ")");
			}
		}
		else if (leftButtonDragging)
		{
			if (listener != null)
			{
				Point remoteCoord = getRemoteCoordinate();
				listener.onTouchPointerLeftClick(remoteCoord, false);
				if (DEBUG) Log.d(TAG, "Left button UP after drag, remote: (" + remoteCoord.x + "," + remoteCoord.y + ")");
			}
		}
		else if (rightButtonPressed && !hasMoved)
		{
			if (listener != null)
			{
				Point remoteCoord = getRemoteCoordinate();
				listener.onTouchPointerRightClick(remoteCoord, true);
				listener.onTouchPointerRightClick(remoteCoord, false);
				if (DEBUG) Log.d(TAG, "Right button CLICK (down+up), remote: (" + remoteCoord.x + "," + remoteCoord.y + ")");
			}
		}
	} catch (Exception ex) {
		Log.e(TAG, "Exception in onUp", ex);
	} finally {
		boolean wasLeftDragging = leftButtonDragging;
		boolean wasCenterMoving = pointerMoving && !leftButtonDragging;
		
		pointerMoving = false;
		leftButtonDragging = false;
		leftButtonPressed = false;
		rightButtonPressed = false;
		hasMoved = false;
		updatePointerDrawable();
		
		// 松开左键拖拽或中键移动后，立即检测虚实线（不延迟）
		if (wasLeftDragging || wasCenterMoving)
		{
			detectDashedLine();
		}
	}

	return true;
}

	public void onLongPress(MotionEvent e)
	{
		// 移除中间键长按变左键的功能
	if (DEBUG) Log.d(TAG, "onLongPress - disabled");
}

public void onLongPressUp(MotionEvent e)
{
	if (DEBUG) Log.d(TAG, "onLongPressUp - disabled");
}

	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY)
	{
	// 如果右键按下时移动了，标记为已移动（取消右键点击）
	if (rightButtonPressed && !hasMoved)
	{
		hasMoved = true;
		if (DEBUG) Log.d(TAG, "Right button cancelled due to movement");
		// 不触发右键点击，因为用户移动了手指
		return true;
	}
	
	// 如果左键按下且开始移动，触发拖动
	if (leftButtonPressed && !hasMoved)
	{
		hasMoved = true;
		pointerMoving = true;
		
		if (listener != null)
		{
			leftButtonDragging = true;
			Point remoteCoord = getRemoteCoordinate();
			listener.onTouchPointerLeftClick(remoteCoord, true);
			if (DEBUG) Log.d(TAG, "Left button DOWN for drag, remote: (" + remoteCoord.x + "," + remoteCoord.y + ")");
		}
		else
		{
			if (DEBUG) Log.e(TAG, "Cannot start drag: listener is null");
			pointerMoving = false;
		}
	}
		
		if (pointerMoving)
		{
			movePointer((int)(e2.getX() - prevEvent.getX()),
			            (int)(e2.getY() - prevEvent.getY()));
			prevEvent.recycle();
			prevEvent = MotionEvent.obtain(e2);

			// 移动事件已在movePointer()中发送，此处不再重复发送
			// 避免重复发送导致Windows无法正确识别拖动操作（如Excel表格线拖动）

			return true;
		}
			return false;
		}

	public boolean onSingleTapUp(MotionEvent e)
	{
		if (DEBUG) Log.d(TAG, "onSingleTapUp at (" + e.getX() + "," + e.getY() + ")");

		// 左键和右键单击都已在onUp中处理
		// onSingleTapUp 在 onUp 之前被调用，所以这里不需要重复处理
		// 避免重复触发导致右键菜单闪现问题
		
		return true;
	}

		public boolean onDoubleTap(MotionEvent e)
		{
			// double click removed by design
			return true;
		}
	}

	// 设置光标类型（由SessionActivity调用）
public void setCursorType(int cursorType)
{
	if (DEBUG) Log.i(TAG, "setCursorType called: old=" + currentCursorType + " new=" + cursorType);
	
	if (currentCursorType != cursorType)
	{
		if (DEBUG) Log.i(TAG, "setCursorType: CHANGING " + currentCursorType + " -> " + cursorType);
		currentCursorType = cursorType;
		updatePointerDrawable();
	}
}

	// ==================== 虚实线检测和自动对准功能 ====================

	// 检测虚实线（在鼠标按钮松开后100ms调用）
	// ✅ P1 Bug修复: 添加节流机制，防止过于频繁的检测导致内存压力
	private void detectDashedLine()
	{
		// 节流检查：如果距离上次检测不足300ms，跳过本次检测
		long now = System.currentTimeMillis();
		if (now - lastDetectTime < DETECT_INTERVAL_MS) {
			if (DEBUG) Log.d(TAG, "detectDashedLine: throttled (last detect " + (now - lastDetectTime) + "ms ago)");
			return;
		}
		lastDetectTime = now;
		
		final Point coord = getRemoteCoordinate();
		if (coord == null) return;

		if (sessionActivity == null)
		{
			if (DEBUG) Log.w(TAG, "SessionActivity not set, cannot detect dashed line");
			return;
		}

		final Bitmap bitmap = sessionActivity.getBitmap();
		if (bitmap == null || bitmap.isRecycled())
		{
			if (DEBUG) Log.w(TAG, "Bitmap not available, cannot detect dashed line");
			return;
		}

		// 在后台线程执行两步检测
		new Thread(new Runnable() {
			@Override
			public void run()
			{
				// ========== 第一步：快速预扫描（Y±4像素，左右各5px） ==========
				// 目的：快速检测是否有红绿线，保证最远4像素能检测到
				int quickScanRange = 5;  // 左右各5px（共10px）
				boolean hasRedGreenLine = false;
				int candidateY = -1;
				int minDistanceStep1 = 999;
				
				if (DEBUG) Log.d(TAG, "【第一步】快速预扫描: Y±4像素 × 左右5px (90像素)");
				
				// 扫描9行（Y-4到Y+4）
				for (int dy = -4; dy <= 4; dy++)
				{
					int y = coord.y + dy;
					if (y < 0 || y >= bitmap.getHeight()) continue;
					
					// 只扫描指针左右各5px（共10px）
					if (hasRedGreenPixelsInRange(bitmap, y, coord.x, quickScanRange))
					{
						hasRedGreenLine = true;
						int distance = Math.abs(dy);
						if (distance < minDistanceStep1)
						{
							minDistanceStep1 = distance;
							candidateY = y;
						}
						
						if (DEBUG) Log.d(TAG, "  发现红绿线: Y=" + y + " (距离=" + distance + "px)");
					}
				}
				
				if (!hasRedGreenLine)
				{
					if (DEBUG) Log.d(TAG, "【第一步】未发现红绿线，结束检测");
					// 未找到红绿线，清除旧状态，恢复黑色
					post(new Runnable() {
						@Override
						public void run()
						{
							isOnDashedLine = false;
							dashedLineY = -1;
							updatePointerDrawable();
						}
					});
					return;
				}
				
				if (DEBUG) Log.d(TAG, "【第一步】完成，找到最近红绿线: Y=" + candidateY + " (距离=" + minDistanceStep1 + "px)");
				
				// ========== 第二步：详细判定（Y±2像素，左右100px） ==========
				// 目的：详细判定是否是虚实线模式
				if (DEBUG) Log.d(TAG, "【第二步】详细判定: 以Y=" + candidateY + "为中心，Y±2像素 × 左右100px (1000像素)");
				
				int foundY = -1;
				int minDistanceStep2 = 999;
				
				// 以candidateY为中心，扫描Y±2像素（5行）
				for (int dy = -2; dy <= 2; dy++)
				{
					int y = candidateY + dy;
					if (y < 0 || y >= bitmap.getHeight()) continue;
					
					// 详细检测：全范围（左右100px），判定是否是虚实线
					if (isDashedLineAt(bitmap, y, coord.x))
					{
						// 计算相对于指针的距离（而不是相对于candidateY）
						int distanceFromPointer = Math.abs(y - coord.y);
						if (distanceFromPointer < minDistanceStep2)
						{
							minDistanceStep2 = distanceFromPointer;
							foundY = y;
						}
						
						if (DEBUG) Log.d(TAG, "  确认虚实线: Y=" + y + " (距指针=" + distanceFromPointer + "px)");
					}
				}
				
				// 找到了虚实线，自动移动到最近的虚实线
				if (foundY != -1)
				{
					final int targetY = foundY;
					if (DEBUG) Log.d(TAG, "【第二步】完成，确认虚实线: Y=" + targetY + " (距指针=" + minDistanceStep2 + "px)");
					if (DEBUG) Log.d(TAG, "✅ 自动移动指针到虚实线: Y=" + targetY);
					
					post(new Runnable() {
						@Override
						public void run()
						{
							moveToTarget(coord.x, targetY);
						}
					});
				}
				else
				{
					if (DEBUG) Log.d(TAG, "【第二步】有红绿线但不是虚实线，清除状态");
					// 有红绿线但不是虚实线，清除旧状态
					post(new Runnable() {
						@Override
						public void run()
						{
							isOnDashedLine = false;
							dashedLineY = -1;
							updatePointerDrawable();
						}
					});
				}
			}
		}).start();
	}

	// 第一步：快速预扫描 - 检查指定行的小范围内是否有红绿像素
	// 用于Y±4像素（9行）× 左右5px（10px）的快速检测
	private boolean hasRedGreenPixelsInRange(Bitmap bitmap, int y, int centerX, int range)
	{
		int leftBound = Math.max(0, centerX - range);
		int rightBound = Math.min(bitmap.getWidth(), centerX + range);
		int width = rightBound - leftBound;
		
		if (width <= 0) return false;
		
		// 读取像素
		int[] pixels = new int[width];
		try
		{
			bitmap.getPixels(pixels, 0, width, leftBound, y, width, 1);
		}
		catch (Exception e)
		{
			return false;
		}
		
		// 检查是否有红色或绿色像素
		for (int pixel : pixels)
		{
			if (isTargetColorPixel(pixel))
			{
				return true;  // 发现红绿线，立即返回
			}
		}
		
		return false;
	}
	
	// 第二步：详细检测 - 检查某一行是否有虚实线（在指针左右100px范围内检测）
	private boolean isDashedLineAt(Bitmap bitmap, int y, int centerX)
	{
		// 改进(1): 使用固定范围：指针左右100px（缩小检测范围提高精度）
		int detectionRange = 100;
		int leftBound = Math.max(0, centerX - detectionRange);
		int rightBound = Math.min(bitmap.getWidth(), centerX + detectionRange);
		
		if (checkSingleLineAt(bitmap, y, leftBound, rightBound, centerX))
		{
			return true;
		}
		
		return false;
	}
	
	// 改进(3): 检查单行是否有虚实线模式（增加左右线段>=3px验证）
	private boolean checkSingleLineAt(Bitmap bitmap, int y, int leftBound, int rightBound, int centerX)
	{
		// 改进(3): 左右线段验证 - 必须两边都有>=3px线段才是虚实线
		boolean leftHasSegment = hasColorSegment(bitmap, y, leftBound, centerX);
		boolean rightHasSegment = hasColorSegment(bitmap, y, centerX, rightBound);
		
		// 必须两边都有线段，才继续检测（只有一边或两边都没有则排除）
		if (!leftHasSegment || !rightHasSegment) return false;
		
		// 预检查：排除有>30px长线段的情况（可能是直线，不是虚实线）
		if (hasVeryLongSegment(bitmap, y, leftBound, rightBound)) return false;
		
		// 两边都有线段且无长断开/长线段，继续详细检测
		int startX = leftBound;
		int endX = rightBound;
		int width = endX - startX;

		if (width <= 0) return false;
		
		if (centerX < startX || centerX > endX)
		{
			return false;
		}

		// 改进(4)(5): 读取多行数据用于竖线检测
		int[][] multiRowPixels = new int[5][];
		int validRows = 0;
		
		for (int dy = -2; dy <= 2; dy++)
		{
			int rowY = y + dy;
			if (rowY < 0 || rowY >= bitmap.getHeight()) continue;
			
			int rowIndex = dy + 2;
			multiRowPixels[rowIndex] = new int[width];
			try
			{
				bitmap.getPixels(multiRowPixels[rowIndex], 0, width, startX, rowY, width, 1);
				validRows++;
			}
			catch (Exception e)
			{
				multiRowPixels[rowIndex] = null;
			}
		}
		
		if (validRows < 3) return false;

		return hasLongShortPattern(multiRowPixels, width, y, centerX, startX);
	}
	
	// 辅助方法：检查是否有过长的线段(>30px)，用于快速排除直线
	private boolean hasVeryLongSegment(Bitmap bitmap, int y, int startX, int endX)
	{
		int width = endX - startX;
		if (width < 3) return false;
		
		int[] pixels = new int[width];
		try
		{
			bitmap.getPixels(pixels, 0, width, startX, y, width, 1);
		}
		catch (Exception e)
		{
			return false;
		}
		
		// 分段统计，只检查线段长度
		boolean isTarget = isTargetColorPixel(pixels[0]);
		int segmentLength = 1;
		
		for (int i = 1; i < pixels.length; i++)
		{
			boolean currentTarget = isTargetColorPixel(pixels[i]);
			
			if (currentTarget == isTarget)
			{
				// 同类型，累加长度
				segmentLength++;
			}
			else
			{
				// 段改变，只检查线段（深色）是否过长
				if (isTarget && segmentLength > 30)
				{
					return true;  // 有>30px的线段，可能是直线
				}
				// 断开（浅色）不检查，因为难以准确测量
				
				// 开始新段
				isTarget = currentTarget;
				segmentLength = 1;
			}
		}
		
		// 检查最后一段
		if (isTarget && segmentLength > 30)
		{
			return true;
		}
		
		return false;
	}
	
	// 辅助方法：检查某个范围是否有 ≥3px 的红/绿线段
	private boolean hasColorSegment(Bitmap bitmap, int y, int startX, int endX)
	{
		int width = endX - startX;
		if (width < 3) return false;
		
		int[] pixels = new int[width];
		try
		{
			bitmap.getPixels(pixels, 0, width, startX, y, width, 1);
		}
		catch (Exception e)
		{
			return false;
		}
		
		int consecutiveCount = 0;
		for (int pixel : pixels)
		{
			if (isTargetColorPixel(pixel))
			{
				consecutiveCount++;
				if (consecutiveCount >= 3)
				{
					return true;
				}
			}
			else
			{
				consecutiveCount = 0;
			}
		}
		
		return false;
	}
	
	// 改进(4)(5): 合并竖线检测 + 改进(6): 断开范围3-9px
	private boolean hasLongShortPattern(int[][] multiRowPixels, int width, int centerY, int centerX, int startX)
	{
		if (width < 20) return false;
		
	// ============ 阶段1：预扫描 - 标记竖线区域（改进4+5） ============
	boolean[] excludeX = new boolean[width];  // 标记要排除的X坐标
	
	// 中心行索引是2（对应Y坐标）
	int centerRowIndex = 2;
	
	// 获取中心行的像素数据（用于检测水平交叉线）
	if (multiRowPixels[centerRowIndex] == null) return false;
	int[] centerPixels = multiRowPixels[centerRowIndex];
	
	for (int x = 0; x < width; x++)
	{
		// ====== 新增：检测中心行的交叉线（水平交叉） ======
		// 如果该像素既不是目标色（红/绿），也不是背景色 → 交叉线（如白色线条）
		if (x < centerPixels.length)
		{
			int centerPixel = centerPixels[x];
			if (!isTargetColorPixel(centerPixel) && !isBackgroundColor(centerPixel))
			{
				// 标记为排除区域，既不算线段也不算断开
				excludeX[x] = true;
				continue;  // 跳过后续的垂直交叉线检测
			}
		}
		// ================================================
		
		int coloredRowCountAbove = 0;  // 统计虚实线上方有多少行有颜色
		int coloredRowCountBelow = 0;  // 统计虚实线下方有多少行有颜色
			
			// 扫描上方2行（Y-2到Y-1，对应索引0-1）
			for (int rowIndex = 0; rowIndex < centerRowIndex; rowIndex++)
			{
				if (multiRowPixels[rowIndex] == null) continue;
				if (x >= multiRowPixels[rowIndex].length) continue;
				
				int pixel = multiRowPixels[rowIndex][x];
				
				// 任何非背景色都算（不区分红、绿还是其他颜色）
				if (!isBackgroundColor(pixel))
				{
					coloredRowCountAbove++;
				}
			}
			
			// 扫描下方2行（Y+1到Y+2，对应索引3-4）
			for (int rowIndex = centerRowIndex + 1; rowIndex < 5; rowIndex++)
			{
				if (multiRowPixels[rowIndex] == null) continue;
				if (x >= multiRowPixels[rowIndex].length) continue;
				
				int pixel = multiRowPixels[rowIndex][x];
				
				// 任何非背景色都算（不区分红、绿还是其他颜色）
				if (!isBackgroundColor(pixel))
				{
					coloredRowCountBelow++;
				}
			}
			
			// 改进(2): 必须上下都有才判定为竖线/交叉线
			// 上方至少1行 且 下方至少1行 → 判定为竖线
			if (coloredRowCountAbove >= 1 && coloredRowCountBelow >= 1)
			{
				// 标记该位置及其±3px为排除区域（因为可能有>3px粗）
				for (int dx = Math.max(0, x-3); dx <= Math.min(width-1, x+3); dx++)
				{
					excludeX[dx] = true;
				}
			}
			// 如果只是上方有或只是下方有，不排除，当作正常线段或断开统计
		}
		
		// ============ 阶段2：分段统计 - 跳过排除区域 ============
		// 使用中心行（索引2）的像素数据
		if (multiRowPixels[centerRowIndex] == null) return false;
		int[] pixels = multiRowPixels[centerRowIndex];
		
		List<Segment> segments = new ArrayList<>();
		boolean isTarget = false;
		int segmentStart = -1;
		
		for (int x = 0; x < width; x++)
		{
			if (excludeX[x])
			{
				// 遇到排除区域，先保存之前的段
				if (segmentStart != -1)
				{
					int segmentLength = x - segmentStart;
					segments.add(new Segment(isTarget, segmentLength, segmentStart));
					segmentStart = -1;
				}
				continue;  // 跳过该像素，既不算线段也不算断开
			}
			
			// 正常统计
			boolean currentTarget = isTargetColorPixel(pixels[x]);
			
			if (segmentStart == -1)
			{
				// 开始新段
				isTarget = currentTarget;
				segmentStart = x;
			}
			else if (currentTarget != isTarget)
			{
				// 段改变
				int segmentLength = x - segmentStart;
				segments.add(new Segment(isTarget, segmentLength, segmentStart));
				isTarget = currentTarget;
				segmentStart = x;
			}
		}
		
		// 保存最后一段
		if (segmentStart != -1)
		{
			segments.add(new Segment(isTarget, width - segmentStart, segmentStart));
		}
		
		if (segments.size() < 3) return false;
		
		// ============ 阶段3：模式判断 ============
		int lineSegmentCount = 0;
		int gapCount = 0;
		int maxLineLength = 0;
		
		// 步骤1：统计所有线段和断开
		for (Segment seg : segments)
		{
			if (seg.isDark)
			{
				// 修改：有效线段范围3-15px
				if (seg.length >= 3 && seg.length <= 15)
				{
					lineSegmentCount++;
					if (seg.length > maxLineLength)
					{
						maxLineLength = seg.length;
					}
				}
			}
			else
			{
				// 断开范围3-9px
				if (seg.length >= 3 && seg.length <= 9)
				{
					gapCount++;
				}
			}
		}
		
		// 步骤2：找到第一个和最后一个有效线段的索引位置
		int firstLineIndex = -1;
		int lastLineIndex = -1;
		
		for (int i = 0; i < segments.size(); i++)
		{
			Segment seg = segments.get(i);
			if (seg.isDark && seg.length >= 3)  // 有效线段
			{
				if (firstLineIndex == -1)
				{
					firstLineIndex = i;  // 记录第一个线段位置
				}
				lastLineIndex = i;  // 持续更新最后一个线段位置
			}
		}
		
		// 步骤3：只检查"中间范围"（第一个线段到最后一个线段之间）是否有>=20px的背景色断开
		if (firstLineIndex != -1 && lastLineIndex != -1 && lastLineIndex > firstLineIndex)
		{
			for (int i = firstLineIndex + 1; i < lastLineIndex; i++)
			{
				Segment seg = segments.get(i);
				
				// 只检查断开段
				if (!seg.isDark && seg.length >= 20)
				{
					// 检查该断开段是否都是背景色
					boolean allBackground = true;
					for (int j = 0; j < seg.length; j++)
					{
						int pixelIndex = seg.startX + j;
						if (pixelIndex >= 0 && pixelIndex < pixels.length)
						{
							if (!isBackgroundColor(pixels[pixelIndex]))
							{
								allBackground = false;
								break;
							}
						}
					}
					
					if (allBackground)
					{
						// 虚实线中间有>=20px背景色断开，不是虚实线
						if (DEBUG)
						{
							Log.v(TAG, "✗ 排除: 虚实线中间检测到" + seg.length + "px背景色断开");
						}
						return false;
					}
				}
			}
		}
		
		// 判定条件：必须同时满足
		// - lineSegmentCount >= 2：至少2个有效线段（3-15px的深色段）
		// - gapCount >= 2：至少2个断开（3-9px的浅色段）
		boolean hasPattern = (lineSegmentCount >= 2) && (gapCount >= 2);
		
		if (DEBUG)
		{
			if (hasPattern)
			{
				Log.i(TAG, "✓ 虚实线检测成功: " + lineSegmentCount + "个线段(3-15px), " + gapCount + "个断开(3-9px), 最长线段=" + maxLineLength + "px");
			}
			else
			{
				Log.v(TAG, "✗ 检测失败: 线段=" + lineSegmentCount + ", 断开=" + gapCount + ", 最长线段=" + maxLineLength + "px");
			}
		}
		
		return hasPattern;
	}
	
	// 判断是否是背景色（用于竖线检测）
	// 优化版：针对DarkGray/Gray/SlateGray三种特定背景
	private boolean isBackgroundColor(int pixel)
	{
		int r = (pixel >> 16) & 0xFF;
		int g = (pixel >> 8) & 0xFF;
		int b = pixel & 0xFF;
		
		// 允许的色差容差（考虑抗锯齿和颜色渐变）
		int tolerance = 25;
		
		// 方案1: DarkGray (169, 169, 169) - 中等灰色
		// 范围: R、G、B 都在 144-194 之间
		if (Math.abs(r - 169) <= tolerance && 
		    Math.abs(g - 169) <= tolerance && 
		    Math.abs(b - 169) <= tolerance)
		{
			// 进一步验证：RGB差值应该很小（纯灰色）
			int maxDiff = Math.max(Math.max(Math.abs(r-g), Math.abs(g-b)), Math.abs(r-b));
			if (maxDiff <= 25)
			{
				return true;
			}
		}
		
		// 方案2: Gray (128, 128, 128) - 标准灰色
		// 范围: R、G、B 都在 103-153 之间
		if (Math.abs(r - 128) <= tolerance && 
		    Math.abs(g - 128) <= tolerance && 
		    Math.abs(b - 128) <= tolerance)
		{
			int maxDiff = Math.max(Math.max(Math.abs(r-g), Math.abs(g-b)), Math.abs(r-b));
			if (maxDiff <= 25)
			{
				return true;
			}
		}
		
		// 方案3: SlateGray (112, 128, 144) - 蓝灰色（特殊处理）
		// 这是一个偏蓝的灰色，需要更精确的匹配
		if (Math.abs(r - 112) <= tolerance && 
		    Math.abs(g - 128) <= tolerance && 
		    Math.abs(b - 144) <= tolerance)
		{
			// SlateGray的RGB分布有规律：R < G < B
			// 验证这个渐变关系（允许一定误差）
			if (r <= g + 20 && g <= b + 20)
			{
				return true;
			}
		}
		
		return false;
	}
	
	// 旧方法签名兼容（已废弃，但保留以防其他地方调用）
	@Deprecated
	private boolean hasLongShortPattern(int[] pixels)
	{
		if (pixels.length < 20) return false;
		
		List<Segment> segments = new ArrayList<>();
		
		boolean isTarget = isTargetColorPixel(pixels[0]);
		int start = 0;
		
		for (int i = 1; i < pixels.length; i++)
		{
			boolean currentTarget = isTargetColorPixel(pixels[i]);
			if (currentTarget != isTarget)
			{
				segments.add(new Segment(isTarget, i - start, start));
				start = i;
				isTarget = currentTarget;
			}
		}
		segments.add(new Segment(isTarget, pixels.length - start, start));
		
		if (segments.size() < 3) return false;
		
		int lineSegmentCount = 0;
		int gapCount = 0;
		int maxLineLength = 0;
		int longSegmentCount = 0;
		
		for (Segment seg : segments)
		{
			if (seg.isDark)
			{
				if (seg.length >= 3)
				{
					lineSegmentCount++;
					if (seg.length > maxLineLength)
					{
						maxLineLength = seg.length;
					}
					
					if (seg.length >= 5 && seg.length <= 15)
					{
						longSegmentCount++;
					}
				}
			}
			else
			{
				// 改进(6): 断开范围从3-7px改为3-9px（兼容旧方法）
				if (seg.length >= 3 && seg.length <= 9)
				{
					gapCount++;
				}
			}
		}
		
		boolean hasPattern = (lineSegmentCount >= 3) && (gapCount >= 2) && (longSegmentCount >= 1);
		
		if (DEBUG)
		{
			if (hasPattern)
			{
				Log.i(TAG, "✓ 虚实线检测成功: " + lineSegmentCount + "个线段, " + longSegmentCount + "个长段(5-15px), " + gapCount + "个断开, 最长线段=" + maxLineLength + "px");
			}
			else
			{
				Log.v(TAG, "✗ 检测失败: 线段=" + lineSegmentCount + ", 长段=" + longSegmentCount + ", 断开=" + gapCount + ", 最长线段=" + maxLineLength + "px");
			}
		}
		
		return hasPattern;
	}
	
	// 辅助类：表示一个段（深色或浅色）
	private static class Segment
	{
		boolean isDark;
		int length;
		int startX;  // 记录起始位置（相对于width的偏移）
		
		Segment(boolean isDark, int length, int startX)
		{
			this.isDark = isDark;
			this.length = length;
			this.startX = startX;
		}
	}

	// 判断是否是目标颜色像素（Lime绿色或Red红色）
	private boolean isTargetColorPixel(int pixel)
	{
		int r = (pixel >> 16) & 0xFF;
		int g = (pixel >> 8) & 0xFF;
		int b = pixel & 0xFF;
		
		// 检测 Lime 绿色 (接近 #00FF00)
		boolean isLime = (g > 180) && (r < 120) && (b < 120);
		
		// 检测 Red 红色 (接近 #FF0000)
		boolean isRed = (r > 180) && (g < 120) && (b < 120);
		
		return isLime || isRed;
	}
	
	// 旧的深色检测函数（保留作为内部使用）
	private boolean isDarkPixel(int pixel)
	{
		int r = (pixel >> 16) & 0xFF;
		int g = (pixel >> 8) & 0xFF;
		int b = pixel & 0xFF;
		int brightness = (r + g + b) / 3;
		return brightness < 100;
	}

	// 反向函数：根据目标远程坐标，精确设置指针位置（一步到位）
	private boolean setRemoteCoordinate(int targetRemoteX, int targetRemoteY)
	{
		try {
			if (sessionView == null)
			{
				Log.w(TAG, "SessionView not set");
				return false;
			}

			android.graphics.drawable.Drawable drawable = getDrawable();
			if (drawable == null)
			{
				Log.w(TAG, "Drawable is null");
				return false;
			}

			// 1. 获取缩放矩阵（与 getRemoteCoordinate 使用的 invScaleMatrix 相反）
			android.graphics.Matrix scaleMatrix = sessionView.getScaleMatrix();
			if (scaleMatrix == null)
			{
				Log.w(TAG, "Scale matrix is null");
				return false;
			}

			// 2. 远程坐标通过 scaleMatrix 转换为 SessionView 坐标
			float[] targetRemote = {targetRemoteX, targetRemoteY};
			float[] targetView = new float[2];
			scaleMatrix.mapPoints(targetView, targetRemote);

			// 3. 获取两个 View 在屏幕上的位置
			int[] sessionViewLocation = new int[2];
			sessionView.getLocationOnScreen(sessionViewLocation);

			int[] touchPointerLocation = new int[2];
			getLocationOnScreen(touchPointerLocation);

			// 4. 转换为相对于 TouchPointerView 的坐标
			float tipX = targetView[0] - (touchPointerLocation[0] - sessionViewLocation[0]);
			float tipY = targetView[1] - (touchPointerLocation[1] - sessionViewLocation[1]);

			// 5. 减去箭头尖端偏移，得到 TouchPointerView 的左上角位置
			float drawableWidth = (float)drawable.getIntrinsicWidth();
			float drawableHeight = (float)drawable.getIntrinsicHeight();
			float tipXRatio = 20f / 150f;
			float tipYRatio = 25f / 140f;

			float pointerX = tipX - (drawableWidth * tipXRatio);
			float pointerY = tipY - (drawableHeight * tipYRatio);

			// 6. 设置位置 - 一步到位！
			translationMatrix.setTranslate(pointerX, pointerY);
			setImageMatrix(translationMatrix);

			if (DEBUG) Log.i(TAG, String.format("一步到位：远程(%d,%d) → 屏幕(%.1f,%.1f)", 
				targetRemoteX, targetRemoteY, pointerX, pointerY));

			return true;
		} catch (Exception e) {
			Log.e(TAG, "Exception in setRemoteCoordinate", e);
			return false;
		}
	}

	// 移动到目标位置（使用精确坐标转换 - 一步到位）
	private void moveToTarget(final int targetX, final int targetY)
	{
		final Point current = getRemoteCoordinate();
		if (current == null) return;

		// 计算距离
		final int deltaY = Math.abs(targetY - current.y);
		
		if (deltaY < 1)
		{
			// 已经在目标位置
			if (DEBUG) Log.d(TAG, "目标位置已达到，无需移动");
			dashedLineY = targetY;
			isOnDashedLine = true;
			if (DEBUG) Log.i(TAG, "设置虚实线状态（无需移动）: isOnDashedLine=true");
			updatePointerDrawable();
			return;
		}

		if (DEBUG) Log.i(TAG, "开始移动: 当前Y=" + current.y + " → 目标Y=" + targetY + ", 距离=" + deltaY + "px");

		// 使用精确坐标转换 - 一步到位
		isAutoMoving = true;
		
		// 平滑动画（视觉效果）
		final Point startRemote = new Point(current.x, current.y);
		ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
		animator.setDuration(150);  // 缩短动画时间
		animator.setInterpolator(new DecelerateInterpolator());

		animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation)
			{
				float t = animation.getAnimatedFraction();
				int intermediateY = (int)(startRemote.y + (targetY - startRemote.y) * t);
				setRemoteCoordinate(targetX, intermediateY);
			}
		});

		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation)
			{
				setRemoteCoordinate(targetX, targetY);
				isAutoMoving = false;
				
				if (listener != null)
				{
					Point finalCoord = getRemoteCoordinate();
					listener.onTouchPointerMove(finalCoord);
				}
				
				dashedLineY = targetY;
				isOnDashedLine = true;
				updatePointerDrawable();
			}
		});

		animator.start();
	}

	private void updatePointerDrawable()
	{
		if (isOnDashedLine)
		{
			currentBaseDrawable = R.drawable.touch_pointer_simple_default_resize;
		}
		else
		{
			currentBaseDrawable = R.drawable.touch_pointer_simple_default;
		}
		
		setImageResource(currentBaseDrawable);
		setImageMatrix(translationMatrix);
	}
	
	public void clearDashedLineState()
	{
		isOnDashedLine = false;
		dashedLineY = -1;
		updatePointerDrawable();
	}
}
 