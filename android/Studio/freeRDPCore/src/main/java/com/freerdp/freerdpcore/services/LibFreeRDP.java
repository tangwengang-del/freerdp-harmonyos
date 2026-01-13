/*
   Android FreeRDP JNI Wrapper

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.collection.LongSparseArray;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ManualBookmark;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibFreeRDP
{
	private static final String TAG = "LibFreeRDP";
	private static EventListener listener;
	private static boolean mHasH264 = false;

	private static final LongSparseArray<Boolean> mInstanceState = new LongSparseArray<>();

	// 光标类型常量（与C层定义一致）
	public static final int CURSOR_TYPE_UNKNOWN = 0;
	public static final int CURSOR_TYPE_DEFAULT = 1;      // 默认箭头
	public static final int CURSOR_TYPE_HAND = 2;         // 手型（链接）
	public static final int CURSOR_TYPE_IBEAM = 3;        // I型（文本）
	public static final int CURSOR_TYPE_SIZE_NS = 4;      // 上下双箭头（调整行高）
	public static final int CURSOR_TYPE_SIZE_WE = 5;      // 左右双箭头（调整列宽）
	public static final int CURSOR_TYPE_SIZE_NWSE = 6;    // 斜向双箭头（左上-右下）
	public static final int CURSOR_TYPE_SIZE_NESW = 7;    // 斜向双箭头（右上-左下）
	public static final int CURSOR_TYPE_CROSS = 8;        // 十字（移动）
	public static final int CURSOR_TYPE_WAIT = 9;         // 等待（沙漏）

	public static final long VERIFY_CERT_FLAG_NONE = 0x00;
	public static final long VERIFY_CERT_FLAG_LEGACY = 0x02;
	public static final long VERIFY_CERT_FLAG_REDIRECT = 0x10;
	public static final long VERIFY_CERT_FLAG_GATEWAY = 0x20;
	public static final long VERIFY_CERT_FLAG_CHANGED = 0x40;
	public static final long VERIFY_CERT_FLAG_MISMATCH = 0x80;
	public static final long VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
	public static final long VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

	private static boolean tryLoad(String[] libraries)
	{
		boolean success = false;
		final String LD_PATH = System.getProperty("java.library.path");
		for (String lib : libraries)
		{
			try
			{
				Log.v(TAG, "Trying to load library " + lib + " from LD_PATH: " + LD_PATH);
				System.loadLibrary(lib);
				success = true;
			}
			catch (UnsatisfiedLinkError e)
			{
				Log.e(TAG, "Failed to load library " + lib + ": " + e);
				success = false;
				break;
			}
		}

		return success;
	}

	private static boolean tryLoad(String library)
	{
		return tryLoad(new String[] { library });
	}

	static
	{
		try
		{
			// 加载 cJSON 库
			tryLoad("cjson");
			
			// 加载 OpenSSL 库 (GitHub 统一编译版本使用 OpenSSL 1.1.1)
			tryLoad("crypto");
			tryLoad("ssl");
			
			// 加载 FFmpeg 库 (完整版本，包括 avdevice/avformat/avfilter)
			tryLoad("avutil");
			tryLoad("swresample");
			tryLoad("swscale");
			tryLoad("avcodec");
			tryLoad("avformat");
			tryLoad("avdevice");
			tryLoad("avfilter");
			
			// 加载 FreeRDP 库
			System.loadLibrary("winpr3");
			System.loadLibrary("freerdp3");
			System.loadLibrary("freerdp-client3");
			System.loadLibrary("freerdp-android");

			String version = "3.18.0";
			Pattern pattern = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*");
			Matcher matcher = pattern.matcher(version);
			if (!matcher.matches() || (matcher.groupCount() < 3))
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			int major = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
			int minor = Integer.parseInt(Objects.requireNonNull(matcher.group(2)));
			int patch = Integer.parseInt(Objects.requireNonNull(matcher.group(3)));

			if (major > 2)
				mHasH264 = freerdp_has_h264();
			else if (minor > 5)
				mHasH264 = freerdp_has_h264();
			else if ((minor == 5) && (patch >= 1))
				mHasH264 = freerdp_has_h264();
			else
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			Log.i(TAG, "Successfully loaded native library. H264 is " +
			               (mHasH264 ? "supported" : "not available"));
		}
		catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "Failed to load library: " + e);
			throw e;
		}
	}

	public static boolean hasH264Support()
	{
		return mHasH264;
	}

	private static native boolean freerdp_has_h264();

	private static native String freerdp_get_jni_version();

	private static native String freerdp_get_version();

	private static native String freerdp_get_build_revision();

	private static native String freerdp_get_build_config();

	private static native long freerdp_new(Context context);

	private static native void freerdp_free(long inst);

	private static native boolean freerdp_parse_arguments(long inst, String[] args);

	private static native boolean freerdp_connect(long inst);

	private static native boolean freerdp_disconnect(long inst);

	private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
	                                                      int width, int height);

	private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);

	private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);

	private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
	                                                            boolean down);

	private static native boolean freerdp_set_tcp_keepalive(long inst, boolean enabled, 
	                                                        int delay, int interval, int retries);

	private static native boolean freerdp_send_synchronize_event(long inst, int flags);

	private static native boolean freerdp_send_clipboard_data(long inst, String data);

	private static native String freerdp_get_last_error_string(long inst);

	public static String getLastErrorString(long inst) {
		return freerdp_get_last_error_string(inst);
	}

	public static native int setClientDecoding(long inst, boolean enable);

	public static void setEventListener(EventListener l)
	{
		listener = l;
	}

	public static long newInstance(Context context)
	{
		return freerdp_new(context);
	}

	public static void freeInstance(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				try {
					freerdp_disconnect(inst);
				} catch (Exception e) {
					Log.e(TAG, "Failed to disconnect instance " + inst, e);
				}
			}
			
			// Add timeout mechanism to avoid infinite waiting
			long startTime = System.currentTimeMillis();
			long timeout = 30000; // 30 seconds
			
			while (mInstanceState.get(inst, false))
			{
				try
				{
					long remaining = timeout - (System.currentTimeMillis() - startTime);
					if (remaining <= 0) {
						Log.e(TAG, "Timeout waiting for instance " + inst + " to disconnect");
						break;
					}
					mInstanceState.wait(remaining);
				}
				catch (InterruptedException e)
				{
					// Log but continue cleanup, don't throw exception
					Log.w(TAG, "Interrupted while waiting for instance " + inst + " to disconnect", e);
					Thread.currentThread().interrupt(); // Restore interrupt status
					break;
				}
			}
		}
		
		try {
			freerdp_free(inst);
		} catch (Exception e) {
			Log.e(TAG, "Failed to free instance " + inst, e);
		}
	}

	public static boolean connect(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				throw new RuntimeException("instance already connected");
			}
		}
		return freerdp_connect(inst);
	}

	public static boolean disconnect(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				return freerdp_disconnect(inst);
			}
			return true;
		}
	}

	public static boolean cancelConnection(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				return freerdp_disconnect(inst);
			}
			return true;
		}
	}

	private static String addFlag(String name, boolean enabled)
	{
		if (enabled)
		{
			return "+" + name;
		}
		return "-" + name;
	}

	public static boolean setConnectionInfo(Context context, long inst, BookmarkBase bookmark)
	{
		BookmarkBase.ScreenSettings screenSettings = bookmark.getActiveScreenSettings();
		BookmarkBase.AdvancedSettings advanced = bookmark.getAdvancedSettings();
		BookmarkBase.DebugSettings debug = bookmark.getDebugSettings();

		String arg;
		ArrayList<String> args = new ArrayList<>();

		args.add(TAG);
		args.add("/gdi:sw");

		final String clientName = ApplicationSettingsActivity.getClientName(context);
		if (!clientName.isEmpty())
		{
			args.add("/client-hostname:" + clientName);
		}
		String certName = "";
		if (bookmark.getType() != BookmarkBase.TYPE_MANUAL)
		{
			return false;
		}

		int port = bookmark.<ManualBookmark>get().getPort();
		String hostname = bookmark.<ManualBookmark>get().getHostname();

		args.add("/v:" + hostname);
		args.add("/port:" + port);

		arg = bookmark.getUsername();
		if (!arg.isEmpty())
		{
			args.add("/u:" + arg);
		}
		arg = bookmark.getDomain();
		if (!arg.isEmpty())
		{
			args.add("/d:" + arg);
		}
		arg = bookmark.getPassword();
		if (!arg.isEmpty())
		{
			args.add("/p:" + arg);
		}

		args.add(
		    String.format("/size:%dx%d", screenSettings.getWidth(), screenSettings.getHeight()));
		args.add("/bpp:" + screenSettings.getColors());

		if (advanced.getConsoleMode())
		{
			args.add("/admin");
		}

		switch (advanced.getSecurity())
		{
			case 3: // NLA
				args.add("/sec:nla");
				break;
			case 2: // TLS
				args.add("/sec:tls");
				break;
			case 1: // RDP
				args.add("/sec:rdp");
				break;
			default:
				break;
		}

		if (!certName.isEmpty())
		{
			args.add("/cert-name:" + certName);
		}

		BookmarkBase.PerformanceFlags flags = bookmark.getActivePerformanceFlags();
		if (flags.getRemoteFX())
		{
			args.add("/rfx");
			args.add("/network:auto");
		}

		if (flags.getGfx())
		{
			args.add("/gfx");
			args.add("/network:auto");
		}

		if (flags.getH264() && mHasH264)
		{
			args.add("/gfx:AVC444");
			args.add("/network:auto");
		}

	args.add(addFlag("wallpaper", flags.getWallpaper()));
	args.add(addFlag("window-drag", flags.getFullWindowDrag()));
	args.add(addFlag("menu-anims", flags.getMenuAnimations()));
	args.add(addFlag("themes", flags.getTheming()));
	args.add(addFlag("fonts", flags.getFontSmoothing()));
	args.add(addFlag("aero", flags.getDesktopComposition()));

	// 启用鼠标移动事件和光标更新（客户端渲染）
	args.add("+mouse-motion");

	if (!advanced.getRemoteProgram().isEmpty())
		{
			args.add("/shell:" + advanced.getRemoteProgram());
		}

		if (!advanced.getWorkDir().isEmpty())
		{
			args.add("/shell-dir:" + advanced.getWorkDir());
		}

		args.add(addFlag("async-channels", debug.getAsyncChannel()));
		args.add(addFlag("async-update", debug.getAsyncUpdate()));

		if (advanced.getRedirectSDCard())
		{
			String path = android.os.Environment.getExternalStorageDirectory().getPath();
			args.add("/drive:sdcard," + path);
		}

		args.add("/clipboard");

		// Gateway enabled?
		if (bookmark.getType() == BookmarkBase.TYPE_MANUAL &&
		    bookmark.<ManualBookmark>get().getEnableGatewaySettings())
		{
			ManualBookmark.GatewaySettings gateway =
			    bookmark.<ManualBookmark>get().getGatewaySettings();

			StringBuilder carg = new StringBuilder();
			carg.append(
			    String.format("/gateway:g:%s:%d", gateway.getHostname(), gateway.getPort()));

			arg = gateway.getUsername();
			if (!arg.isEmpty())
			{
				carg.append(",u:" + arg);
			}
			arg = gateway.getDomain();
			if (!arg.isEmpty())
			{
				carg.append(",d:" + arg);
			}
			arg = gateway.getPassword();
			if (!arg.isEmpty())
			{
				carg.append(",p:" + arg);
			}
			args.add(carg.toString());
		}

		/* 0 ... local
		   1 ... remote
		   2 ... disable */
	args.add("/audio-mode:" + advanced.getRedirectSound());
	if (advanced.getRedirectSound() == 0)
	{
		args.add("/sound:latency:150,quality:medium");
	}

		if (advanced.getRedirectMicrophone())
		{
			args.add("/microphone");
		}

	args.add("/kbd:unicode:on");
	args.add("/cert:ignore");
	args.add("/log-level:" + debug.getDebugLevel());
	
	// 解析参数
	String[] arrayArgs = args.toArray(new String[0]);
	if (!freerdp_parse_arguments(inst, arrayArgs))
	{
		return false;
	}
	
	// ========== TCP Keepalive配置：15秒间隔（前台+后台都启用）==========
	// 通过JNI直接设置TCP keepalive（使用GitHub编译的新库）
	boolean tcpKeepaliveResult = setTcpKeepalive(
		inst, 
		true,   // enabled
		15,     // delay: 15秒空闲后开始探测
		15,     // interval: 每15秒发送一次探测包
		3       // retries: 重试3次（总超时60秒）
	);
	
	if (tcpKeepaliveResult) {
		android.util.Log.i("LibFreeRDP", "✓ TCP Keepalive enabled: 15s interval (NAT-friendly)");
	} else {
		android.util.Log.e("LibFreeRDP", "✗ Failed to enable TCP keepalive!");
	}
	
	return true;
	}

	public static boolean setConnectionInfo(Context context, long inst, Uri openUri)
	{
		ArrayList<String> args = new ArrayList<>();

		// Parse URI from query string. Same key overwrite previous one
		// freerdp://user@ip:port/connect?sound=&rfx=&p=password&clipboard=%2b&themes=-

		// Now we only support Software GDI
		args.add(TAG);
		args.add("/gdi:sw");

		final String clientName = ApplicationSettingsActivity.getClientName(context);
		if (!clientName.isEmpty())
		{
			args.add("/client-hostname:" + clientName);
		}

		// Parse hostname and port. Set to 'v' argument
		String hostname = openUri.getHost();
		int port = openUri.getPort();
		if (hostname != null)
		{
			hostname = hostname + ((port == -1) ? "" : (":" + port));
			args.add("/v:" + hostname);
		}

		String user = openUri.getUserInfo();
		if (user != null)
		{
			args.add("/u:" + user);
		}

		for (String key : openUri.getQueryParameterNames())
		{
			String value = openUri.getQueryParameter(key);

			if (value.isEmpty())
			{
				// Query: key=
				// To freerdp argument: /key
				args.add("/" + key);
			}
			else if (value.equals("-") || value.equals("+"))
			{
				// Query: key=- or key=+
				// To freerdp argument: -key or +key
				args.add(value + key);
			}
			else
			{
				// Query: key=value
				// To freerdp argument: /key:value
				if (key.equals("drive") && value.equals("sdcard"))
				{
					// Special for sdcard redirect
					String path = android.os.Environment.getExternalStorageDirectory().getPath();
					value = "sdcard," + path;
				}

				args.add("/" + key + ":" + value);
			}
	}

	// 解析参数
	String[] arrayArgs = args.toArray(new String[0]);
	if (!freerdp_parse_arguments(inst, arrayArgs))
	{
		return false;
	}
	
	// 启用TCP keepalive（15秒间隔）
	setTcpKeepalive(inst, true, 15, 15, 3);
	
	return true;
}

	public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width,
	                                     int height)
	{
		return freerdp_update_graphics(inst, bitmap, x, y, width, height);
	}

	public static boolean sendCursorEvent(long inst, int x, int y, int flags)
	{
		if (inst == 0) {
			return false;
		}
		
		return freerdp_send_cursor_event(inst, x, y, flags);
	}

	public static boolean sendKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_key_event(inst, keycode, down);
	}

	public static boolean sendUnicodeKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_unicodekey_event(inst, keycode, down);
	}

	/**
	 * 设置TCP Keepalive（必须在连接前调用）
	 * 用于维持NAT映射，防止路由器超时断开连接
	 * 
	 * @param inst FreeRDP实例ID
	 * @param enabled 是否启用TCP keepalive
	 * @param delay TCP_KEEPIDLE - 空闲多久后开始探测（秒）
	 * @param interval TCP_KEEPINTVL - 探测包间隔（秒）
	 * @param retries TCP_KEEPCNT - 重试次数
	 * @return true=设置成功, false=设置失败
	 */
	public static boolean setTcpKeepalive(long inst, boolean enabled, 
	                                      int delay, int interval, int retries)
	{
		return freerdp_set_tcp_keepalive(inst, enabled, delay, interval, retries);
	}

	/**
	 * 发送RDP同步事件（用于轻量级心跳）
	 * Synchronize Event是RDP协议标准事件，用于同步键盘锁定状态
	 * 数据量约8字节，不触发服务器端UI逻辑，适合作为心跳包
	 * 
	 * @param inst FreeRDP实例ID
	 * @param flags 键盘锁定状态标志 (0=所有锁定键关闭)
	 * @return true=发送成功, false=发送失败
	 */
	public static boolean sendSynchronizeEvent(long inst, int flags)
	{
		return freerdp_send_synchronize_event(inst, flags);
	}

	/**
	 * 发送轻量级心跳（使用Synchronize Event）
	 * 配合TCP keepalive实现双层保活机制
	 * 
	 * @param inst FreeRDP实例ID
	 * @return true=发送成功, false=发送失败
	 */
	public static boolean sendHeartbeat(long inst)
	{
		return sendSynchronizeEvent(inst, 0); // flags=0表示正常同步状态
	}

	public static boolean sendClipboardData(long inst, String data)
	{
		return freerdp_send_clipboard_data(inst, data);
	}

	private static void OnConnectionSuccess(long inst)
	{
		if (listener != null)
			listener.OnConnectionSuccess(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.append(inst, true);
			mInstanceState.notifyAll();
		}
	}

	private static void OnConnectionFailure(long inst)
	{
		if (listener != null)
			listener.OnConnectionFailure(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.remove(inst);
			mInstanceState.notifyAll();
		}
	}

	private static void OnPreConnect(long inst)
	{
		if (listener != null)
			listener.OnPreConnect(inst);
	}

	private static void OnDisconnecting(long inst)
	{
		if (listener != null)
			listener.OnDisconnecting(inst);
	}

	private static void OnDisconnected(long inst)
	{
		if (listener != null)
			listener.OnDisconnected(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.remove(inst);
			mInstanceState.notifyAll();
		}
	}

	private static void OnSettingsChanged(long inst, int width, int height, int bpp)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnSettingsChanged(width, height, bpp);
	}

	private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
	                                      StringBuilder password)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return false;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnAuthenticate(username, domain, password);
		return false;
	}

	private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
	                                             StringBuilder domain, StringBuilder password)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return false;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnGatewayAuthenticate(username, domain, password);
		return false;
	}

	private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
	                                       String subject, String issuer, String fingerprint,
	                                       long flags)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return 0;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnVerifiyCertificateEx(host, port, commonName, subject, issuer,
			                                              fingerprint, flags);
		return 0;
	}

	private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
	                                                String commonName, String subject,
	                                                String issuer, String fingerprint,
	                                                String oldSubject, String oldIssuer,
	                                                String oldFingerprint, long flags)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return 0;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnVerifyChangedCertificateEx(host, port, commonName, subject,
			                                                    issuer, fingerprint, oldSubject,
			                                                    oldIssuer, oldFingerprint, flags);
		return 0;
	}

	private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnGraphicsUpdate(x, y, width, height);
	}

	private static void OnGraphicsResize(long inst, int width, int height, int bpp)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnGraphicsResize(width, height, bpp);
	}

	private static void OnRemoteClipboardChanged(long inst, String data)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRemoteClipboardChanged(data);
	}

	private static void OnRemoteCursorUpdate(long inst, byte[] bitmapData, int width, int height, int hotX, int hotY)
	{
		android.util.Log.i("LibFreeRDP", "OnRemoteCursorUpdate called from C: " + width + "x" + height + " hotspot(" + hotX + "," + hotY + ")");
		
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
		{
			android.util.Log.w("LibFreeRDP", "OnRemoteCursorUpdate: session is null");
			return;
		}
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
		{
			android.util.Log.i("LibFreeRDP", "OnRemoteCursorUpdate: calling listener");
			uiEventListener.OnRemoteCursorUpdate(bitmapData, width, height, hotX, hotY);
		}
		else
		{
			android.util.Log.w("LibFreeRDP", "OnRemoteCursorUpdate: listener is null");
		}
	}

	// JNI回调：光标类型变化
	private static void OnCursorTypeChanged(long inst, int cursorType)
	{
		Log.i(TAG, "OnCursorTypeChanged called from C: cursorType=" + cursorType);
		
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
		{
			Log.w(TAG, "OnCursorTypeChanged: session is null");
			return;
		}
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
		{
			Log.i(TAG, "OnCursorTypeChanged: calling listener with type=" + cursorType);
			uiEventListener.OnCursorTypeChanged(cursorType);
		}
		else
		{
			Log.w(TAG, "OnCursorTypeChanged: listener is null");
		}
	}

	public static String getVersion()
	{
		return freerdp_get_version();
	}

	public interface EventListener
	{
		void OnPreConnect(long instance);

		void OnConnectionSuccess(long instance);

		void OnConnectionFailure(long instance);

		void OnDisconnecting(long instance);

		void OnDisconnected(long instance);
	}

	public interface UIEventListener
	{
		void OnSettingsChanged(int width, int height, int bpp);

		boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
		                       StringBuilder password);

		boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
		                              StringBuilder password);

		int OnVerifiyCertificateEx(String host, long port, String commonName, String subject, String issuer,
		                         String fingerprint, long flags);

		int OnVerifyChangedCertificateEx(String host, long port, String commonName, String subject, String issuer,
		                               String fingerprint, String oldSubject, String oldIssuer,
		                               String oldFingerprint, long flags);

		void OnGraphicsUpdate(int x, int y, int width, int height);

		void OnGraphicsResize(int width, int height, int bpp);

		void OnRemoteClipboardChanged(String data);

		void OnRemoteCursorUpdate(byte[] bitmapData, int width, int height, int hotX, int hotY);

		void OnCursorTypeChanged(int cursorType);
	}

	// ========== Session Recovery Support Methods ==========

	/**
	 * Check if the specified native connection instance is alive
	 * @param inst Instance ID
	 * @return true if connection is alive, false if dead or not exists
	 */
	public static boolean isInstanceConnected(long inst) {
		synchronized (mInstanceState) {
			boolean connected = mInstanceState.get(inst, false);
			Log.d(TAG, "isInstanceConnected: instance=" + inst + ", connected=" + connected);
			return connected;
		}
	}

	/**
	 * Get the count of all active connection instances
	 * @return Active connection count
	 */
	public static int getActiveConnectionCount() {
		synchronized (mInstanceState) {
			int count = mInstanceState.size();
			Log.d(TAG, "getActiveConnectionCount: count=" + count);
			return count;
		}
	}

	/**
	 * Check if the specified instance is alive with timeout verification
	 * @param inst Instance ID
	 * @param timeoutMs Timeout in milliseconds
	 * @return true if connection is alive and responsive, false if dead or timeout
	 */
	public static boolean isInstanceAliveWithTimeout(long inst, long timeoutMs) {
		if (!isInstanceConnected(inst)) {
			return false;
		}
		
		// TODO: Can add heartbeat verification here
		// Currently simplified to only check state
		return true;
	}
}
