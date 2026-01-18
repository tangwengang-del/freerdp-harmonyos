/*
   Session State class

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.application;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.services.LibFreeRDP;

public class SessionState implements Parcelable
{
	public static final Parcelable.Creator<SessionState> CREATOR =
	    new Parcelable.Creator<SessionState>() {
		    public SessionState createFromParcel(Parcel in)
		    {
			    return new SessionState(in);
		    }

		    @Override public SessionState[] newArray(int size)
		    {
			    return new SessionState[size];
		    }
	    };
	private final long instance;
	private final BookmarkBase bookmark;
	private final Uri openUri;
	// ✅ Bug修复 #1: 使用volatile确保跨线程可见性
	// 这些字段会被UI线程、Native回调线程和Connect线程访问
	private volatile BitmapDrawable surface;
	private volatile LibFreeRDP.UIEventListener uiEventListener;
	// 用于同步保护的锁对象
	private final Object stateLock = new Object();

	public SessionState(Parcel parcel)
	{
		instance = parcel.readLong();
		bookmark = parcel.readParcelable(null);
		openUri = parcel.readParcelable(null);

		Bitmap bitmap = parcel.readParcelable(null);
		surface = new BitmapDrawable(bitmap);
	}

	public SessionState(long instance, BookmarkBase bookmark)
	{
		this.instance = instance;
		this.bookmark = bookmark;
		this.openUri = null;
		this.uiEventListener = null;
	}

	public SessionState(long instance, Uri openUri)
	{
		this.instance = instance;
		this.bookmark = null;
		this.openUri = openUri;
		this.uiEventListener = null;
	}

	public void connect(Context context)
	{
		if (bookmark != null)
		{
			LibFreeRDP.setConnectionInfo(context, instance, bookmark);
		}
		else
		{
			LibFreeRDP.setConnectionInfo(context, instance, openUri);
		}
		LibFreeRDP.connect(instance);
	}

	public long getInstance()
	{
		return instance;
	}

	public BookmarkBase getBookmark()
	{
		return bookmark;
	}

	public Uri getOpenUri()
	{
		return openUri;
	}

	// ✅ Bug修复 #1: 添加synchronized保护，确保线程安全
	public LibFreeRDP.UIEventListener getUIEventListener()
	{
		synchronized (stateLock) {
			return uiEventListener;
		}
	}

	public void setUIEventListener(LibFreeRDP.UIEventListener uiEventListener)
	{
		synchronized (stateLock) {
			this.uiEventListener = uiEventListener;
		}
	}

	public BitmapDrawable getSurface()
	{
		synchronized (stateLock) {
			return surface;
		}
	}

	public void setSurface(BitmapDrawable surface)
	{
		synchronized (stateLock) {
			this.surface = surface;
		}
	}

	@Override public int describeContents()
	{
		return 0;
	}

	@Override public void writeToParcel(Parcel out, int flags)
	{
		out.writeLong(instance);
		out.writeParcelable(bookmark, flags);
		out.writeParcelable(openUri, flags);
		// ✅ Bug修复 #1: 添加同步保护和null检查
		synchronized (stateLock) {
			out.writeParcelable(surface != null ? surface.getBitmap() : null, flags);
		}
	}
}
