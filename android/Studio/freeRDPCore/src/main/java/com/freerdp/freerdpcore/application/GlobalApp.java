/*
   Android Main Application

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.application;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;

import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;
import com.freerdp.freerdpcore.services.BookmarkDB;
import com.freerdp.freerdpcore.services.HistoryDB;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.services.ManualBookmarkGateway;
import com.freerdp.freerdpcore.services.QuickConnectHistoryGateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class GlobalApp extends Application implements LibFreeRDP.EventListener, ComponentCallbacks2
{
	// event notification defines
	public static final String EVENT_TYPE = "EVENT_TYPE";
	public static final String EVENT_PARAM = "EVENT_PARAM";
	public static final String EVENT_STATUS = "EVENT_STATUS";
	public static final String EVENT_ERROR = "EVENT_ERROR";
	public static final String ACTION_EVENT_FREERDP = "com.freerdp.freerdp.event.freerdp";
	public static final int FREERDP_EVENT_CONNECTION_SUCCESS = 1;
	public static final int FREERDP_EVENT_CONNECTION_FAILURE = 2;
	public static final int FREERDP_EVENT_DISCONNECTED = 3;
	private static final String TAG = "GlobalApp";
	public static boolean ConnectedTo3G = false;
	private static Map<Long, SessionState> sessionMap;
	private static BookmarkDB bookmarkDB;
	private static ManualBookmarkGateway manualBookmarkGateway;

	private static HistoryDB historyDB;
	private static QuickConnectHistoryGateway quickConnectHistoryGateway;

	// Timer mechanism removed - connection maintained by Foreground Service + heartbeat

	public static ManualBookmarkGateway getManualBookmarkGateway()
	{
		return manualBookmarkGateway;
	}

	public static QuickConnectHistoryGateway getQuickConnectHistoryGateway()
	{
		return quickConnectHistoryGateway;
	}

	// RDP session handling
	static public SessionState createSession(BookmarkBase bookmark, Context context)
	{
		SessionState session = new SessionState(LibFreeRDP.newInstance(context), bookmark);
		// ✅ 使用synchronized确保线程安全
		synchronized (sessionMap) {
			sessionMap.put(session.getInstance(), session);
		}
		return session;
	}

	static public SessionState createSession(Uri openUri, Context context)
	{
		SessionState session = new SessionState(LibFreeRDP.newInstance(context), openUri);
		// ✅ 使用synchronized确保线程安全
		synchronized (sessionMap) {
			sessionMap.put(session.getInstance(), session);
		}
		return session;
	}

	static public SessionState getSession(long instance)
	{
		// ✅ 使用synchronized确保线程安全
		synchronized (sessionMap) {
			return sessionMap.get(instance);
		}
	}

	static public Collection<SessionState> getSessions()
	{
		// return a copy of the session items
		// ✅ 使用synchronized确保线程安全
		synchronized (sessionMap) {
			return new ArrayList<>(sessionMap.values());
		}
	}

	static public void freeSession(long instance)
	{
		// ✅ 使用synchronized确保线程安全
		// Important: do NOT call into native teardown while holding sessionMap lock
		// to avoid long lock holds / potential lock inversions with JNI callbacks.
		boolean shouldFree = false;
		synchronized (sessionMap)
		{
			if (GlobalApp.sessionMap.containsKey(instance))
			{
				GlobalApp.sessionMap.remove(instance);
				shouldFree = true;
			}
		}
		if (shouldFree)
			LibFreeRDP.freeInstance(instance);
	}

	/**
	 * Add an existing SessionState to the session map
	 * Used for recovering SessionState after process kill
	 * @param instance Instance ID
	 * @param session SessionState object
	 */
	public static void addSession(long instance, SessionState session) {
		if (sessionMap != null) {
			synchronized (sessionMap) {
				sessionMap.put(instance, session);
				Log.d(TAG, "Session added to map: instance=" + instance);
			}
		} else {
			Log.e(TAG, "Cannot add session: sessionMap is null");
		}
	}

	/**
	 * Remove SessionState without freeing native resources
	 * Used for cleaning up old SessionState references
	 * @param instance Instance ID
	 */
	public static void removeSession(long instance) {
		if (sessionMap != null) {
			synchronized (sessionMap) {
				SessionState removed = sessionMap.remove(instance);
				if (removed != null) {
					Log.d(TAG, "Session removed from map: instance=" + instance);
				} else {
					Log.w(TAG, "Session not found in map: instance=" + instance);
				}
			}
		} else {
			Log.e(TAG, "Cannot remove session: sessionMap is null");
		}
	}

	/**
	 * Check if SessionState exists
	 * @param instance Instance ID
	 * @return true if exists, false otherwise
	 */
	public static boolean hasSession(long instance) {
		if (sessionMap != null) {
			synchronized (sessionMap) {
				return sessionMap.containsKey(instance);
			}
		}
		return false;
	}

	/**
	 * ✅ Bug修复 #5: 原子地替换SessionState
	 * 这个方法确保检查、移除、添加是原子操作，避免竞态条件
	 * @param instance Instance ID
	 * @param newSession New SessionState object
	 * @return Old SessionState if existed, null otherwise
	 */
	public static SessionState replaceSession(long instance, SessionState newSession) {
		if (sessionMap != null) {
			synchronized (sessionMap) {
				SessionState oldSession = sessionMap.remove(instance);
				if (oldSession != null) {
					Log.d(TAG, "Replacing existing session: instance=" + instance);
				} else {
					Log.d(TAG, "No existing session to replace, adding new: instance=" + instance);
				}
				sessionMap.put(instance, newSession);
				return oldSession;
			}
		} else {
			Log.e(TAG, "Cannot replace session: sessionMap is null");
			return null;
		}
	}

	@Override public void onCreate()
	{
		super.onCreate();

		/* Initialize preferences. */
		ApplicationSettingsActivity.get(this);

		sessionMap = Collections.synchronizedMap(new HashMap<Long, SessionState>());

		LibFreeRDP.setEventListener(this);

		bookmarkDB = new BookmarkDB(this);

		manualBookmarkGateway = new ManualBookmarkGateway(bookmarkDB);

		historyDB = new HistoryDB(this);
		quickConnectHistoryGateway = new QuickConnectHistoryGateway(historyDB);

		ConnectedTo3G = NetworkStateReceiver.isConnectedTo3G(this);

		// init screen receiver here (this can't be declared in AndroidManifest - refer to:
		// http://thinkandroid.wordpress.com/2010/01/24/handling-screen-off-and-screen-on-intents/
		IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		registerReceiver(new ScreenReceiver(), filter);
	}

	// helper to send FreeRDP notifications
	private void sendRDPNotification(int type, long param)
	{
		// send broadcast
		Intent intent = new Intent(ACTION_EVENT_FREERDP);
		intent.putExtra(EVENT_TYPE, type);
		intent.putExtra(EVENT_PARAM, param);
		sendBroadcast(intent);
	}

	@Override public void OnPreConnect(long instance)
	{
		Log.v(TAG, "OnPreConnect");
	}

	// //////////////////////////////////////////////////////////////////////
	// Implementation of LibFreeRDP.EventListener
	public void OnConnectionSuccess(long instance)
	{
		Log.v(TAG, "OnConnectionSuccess");
		sendRDPNotification(FREERDP_EVENT_CONNECTION_SUCCESS, instance);
	}

	public void OnConnectionFailure(long instance)
	{
		Log.v(TAG, "OnConnectionFailure");

		// send notification to session activity
		sendRDPNotification(FREERDP_EVENT_CONNECTION_FAILURE, instance);
	}

	public void OnDisconnecting(long instance)
	{
		Log.v(TAG, "OnDisconnecting");
	}

	public void OnDisconnected(long instance)
	{
		Log.v(TAG, "OnDisconnected");
		sendRDPNotification(FREERDP_EVENT_DISCONNECTED, instance);
	}

	// //////////////////////////////////////////////////////////////////////
	// Implementation of ComponentCallbacks2 - Memory management
	
	@Override
	public void onTrimMemory(int level)
	{
		super.onTrimMemory(level);
		
		Log.w(TAG, "onTrimMemory called with level: " + level + " (" + getTrimLevelName(level) + ")");
		
		switch (level)
		{
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
				// 🚨 Severe memory pressure - aggressive cleanup
				Log.e(TAG, "🚨 CRITICAL memory pressure - notifying all sessions");
				handleCriticalMemoryPressure();
				break;
				
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
				// ⚠️ Medium memory pressure - moderate cleanup
				Log.w(TAG, "⚠️ LOW memory pressure - notifying all sessions");
				handleLowMemoryPressure();
				break;
				
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
				// 📝 Light memory pressure
				Log.i(TAG, "📝 MODERATE memory pressure - light cleanup");
				handleModerateMemoryPressure();
				break;
				
			case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
				// UI not visible (background)
				Log.i(TAG, "UI hidden - background cleanup");
				handleBackgroundCleanup();
				break;
				
			case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
			case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
				// App in background with extreme memory pressure
				Log.e(TAG, "BACKGROUND/COMPLETE - extreme pressure");
				handleCriticalMemoryPressure();
				break;
		}
	}
	
	@Override
	public void onLowMemory()
	{
		super.onLowMemory();
		Log.e(TAG, "❌ onLowMemory - EMERGENCY cleanup (equivalent to TRIM_MEMORY_COMPLETE)");
		// Most severe memory pressure
		handleCriticalMemoryPressure();
	}
	
	private void handleCriticalMemoryPressure()
	{
		// Notify all active sessions to perform aggressive memory cleanup
		Collection<SessionState> sessions = getSessions();
		for (SessionState session : sessions)
		{
			Intent intent = new Intent("com.freerdp.MEMORY_PRESSURE");
			intent.putExtra("level", "critical");
			intent.putExtra("instance", session.getInstance());
			sendBroadcast(intent);
		}
		
		// Suggest GC (system will decide)
		System.gc();
		
		Log.e(TAG, "Critical memory pressure handled for " + sessions.size() + " session(s)");
	}
	
	private void handleLowMemoryPressure()
	{
		// Notify all active sessions to perform moderate cleanup
		Collection<SessionState> sessions = getSessions();
		for (SessionState session : sessions)
		{
			Intent intent = new Intent("com.freerdp.MEMORY_PRESSURE");
			intent.putExtra("level", "low");
			intent.putExtra("instance", session.getInstance());
			sendBroadcast(intent);
		}
		
		Log.w(TAG, "Low memory pressure handled for " + sessions.size() + " session(s)");
	}
	
	private void handleModerateMemoryPressure()
	{
		// Notify all active sessions to perform light cleanup
		Collection<SessionState> sessions = getSessions();
		for (SessionState session : sessions)
		{
			Intent intent = new Intent("com.freerdp.MEMORY_PRESSURE");
			intent.putExtra("level", "moderate");
			intent.putExtra("instance", session.getInstance());
			sendBroadcast(intent);
		}
		
		Log.i(TAG, "Moderate memory pressure handled for " + sessions.size() + " session(s)");
	}
	
	private void handleBackgroundCleanup()
	{
		// UI went to background - light cleanup
		Log.i(TAG, "App in background, sessions will handle via onPause");
	}
	
	private String getTrimLevelName(int level)
	{
		switch (level)
		{
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL: return "RUNNING_CRITICAL";
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW: return "RUNNING_LOW";
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE: return "RUNNING_MODERATE";
			case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN: return "UI_HIDDEN";
			case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND: return "BACKGROUND";
			case ComponentCallbacks2.TRIM_MEMORY_MODERATE: return "MODERATE";
			case ComponentCallbacks2.TRIM_MEMORY_COMPLETE: return "COMPLETE";
			default: return "UNKNOWN(" + level + ")";
		}
	}
}
