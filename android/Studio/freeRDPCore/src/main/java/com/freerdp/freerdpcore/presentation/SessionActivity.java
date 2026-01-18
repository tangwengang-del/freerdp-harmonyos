/*
   Android Session Activity

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.presentation;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ZoomControls;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ConnectionReference;
import com.freerdp.freerdpcore.domain.ManualBookmark;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.services.RdpAudioService;
import com.freerdp.freerdpcore.utils.ClipboardManagerProxy;
import com.freerdp.freerdpcore.utils.KeyboardMapper;
import com.freerdp.freerdpcore.utils.Mouse;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionActivity extends AppCompatActivity
    implements LibFreeRDP.UIEventListener, KeyboardView.OnKeyboardActionListener,
               ScrollView2D.ScrollView2DListener, KeyboardMapper.KeyProcessingListener,
               SessionView.SessionViewListener, TouchPointerView.TouchPointerListener,
               ClipboardManagerProxy.OnClipboardChangedListener
{
	public static final String PARAM_CONNECTION_REFERENCE = "conRef";
	public static final String PARAM_INSTANCE = "instance";
	private static final float ZOOMING_STEP = 0.5f;
	private static final int ZOOMCONTROLS_AUTOHIDE_TIMEOUT = 4000;
	// timeout between subsequent scrolling requests when the touch-pointer is
	// at the edge of the session view
	private static final int SCROLLING_TIMEOUT = 50;
	private static final int SCROLLING_DISTANCE = 20;
	private static final String TAG = "FreeRDP.SessionActivity";
	private static final boolean DEBUG_MOUSE = false;
	private static final int MAX_DISCARDED_MOVE_EVENTS = 3;
	private static final int SEND_MOVE_EVENT_TIMEOUT = 150;
	private static final int PTR_MOVE = 0x0800;
	private Bitmap bitmap;
	private SessionState session;
	private SessionView sessionView;
	private TouchPointerView touchPointerView;
	private ProgressDialog progressDialog;
	private KeyboardView keyboardView;
	private KeyboardView modifiersKeyboardView;
	private ZoomControls zoomControls;
	private KeyboardMapper keyboardMapper;

	// ALT key state tracking (uses LOCKED mode for reliability)
	private boolean isAltKeyActive = false;
	private MenuItem altKeyMenuItem = null;
	private Handler altKeyStateCheckHandler = new Handler();
	private Runnable altKeyStateCheckTask = null;
	
	// ALT key keep-alive mechanism (ensures Alt stays active on RDP server)
	private Handler altKeyKeepAliveHandler = new Handler();
	private Runnable altKeyKeepAliveTask = null;
	private static final long ALT_KEEPALIVE_INTERVAL = 150; // Send every 150ms

	// Default zoom flag - only set once on first connection
	private boolean hasSetDefaultZoom = false;

	private Keyboard specialkeysKeyboard;
	private Keyboard numpadKeyboard;
	private Keyboard cursorKeyboard;
	private Keyboard modifiersKeyboard;

	private AlertDialog dlgVerifyCertificate;
	private AlertDialog dlgUserCredentials;
	private View userCredView;

	private UIHandler uiHandler;

	private int screen_width;
	private int screen_height;

	private boolean connectCancelledByUser = false;
	private boolean sessionRunning = false;
	private boolean manualDisconnect = false; // Flag for manual disconnect (no auto-reconnect)
	private boolean toggleMouseButtons = false;
	// ✅ Bug修复 #7: 添加destroyed标志，用于可靠检测Activity生命周期（兼容所有API级别）
	private volatile boolean isActivityDestroyed = false;
	// ✅ Bug修复 #15: 添加对话框显示标志，防止onResume时关闭正在显示对话框的Activity
	private volatile boolean kickedOutDialogShowing = false;

	// WakeLock for background connection stability (fallback if foreground service fails)
	private android.os.PowerManager.WakeLock wakeLock = null;

	private LibFreeRDPBroadcastReceiver libFreeRDPBroadcastReceiver;

	// 提供Bitmap访问接口供TouchPointerView使用（用于虚实线检测）
	public Bitmap getBitmap()
	{
		return bitmap;
	}
	private ScrollView2D scrollView;
	// keyboard visibility flags
	private boolean sysKeyboardVisible = false;
	private boolean extKeyboardVisible = false;
	private int discardedMoveEvents = 0;
	private ClipboardManagerProxy mClipboardManager;
	private boolean callbackDialogResult;
	View mDecor;
	
	// ===== Session/Activity recovery state (used by ServiceRestartReceiver) =====
	private static final String RDP_STATE_PREFS = "rdp_state";
	private static final long ACTIVITY_HEARTBEAT_INTERVAL_MS = 2000;
	private final Handler activityHeartbeatHandler = new Handler();
	private Runnable activityHeartbeatTask = null;

	private void createDialogs()
	{
		// build verify certificate dialog
		dlgVerifyCertificate =
		    new AlertDialog.Builder(this)
		        .setTitle(R.string.dlg_title_verify_certificate)
		        .setPositiveButton(android.R.string.yes,
		                           new DialogInterface.OnClickListener() {
			                           @Override
			                           public void onClick(DialogInterface dialog, int which)
			                           {
				                           callbackDialogResult = true;
				                           synchronized (dialog)
				                           {
					                           dialog.notify();
				                           }
			                           }
		                           })
		        .setNegativeButton(android.R.string.no,
		                           new DialogInterface.OnClickListener() {
			                           @Override
			                           public void onClick(DialogInterface dialog, int which)
			                           {
				                           callbackDialogResult = false;
				                           connectCancelledByUser = true;
				                           synchronized (dialog)
				                           {
					                           dialog.notify();
				                           }
			                           }
		                           })
		        .setCancelable(false)
		        .create();

		// build the dialog
		userCredView = getLayoutInflater().inflate(R.layout.credentials, null, true);
		dlgUserCredentials =
		    new AlertDialog.Builder(this)
		        .setView(userCredView)
		        .setTitle(R.string.dlg_title_credentials)
		        .setPositiveButton(android.R.string.ok,
		                           new DialogInterface.OnClickListener() {
			                           @Override
			                           public void onClick(DialogInterface dialog, int which)
			                           {
				                           callbackDialogResult = true;
				                           synchronized (dialog)
				                           {
					                           dialog.notify();
				                           }
			                           }
		                           })
		        .setNegativeButton(android.R.string.cancel,
		                           new DialogInterface.OnClickListener() {
			                           @Override
			                           public void onClick(DialogInterface dialog, int which)
			                           {
				                           callbackDialogResult = false;
				                           connectCancelledByUser = true;
				                           synchronized (dialog)
				                           {
					                           dialog.notify();
				                           }
			                           }
		                           })
		        .setCancelable(false)
		        .create();
	}

	private void updateActivityState(String state)
	{
		try
		{
			getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
			    .edit()
			    .putString("activity_state", state)
			    .putLong("activity_last_heartbeat", System.currentTimeMillis())
			    .apply();
		}
		catch (Exception e)
		{
			Log.w(TAG, "Failed to persist activity_state=" + state, e);
		}
	}

	private void startActivityHeartbeat()
	{
		if (activityHeartbeatTask != null)
			return;

		activityHeartbeatTask = new Runnable() {
			@Override
			public void run()
			{
				// ✅ Bug修复 #8: 使用isActivityDestroyed标志（兼容所有API）
				if (isActivityDestroyed || isFinishing())
					return;
				updateActivityState("ready");
				activityHeartbeatHandler.postDelayed(this, ACTIVITY_HEARTBEAT_INTERVAL_MS);
			}
		};

		activityHeartbeatHandler.postDelayed(activityHeartbeatTask, ACTIVITY_HEARTBEAT_INTERVAL_MS);
	}

	private void stopActivityHeartbeat()
	{
		if (activityHeartbeatTask != null)
		{
			activityHeartbeatHandler.removeCallbacks(activityHeartbeatTask);
			activityHeartbeatTask = null;
		}
	}

	private boolean hasHardwareMenuButton()
	{
		if (Build.VERSION.SDK_INT <= 10)
			return true;

		if (Build.VERSION.SDK_INT >= 14)
		{
			boolean rc = false;
			final ViewConfiguration cfg = ViewConfiguration.get(this);

			return cfg.hasPermanentMenuKey();
		}

		return false;
	}

	@Override public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		hasSetDefaultZoom = false;

		final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable ex) {
				try {
					int arch = 32;
					if (Build.VERSION.SDK_INT >= 23) {
						arch = android.os.Process.is64Bit() ? 64 : 32;
					} else {
						String abi = Build.CPU_ABI;
						if (abi != null && (abi.contains("64") || abi.contains("arm64") || abi.contains("x86_64"))) {
							arch = 64;
						}
					}
					
					SharedPreferences prefs = getSharedPreferences("rdp_state", MODE_PRIVATE);
					prefs.edit()
						.putBoolean("crash_detected", true)
						.putLong("crash_time", System.currentTimeMillis())
						.putString("crash_thread", thread.getName())
						.putString("crash_info", ex.toString())
						.putInt("crash_arch", arch)
						.apply();
					
					if (session != null) {
						try {
							LibFreeRDP.disconnect(session.getInstance());
						} catch (Exception ignored) {}
					}
				} catch (Exception ignored) {}
				
				if (defaultHandler != null) {
					defaultHandler.uncaughtException(thread, ex);
				}
			}
		});

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
	{
		// Android 5.0+ 使用现代API设置透明系统栏
		Window window = getWindow();
		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
		window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
	}
	else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
	{
		// Android 4.4 使用半透明标志
		Window window = getWindow();
		window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
		               WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
		               WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
	}

		this.setContentView(R.layout.session);
		if (hasHardwareMenuButton() || ApplicationSettingsActivity.getHideActionBar(this))
		{
			this.getSupportActionBar().hide();
		}
		else
		{
			this.getSupportActionBar().show();
			// Hide ActionBar title text (remove "aFreeRDP" text)
			this.getSupportActionBar().setDisplayShowTitleEnabled(false);
		}

		Log.v(TAG, "Session.onCreate");

		// ATTENTION: We use the onGlobalLayout notification to start our
		// session.
		// This is because only then we can know the exact size of our session
		// when using fit screen
		// accounting for any status bars etc. that Android might throws on us.
		// A bit weird looking
		// but this is the only way ...
		final View activityRootView = findViewById(R.id.session_root_view);
		activityRootView.getViewTreeObserver().addOnGlobalLayoutListener(
		    new OnGlobalLayoutListener() {
			    @Override public void onGlobalLayout()
			    {
				    screen_width = activityRootView.getWidth();
				    screen_height = activityRootView.getHeight();

				    // start session
				    if (!sessionRunning && getIntent() != null)
				    {
					    processIntent(getIntent());
					    sessionRunning = true;
				    }
			    }
		    });

		sessionView = findViewById(R.id.sessionView);
		sessionView.setScaleGestureDetector(
		    new ScaleGestureDetector(this, new PinchZoomListener()));
		sessionView.setSessionViewListener(this);
		sessionView.requestFocus();

		touchPointerView = findViewById(R.id.touchPointerView);
		touchPointerView.setTouchPointerListener(this);
		touchPointerView.setSessionView(sessionView);
		touchPointerView.setSessionActivity(this);
		sessionView.setTouchPointerView(touchPointerView);

		keyboardMapper = new KeyboardMapper();
		keyboardMapper.init(this);
		keyboardMapper.reset(this);

		modifiersKeyboard = new Keyboard(getApplicationContext(), R.xml.modifiers_keyboard);
		specialkeysKeyboard = new Keyboard(getApplicationContext(), R.xml.specialkeys_keyboard);
		numpadKeyboard = new Keyboard(getApplicationContext(), R.xml.numpad_keyboard);
		cursorKeyboard = new Keyboard(getApplicationContext(), R.xml.cursor_keyboard);

		// hide keyboard below the sessionView
		keyboardView = findViewById(R.id.extended_keyboard);
		keyboardView.setKeyboard(specialkeysKeyboard);
		keyboardView.setOnKeyboardActionListener(this);
		keyboardView.setPreviewEnabled(false); // 禁用按键预览弹窗

		modifiersKeyboardView = findViewById(R.id.extended_keyboard_header);
		modifiersKeyboardView.setKeyboard(modifiersKeyboard);
		modifiersKeyboardView.setOnKeyboardActionListener(this);
		modifiersKeyboardView.setPreviewEnabled(false); // 禁用按键预览弹窗

		scrollView = findViewById(R.id.sessionScrollView);
		scrollView.setScrollViewListener(this);
		uiHandler = new UIHandler();
		libFreeRDPBroadcastReceiver = new LibFreeRDPBroadcastReceiver();

		zoomControls = findViewById(R.id.zoomControls);
		zoomControls.hide();
		zoomControls.setOnZoomInClickListener(new View.OnClickListener() {
			@Override public void onClick(View v)
			{
				resetZoomControlsAutoHideTimeout();
				zoomControls.setIsZoomInEnabled(sessionView.zoomIn(ZOOMING_STEP));
				zoomControls.setIsZoomOutEnabled(true);
			}
		});
		zoomControls.setOnZoomOutClickListener(new View.OnClickListener() {
			@Override public void onClick(View v)
			{
				resetZoomControlsAutoHideTimeout();
				zoomControls.setIsZoomOutEnabled(sessionView.zoomOut(ZOOMING_STEP));
				zoomControls.setIsZoomInEnabled(true);
			}
		});

		toggleMouseButtons = false;

		createDialogs();

		// register freerdp events broadcast receiver
		IntentFilter filter = new IntentFilter();
		filter.addAction(GlobalApp.ACTION_EVENT_FREERDP);
		registerReceiver(libFreeRDPBroadcastReceiver, filter);

		mClipboardManager = ClipboardManagerProxy.getClipboardManager(this);
		mClipboardManager.addClipboardChangedListener(this);

	mDecor = getWindow().getDecorView();
	// 设置UI标志：让内容延伸到系统栏下方，但保持系统栏可见（透明显示）
	int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
	              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
	              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
	mDecor.setSystemUiVisibility(uiOptions);

		updateActivityState("creating");
	}

	@Override public void onWindowFocusChanged(boolean hasFocus)
	{
		super.onWindowFocusChanged(hasFocus);
		mClipboardManager.getPrimaryClipManually();
	}

	@Override protected void onStart()
	{
		super.onStart();
		Log.v(TAG, "Session.onStart");
	}

	@Override protected void onRestart()
	{
		super.onRestart();
		Log.v(TAG, "Session.onRestart");
	}

	@Override protected void onResume()
	{
		super.onResume();
		Log.v(TAG, "Session.onResume");
		
	// ✅ 检查会话是否已结束（被踢出且未勾选自动重连，或手动断开）
	// 注意：只有当之前存在会话（session != null）但现在已结束时才关闭Activity
	// 首次连接时 session = null 且 has_active_session = false，这是正常情况，不应该关闭
	SharedPreferences rdpPrefs = getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE);
	if (session != null && !rdpPrefs.getBoolean("has_active_session", false)) {
		// 会话已结束（被踢出或手动断开）
		if (!kickedOutDialogShowing) {
			// 情况1：没有对话框显示，直接关闭Activity（手动退出等情况）
			Log.i(TAG, "onResume: Session exists but has_active_session=false, finishing activity");
			finish();
			return;
		} else {
			// 情况2：对话框正在显示（被踢出且未勾选）
			// 提前返回，避免执行 updateActivityState 等逻辑，防止触发重连
			// 对话框仍然会通过 uiHandler 正常显示
			Log.i(TAG, "onResume: Kicked out dialog showing, skip state update to avoid reconnection triggers");
			return;
		}
	}
		
		SharedPreferences prefs = getSharedPreferences("rdp_state", MODE_PRIVATE);
		if (prefs.getBoolean("crash_detected", false)) {
			long crashTime = prefs.getLong("crash_time", 0);
			String crashInfo = prefs.getString("crash_info", "Unknown");
			String crashThread = prefs.getString("crash_thread", "Unknown");
			int crashArch = prefs.getInt("crash_arch", 0);
			Log.e(TAG, "Previous crash detected: thread=" + crashThread + 
				", arch=" + crashArch + "bit, info=" + crashInfo + 
				", time=" + new java.util.Date(crashTime));
			prefs.edit().remove("crash_detected").apply();
		}
		
		if (isAltKeyActive) {
			isAltKeyActive = false;
			updateAltKeyIcon(false);
			stopAltKeyStateVerification();
			stopAltKeyKeepAlive();  // Stop keep-alive when screen unlocks
			// Release ALT key through KeyboardMapper (ensures proper unlock)
			if (keyboardMapper != null) {
				keyboardMapper.setAltLocked(false);
			}
			Log.d(TAG, "ALT key reset on resume (unlocked, screen unlocked, keep-alive stopped)");
		} else {
			// Even if UI shows inactive, verify keyboard state
			syncAltKeyState();
		}
		
		// Hide touch pointer when screen unlocks (any time)
		if (touchPointerView != null && touchPointerView.getVisibility() == View.VISIBLE) {
			touchPointerView.setVisibility(View.INVISIBLE);
			sessionView.setTouchPointerPadding(0, 0);
			Log.d(TAG, "Touch pointer hidden on resume (screen unlocked)");
		}
		
		// Hide keyboards when screen unlocks (any time)
		if (sysKeyboardVisible || extKeyboardVisible) {
			showKeyboard(false, false);
			Log.d(TAG, "Keyboards hidden on resume (screen unlocked)");
		}
		
	// ✅ 修复：不要停止心跳！心跳应该在整个会话期间持续运行
	// 确保心跳正在运行（如果还没有启动）
	if (session != null && sessionRunning && keepaliveTask == null) {
		Log.i(TAG, "Resume: Starting keepalive (was not running)");
		startBackgroundKeepalive(session.getInstance());
	} else if (keepaliveTask != null) {
		Log.d(TAG, "Resume: Keepalive already running, continue");
	}
	
	// Release WakeLock when returning to foreground (前台不需要WakeLock)
	releaseWakeLock();
	
	// Stop foreground service when returning to foreground（前台不需要前台服务）
	RdpAudioService.stop(this);
	Log.i(TAG, "Foreground service stopped (app in foreground)");
	
// Resume graphics updates
isInForeground = true;
	serverUpdateReceived = false;
	lastServerUpdateTime = 0;

	// Mark Activity ready and keep heartbeat running for recovery checks
	updateActivityState("ready");
	startActivityHeartbeat();
	
	// Only enable graphics if session is fully connected
	if (session != null && sessionRunning && scrollView != null && scrollView.getChildCount() > 0) {
		
		// ============ 修复1：强制刷新 surface 引用（防止引用过期导致花屏）============
		// 问题：偶发性bug - 解锁后 SessionView 的 surface 可能与 bitmap 失去同步
		// 解决：重建 surface-bitmap 绑定关系，确保渲染使用最新的 bitmap 对象
		if (sessionView != null && bitmap != null) {
			try {
				session.setSurface(new BitmapDrawable(getResources(), bitmap));
				sessionView.onSurfaceChange(session);
				Log.i(TAG, "✅ [Fix 1/3] Surface-bitmap binding refreshed (unlock screen race condition fix)");
			} catch (Exception e) {
				Log.e(TAG, "Failed to refresh surface binding", e);
			}
		}
		
		// ============ 修复2：清空 invalidRegions 栈（防止脏数据）============
		// 问题：栈中可能有锁屏前的脏数据，导致刷新错误区域
		// 解决：清空栈，确保只刷新解锁后的新数据
		if (sessionView != null) {
			try {
				sessionView.clearInvalidRegions();
				Log.i(TAG, "✅ [Fix 2/3] Invalid regions cleared (prevent stale data)");
			} catch (Exception e) {
				Log.e(TAG, "Failed to clear invalid regions", e);
			}
		}
		
		// === Layer 1: Enable decoding IMMEDIATELY (0ms) - RDP will auto-push updates ===
		try {
			LibFreeRDP.setClientDecoding(session.getInstance(), true);
			Log.i(TAG, "[Layer 1/6] Graphics decoding enabled (0ms)");
		} catch (Exception e) {
			Log.w(TAG, "Failed to enable graphics decoding", e);
		}
		
		// ============ 修复3：首次刷新使用同步方式（防止时序竞态）============
		// 问题：直接调用 invalidate() 可能在栈为空时导致 invalidateRegion() 跳过刷新
		// 解决：使用 addInvalidRegion + sendMessage 组合，确保栈中有数据时才触发刷新
		// 替代原 Layer 2 的不安全 invalidate() 调用
		if (sessionView != null && bitmap != null) {
			sessionView.addInvalidRegion(new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()));
			uiHandler.sendEmptyMessage(UIHandler.REFRESH_SESSIONVIEW);
			Log.i(TAG, "✅ [Fix 3/3] Immediate synchronized refresh with region (0ms, replaces unsafe invalidate)");
		}
		
		// === Layer 3: Bitmap refresh (50ms) - display server-pushed data ===
		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (isFinishing() || isDestroyed() || serverUpdateReceived) return;
				
				if (sessionView != null && bitmap != null) {
					sessionView.addInvalidRegion(new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()));
					uiHandler.sendEmptyMessage(UIHandler.REFRESH_SESSIONVIEW);
					Log.d(TAG, "[Layer 3/6] Bitmap refresh at 50ms");
				}
			}
		}, 50);
		
		// === Layer 4: Bitmap refresh (300ms) - network delay recovery ===
		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (isFinishing() || isDestroyed() || serverUpdateReceived) return;
				
				if (sessionView != null && bitmap != null) {
					sessionView.addInvalidRegion(new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()));
					uiHandler.sendEmptyMessage(UIHandler.REFRESH_SESSIONVIEW);
					Log.d(TAG, "[Layer 4/6] Bitmap refresh at 300ms");
				}
			}
		}, 300);
		
		// === Layer 5: Bitmap refresh (1000ms) - poor network recovery ===
		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (isFinishing() || isDestroyed() || serverUpdateReceived) return;
				
				if (sessionView != null && bitmap != null) {
					sessionView.addInvalidRegion(new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()));
					uiHandler.sendEmptyMessage(UIHandler.REFRESH_SESSIONVIEW);
					Log.d(TAG, "[Layer 5/6] Bitmap refresh at 1000ms");
				}
			}
		}, 1000);
		
		// === Layer 6: Ultimate fallback (1800ms) - force mouse simulation ===
		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (isFinishing() || isDestroyed()) return;
				
				long timeSinceUpdate = System.currentTimeMillis() - lastServerUpdateTime;
				if (!serverUpdateReceived || timeSinceUpdate > 2000) {
					Log.w(TAG, "[Layer 6/6] No server update, forcing mouse simulation");
					forceTriggerServerUpdate();
				}
			}
		}, 1800);
	}
}

	@Override protected void onPause()
	{
		super.onPause();
		Log.v(TAG, "Session.onPause");

		// hide any visible keyboards
		showKeyboard(false, false);
		
		// Stop ALT key verification and keep-alive when app goes to background
		stopAltKeyStateVerification();
		stopAltKeyKeepAlive();
		
		// Stop graphics updates (save CPU and traffic, keep connection and audio)
		isInForeground = false;
		updateActivityState("paused");
		stopActivityHeartbeat();
		
		// Only disable graphics if session is fully connected (not during connection)
		if (session != null && sessionRunning && scrollView != null && scrollView.getChildCount() > 0) {
			try {
				// Start foreground service to prevent system from killing the connection
				RdpAudioService.start(this);
				Log.i(TAG, "Foreground service started for background audio");
				
				// Verify if service started successfully
				// Use a short delay to allow service to start
				new android.os.Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						if (!RdpAudioService.isRunning()) {
							Log.e(TAG, "⚠️ Foreground service failed to start! Using WakeLock as fallback.");
							acquireWakeLock();
						} else {
							Log.i(TAG, "✓ Foreground service verified running");
						}
					}
				}, 500);
				
				// Clear update flags to force fresh update on resume
				serverUpdateReceived = false;
				lastServerUpdateTime = 0;
				
			LibFreeRDP.setClientDecoding(session.getInstance(), false);
			Log.i(TAG, "Background: graphics decoding disabled, connection and audio continue");
			
			// ✅ 确保心跳正在运行（如果还没有启动）
			if (keepaliveTask == null) {
				startBackgroundKeepalive(session.getInstance());
				Log.i(TAG, "Background: Started RDP heartbeat");
			} else {
				Log.d(TAG, "Background: RDP heartbeat already running, continue");
			}
			
			Log.i(TAG, "Using Foreground Service + Application Heartbeat for connection stability");
			} catch (Exception e) {
				Log.e(TAG, "Critical error in onPause background optimization", e);
				// Try WakeLock as fallback
				acquireWakeLock();
			}
		} else {
			Log.d(TAG, "Skip background optimization: session not fully connected yet");
		}
	}
	
	/**
	 * Acquire WakeLock to keep CPU active in background (fallback if foreground service fails)
	 */
	private void acquireWakeLock() {
		try {
			if (wakeLock == null) {
				android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
				if (pm != null) {
					wakeLock = pm.newWakeLock(
						android.os.PowerManager.PARTIAL_WAKE_LOCK,
						"FreeRDP::BackgroundConnection"
					);
					wakeLock.setReferenceCounted(false);
				}
			}
			
			if (wakeLock != null && !wakeLock.isHeld()) {
				wakeLock.acquire(30*60*1000L); // 30 minutes max
				Log.i(TAG, "✓ WakeLock acquired (30 min timeout)");
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to acquire WakeLock", e);
		}
	}
	
	/**
	 * Release WakeLock when no longer needed
	 */
	private void releaseWakeLock() {
		try {
			if (wakeLock != null && wakeLock.isHeld()) {
				wakeLock.release();
				Log.i(TAG, "✓ WakeLock released");
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to release WakeLock", e);
		}
	}

	/**
	 * 启动后台心跳（使用Synchronize Event代替鼠标微动）
	 * 配合TCP keepalive实现双层保活：
	 * - TCP层 (15秒)：维持NAT映射，内核自动处理
	 * - RDP层 (45秒)：保持会话活跃，使用轻量级Sync Event
	 */
	private void startBackgroundKeepalive(final long inst) {
		// ✅ 双保险机制：TCP keepalive (15s) + RDP心跳 (45s)
		// Prevent duplicate keepalive tasks running in parallel
		if (keepaliveTask != null) {
			Log.w(TAG, "Keepalive already running, skip duplicate start");
			return;
		}
		
		keepaliveTask = new Runnable() {
			private int heartbeatCount = 0;
			private int consecutiveFailures = 0;  // ✅ 修复Bug #9: 连续失败计数，用于触发重连
			
			@Override
			public void run() {
				// ✅ 修复：只检查session是否运行，不检查isInForeground
				// 锁屏/后台时更需要心跳来保持连接
				if (!sessionRunning || session == null) {
					Log.d(TAG, "RDP heartbeat stopped: sessionRunning=" + sessionRunning);
					return;
				}
				
				try {
					// Verify session instance still matches
					if (session.getInstance() != inst) {
						Log.w(TAG, "RDP heartbeat stopped: instance ID mismatch");
						return;
					}
					
					// ✅ 使用轻量级Synchronize Event（代替鼠标微动）
					// 优势：8字节，RDP标准协议，无副作用
					boolean success = LibFreeRDP.sendHeartbeat(inst);
					
					heartbeatCount++;
					
			if (success) {
				// ✅ 成功 - 重置失败计数
				if (consecutiveFailures > 0) {
					Log.i(TAG, "✓ RDP heartbeat #" + heartbeatCount + 
					           " recovered after " + consecutiveFailures + " failures");
				}
				consecutiveFailures = 0;
				
				// 根据前后台状态显示不同的日志级别
				if (isInForeground) {
					Log.v(TAG, "✓ RDP heartbeat #" + heartbeatCount + " (前台, TCP@15s+RDP@45s双保险)");
				} else {
					Log.d(TAG, "✓ RDP heartbeat #" + heartbeatCount + 
					           " (后台/锁屏, TCP@15s+RDP@45s双保险)");
				}
			} else {
				// ❌ 失败 - 累计失败次数
				consecutiveFailures++;
				Log.w(TAG, "⚠ RDP heartbeat #" + heartbeatCount + " failed (" + 
				           consecutiveFailures + "/2 连续失败)");
				
				// 🔥 修复Bug #9: 连续失败2次 = 连接很可能已断开
				// 时间：45秒×2=90秒，作为TCP超时(60秒)的补充检测
				// 容错：允许1次失败（网络抖动），2次才触发
				if (consecutiveFailures >= 2) {
					Log.e(TAG, "❌ RDP心跳连续失败" + consecutiveFailures + 
					           "次，判定连接已断开，触发重连");
					
					// 重置状态
					consecutiveFailures = 0;
					keepaliveTask = null;
					
					// ✅ 修复Bug #10: 防止短期重复重连
					// 检查是否已经在重连流程中（通过reconnectAttempts判断）
					// reconnectAttempts > 0 表示已有重连在进行，避免重复触发
					if (reconnectAttempts.get() > 0) {
						Log.i(TAG, "⚠️ Reconnection already in progress (attempts=" + 
						           reconnectAttempts.get() + "), skip RDP trigger to avoid duplicate");
						return;  // 已在重连流程中，跳过此次触发
					}
					
					// 在UI线程触发重连
					uiHandler.post(new Runnable() {
						@Override
						public void run() {
							if (!isActivityDestroyed && !isFinishing() && sessionRunning) {
								attemptReconnect("RDP心跳连续失败");
							}
						}
					});
					return;  // 停止心跳循环，重连后会重新启动
				}
			}
					
			// 继续下一次心跳（前后台都执行）
			keepaliveHandler.postDelayed(this, RDP_HEARTBEAT_INTERVAL);
			
		} catch (Exception e) {
			Log.e(TAG, "RDP heartbeat exception", e);
			consecutiveFailures++;
			
			// 异常也算失败，连续2次异常也触发重连
			if (consecutiveFailures >= 2) {
				Log.e(TAG, "❌ RDP心跳异常" + consecutiveFailures + "次，触发重连");
				keepaliveTask = null;
				
				// ✅ 修复Bug #10: 同样检查是否已在重连流程
				if (reconnectAttempts.get() > 0) {
					Log.i(TAG, "⚠️ Reconnection in progress (attempts=" + 
					           reconnectAttempts.get() + "), skip RDP exception trigger");
					return;
				}
				
				uiHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isActivityDestroyed && !isFinishing() && sessionRunning) {
							attemptReconnect("RDP心跳异常");
						}
					}
				});
				return;
			}
			
			// 异常后10秒重试
			if (keepaliveHandler != null) {
				keepaliveHandler.postDelayed(this, 10000);
			}
		}
	}
};

// 首次立即启动（不延迟）
if (keepaliveHandler != null) {
	keepaliveHandler.post(keepaliveTask);
	Log.i(TAG, "✓ 三层保活启动: " +
	           "TCP keepalive@" + (TCP_KEEPALIVE_INTERVAL/1000) + "s (内核层, 60s超时) + " +
	           "RDP heartbeat@" + (RDP_HEARTBEAT_INTERVAL/1000) + "s (应用层, 2次失败触发重连)");
}
}

	private void stopBackgroundKeepalive() {
		if (keepaliveTask != null) {
			keepaliveHandler.removeCallbacks(keepaliveTask);
			keepaliveTask = null;
			Log.i(TAG, "Background keepalive stopped");
		}
	}

	/**
	 * Force trigger server update by sending simulated mouse movement
	 * This is the ultimate fallback to prevent static screens
	 */
	private void forceTriggerServerUpdate() {
		if (session == null || !sessionRunning || bitmap == null || bitmap.isRecycled()) {
			Log.d(TAG, "Force update skipped: session not ready or bitmap recycled");
			return;
		}
		
		try {
			// Send mouse micro-movement (move 1 pixel and back)
			int centerX = bitmap.getWidth() / 2;
			int centerY = bitmap.getHeight() / 2;
			
		LibFreeRDP.sendCursorEvent(session.getInstance(), centerX, centerY, PTR_MOVE);
		LibFreeRDP.sendCursorEvent(session.getInstance(), centerX + 1, centerY, PTR_MOVE);
		LibFreeRDP.sendCursorEvent(session.getInstance(), centerX, centerY, PTR_MOVE);
			
			Log.w(TAG, "Forced server update by mouse simulation (3 micro-movements)");
			
			// Mark as received to prevent repeated attempts
			serverUpdateReceived = true;
			lastServerUpdateTime = System.currentTimeMillis();
		} catch (Exception e) {
			Log.w(TAG, "Failed to force server update", e);
		}
	}

	private void attemptReconnect(String disconnectReason) {
		// ✅ 使用synchronized锁防止并发重连 - 修复Bug #1: 将所有操作移到synchronized块内
		int currentAttempt;
		long delay;
		Runnable reconnectRunnable;
		
		// 格式化断开原因显示
		final String reasonText;
		if (disconnectReason != null && !disconnectReason.isEmpty()) {
			// 清理和简化错误消息
			String cleanReason = disconnectReason.trim();
			// 移除多余的前缀
			if (cleanReason.startsWith("Error:")) {
				cleanReason = cleanReason.substring(6).trim();
			}
			reasonText = cleanReason;
		} else {
			reasonText = "未知原因";
		}
		
		synchronized (reconnectLock) {
			// 检查是否已经在重连
			if (isReconnecting) {
				Log.w(TAG, "❌ Reconnection already in progress, skip duplicate attempt");
				return;
			}
			
			// Check if max attempts reached (thread-safe read)
			if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
				Log.e(TAG, "Max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS + "), giving up");
				showReconnectFailedAndExit();
				return;
			}
			
			if (reconnectBookmark == null) {
				Log.w(TAG, "Reconnect not possible: bookmark is null");
				return;
			}
			
			// ✅ 修复Bug #1: 在synchronized块内完成所有状态更新
			// 设置重连标志
			isReconnecting = true;
			// Persist reconnection lock for ServiceRestartReceiver to avoid duplicate recovery
			try {
				getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
					.edit()
					.putBoolean("reconnect_in_progress", true)
					.putLong("reconnect_lock_time", System.currentTimeMillis())
					.putString("reconnect_source", "SessionActivity")
					.apply();
			} catch (Exception e) {
				Log.w(TAG, "Failed to persist reconnect_in_progress", e);
			}
			// Thread-safe increment and get current attempt
			currentAttempt = reconnectAttempts.incrementAndGet();
			// ✅ Bug修复 #6: 更安全的数组访问 - 添加额外的边界检查
			int delayIndex = currentAttempt - 1;
			if (delayIndex < 0) delayIndex = 0;
			if (delayIndex >= RECONNECT_DELAYS.length) delayIndex = RECONNECT_DELAYS.length - 1;
			delay = RECONNECT_DELAYS[delayIndex];
			
			Log.d(TAG, "✓ Reconnection lock acquired (attempt " + currentAttempt + ")");
		}
		
		Log.i(TAG, "Scheduling reconnect attempt " + currentAttempt + "/" + 
		           MAX_RECONNECT_ATTEMPTS + " in " + (delay/1000) + " seconds");

		// Vibrate once for each reconnect attempt
		try {
			android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
			if (vibrator != null && vibrator.hasVibrator()) {
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
					vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
				} else {
					vibrator.vibrate(200);
				}
				Log.d(TAG, "Reconnect vibration triggered (attempt " + currentAttempt + ")");
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to trigger reconnect vibration", e);
		}

	// Show toast notification with disconnect reason
	final String message = "断开原因: " + reasonText + "\n" + 
	                      (delay/1000) + "秒后重连 (" + 
	                      currentAttempt + "/" + MAX_RECONNECT_ATTEMPTS + ")";
	runOnUiThread(new Runnable() {
		@Override
		public void run() {
			Toast.makeText(SessionActivity.this, message, Toast.LENGTH_LONG).show();
		}
	});

		// ✅ 修复Bug #4: 在回调开始时检查Activity状态，防止生命周期问题
		reconnectRunnable = new Runnable() {
			@Override
			public void run() {
				// ✅ Bug修复 #4 & #7: 使用isActivityDestroyed标志检查Activity状态（兼容所有API）
				if (isActivityDestroyed || isFinishing()) {
					Log.w(TAG, "Activity destroyed, canceling reconnect task");
					synchronized (reconnectLock) {
						isReconnecting = false;
						pendingReconnectTask = null;
					}
					try {
						getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
							.edit()
							.putBoolean("reconnect_in_progress", false)
							.apply();
					} catch (Exception e) {
						Log.w(TAG, "Failed to clear reconnect_in_progress", e);
					}
					return;
				}
				
				try {
					Log.i(TAG, "Attempting reconnect (attempt " + reconnectAttempts.get() + ")");
					
					// Clean up current session
					if (session != null) {
						session.setUIEventListener(null);
						LibFreeRDP.disconnect(session.getInstance());
					}
					
					// Start new connection
					connect(reconnectBookmark);
				} finally {
					// ✅ 释放重连锁（无论成功或失败）
					synchronized (reconnectLock) {
						isReconnecting = false;
						pendingReconnectTask = null;
						Log.d(TAG, "✓ Reconnection lock released");
					}
					try {
						getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
							.edit()
							.putBoolean("reconnect_in_progress", false)
							.apply();
					} catch (Exception e) {
						Log.w(TAG, "Failed to clear reconnect_in_progress", e);
					}
				}
			}
		};
		
		// ✅ 修复Bug #3: 保存任务引用以便后续取消
		synchronized (reconnectLock) {
			pendingReconnectTask = reconnectRunnable;
		}
		
		keepaliveHandler.postDelayed(reconnectRunnable, delay);
	}
	
	private void showReconnectFailedAndExit() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				// Play notification sound
				try {
					android.media.RingtoneManager.getRingtone(
						SessionActivity.this,
						android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
					).play();
				} catch (Exception e) {
					Log.w(TAG, "Failed to play notification sound", e);
				}
				
			// Vibrate 3 times to alert user
			try {
				android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
				if (vibrator != null && vibrator.hasVibrator()) {
					if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
						vibrator.vibrate(android.os.VibrationEffect.createWaveform(
							new long[]{0, 300, 200, 300, 200, 300}, // pattern: vibrate-pause-vibrate-pause-vibrate
							-1 // don't repeat
						));
					} else {
						vibrator.vibrate(new long[]{0, 300, 200, 300, 200, 300}, -1);
					}
					Log.i(TAG, "Failure vibration (3x) triggered");
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to trigger vibration", e);
			}
			
			// Show alert dialog (must be manually dismissed)
			Log.e(TAG, "showReconnectFailedAndExit called - max reconnect attempts reached");
			AlertDialog dialog = new AlertDialog.Builder(SessionActivity.this)
					.setTitle("连接失败")
					.setMessage("登录失败\n\n已尝试重连 " + MAX_RECONNECT_ATTEMPTS + " 次，仍无法连接到远程服务器。")
					.setCancelable(false)
					.setPositiveButton("确定", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							// Stop foreground service
							RdpAudioService.stop(SessionActivity.this);
							
							// Clean up and exit
							if (session != null) {
								session.setUIEventListener(null);
								LibFreeRDP.disconnect(session.getInstance());
							}
							
							// Finish activity and return to home screen
							finish();
						}
					})
				.create();
			dialog.show();
			Log.i(TAG, "Reconnect failure dialog shown");
			}
		});
	}

	private void resetReconnectState() {
		Runnable taskToCancel = null;
		
		synchronized (reconnectLock) {
			// ✅ 修复Bug #3: 取消待执行的重连任务
			if (pendingReconnectTask != null) {
				taskToCancel = pendingReconnectTask;
				pendingReconnectTask = null;
				Log.d(TAG, "Canceling pending reconnect task");
			}
			
			reconnectAttempts.set(0); // Thread-safe reset
			isReconnecting = false; // 清除重连标志
			manualDisconnect = false; // Reset manual disconnect flag
			try {
				getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
					.edit()
					.putBoolean("reconnect_in_progress", false)
					.remove("reconnect_source")
					.remove("reconnect_lock_time")
					.apply();
			} catch (Exception e) {
				Log.w(TAG, "Failed to reset reconnect prefs", e);
			}
			
			Log.d(TAG, "Reconnect state reset");
		}
		
		// ✅ 修复Bug #3: 在锁外取消任务，避免死锁
		if (taskToCancel != null && keepaliveHandler != null) {
			keepaliveHandler.removeCallbacks(taskToCancel);
			Log.d(TAG, "Pending reconnect task canceled");
		}
	}

	@Override protected void onStop()
	{
		super.onStop();
		Log.v(TAG, "Session.onStop");
	}

	@Override protected void onDestroy()
	{
		// ✅ Bug修复 #7: 立即设置destroyed标志，确保所有线程能尽早检测到Activity销毁
		isActivityDestroyed = true;
		Log.v(TAG, "Session.onDestroy - START");
		updateActivityState("destroyed");
		stopActivityHeartbeat();
		
		// ✅ 修复Bug #4: 取消待执行的重连任务，防止Activity销毁后执行
		Runnable taskToCancel = null;
		synchronized (reconnectLock) {
			if (pendingReconnectTask != null) {
				taskToCancel = pendingReconnectTask;
				pendingReconnectTask = null;
				isReconnecting = false;
				Log.d(TAG, "Canceling pending reconnect task in onDestroy");
			}
		}
		if (taskToCancel != null && keepaliveHandler != null) {
			keepaliveHandler.removeCallbacks(taskToCancel);
			Log.d(TAG, "Pending reconnect task removed from handler");
		}
		
		// Use try-finally to ensure all cleanup is executed
		try {
			if (connectThread != null)
			{
				try {
					connectThread.interrupt();
				} catch (Exception e) {
					Log.w(TAG, "Failed to interrupt connect thread", e);
				}
			}
			
		// Stop foreground service when activity is destroyed
		try {
			RdpAudioService.stop(this);
			Log.i(TAG, "Foreground service stopped (activity destroyed)");
		} catch (Exception e) {
			Log.w(TAG, "Failed to stop foreground service", e);
		}
		
		// Release WakeLock if held
		try {
			releaseWakeLock();
		} catch (Exception e) {
			Log.w(TAG, "Failed to release WakeLock in onDestroy", e);
		}
		
		// Clean up all Handler tasks to prevent memory leaks
			try {
				if (keepaliveHandler != null) {
					keepaliveHandler.removeCallbacksAndMessages(null);
					Log.d(TAG, "Keepalive handler cleaned (all tasks removed)");
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to clean keepalive handler", e);
			}
			
			try {
				if (uiHandler != null) {
					uiHandler.removeCallbacksAndMessages(null);
					Log.d(TAG, "UI handler cleaned (all tasks removed)");
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to clean UI handler", e);
			}
			
			try {
				if (altKeyStateCheckHandler != null) {
					stopAltKeyStateVerification();
					altKeyStateCheckHandler.removeCallbacksAndMessages(null);
					Log.d(TAG, "ALT key state check handler cleaned");
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to clean ALT key state check handler", e);
			}
			
			try {
				if (altKeyKeepAliveHandler != null) {
					stopAltKeyKeepAlive();
					altKeyKeepAliveHandler.removeCallbacksAndMessages(null);
					Log.d(TAG, "ALT key keep-alive handler cleaned");
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to clean ALT key keep-alive handler", e);
			}
			
		} finally {
			try {
				super.onDestroy();
				Log.v(TAG, "Session.onDestroy - super.onDestroy() called");
			} catch (Exception e) {
				Log.e(TAG, "Failed in super.onDestroy()", e);
			}
		}
		
		// Activity 完全销毁时清除会话标记（保险）
		if (isFinishing()) {
			getSharedPreferences("rdp_state", MODE_PRIVATE)
				.edit()
				.putBoolean("has_active_session", false)
				.apply();
			Log.d(TAG, "Activity finishing, cleared active session flag");
		}

		// All cleanup operations with exception handling
		// Note: Timer mechanism removed - connection maintained by Foreground Service + heartbeat
		// GlobalApp.cancelDisconnectTimer() is no longer needed

		try {
			Collection<SessionState> sessions = GlobalApp.getSessions();
			for (SessionState s : sessions) {
				try {
					LibFreeRDP.disconnect(s.getInstance());
				} catch (Exception e) {
					Log.w(TAG, "Failed to disconnect session " + s.getInstance(), e);
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to disconnect sessions", e);
		}

		try {
			unregisterReceiver(libFreeRDPBroadcastReceiver);
		} catch (Exception e) {
			Log.w(TAG, "Failed to unregister broadcast receiver (may already be unregistered)", e);
		}

		try {
			if (mClipboardManager != null) {
				mClipboardManager.removeClipboardboardChangedListener(this);
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to remove clipboard listener", e);
		}

		try {
			if (session != null) {
				GlobalApp.freeSession(session.getInstance());
				session = null;
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to free session", e);
		}
		
		Log.v(TAG, "Session.onDestroy - END");
	}

	@Override public void onConfigurationChanged(Configuration newConfig)
	{
		super.onConfigurationChanged(newConfig);

		// reload keyboard resources (changed from landscape)
		modifiersKeyboard = new Keyboard(getApplicationContext(), R.xml.modifiers_keyboard);
		specialkeysKeyboard = new Keyboard(getApplicationContext(), R.xml.specialkeys_keyboard);
		numpadKeyboard = new Keyboard(getApplicationContext(), R.xml.numpad_keyboard);
		cursorKeyboard = new Keyboard(getApplicationContext(), R.xml.cursor_keyboard);

		// apply loaded keyboards
		keyboardView.setKeyboard(specialkeysKeyboard);
		modifiersKeyboardView.setKeyboard(modifiersKeyboard);
		
		// Re-adjust modifiers keyboard position after orientation change
		if (modifiersKeyboardView.getVisibility() == View.VISIBLE) {
			// Post delayed to ensure ActionBar has been laid out
			modifiersKeyboardView.post(new Runnable() {
				@Override
				public void run() {
					adjustModifiersKeyboardPosition();
				}
			});
		}

		// 保持透明系统栏可见
		int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
		              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
		              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
		mDecor.setSystemUiVisibility(uiOptions);
	}

	private void processIntent(Intent intent)
	{
		// get either session instance or create one from a bookmark/uri
		Bundle bundle = intent.getExtras();
		Uri openUri = intent.getData();
		if (openUri != null)
		{
			// Launched from URI, e.g:
			// freerdp://user@ip:port/connect?sound=&rfx=&p=password&clipboard=%2b&themes=-
			connect(openUri);
		}
		else if (bundle.containsKey(PARAM_INSTANCE))
		{
			int inst = bundle.getInt(PARAM_INSTANCE);
			session = GlobalApp.getSession(inst);
			bitmap = session.getSurface().getBitmap();
			bindSession();
		}
		else if (bundle.containsKey(PARAM_CONNECTION_REFERENCE))
		{
			BookmarkBase bookmark = null;
			String refStr = bundle.getString(PARAM_CONNECTION_REFERENCE);
			if (ConnectionReference.isHostnameReference(refStr))
			{
				bookmark = new ManualBookmark();
				bookmark.<ManualBookmark>get().setHostname(ConnectionReference.getHostname(refStr));
			}
			else if (ConnectionReference.isBookmarkReference(refStr))
			{
				if (ConnectionReference.isManualBookmarkReference(refStr))
					bookmark = GlobalApp.getManualBookmarkGateway().findById(
					    ConnectionReference.getManualBookmarkId(refStr));
				else
					assert false;
			}

			if (bookmark != null)
			{
			// 设置屏幕方向
			int orientation = bookmark.getAdvancedSettings().getScreenOrientation();
			Log.i(TAG, "Bookmark screen orientation value: " + orientation);
			switch (orientation)
			{
				case 0: // 自动
					setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
					Log.i(TAG, "Screen orientation set to: UNSPECIFIED (Auto)");
					break;
				case 1: // 锁定横屏（顺时针旋转90度）= 左横屏（Home键在左）
					setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
					Log.i(TAG, "Screen orientation set to: LANDSCAPE (Clockwise 90°, Home on left)");
					break;
				case 2: // 锁定横屏（逆时针旋转90度）= 右横屏（Home键在右）
					setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
					Log.i(TAG, "Screen orientation set to: REVERSE_LANDSCAPE (Counter-clockwise 90°, Home on right)");
					break;
				case 3: // 锁定竖屏（支持正反两个竖屏方向）
					setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
					Log.i(TAG, "Screen orientation set to: SENSOR_PORTRAIT");
					break;
				default:
					Log.w(TAG, "Unknown screen orientation value: " + orientation);
					setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
					break;
			}
				
				connect(bookmark);
			}
			else
				closeSessionActivity(RESULT_CANCELED);
		}
		else
		{
			// no session found - exit
			closeSessionActivity(RESULT_CANCELED);
		}
	}

	private void connect(BookmarkBase bookmark)
	{
		// ✅ 最终防线：阻止在以下情况建立连接
		// 1. 对话框正在显示（被踢出且未勾选，等待用户确认）
		// 2. 会话已结束但用户选择不重连
		
		if (kickedOutDialogShowing) {
			Log.w(TAG, "❌ connect() blocked: kicked out dialog showing, waiting for user confirmation");
			return;
		}
		
		SharedPreferences rdpPrefs = getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE);
		if (session != null && !rdpPrefs.getBoolean("has_active_session", false)) {
			Log.w(TAG, "❌ connect() blocked: session ended, user chose not to reconnect");
			finish();
			return;
		}
		
		// 如果是从数据库加载的 bookmark（有ID），重新加载以确保获取最新设置
		if (bookmark != null && bookmark.getId() > 0) {
			try {
				BookmarkBase latestBookmark = GlobalApp.getManualBookmarkGateway().findById(bookmark.getId());
				if (latestBookmark != null) {
					bookmark = latestBookmark;
					Log.i(TAG, "connect: Reloaded bookmark from DB (ID=" + bookmark.getId() + ") to get latest settings");
				}
			} catch (Exception e) {
				Log.w(TAG, "connect: Failed to reload bookmark from DB: " + e.getMessage());
			}
		}
		
		// Save bookmark for auto-reconnection
		reconnectBookmark = bookmark;
		
		session = GlobalApp.createSession(bookmark, getApplicationContext());

		BookmarkBase.ScreenSettings screenSettings =
		    session.getBookmark().getActiveScreenSettings();
		Log.v(TAG, "Screen Resolution: " + screenSettings.getResolutionString());
		if (screenSettings.isAutomatic())
		{
			if ((getResources().getConfiguration().screenLayout &
			     Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE)
			{
				// large screen device i.e. tablet: simply use screen info
				screenSettings.setHeight(screen_height);
				screenSettings.setWidth(screen_width);
			}
			else
			{
				// small screen device i.e. phone:
				// Automatic uses the largest side length of the screen and
				// makes a 16:10 resolution setting out of it
				int screenMax = Math.max(screen_width, screen_height);
				screenSettings.setHeight(screenMax);
				screenSettings.setWidth((int)((float)screenMax * 1.6f));
			}
		}
		if (screenSettings.isFitScreen())
		{
			screenSettings.setHeight(screen_height);
			screenSettings.setWidth(screen_width);
		}

		connectWithTitle(bookmark.getLabel());
	}

	private void connect(Uri openUri)
	{
		session = GlobalApp.createSession(openUri, getApplicationContext());

		connectWithTitle(openUri.getAuthority());
	}

	static class ConnectThread extends Thread
	{
		private final SessionState runnableSession;
		private final Context context;

		public ConnectThread(@NonNull Context context, @NonNull SessionState session)
		{
			this.context = context;
			runnableSession = session;
		}

		public void run()
		{
			runnableSession.connect(context.getApplicationContext());
		}
	}

	private ConnectThread connectThread = null;

	private void connectWithTitle(String title)
	{
		session.setUIEventListener(this);

		progressDialog = new ProgressDialog(this);
		progressDialog.setTitle(title);
		progressDialog.setMessage(getResources().getText(R.string.dlg_msg_connecting));
		progressDialog.setButton(
		    ProgressDialog.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
			    @Override public void onClick(DialogInterface dialog, int which)
			    {
				    connectCancelledByUser = true;
				    LibFreeRDP.cancelConnection(session.getInstance());
			    }
		    });
		progressDialog.setCancelable(false);
		progressDialog.show();

		connectThread = new ConnectThread(getApplicationContext(), session);
		connectThread.start();
	}

	// binds the current session to the activity by wiring it up with the
	// sessionView and updating all internal objects accordingly
	private void bindSession()
	{
		Log.v(TAG, "bindSession called");
		session.setUIEventListener(this);
		sessionView.onSurfaceChange(session);
	scrollView.requestLayout();
	keyboardMapper.reset(this);
	// 保持透明系统栏可见
	int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
	              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
	              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
	mDecor.setSystemUiVisibility(uiOptions);
	}

	private void setSoftInputState(boolean state)
	{
		InputMethodManager mgr = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);

		if (state)
		{
			mgr.showSoftInput(sessionView, InputMethodManager.SHOW_FORCED);
		}
		else
		{
			mgr.hideSoftInputFromWindow(sessionView.getWindowToken(), 0);
		}
	}

	// displays either the system or the extended keyboard or non of them
	private void showKeyboard(final boolean showSystemKeyboard, final boolean showExtendedKeyboard)
	{
		// no matter what we are doing ... hide the zoom controls
		// onScrollChange notification showing the control again ...
		// i think check for "preference_key_ui_hide_zoom_controls" preference should be there
		uiHandler.removeMessages(UIHandler.SHOW_ZOOMCONTROLS);
		uiHandler.sendEmptyMessage(UIHandler.HIDE_ZOOMCONTROLS);

		InputMethodManager mgr = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);

		if (showSystemKeyboard)
		{
			// hide extended keyboard
			keyboardView.setVisibility(View.GONE);
			// show system keyboard
			setSoftInputState(true);

			// show modifiers keyboard and position it below ActionBar
			adjustModifiersKeyboardPosition();
			modifiersKeyboardView.setVisibility(View.VISIBLE);
		}
		else if (showExtendedKeyboard)
		{
			// hide system keyboard
			setSoftInputState(false);

			// show extended keyboard
			keyboardView.setKeyboard(specialkeysKeyboard);
			keyboardView.setVisibility(View.VISIBLE);
			
			// show modifiers keyboard and position it below ActionBar
			adjustModifiersKeyboardPosition();
			modifiersKeyboardView.setVisibility(View.VISIBLE);
		}
		else
		{
			// hide both
			setSoftInputState(false);
			keyboardView.setVisibility(View.GONE);
			modifiersKeyboardView.setVisibility(View.GONE);

			// clear any active key modifiers)
			keyboardMapper.clearlAllModifiers();
		}

		sysKeyboardVisible = showSystemKeyboard;
		extKeyboardVisible = showExtendedKeyboard;
	}
	
	// Adjust modifiers keyboard position to be below ActionBar
	private void adjustModifiersKeyboardPosition()
	{
		if (modifiersKeyboardView == null) return;
		
		int topMargin = 0;
		
		// Get status bar height (for transparent status bar)
		int statusBarHeight = 0;
		int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
		if (resourceId > 0) {
			statusBarHeight = getResources().getDimensionPixelSize(resourceId);
		}
		
		// Get ActionBar height (if visible)
		int actionBarHeight = 0;
		androidx.appcompat.app.ActionBar actionBar = getSupportActionBar();
		if (actionBar != null && actionBar.isShowing()) {
			actionBarHeight = actionBar.getHeight();
			// If ActionBar height is 0, use default height
			if (actionBarHeight == 0) {
				android.util.TypedValue tv = new android.util.TypedValue();
				if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
					actionBarHeight = android.util.TypedValue.complexToDimensionPixelSize(
						tv.data, getResources().getDisplayMetrics());
				}
			}
		}
		
		// Total margin = status bar + action bar
		topMargin = statusBarHeight + actionBarHeight;
		
		// Apply margin to modifiers keyboard
		android.widget.RelativeLayout.LayoutParams params = 
			(android.widget.RelativeLayout.LayoutParams) modifiersKeyboardView.getLayoutParams();
		if (params != null) {
			params.topMargin = topMargin;
			modifiersKeyboardView.setLayoutParams(params);
			Log.d(TAG, "Modifiers keyboard positioned: statusBar=" + statusBarHeight + 
				"px, actionBar=" + actionBarHeight + "px, total=" + topMargin + "px");
		}
	}

	private void closeSessionActivity(int resultCode)
	{
		// 清除活跃会话标记（停止服务自动重启）
		getSharedPreferences("rdp_state", MODE_PRIVATE)
			.edit()
			.putBoolean("has_active_session", false)
			.remove("session_instance")
			.remove("activity_state")
			.remove("activity_last_heartbeat")
			.putBoolean("reconnect_in_progress", false)
			.apply();
		
		// 停止前台服务
		RdpAudioService.stop(this);
		
		// Go back to home activity (and send intent data back to home)
		setResult(resultCode, getIntent());
		finish();
	}

	// update the state of our modifier keys
	private void updateModifierKeyStates()
	{
		// check if any key is in the keycodes list

		List<Keyboard.Key> keys = modifiersKeyboard.getKeys();
		for (Keyboard.Key curKey : keys)
		{
			// if the key is a sticky key - just set it to off
			if (curKey.sticky)
			{
				switch (keyboardMapper.getModifierState(curKey.codes[0]))
				{
					case KeyboardMapper.KEYSTATE_ON:
						curKey.on = true;
						curKey.pressed = false;
						break;

					case KeyboardMapper.KEYSTATE_OFF:
						curKey.on = false;
						curKey.pressed = false;
						break;

					case KeyboardMapper.KEYSTATE_LOCKED:
						curKey.on = true;
						curKey.pressed = true;
						break;
				}
			}
		}

		// refresh image
		modifiersKeyboardView.invalidateAllKeys();
	}

	private void sendDelayedMoveEvent(int x, int y)
	{
		if (uiHandler == null) {
			return;
		}
		
		// ✅ Bug Fix: 使用局部变量快照，防止并发时session被置null导致崩溃
		final SessionState currentSession = this.session;
		if (currentSession == null) {
			return;
		}
		
		final long sessionInstance = currentSession.getInstance();
		if (sessionInstance == 0) {
			return;
		}
		
		try {
			if (uiHandler.hasMessages(UIHandler.SEND_MOVE_EVENT))
			{
				uiHandler.removeMessages(UIHandler.SEND_MOVE_EVENT);
				discardedMoveEvents++;
			}
			else
				discardedMoveEvents = 0;

			if (discardedMoveEvents > MAX_DISCARDED_MOVE_EVENTS)
				LibFreeRDP.sendCursorEvent(sessionInstance, x, y, PTR_MOVE);
			else
				uiHandler.sendMessageDelayed(Message.obtain(null, UIHandler.SEND_MOVE_EVENT, x, y),
				                             SEND_MOVE_EVENT_TIMEOUT);
		} catch (Exception e) {
			Log.e(TAG, "Exception in sendDelayedMoveEvent", e);
		}
	}

	private void cancelDelayedMoveEvent()
	{
		if (uiHandler != null) {
			try {
				uiHandler.removeMessages(UIHandler.SEND_MOVE_EVENT);
			} catch (Exception e) {
				Log.e(TAG, "Exception in cancelDelayedMoveEvent", e);
			}
		}
	}

	@Override public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.session_menu, menu);
		
		// Store reference to ALT key menu item
		altKeyMenuItem = menu.findItem(R.id.session_alt_key);
		// Initialize ALT key to inactive state (lime)
		updateAltKeyIcon(false);
		
		return true;
	}

	@Override public boolean onOptionsItemSelected(MenuItem item)
	{
		// refer to http://tools.android.com/tips/non-constant-fields why we
		// can't use switch/case here ..
		int itemId = item.getItemId();

		if (itemId == R.id.session_zoom_310)
		{
			// Zoom to 310% (3.1x)
			if (sessionView != null) {
				sessionView.setZoom(3.1f);
				Log.d(TAG, "Zoom set to 310% (3.1x)");
				Toast.makeText(this, "缩放: 310%", Toast.LENGTH_SHORT).show();
			}
		}
		else if (itemId == R.id.session_zoom_400)
		{
			// Zoom to 400% (4.0x)
			if (sessionView != null) {
				sessionView.setZoom(4.0f);
				Log.d(TAG, "Zoom set to 400% (4.0x)");
				Toast.makeText(this, "缩放: 400%", Toast.LENGTH_SHORT).show();
			}
		}
	else if (itemId == R.id.session_touch_pointer)
	{
		// toggle touch pointer
		if (touchPointerView.getVisibility() == View.VISIBLE)
		{
			touchPointerView.setVisibility(View.INVISIBLE);
			sessionView.setTouchPointerPadding(0, 0);
		}
		else
		{
			touchPointerView.setVisibility(View.VISIBLE);
			
			// 每次显示时，将触摸指针移动到屏幕中间
			touchPointerView.setPosition(
			    (screen_width - touchPointerView.getPointerWidth()) / 2,
			    (screen_height - touchPointerView.getPointerHeight()) / 2
			);
			
			sessionView.setTouchPointerPadding(touchPointerView.getPointerWidth(),
			                                   touchPointerView.getPointerHeight());
		}
	}
		else if (itemId == R.id.session_sys_keyboard)
		{
			showKeyboard(!sysKeyboardVisible, false);
		}
		else if (itemId == R.id.session_ext_keyboard)
		{
			showKeyboard(false, !extKeyboardVisible);
		}
		else if (itemId == R.id.session_alt_key)
		{
			// Toggle ALT key state using LOCKED mode (prevents auto-release)
			isAltKeyActive = !isAltKeyActive;
			updateAltKeyIcon(isAltKeyActive);
			
			// Use KeyboardMapper's lock mechanism for reliability
			if (keyboardMapper != null) {
				keyboardMapper.setAltLocked(isAltKeyActive);
				if (isAltKeyActive) {
					Log.i(TAG, "ALT key LOCKED (RED) - continuous keep-alive started");
					// Start periodic state verification
					startAltKeyStateVerification();
					// Start continuous keep-alive to RDP server
					startAltKeyKeepAlive();
				} else {
					Log.i(TAG, "ALT key UNLOCKED (LIME) - keep-alive stopped");
					// Stop periodic verification
					stopAltKeyStateVerification();
					// Stop keep-alive
					stopAltKeyKeepAlive();
				}
			}
		}
		else if (itemId == R.id.session_disconnect)
		{
			showKeyboard(false, false);
			manualDisconnect = true; // Mark as manual disconnect (no auto-reconnect)
			Log.i(TAG, "Manual disconnect requested by user");

			// Persist manual disconnect immediately to prevent service auto-restart races
			try {
				getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
					.edit()
					.putBoolean("manual_disconnect", true)
					.putBoolean("has_active_session", false)
					.apply();
			} catch (Exception e) {
				Log.w(TAG, "Failed to persist manual_disconnect", e);
			}
			LibFreeRDP.disconnect(session.getInstance());
		}

		return true;
	}

	/**
	 * Update ALT key appearance based on active state
	 * @param isActive true = red text (active/locked), false = lime text (inactive)
	 */
	private void updateAltKeyIcon(boolean isActive) {
		if (altKeyMenuItem != null) {
			// Use SpannableString to set text color and style
			SpannableString spanString = new SpannableString("ALT");
			// Set color: RED when active (locked), LIME when inactive
			spanString.setSpan(new ForegroundColorSpan(
				isActive ? Color.RED : 0xFF00FF00), 
				0, spanString.length(), 0);
			// Set bold style
			spanString.setSpan(new StyleSpan(Typeface.BOLD), 
				0, spanString.length(), 0);
			altKeyMenuItem.setTitle(spanString);
		}
	}
	
	/**
	 * Start periodic ALT key state verification (safety mechanism)
	 * Ensures UI always reflects actual keyboard state
	 */
	private void startAltKeyStateVerification() {
		// Stop any existing verification task
		stopAltKeyStateVerification();
		
		altKeyStateCheckTask = new Runnable() {
			@Override
			public void run() {
				// Check if SessionActivity state matches KeyboardMapper state
				if (keyboardMapper != null && isAltKeyActive) {
					boolean actualAltPressed = keyboardMapper.isAltPressed();
					boolean actualAltLocked = keyboardMapper.isAltLocked();
					
					// If state mismatch detected, synchronize UI
					if (!actualAltPressed && !actualAltLocked) {
						Log.w(TAG, "⚠️ ALT key state mismatch detected! UI shows RED but Alt is not pressed. Fixing...");
						isAltKeyActive = false;
						updateAltKeyIcon(false);
						// Don't reschedule - state is now synced
						return;
					}
					
					// Continue checking while Alt is active
					if (altKeyStateCheckHandler != null) {
						altKeyStateCheckHandler.postDelayed(this, 500); // Check every 500ms
					}
				}
			}
		};
		
		// Start checking after 500ms
		altKeyStateCheckHandler.postDelayed(altKeyStateCheckTask, 500);
		Log.d(TAG, "Started ALT key state verification (checks every 500ms)");
	}
	
	/**
	 * Stop periodic ALT key state verification
	 */
	private void stopAltKeyStateVerification() {
		if (altKeyStateCheckTask != null && altKeyStateCheckHandler != null) {
			altKeyStateCheckHandler.removeCallbacks(altKeyStateCheckTask);
			altKeyStateCheckTask = null;
			Log.d(TAG, "Stopped ALT key state verification");
		}
	}
	
	/**
	 * Synchronize ALT key UI with actual keyboard state
	 * Called when modifier key is released by KeyboardMapper
	 */
	private void syncAltKeyState() {
		if (keyboardMapper != null) {
			boolean actualAltPressed = keyboardMapper.isAltPressed();
			boolean actualAltLocked = keyboardMapper.isAltLocked();
			
			// If UI shows active but keyboard shows inactive, update UI
			if (isAltKeyActive && !actualAltPressed && !actualAltLocked) {
				Log.i(TAG, "Syncing ALT key state: UI RED -> LIME (keyboard released Alt)");
				isAltKeyActive = false;
				updateAltKeyIcon(false);
				stopAltKeyStateVerification();
				stopAltKeyKeepAlive();
			}
		}
	}
	
	/**
	 * Verify ALT key state before processing input
	 * Ensures UI and keyboard state are synchronized before critical operations
	 */
	private void verifyAltKeyStateBeforeInput() {
		if (isAltKeyActive && keyboardMapper != null) {
			boolean actualAltPressed = keyboardMapper.isAltPressed();
			boolean actualAltLocked = keyboardMapper.isAltLocked();
			
			// If UI shows active but keyboard is not, synchronize immediately
			if (!actualAltPressed && !actualAltLocked) {
				Log.w(TAG, "⚠️ ALT key verification FAILED before input! Syncing UI RED -> LIME");
				isAltKeyActive = false;
				// Update UI in a safe way
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						updateAltKeyIcon(false);
						stopAltKeyStateVerification();
						stopAltKeyKeepAlive();
					}
				});
			}
		}
	}
	
	/**
	 * Start continuous ALT key keep-alive mechanism
	 * Sends Alt key press event to RDP server every 150ms to maintain Alt state
	 * This is CRITICAL for drag operations that require Alt key
	 */
	private void startAltKeyKeepAlive() {
		// Stop any existing keep-alive task first
		stopAltKeyKeepAlive();
		
		if (session == null) {
			Log.w(TAG, "Cannot start Alt keep-alive: session is null");
			return;
		}
		
		final int VK_LMENU = 0xA4;
		final long sessionInstance = session.getInstance();
		
		altKeyKeepAliveTask = new Runnable() {
			private int heartbeatCount = 0;
			
			@Override
			public void run() {
				// Check if Alt is still locked and session is valid
				if (!isAltKeyActive || session == null || session.getInstance() != sessionInstance) {
					Log.d(TAG, "Alt keep-alive stopped: isActive=" + isAltKeyActive + ", session valid=" + (session != null));
					return;
				}
				
				try {
					// Re-send Alt key press event to RDP server
					LibFreeRDP.sendKeyEvent(sessionInstance, VK_LMENU, true);
					heartbeatCount++;
					
					if (heartbeatCount % 20 == 0) {
						// Log every 3 seconds (20 * 150ms = 3000ms)
						Log.d(TAG, "Alt keep-alive: " + heartbeatCount + " heartbeats sent (ensures drag works)");
					}
				} catch (Exception e) {
					Log.e(TAG, "Alt keep-alive send failed", e);
				}
				
				// Schedule next keep-alive
				if (altKeyKeepAliveHandler != null && isAltKeyActive) {
					altKeyKeepAliveHandler.postDelayed(this, ALT_KEEPALIVE_INTERVAL);
				}
			}
		};
		
		// Send immediately to ensure Alt is active right away
		try {
			LibFreeRDP.sendKeyEvent(sessionInstance, VK_LMENU, true);
			Log.i(TAG, "✓ Alt keep-alive started (sends every " + ALT_KEEPALIVE_INTERVAL + "ms, critical for continuous drag)");
		} catch (Exception e) {
			Log.e(TAG, "Initial Alt keep-alive send failed", e);
		}
		
		// Start periodic keep-alive
		altKeyKeepAliveHandler.postDelayed(altKeyKeepAliveTask, ALT_KEEPALIVE_INTERVAL);
	}
	
	/**
	 * Stop continuous ALT key keep-alive mechanism
	 */
	private void stopAltKeyKeepAlive() {
		if (altKeyKeepAliveTask != null && altKeyKeepAliveHandler != null) {
			altKeyKeepAliveHandler.removeCallbacks(altKeyKeepAliveTask);
			altKeyKeepAliveTask = null;
			Log.i(TAG, "✓ Alt keep-alive stopped");
		}
	}

	@Override public void onBackPressed()
	{
		// 只用于隐藏键盘，禁止对远程桌面内容进行操作
		if (sysKeyboardVisible || extKeyboardVisible)
			showKeyboard(false, false);
		// 移除 Alt+F4 功能，返回键不再发送任何内容到远程桌面
	}

	@Override public boolean onKeyLongPress(int keyCode, KeyEvent event)
	{
		// Long press back key: removed manual disconnect feature
		// Use menu -> disconnect to manually close connection
		return super.onKeyLongPress(keyCode, event);
	}

	// android keyboard input handling
	// We always use the unicode value to process input from the android
	// keyboard except if key modifiers
	// (like Win, Alt, Ctrl) are activated. In this case we will send the
	// virtual key code to allow key
	// combinations (like Win + E to open the explorer).
	@Override public boolean onKeyDown(int keycode, KeyEvent event)
	{
		return keyboardMapper.processAndroidKeyEvent(event);
	}

	@Override public boolean onKeyUp(int keycode, KeyEvent event)
	{
		return keyboardMapper.processAndroidKeyEvent(event);
	}

	// onKeyMultiple is called for input of some special characters like umlauts
	// and some symbol characters
	@Override public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event)
	{
		return keyboardMapper.processAndroidKeyEvent(event);
	}

	// ****************************************************************************
	// KeyboardView.KeyboardActionEventListener
	@Override public void onKey(int primaryCode, int[] keyCodes)
	{
		keyboardMapper.processCustomKeyEvent(primaryCode);
	}

	@Override public void onText(CharSequence text)
	{
	}

	@Override public void swipeRight()
	{
	}

	@Override public void swipeLeft()
	{
	}

	@Override public void swipeDown()
	{
	}

	@Override public void swipeUp()
	{
	}

	@Override public void onPress(int primaryCode)
	{
	}

	@Override public void onRelease(int primaryCode)
	{
	}

	// ****************************************************************************
	// KeyboardMapper.KeyProcessingListener implementation
	@Override public void processVirtualKey(int virtualKeyCode, boolean down)
	{
		LibFreeRDP.sendKeyEvent(session.getInstance(), virtualKeyCode, down);
	}

	@Override public void processUnicodeKey(int unicodeKey)
	{
		LibFreeRDP.sendUnicodeKeyEvent(session.getInstance(), unicodeKey, true);
		LibFreeRDP.sendUnicodeKeyEvent(session.getInstance(), unicodeKey, false);
	}

	@Override public void switchKeyboard(int keyboardType)
	{
		switch (keyboardType)
		{
			case KeyboardMapper.KEYBOARD_TYPE_FUNCTIONKEYS:
				keyboardView.setKeyboard(specialkeysKeyboard);
				break;

			case KeyboardMapper.KEYBOARD_TYPE_NUMPAD:
				keyboardView.setKeyboard(numpadKeyboard);
				break;

			case KeyboardMapper.KEYBOARD_TYPE_CURSOR:
				keyboardView.setKeyboard(cursorKeyboard);
				break;

			default:
				break;
		}
	}

	@Override public void modifiersChanged()
	{
		updateModifierKeyStates();
	}
	
	@Override public void onModifierKeyReleased(int virtualKeyCode)
	{
		// Callback from KeyboardMapper when a modifier key is released
		final int VK_LMENU = 0xA4;
		
		if (virtualKeyCode == VK_LMENU) {
			// Alt key was released by KeyboardMapper, synchronize UI
			Log.i(TAG, "KeyboardMapper released ALT key, synchronizing UI state");
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					syncAltKeyState();
				}
			});
		}
	}

	// ****************************************************************************
	// LibFreeRDP UI event listener implementation
	@Override public void OnSettingsChanged(int width, int height, int bpp)
	{

		if (bpp > 16)
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
		else
			bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);

		session.setSurface(new BitmapDrawable(getResources(), bitmap));

		if (session.getBookmark() == null)
		{
			// Return immediately if we launch from URI
			return;
		}

		// check this settings and initial settings - if they are not equal the
		// server doesn't support our settings
		// FIXME: the additional check (settings.getWidth() != width + 1) is for
		// the RDVH bug fix to avoid accidental notifications
		// (refer to android_freerdp.c for more info on this problem)
		BookmarkBase.ScreenSettings settings = session.getBookmark().getActiveScreenSettings();
		if ((settings.getWidth() != width && settings.getWidth() != width + 1) ||
		    settings.getHeight() != height || settings.getColors() != bpp)
			uiHandler.sendMessage(
			    Message.obtain(null, UIHandler.DISPLAY_TOAST,
			                   getResources().getText(R.string.info_capabilities_changed)));
	}

	// Track foreground state for background optimization
	private boolean isInForeground = true;
	
	// Server update tracking (prevent static screen after unlock)
	private volatile boolean serverUpdateReceived = false;
	private volatile long lastServerUpdateTime = 0;
	
	// Background keepalive (application layer heartbeat)
	private Handler keepaliveHandler = new Handler();
	private Runnable keepaliveTask;
	private static final long RDP_HEARTBEAT_INTERVAL = 45000;  // 45秒 - RDP Sync Event心跳（应用层保障）
	private static final long TCP_KEEPALIVE_INTERVAL = 15000;  // 15秒 - TCP层保活（内核层保障）
	
	// Auto reconnection (smart reconnection with exponential backoff)
	// Thread-safe counter to prevent race conditions in multi-threaded reconnection logic
	private AtomicInteger reconnectAttempts = new AtomicInteger(0);
	private static final int MAX_RECONNECT_ATTEMPTS = 10;
	// ✅ Bug修复 #6: 确保数组长度与MAX_RECONNECT_ATTEMPTS一致，防止IndexOutOfBoundsException
	private static final long[] RECONNECT_DELAYS = {
		5000,    // 1st: 5 seconds
		10000,   // 2nd: 10 seconds
		15000,   // 3rd: 15 seconds
		15000,   // 4th: 15 seconds
		15000,   // 5th: 15 seconds
		15000,   // 6th: 15 seconds
		15000,   // 7th: 15 seconds
		15000,   // 8th: 15 seconds
		15000,   // 9th: 15 seconds
		15000    // 10th: 15 seconds
	};
	
	// ✅ Bug修复 #6: 静态初始化块 - 验证数组长度
	static {
		if (RECONNECT_DELAYS.length != MAX_RECONNECT_ATTEMPTS) {
			throw new IllegalStateException(
				"RECONNECT_DELAYS array length (" + RECONNECT_DELAYS.length + 
				") must equal MAX_RECONNECT_ATTEMPTS (" + MAX_RECONNECT_ATTEMPTS + ")"
			);
		}
	}
	private BookmarkBase reconnectBookmark = null;
	
	// ✅ 重连锁 - 防止并发重连尝试
	private final Object reconnectLock = new Object();
	// ✅ Bug修复 #3: 改为volatile，确保跨CPU核心的内存可见性
	private volatile boolean isReconnecting = false;
	private volatile Runnable pendingReconnectTask = null; // 跟踪待执行的重连任务

	@Override public void OnGraphicsUpdate(int x, int y, int width, int height)
	{
		// Mark that we received a server update
		serverUpdateReceived = true;
		lastServerUpdateTime = System.currentTimeMillis();
		
		// Skip graphics update in background (save CPU, keep audio)
		if (!isInForeground) {
			return;
		}

		LibFreeRDP.updateGraphics(session.getInstance(), bitmap, x, y, width, height);

		sessionView.addInvalidRegion(new Rect(x, y, x + width, y + height));

		/*
		 * since sessionView can only be modified from the UI thread any
		 * modifications to it need to be scheduled
		 */

		uiHandler.sendEmptyMessage(UIHandler.REFRESH_SESSIONVIEW);
	}

	@Override public void OnGraphicsResize(int width, int height, int bpp)
	{
		// replace bitmap
		if (bpp > 16)
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
		else
			bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);
		session.setSurface(new BitmapDrawable(getResources(), bitmap));

		// Set default zoom to 310% (3.1x) when graphics is first initialized
		// Use flag to ensure it's only done once, and use UI thread to avoid crashes
		if (!hasSetDefaultZoom && sessionView != null && bitmap != null) {
			sessionView.post(new Runnable() {
				@Override
				public void run() {
					// Enhanced checks: Activity lifecycle + session state + bitmap
					if (sessionView != null && session != null && sessionRunning 
					    && bitmap != null && !isFinishing() && !isDestroyed()) {
						try {
							sessionView.setZoom(3.1f);
							scrollView.requestLayout();
							hasSetDefaultZoom = true;
							Log.d(TAG, "Default zoom set to 310% (3.1x) on graphics resize");
						} catch (Exception e) {
							Log.e(TAG, "Failed to set default zoom", e);
						}
					} else {
						Log.d(TAG, "Default zoom skipped (session not ready or Activity destroyed)");
					}
				}
			});
		}
		
		// ✅ 连接成功后立即启动心跳（确保从一开始就有心跳保护）
		if (session != null && sessionRunning && keepaliveTask == null) {
			startBackgroundKeepalive(session.getInstance());
			Log.i(TAG, "RDP keepalive started after graphics initialized");
		}

		/*
		 * since sessionView can only be modified from the UI thread any
		 * modifications to it need to be scheduled
		 */
		uiHandler.sendEmptyMessage(UIHandler.GRAPHICS_CHANGED);
	}

	@Override
	public boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
	                              StringBuilder password)
	{
		// this is where the return code of our dialog will be stored
		callbackDialogResult = false;

		// set text fields
		((EditText)userCredView.findViewById(R.id.editTextUsername)).setText(username);
		((EditText)userCredView.findViewById(R.id.editTextDomain)).setText(domain);
		((EditText)userCredView.findViewById(R.id.editTextPassword)).setText(password);

		// start dialog in UI thread
		uiHandler.sendMessage(Message.obtain(null, UIHandler.SHOW_DIALOG, dlgUserCredentials));

		// wait for result
		try
		{
			synchronized (dlgUserCredentials)
			{
				dlgUserCredentials.wait();
			}
		}
		catch (InterruptedException e)
		{
		}

		// clear buffers
		username.setLength(0);
		domain.setLength(0);
		password.setLength(0);

		// read back user credentials
		username.append(
		    ((EditText)userCredView.findViewById(R.id.editTextUsername)).getText().toString());
		domain.append(
		    ((EditText)userCredView.findViewById(R.id.editTextDomain)).getText().toString());
		password.append(
		    ((EditText)userCredView.findViewById(R.id.editTextPassword)).getText().toString());

		return callbackDialogResult;
	}

	@Override
	public boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
	                                     StringBuilder password)
	{
		// this is where the return code of our dialog will be stored
		callbackDialogResult = false;

		// set text fields
		((EditText)userCredView.findViewById(R.id.editTextUsername)).setText(username);
		((EditText)userCredView.findViewById(R.id.editTextDomain)).setText(domain);
		((EditText)userCredView.findViewById(R.id.editTextPassword)).setText(password);

		// start dialog in UI thread
		uiHandler.sendMessage(Message.obtain(null, UIHandler.SHOW_DIALOG, dlgUserCredentials));

		// wait for result
		try
		{
			synchronized (dlgUserCredentials)
			{
				dlgUserCredentials.wait();
			}
		}
		catch (InterruptedException e)
		{
		}

		// clear buffers
		username.setLength(0);
		domain.setLength(0);
		password.setLength(0);

		// read back user credentials
		username.append(
		    ((EditText)userCredView.findViewById(R.id.editTextUsername)).getText().toString());
		domain.append(
		    ((EditText)userCredView.findViewById(R.id.editTextDomain)).getText().toString());
		password.append(
		    ((EditText)userCredView.findViewById(R.id.editTextPassword)).getText().toString());

		return callbackDialogResult;
	}

	@Override
	public int OnVerifiyCertificateEx(String host, long port, String commonName, String subject,
	                                  String issuer, String fingerprint, long flags)
	{
		// see if global settings says accept all
		if (ApplicationSettingsActivity.getAcceptAllCertificates(this))
			return 0;

		// this is where the return code of our dialog will be stored
		callbackDialogResult = false;

		// set message
		String msg = getResources().getString(R.string.dlg_msg_verify_certificate);
		String type = "RDP-Server";
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_GATEWAY) != 0)
			type = "RDP-Gateway";
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_REDIRECT) != 0)
			type = "RDP-Redirect";
		msg += "\n\n" + type + ": " + host + ":" + port;

		msg += "\n\nSubject: " + subject + "\nIssuer: " + issuer;

		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM) != 0)
			msg += "\nCertificate: " + fingerprint;
		else
			msg += "\nFingerprint: " + fingerprint;
		dlgVerifyCertificate.setMessage(msg);

		// start dialog in UI thread
		uiHandler.sendMessage(Message.obtain(null, UIHandler.SHOW_DIALOG, dlgVerifyCertificate));

		// wait for result
		try
		{
			synchronized (dlgVerifyCertificate)
			{
				dlgVerifyCertificate.wait();
			}
		}
		catch (InterruptedException e)
		{
		}

		return callbackDialogResult ? 1 : 0;
	}

	@Override
	public int OnVerifyChangedCertificateEx(String host, long port, String commonName,
	                                        String subject, String issuer, String fingerprint,
	                                        String oldSubject, String oldIssuer,
	                                        String oldFingerprint, long flags)
	{
		// see if global settings says accept all
		if (ApplicationSettingsActivity.getAcceptAllCertificates(this))
			return 0;

		// this is where the return code of our dialog will be stored
		callbackDialogResult = false;

		// set message
		String msg = getResources().getString(R.string.dlg_msg_verify_certificate);
		String type = "RDP-Server";
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_GATEWAY) != 0)
			type = "RDP-Gateway";
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_REDIRECT) != 0)
			type = "RDP-Redirect";
		msg += "\n\n" + type + ": " + host + ":" + port;
		msg += "\n\nSubject: " + subject + "\nIssuer: " + issuer;
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM) != 0)
			msg += "\nCertificate: " + fingerprint;
		else
			msg += "\nFingerprint: " + fingerprint;
		dlgVerifyCertificate.setMessage(msg);

		// start dialog in UI thread
		uiHandler.sendMessage(Message.obtain(null, UIHandler.SHOW_DIALOG, dlgVerifyCertificate));

		// wait for result
		try
		{
			synchronized (dlgVerifyCertificate)
			{
				dlgVerifyCertificate.wait();
			}
		}
		catch (InterruptedException e)
		{
		}

		return callbackDialogResult ? 1 : 0;
	}

	@Override public void OnRemoteClipboardChanged(String data)
	{
		Log.v(TAG, "OnRemoteClipboardChanged: " + data);
		mClipboardManager.setClipboardData(data);
	}

	@Override public void OnRemoteCursorUpdate(byte[] bitmapData, int width, int height, int hotX, int hotY)
	{
		Log.i(TAG, "SessionActivity.OnRemoteCursorUpdate: " + width + "x" + height + " hotspot(" + hotX + "," + hotY + ")");
		Log.i(TAG, "bitmapData: " + (bitmapData != null ? bitmapData.length + " bytes" : "null"));
		Log.i(TAG, "sessionView: " + (sessionView != null ? "not null" : "null"));
		
		if (sessionView != null)
		{
			sessionView.updateRemoteCursor(bitmapData, width, height, hotX, hotY);
			Log.i(TAG, "updateRemoteCursor called");
		}
	}

	@Override public void OnCursorTypeChanged(int cursorType)
	{
		Log.i(TAG, "SessionActivity.OnCursorTypeChanged: cursorType=" + cursorType);
		
		if (touchPointerView != null)
		{
			touchPointerView.setCursorType(cursorType);
			Log.i(TAG, "TouchPointerView.setCursorType called with type=" + cursorType);
		}
	}

	// ****************************************************************************
	// ScrollView2DListener implementation
	private void resetZoomControlsAutoHideTimeout()
	{
		uiHandler.removeMessages(UIHandler.HIDE_ZOOMCONTROLS);
		uiHandler.sendEmptyMessageDelayed(UIHandler.HIDE_ZOOMCONTROLS,
		                                  ZOOMCONTROLS_AUTOHIDE_TIMEOUT);
	}

	@Override public void onScrollChanged(ScrollView2D scrollView, int x, int y, int oldx, int oldy)
	{
		try {
			if (sessionView == null || zoomControls == null) {
				return;
			}
			
			zoomControls.setIsZoomInEnabled(!sessionView.isAtMaxZoom());
			zoomControls.setIsZoomOutEnabled(!sessionView.isAtMinZoom());

			if (sysKeyboardVisible || extKeyboardVisible)
				return;

			if (!ApplicationSettingsActivity.getHideZoomControls(this))
			{
				if (uiHandler != null) {
					uiHandler.sendEmptyMessage(UIHandler.SHOW_ZOOMCONTROLS);
					resetZoomControlsAutoHideTimeout();
				}
			}
			
		// 屏幕滚动时，发送鼠标移动事件到远程
		// 这样Windows能实时更新光标样式（双箭头、手型等）
		// ✅ Bug Fix: 使用局部变量快照，防止并发时session被置null导致崩溃
		final SessionState currentSession = this.session;
		if (currentSession != null && touchPointerView != null && 
		    touchPointerView.getVisibility() == View.VISIBLE)
		{
			final long sessionInstance = currentSession.getInstance();
			if (sessionInstance == 0) {
				return;
			}
			
			Point p = touchPointerView.getRemoteCoordinate();
			
			// 空指针检查
			if (p == null) {
				return;
			}
			
			// 边界检查
			if (bitmap != null && !bitmap.isRecycled())
			{
				p.x = Math.max(0, Math.min(p.x, bitmap.getWidth()));
				p.y = Math.max(0, Math.min(p.y, bitmap.getHeight()));
			}
			
	LibFreeRDP.sendCursorEvent(sessionInstance, p.x, p.y, PTR_MOVE);
	if (DEBUG_MOUSE) Log.v(TAG, "Scroll changed, sent mouse move to: (" + p.x + "," + p.y + ")");
		}
		} catch (Exception e) {
			Log.e(TAG, "Exception in onScrollChanged", e);
		}
	}

	// ****************************************************************************
	// SessionView.SessionViewListener
	@Override public void onSessionViewBeginTouch()
	{
		scrollView.setScrollEnabled(false);
	}

	@Override public void onSessionViewEndTouch()
	{
		scrollView.setScrollEnabled(true);
	}

	@Override public void onSessionViewLeftTouch(int x, int y, boolean down)
	{
		if (session == null) {
			return;
		}
		
		try {
			// Verify Alt key state before processing touch (prevent inconsistent state)
			verifyAltKeyStateBeforeInput();
			
			if (!down)
				cancelDelayedMoveEvent();

			LibFreeRDP.sendCursorEvent(session.getInstance(), x, y,
			                           toggleMouseButtons ? Mouse.getRightButtonEvent(this, down)
			                                              : Mouse.getLeftButtonEvent(this, down));

			if (!down)
				toggleMouseButtons = false;
		} catch (Exception e) {
			Log.e(TAG, "Failed to send left touch event", e);
		}
	}

	public void onSessionViewRightTouch(int x, int y, boolean down)
	{
		if (!down)
			toggleMouseButtons = !toggleMouseButtons;
	}

	@Override public void onSessionViewMove(int x, int y)
	{
		if (session != null) {
			// Verify Alt key state before mouse move (important for drag operations)
			verifyAltKeyStateBeforeInput();
			sendDelayedMoveEvent(x, y);
		}
	}

	@Override public void onSessionViewScroll(boolean down)
	{
		if (session == null) {
			return;
		}
		
		try {
			LibFreeRDP.sendCursorEvent(session.getInstance(), 0, 0, Mouse.getScrollEvent(this, down));
		} catch (Exception e) {
			Log.e(TAG, "Failed to send scroll event", e);
		}
	}

	// ****************************************************************************
	// TouchPointerView.TouchPointerListener
	@Override public void onTouchPointerClose()
	{
		try {
			if (touchPointerView != null) {
				touchPointerView.setVisibility(View.INVISIBLE);
			}
			if (sessionView != null) {
				sessionView.setTouchPointerPadding(0, 0);
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to close touch pointer", e);
		}
	}

	@Override public void onTouchPointerLeftClick(Point remoteCoord, boolean down)
	{
		// 空指针检查
		if (remoteCoord == null) {
			Log.w(TAG, "onTouchPointerLeftClick: null remoteCoord");
			return;
		}
		
		// ✅ Bug Fix: 使用局部变量快照，防止并发时session被置null导致崩溃
		final SessionState currentSession = this.session;
		if (currentSession == null) {
			Log.w(TAG, "onTouchPointerLeftClick: session is null");
			return;
		}
		
		final long sessionInstance = currentSession.getInstance();
		if (sessionInstance == 0) {
			Log.w(TAG, "onTouchPointerLeftClick: session instance is 0");
			return;
		}
		
		// 边界检查
		if (bitmap != null && !bitmap.isRecycled())
		{
			remoteCoord.x = Math.max(0, Math.min(remoteCoord.x, bitmap.getWidth()));
			remoteCoord.y = Math.max(0, Math.min(remoteCoord.y, bitmap.getHeight()));
		}
		
		try {
			// Verify Alt key state before click (critical for Alt+Click operations)
			verifyAltKeyStateBeforeInput();
			
			LibFreeRDP.sendCursorEvent(sessionInstance, remoteCoord.x, remoteCoord.y,
			                           Mouse.getLeftButtonEvent(this, down));
			
		if (DEBUG_MOUSE && sessionView != null) {
			Log.v(TAG, String.format("LeftClick: remote(%d,%d) [zoom=%.2f]", 
			                         remoteCoord.x, remoteCoord.y, sessionView.getZoom()));
		}
		} catch (Exception e) {
			Log.e(TAG, "Failed to send left click event", e);
		}
	}

	@Override public void onTouchPointerRightClick(Point remoteCoord, boolean down)
	{
		// 空指针检查
		if (remoteCoord == null) {
			Log.w(TAG, "onTouchPointerRightClick: null remoteCoord");
			return;
		}
		
		// ✅ Bug Fix: 使用局部变量快照，防止并发时session被置null导致崩溃
		final SessionState currentSession = this.session;
		if (currentSession == null) {
			Log.w(TAG, "onTouchPointerRightClick: session is null");
			return;
		}
		
		final long sessionInstance = currentSession.getInstance();
		if (sessionInstance == 0) {
			Log.w(TAG, "onTouchPointerRightClick: session instance is 0");
			return;
		}
		
		// 边界检查
		if (bitmap != null && !bitmap.isRecycled())
		{
			remoteCoord.x = Math.max(0, Math.min(remoteCoord.x, bitmap.getWidth()));
			remoteCoord.y = Math.max(0, Math.min(remoteCoord.y, bitmap.getHeight()));
		}
		
		try {
			LibFreeRDP.sendCursorEvent(sessionInstance, remoteCoord.x, remoteCoord.y,
			                           Mouse.getRightButtonEvent(this, down));
		
		if (DEBUG_MOUSE && sessionView != null) {
			Log.v(TAG, String.format("RightClick: remote(%d,%d) [zoom=%.2f]", 
			                         remoteCoord.x, remoteCoord.y, sessionView.getZoom()));
		}
		} catch (Exception e) {
			Log.e(TAG, "Failed to send right click event", e);
		}
	}

	@Override public void onTouchPointerMove(Point remoteCoord)
	{
		// 空指针检查 (移动事件频繁，静默失败)
		if (remoteCoord == null) {
			return;
		}
		
		// ✅ Bug Fix: 使用局部变量快照，防止并发时session被置null导致崩溃
		final SessionState currentSession = this.session;
		if (currentSession == null) {
			return;
		}
		
		final long sessionInstance = currentSession.getInstance();
		if (sessionInstance == 0) {
			return;
		}
		
		// 边界检查
		if (bitmap != null && !bitmap.isRecycled())
		{
			remoteCoord.x = Math.max(0, Math.min(remoteCoord.x, bitmap.getWidth()));
			remoteCoord.y = Math.max(0, Math.min(remoteCoord.y, bitmap.getHeight()));
		}
		
		try {
			// Verify Alt key state before move (important for Alt+Drag operations)
			verifyAltKeyStateBeforeInput();
			
			// Update remote cursor position
			if (sessionView != null)
			{
				sessionView.updateRemoteCursorPosition(remoteCoord.x, remoteCoord.y);
			}
			
		LibFreeRDP.sendCursorEvent(sessionInstance, remoteCoord.x, remoteCoord.y, PTR_MOVE);

		if (ApplicationSettingsActivity.getAutoScrollTouchPointer(this) &&
		    !uiHandler.hasMessages(UIHandler.SCROLLING_REQUESTED))
		{
			if (DEBUG_MOUSE) Log.v(TAG, "Starting auto-scroll");
			uiHandler.sendEmptyMessageDelayed(UIHandler.SCROLLING_REQUESTED, SCROLLING_TIMEOUT);
		}
	} catch (Exception e) {
		// Silent fail for frequent move events
	}
	}

	@Override public void onTouchPointerScroll(boolean down)
	{
		if (session == null) {
			return;
		}
		
		try {
			LibFreeRDP.sendCursorEvent(session.getInstance(), 0, 0, Mouse.getScrollEvent(this, down));
		} catch (Exception e) {
			Log.e(TAG, "Failed to send scroll event", e);
		}
	}

	@Override public void onTouchPointerToggleKeyboard()
	{
		showKeyboard(!sysKeyboardVisible, false);
	}

	@Override public void onTouchPointerToggleExtKeyboard()
	{
		showKeyboard(false, !extKeyboardVisible);
	}

	@Override public void onTouchPointerResetScrollZoom()
	{
		try {
			if (sessionView != null) {
				sessionView.setZoom(1.0f);
			}
			if (scrollView != null) {
				scrollView.scrollTo(0, 0);
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to reset scroll/zoom", e);
		}
	}

	@Override public boolean onGenericMotionEvent(MotionEvent e)
	{
		super.onGenericMotionEvent(e);
		
		if (e == null || session == null) {
			return false;
		}
		
		try {
			switch (e.getAction())
			{
				case MotionEvent.ACTION_SCROLL:
					final float vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
					if (vScroll < 0)
					{
						LibFreeRDP.sendCursorEvent(session.getInstance(), 0, 0,
						                           Mouse.getScrollEvent(this, false));
					}
					if (vScroll > 0)
					{
						LibFreeRDP.sendCursorEvent(session.getInstance(), 0, 0,
						                           Mouse.getScrollEvent(this, true));
					}
					break;
			}
			return true;
		} catch (Exception ex) {
			Log.e(TAG, "Exception in onGenericMotionEvent", ex);
			return false;
		}
	}

	// ****************************************************************************
	// ClipboardManagerProxy.OnClipboardChangedListener
	@Override public void onClipboardChanged(String data)
	{
		Log.v(TAG, "onClipboardChanged: " + data);
		LibFreeRDP.sendClipboardData(session.getInstance(), data);
	}

	// ✅ Bug修复 #2: UIHandler保持为非静态内部类（改为静态会引入太多复杂性）
	// 但在handleMessage开始时检查Activity状态，防止在Activity销毁后处理消息
	private class UIHandler extends Handler
	{

		public static final int REFRESH_SESSIONVIEW = 1;
		public static final int DISPLAY_TOAST = 2;
		public static final int HIDE_ZOOMCONTROLS = 3;
		public static final int SEND_MOVE_EVENT = 4;
		public static final int SHOW_DIALOG = 5;
		public static final int GRAPHICS_CHANGED = 6;
		public static final int SCROLLING_REQUESTED = 7;
		public static final int SHOW_ZOOMCONTROLS = 8;

		UIHandler()
		{
			super();
		}

		@Override public void handleMessage(Message msg)
		{
			// ✅ Bug修复 #2: 检查Activity状态，防止在Activity销毁后处理消息导致内存泄漏
			if (isActivityDestroyed || isFinishing()) {
				Log.w(TAG, "UIHandler: Activity destroyed, ignoring message: " + msg.what);
				return;
			}
			switch (msg.what)
			{
				case GRAPHICS_CHANGED:
				{
					sessionView.onSurfaceChange(session);
					scrollView.requestLayout();
					break;
				}
				case REFRESH_SESSIONVIEW:
				{
					sessionView.invalidateRegion();
					break;
				}
				case DISPLAY_TOAST:
				{
					Toast errorToast = Toast.makeText(getApplicationContext(), msg.obj.toString(),
					                                  Toast.LENGTH_LONG);
					errorToast.show();
					break;
				}
				case HIDE_ZOOMCONTROLS:
				{
					if (zoomControls.isShown())
						zoomControls.hide();
					break;
				}
				case SHOW_ZOOMCONTROLS:
				{
					if (!zoomControls.isShown())
						zoomControls.show();

					break;
				}
		case SEND_MOVE_EVENT:
		{
		// ✅ Bug Fix: 使用局部变量快照，防止并发时session被置null导致崩溃
		final SessionState currentSession = session;
		if (currentSession != null) {
			final long sessionInstance = currentSession.getInstance();
			if (sessionInstance != 0) {
				try {
					LibFreeRDP.sendCursorEvent(sessionInstance, msg.arg1, msg.arg2, PTR_MOVE);
				} catch (Exception e) {
					Log.e(TAG, "Failed to send delayed move event", e);
				}
			}
		}
			break;
		}
				case SHOW_DIALOG:
				{
					// create and show the dialog
					((Dialog)msg.obj).show();
					break;
				}
				case SCROLLING_REQUESTED:
				{
					int scrollX = 0;
					int scrollY = 0;
					float[] pointerPos = touchPointerView.getPointerPosition();

					if (pointerPos[0] > (screen_width - touchPointerView.getPointerWidth()))
						scrollX = SCROLLING_DISTANCE;
					else if (pointerPos[0] < 0)
						scrollX = -SCROLLING_DISTANCE;

					if (pointerPos[1] > (screen_height - touchPointerView.getPointerHeight()))
						scrollY = SCROLLING_DISTANCE;
					else if (pointerPos[1] < 0)
						scrollY = -SCROLLING_DISTANCE;

					scrollView.scrollBy(scrollX, scrollY);

					// see if we reached the min/max scroll positions
					if (scrollView.getScrollX() == 0 ||
					    scrollView.getScrollX() == (sessionView.getWidth() - scrollView.getWidth()))
						scrollX = 0;
					if (scrollView.getScrollY() == 0 ||
					    scrollView.getScrollY() ==
					        (sessionView.getHeight() - scrollView.getHeight()))
						scrollY = 0;

					if (scrollX != 0 || scrollY != 0)
						uiHandler.sendEmptyMessageDelayed(SCROLLING_REQUESTED, SCROLLING_TIMEOUT);
					else
						Log.v(TAG, "Stopping auto-scroll");
					break;
				}
			}
		}
	}

	private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener
	{
		private float scaleFactor = 1.0f;

		@Override public boolean onScaleBegin(ScaleGestureDetector detector)
		{
			scrollView.setScrollEnabled(false);
			return true;
		}

		@Override public boolean onScale(ScaleGestureDetector detector)
		{

			// calc scale factor
			scaleFactor *= detector.getScaleFactor();
			scaleFactor = Math.max(SessionView.MIN_SCALE_FACTOR,
			                       Math.min(scaleFactor, SessionView.MAX_SCALE_FACTOR));
			sessionView.setZoom(scaleFactor);

			if (!sessionView.isAtMinZoom() && !sessionView.isAtMaxZoom())
			{
				// transform scroll origin to the new zoom space
				float transOriginX = scrollView.getScrollX() * detector.getScaleFactor();
				float transOriginY = scrollView.getScrollY() * detector.getScaleFactor();

				// transform center point to the zoomed space
				float transCenterX =
				    (scrollView.getScrollX() + detector.getFocusX()) * detector.getScaleFactor();
				float transCenterY =
				    (scrollView.getScrollY() + detector.getFocusY()) * detector.getScaleFactor();

				// scroll by the difference between the distance of the
				// transformed center/origin point and their old distance
				// (focusX/Y)
				scrollView.scrollBy((int)((transCenterX - transOriginX) - detector.getFocusX()),
				                    (int)((transCenterY - transOriginY) - detector.getFocusY()));
			}

			return true;
		}

		@Override public void onScaleEnd(ScaleGestureDetector de)
		{
			scrollView.setScrollEnabled(true);
		}
	}

	private class LibFreeRDPBroadcastReceiver extends BroadcastReceiver
	{
		@Override public void onReceive(Context context, Intent intent)
		{
			// still got a valid session?
			if (session == null)
				return;

			// is this event for the current session?
			if (session.getInstance() != intent.getExtras().getLong(GlobalApp.EVENT_PARAM, -1))
				return;

			switch (intent.getExtras().getInt(GlobalApp.EVENT_TYPE, -1))
			{
				case GlobalApp.FREERDP_EVENT_CONNECTION_SUCCESS:
					OnConnectionSuccess(context);
					break;

				case GlobalApp.FREERDP_EVENT_CONNECTION_FAILURE:
					OnConnectionFailure(context);
					break;
				case GlobalApp.FREERDP_EVENT_DISCONNECTED:
					OnDisconnected(context);
					break;
			}
		}

		private void OnConnectionSuccess(Context context)
		{
			Log.v(TAG, "OnConnectionSuccess");

			// Reset reconnection attempts on successful connection
			resetReconnectState();
			
			// 标记有活跃会话（用于服务自动重启）
			getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
				.edit()
				.putBoolean("has_active_session", true)
				.putBoolean("manual_disconnect", false)
				.putLong("session_instance", session != null ? session.getInstance() : -1)
				.putString("activity_state", "ready")
				.putLong("activity_last_heartbeat", System.currentTimeMillis())
				.apply();
			
			// 重置服务重启计数
			com.freerdp.freerdpcore.application.ServiceRestartReceiver.resetRestartCount(context);

			// bind session
			bindSession();

			if (progressDialog != null)
			{
				progressDialog.dismiss();
				progressDialog = null;
			}

			if (session.getBookmark() == null)
			{
				// Return immediately if we launch from URI
				return;
			}

			// add hostname to history if quick connect was used
			Bundle bundle = getIntent().getExtras();
			if (bundle != null && bundle.containsKey(PARAM_CONNECTION_REFERENCE))
			{
				if (ConnectionReference.isHostnameReference(
				        bundle.getString(PARAM_CONNECTION_REFERENCE)))
				{
					assert session.getBookmark().getType() == BookmarkBase.TYPE_MANUAL;
					String item = session.getBookmark().<ManualBookmark>get().getHostname();
					if (!GlobalApp.getQuickConnectHistoryGateway().historyItemExists(item))
						GlobalApp.getQuickConnectHistoryGateway().addHistoryItem(item);
				}
			}
		}

	private void OnConnectionFailure(Context context)
	{
		Log.v(TAG, "OnConnectionFailure");

		// 获取错误信息
		String errorString = "";
		if (session != null) {
			errorString = LibFreeRDP.getLastErrorString(session.getInstance());
			Log.i(TAG, "🔍 断开原因检测: " + errorString);
		}

		// remove pending move events
		uiHandler.removeMessages(UIHandler.SEND_MOVE_EVENT);

		if (progressDialog != null)
		{
			progressDialog.dismiss();
			progressDialog = null;
		}

		// ❌ 手动断开 - 任何时候都不重连
		if (manualDisconnect || connectCancelledByUser) {
			Log.i(TAG, "❌ 手动断开 - 不重连");
			if (!connectCancelledByUser)
				uiHandler.sendMessage(
				    Message.obtain(null, UIHandler.DISPLAY_TOAST,
				                   getResources().getText(R.string.error_connection_failure)));
			closeSessionActivity(RESULT_CANCELED);
			return;
		}

		// 🎯 检测是否被踢出
		boolean isKickedOut = false;
		if (errorString != null && 
		    (errorString.contains("administrative tool") || errorString.contains("another session"))) {
			isKickedOut = true;
			Log.i(TAG, "✅ 检测到被踢出");
		}

	// 📌 如果是被踢出，检查是否勾选
	if (isKickedOut) {
		boolean autoReconnectEnabled = false;
		if (reconnectBookmark != null && reconnectBookmark.getId() > 0) {
			try {
				BookmarkBase latestBookmark = GlobalApp.getManualBookmarkGateway().findById(reconnectBookmark.getId());
				if (latestBookmark != null) {
					autoReconnectEnabled = latestBookmark.getAdvancedSettings().getAutoReconnectOnKick();
				}
			} catch (Exception e) {
				Log.e(TAG, "读取设置失败: " + e.getMessage());
			}
		}
		
	if (!autoReconnectEnabled) {
		// 未勾选 - 不重连，显示对话框（需要手动确认）
		Log.i(TAG, "❌ 被踢出且未勾选 - 停止心跳并显示对话框");
		sessionRunning = false;  // ✅ 停止会话，让RDP心跳自动停止（第767行检查）
		
		// ✅ 立即清除活跃会话标记，防止ServiceRestartReceiver或其它逻辑触发重连
		try {
			getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
				.edit()
				.putBoolean("has_active_session", false)
				.putBoolean("reconnect_in_progress", false)
				.remove("session_instance")
				.remove("activity_state")
				.remove("activity_last_heartbeat")
				.apply();
			Log.d(TAG, "✓ Cleared session flags to prevent reconnection triggers");
		} catch (Exception e) {
			Log.w(TAG, "Failed to clear session flags", e);
		}
		
		// ✅ 设置对话框显示标志，防止onResume时关闭Activity
		kickedOutDialogShowing = true;
		
		uiHandler.post(new Runnable() {
			@Override
			public void run() {
				// 再次检查Activity状态
				if (isActivityDestroyed || isFinishing()) {
					Log.w(TAG, "Activity is finishing/destroyed, skip dialog");
					kickedOutDialogShowing = false;
					return;
				}
				
				AlertDialog.Builder builder = new AlertDialog.Builder(SessionActivity.this);
				builder.setTitle(R.string.dialog_kicked_out_title);
				builder.setMessage(R.string.dialog_kicked_out_message);
				builder.setCancelable(false);
				builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						kickedOutDialogShowing = false;  // ✅ 清除标志
						dialog.dismiss();
						closeSessionActivity(RESULT_CANCELED);
					}
				});
				builder.show();
			}
		});
		return;
	}
		
		// 已勾选 - 显示Toast（自动消失），然后重连
		Log.i(TAG, "✅ 被踢出但已勾选 - 显示Toast并尝试重连");
		uiHandler.sendMessage(
		    Message.obtain(null, UIHandler.DISPLAY_TOAST,
		                   getResources().getText(R.string.dialog_kicked_out_message)));
	} else {
		// 其他情况（网络断开等）- 始终重连
		Log.i(TAG, "✅ 网络断开或其他错误 - 始终重连");
	}

	// 🔄 执行重连逻辑
	// ✅ 修复Bug #2: 移除外部检查，只保留attemptReconnect()内部的检查，确保原子性
	// ✅ 修复Bug #8: 防止竞态条件 - 在重连前先清除旧的锁状态
	// 场景：第一个重连任务的connect()失败触发OnConnectionFailure回调时，
	//      第二个断开检测可能已经设置了新的isReconnecting=true，
	//      导致第一个重连被跳过。解决方法：主动清除旧锁，确保能重连。
	synchronized (reconnectLock) {
		if (isReconnecting) {
			// 之前的重连任务失败了（现在在OnConnectionFailure回调中）
			// 清除旧的锁状态，以便能够开始新的重连
			Log.d(TAG, "🔄 Previous reconnect task failed (OnConnectionFailure callback), clearing old lock");
			isReconnecting = false;
			pendingReconnectTask = null;
			
			// 同时清除持久化标志（让ServiceRestartReceiver知道）
			try {
				getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
					.edit()
					.putBoolean("reconnect_in_progress", false)
					.apply();
			} catch (Exception e) {
				Log.w(TAG, "Failed to clear reconnect_in_progress", e);
			}
		}
	}
	
	if (reconnectBookmark != null) {
		// 传递断开原因给重连方法
		attemptReconnect(errorString); // attemptReconnect()内部会检查max attempts
	} else {
		uiHandler.sendMessage(
		    Message.obtain(null, UIHandler.DISPLAY_TOAST,
		                   getResources().getText(R.string.error_connection_failure)));
		closeSessionActivity(RESULT_CANCELED);
	}
}

		private void OnDisconnected(Context context)
		{
			Log.v(TAG, "OnDisconnected");

			// remove pending move events
			uiHandler.removeMessages(UIHandler.SEND_MOVE_EVENT);

			if (progressDialog != null)
			{
				progressDialog.dismiss();
				progressDialog = null;
			}

			// Don't auto-reconnect if manual disconnect
			if (manualDisconnect) {
				Log.i(TAG, "Manual disconnect - skip auto-reconnect");
				session.setUIEventListener(null);
				closeSessionActivity(RESULT_OK);
				return;
			}

		// Check if auto-reconnect on kick is enabled
		// 重新从数据库加载最新设置，确保获取最新的 autoReconnectOnKick 值
		// 注意：只读取设置值，不更新 reconnectBookmark 对象
		Log.i(TAG, "OnDisconnected: ========== 开始检查是否重连 ==========");
		Log.i(TAG, "OnDisconnected: reconnectBookmark=" + (reconnectBookmark != null ? "not null" : "null"));
		if (reconnectBookmark != null) {
			Log.i(TAG, "OnDisconnected: reconnectBookmark.getId()=" + reconnectBookmark.getId());
			Log.i(TAG, "OnDisconnected: reconnectBookmark.getLabel()=" + reconnectBookmark.getLabel());
		}
		Log.i(TAG, "OnDisconnected: sessionRunning=" + sessionRunning);
		Log.i(TAG, "OnDisconnected: reconnectAttempts=" + reconnectAttempts.get());
		
		// 被踢出时的重连判断：必须从数据库读取最新值，如果读取失败，默认不重连（false）
		boolean autoReconnectEnabled = false;  // 默认不重连
		if (reconnectBookmark != null && reconnectBookmark.getId() > 0) {
			Log.i(TAG, "OnDisconnected: 缓存的 reconnectBookmark.autoReconnectOnKick=" + reconnectBookmark.getAdvancedSettings().getAutoReconnectOnKick());
			try {
				// 强制从数据库重新加载 bookmark 以获取最新设置
				BookmarkBase latestBookmark = GlobalApp.getManualBookmarkGateway().findById(reconnectBookmark.getId());
				if (latestBookmark != null) {
					autoReconnectEnabled = latestBookmark.getAdvancedSettings().getAutoReconnectOnKick();
					Log.i(TAG, "OnDisconnected: ✓ 从数据库重新加载成功 (ID=" + reconnectBookmark.getId() + ")");
					Log.i(TAG, "OnDisconnected: ✓ 数据库中的 autoReconnectOnKick=" + autoReconnectEnabled);
					Log.i(TAG, "OnDisconnected: ✓ 对比 - 缓存值=" + reconnectBookmark.getAdvancedSettings().getAutoReconnectOnKick() + ", 数据库值=" + autoReconnectEnabled);
				} else {
					// 从数据库加载失败，默认不重连（false）
					autoReconnectEnabled = false;
					Log.w(TAG, "OnDisconnected: ✗ 从数据库加载失败 (ID=" + reconnectBookmark.getId() + "), 默认不重连 (false)");
				}
			} catch (Exception e) {
				// 从数据库加载异常，默认不重连（false）
				autoReconnectEnabled = false;
				Log.e(TAG, "OnDisconnected: ✗ 从数据库加载异常: " + e.getMessage() + ", 默认不重连 (false)", e);
			}
		} else if (reconnectBookmark != null) {
			// 没有ID，无法从数据库读取，默认不重连（false）
			autoReconnectEnabled = false;
			Log.w(TAG, "OnDisconnected: reconnectBookmark 无ID，无法从数据库读取，默认不重连 (false)");
		} else {
			// reconnectBookmark 为 null，默认不重连（false）
			autoReconnectEnabled = false;
			Log.w(TAG, "OnDisconnected: reconnectBookmark 为 null，默认不重连 (false)");
		}

		Log.i(TAG, "OnDisconnected: 最终判断 - autoReconnectEnabled=" + autoReconnectEnabled);
		
		// 被踢出（OnDisconnected）时的重连逻辑：
		// 只有勾选了"被踢出后自动重连"才重连，否则直接退出
	if (!autoReconnectEnabled) {
		// 未勾选：不重连，直接退出
		Log.i(TAG, "OnDisconnected: ✗ 未勾选自动重连，停止心跳并关闭会话");
		sessionRunning = false;  // ✅ 停止会话，让RDP心跳自动停止（第767行检查）
		
		// ✅ 立即清除活跃会话标记，防止ServiceRestartReceiver或其它逻辑触发重连
		try {
			getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
				.edit()
				.putBoolean("has_active_session", false)
				.putBoolean("reconnect_in_progress", false)
				.remove("session_instance")
				.remove("activity_state")
				.remove("activity_last_heartbeat")
				.apply();
			Log.d(TAG, "✓ Cleared session flags to prevent reconnection triggers (OnDisconnected)");
		} catch (Exception e) {
			Log.w(TAG, "Failed to clear session flags (OnDisconnected)", e);
		}
		
		// ✅ 检查是否有对话框正在显示（OnConnectionFailure 可能已经显示了对话框）
		// 如果对话框正在显示，不要重复关闭Activity，让对话框的OK按钮处理关闭逻辑
		if (kickedOutDialogShowing) {
			Log.i(TAG, "OnDisconnected: Kicked out dialog already showing, let dialog handle closing");
			return;
		}
		
		session.setUIEventListener(null);
		closeSessionActivity(RESULT_OK);
		return;
	}
		
		// 已勾选：检查其他条件
		Log.i(TAG, "OnDisconnected: ✓ 已勾选自动重连，检查其他条件");
		Log.i(TAG, "OnDisconnected: sessionRunning=" + sessionRunning + 
		           ", reconnectBookmark=" + (reconnectBookmark != null) + 
		           ", reconnectAttempts=" + reconnectAttempts.get() + 
		           ", MAX=" + MAX_RECONNECT_ATTEMPTS);
		
	// ✅ 修复Bug #2: 移除外部检查，让attemptReconnect()内部统一处理
	if (sessionRunning && reconnectBookmark != null) {
		Log.i(TAG, "OnDisconnected: ►►► 所有条件满足，开始重连！");
		// 获取断开原因并传递给重连方法
		String disconnectReason = "";
		if (session != null) {
			disconnectReason = LibFreeRDP.getLastErrorString(session.getInstance());
		}
		attemptReconnect(disconnectReason); // attemptReconnect()内部会检查max attempts
	} else {
		Log.i(TAG, "OnDisconnected: ►►► 其他条件不满足");
		if (!sessionRunning) {
			Log.i(TAG, "OnDisconnected: sessionRunning=false，关闭会话");
			session.setUIEventListener(null);
			closeSessionActivity(RESULT_OK);
		} else {
			Log.i(TAG, "OnDisconnected: reconnectBookmark=null，关闭会话");
			session.setUIEventListener(null);
			closeSessionActivity(RESULT_OK);
		}
	}
		Log.i(TAG, "OnDisconnected: ========== 检查结束 ==========");
		}
	}
}
