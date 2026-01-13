/*
   ListPreference to store/load integer values

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.utils;

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.util.Log;

public class IntListPreference extends ListPreference
{

	public IntListPreference(Context context)
	{
		super(context);
	}

	public IntListPreference(Context context, AttributeSet attrs)
	{
		super(context, attrs);
	}

	@Override protected String getPersistedString(String defaultReturnValue)
	{
		int value = getPersistedInt(-1);
		if (value == -1 && defaultReturnValue != null)
		{
			return defaultReturnValue;
		}
		return String.valueOf(value);
	}

	@Override protected boolean persistString(String value)
	{
		int intValue = Integer.parseInt(value);
		Log.d("IntListPreference", "persistString called: key=" + getKey() + ", value=" + value + " (int=" + intValue + ")");
		boolean result = persistInt(intValue);
		Log.d("IntListPreference", "persistInt result: " + result);
		return result;
	}

	@Override protected void onPrepareDialogBuilder(android.app.AlertDialog.Builder builder)
	{
		// 确保有一个有效的值
		String value = getValue();
		Log.d("IntListPreference", "onPrepareDialogBuilder: key=" + getKey() + ", value=" + value);
		
		if (value == null)
		{
			CharSequence[] entryValues = getEntryValues();
			if (entryValues != null && entryValues.length > 0)
			{
				String defaultValue = entryValues[0].toString();
				Log.d("IntListPreference", "Setting default value: " + defaultValue);
				value = defaultValue;
				setValue(defaultValue);
				// 强制持久化
				persistString(defaultValue);
			}
		}
		
		// 手动实现对话框内容，避免调用父类方法导致的 NullPointerException
		final CharSequence[] entries = getEntries();
		final CharSequence[] entryValues = getEntryValues();
		
		if (entries == null || entryValues == null)
		{
			return;
		}
		
		// 找到当前选中的索引
		int selectedIndex = 0;
		if (value != null)
		{
			for (int i = 0; i < entryValues.length; i++)
			{
				if (entryValues[i] != null && value.equals(entryValues[i].toString()))
				{
					selectedIndex = i;
					break;
				}
			}
		}
		
		final int finalSelectedIndex = selectedIndex;
		
		builder.setSingleChoiceItems(entries, selectedIndex, 
			new android.content.DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(android.content.DialogInterface dialog, int which)
				{
					if (which < 0 || which >= entryValues.length || entryValues[which] == null)
					{
						Log.e("IntListPreference", "Invalid selection index: " + which);
						dialog.dismiss();
						return;
					}
					
					String newValue = entryValues[which].toString();
					Log.d("IntListPreference", "Selected: " + newValue);
					
					if (callChangeListener(newValue))
					{
						setValue(newValue);
						persistString(newValue);
					}
					dialog.dismiss();
				}
			});
		
		builder.setPositiveButton(null, null);
	}

	@Override public CharSequence getSummary()
	{
		try
		{
			// 获取当前值
			String value = getValue();
			Log.d("IntListPreference", "getSummary() called, key=" + getKey() + ", value=" + value);
			
			// 如果值为 null，返回第一个条目作为默认显示
			if (value == null)
			{
				CharSequence[] entries = getEntries();
				if (entries != null && entries.length > 0)
				{
					Log.d("IntListPreference", "Value is null, returning first entry: " + entries[0]);
					return entries[0];
				}
				return "";
			}
			
			// 查找对应的条目
			CharSequence[] entryValues = getEntryValues();
			CharSequence[] entries = getEntries();
			
			if (entryValues != null && entries != null)
			{
				for (int i = 0; i < entryValues.length; i++)
				{
					if (value.equals(entryValues[i].toString()))
					{
						Log.d("IntListPreference", "Found matching entry: " + entries[i]);
						return entries[i];
					}
				}
			}
			
			// 如果找不到匹配的，返回第一个条目
			if (entries != null && entries.length > 0)
			{
				Log.d("IntListPreference", "No match found, returning first entry: " + entries[0]);
				return entries[0];
			}
			return "";
		}
		catch (Exception e)
		{
			Log.e("IntListPreference", "Error in getSummary(): " + e.getMessage(), e);
			return "";
		}
	}
}
