/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android JNI Callback Helpers
 *
 * Copyright 2011-2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <stdio.h>

#include "android_jni_callback.h"
#include "android_freerdp_jni.h"

#include <freerdp/log.h>
#define TAG CLIENT_TAG("android.callback")

static JavaVM* jVM;
static jclass jLibFreeRDPClass; /* Changed to jclass global reference */

jint init_callback_environment(JavaVM* vm, JNIEnv* env, jclass libFreeRDPClass)
{
	jVM = vm;
	jLibFreeRDPClass = (*env)->NewGlobalRef(env, libFreeRDPClass);
	if (!jLibFreeRDPClass)
	{
		WLog_ERR(TAG, "failed to create global reference for LibFreeRDP class");
		return -1;
	}
	return JNI_VERSION_1_6;
}

void deinit_callback_environment(JNIEnv* env)
{
	if (jLibFreeRDPClass)
	{
		(*env)->DeleteGlobalRef(env, jLibFreeRDPClass);
		jLibFreeRDPClass = NULL;
	}
}

/* attach current thread to jvm */
jboolean jni_attach_thread(JNIEnv** env)
{
	if ((*jVM)->GetEnv(jVM, (void**)env, JNI_VERSION_1_4) != JNI_OK)
	{
		WLog_DBG(TAG, "android_java_callback: attaching current thread");
		(*jVM)->AttachCurrentThread(jVM, env, NULL);

		if ((*jVM)->GetEnv(jVM, (void**)env, JNI_VERSION_1_4) != JNI_OK)
		{
			WLog_ERR(TAG, "android_java_callback: failed to obtain current JNI environment");
		}

		return JNI_TRUE;
	}

	return JNI_FALSE;
}

/* attach current thread to JVM */
void jni_detach_thread()
{
	(*jVM)->DetachCurrentThread(jVM);
}

/* callback with void result */
static void java_callback_void(const char* callback, const char* signature,
                               va_list args)
{
	jmethodID jCallback;
	jboolean attached;
	JNIEnv* env;
	WLog_DBG(TAG, "java_callback: %s (%s)", callback, signature);
	attached = jni_attach_thread(&env);

	if (!jLibFreeRDPClass)
	{
		WLog_ERR(TAG, "android_java_callback: jLibFreeRDPClass is null");
		goto finish;
	}

	jCallback = (*env)->GetStaticMethodID(env, jLibFreeRDPClass, callback, signature);

	if (!jCallback)
	{
		WLog_ERR(TAG, "android_java_callback: failed to get method id %s with signature %s", callback, signature);
		goto finish;
	}

	(*env)->CallStaticVoidMethodV(env, jLibFreeRDPClass, jCallback, args);
finish:

	if (attached == JNI_TRUE)
		jni_detach_thread();
}

/* callback with bool result */
static jboolean java_callback_bool(const char* callback, const char* signature,
                                   va_list args)
{
	jmethodID jCallback;
	jboolean attached;
	jboolean res = JNI_FALSE;
	JNIEnv* env;
	WLog_DBG(TAG, "java_callback: %s (%s)", callback, signature);
	attached = jni_attach_thread(&env);

	if (!jLibFreeRDPClass)
	{
		WLog_ERR(TAG, "android_java_callback: jLibFreeRDPClass is null");
		goto finish;
	}

	jCallback = (*env)->GetStaticMethodID(env, jLibFreeRDPClass, callback, signature);

	if (!jCallback)
	{
		WLog_ERR(TAG, "android_java_callback: failed to get method id %s with signature %s", callback, signature);
		goto finish;
	}

	res = (*env)->CallStaticBooleanMethodV(env, jLibFreeRDPClass, jCallback, args);
finish:

	if (attached == JNI_TRUE)
		jni_detach_thread();

	return res;
}

/* callback with int result */
static jint java_callback_int(const char* callback, const char* signature,
                              va_list args)
{
	jmethodID jCallback;
	jboolean attached;
	jint res = -1;
	JNIEnv* env;
	WLog_DBG(TAG, "java_callback: %s (%s)", callback, signature);
	attached = jni_attach_thread(&env);

	if (!jLibFreeRDPClass)
	{
		WLog_ERR(TAG, "android_java_callback: jLibFreeRDPClass is null");
		goto finish;
	}

	jCallback = (*env)->GetStaticMethodID(env, jLibFreeRDPClass, callback, signature);

	if (!jCallback)
	{
		WLog_ERR(TAG, "android_java_callback: failed to get method id %s with signature %s", callback, signature);
		goto finish;
	}

	res = (*env)->CallStaticIntMethodV(env, jLibFreeRDPClass, jCallback, args);
finish:

	if (attached == JNI_TRUE)
		jni_detach_thread();

	return res;
}

/* callback to freerdp class */
void freerdp_callback(const char* callback, const char* signature, ...)
{
	va_list vl;
	va_start(vl, signature);
	java_callback_void(callback, signature, vl);
	va_end(vl);
}

jboolean freerdp_callback_bool_result(const char* callback, const char* signature, ...)
{
	va_list vl;
	va_start(vl, signature);
	jboolean res = java_callback_bool(callback, signature, vl);
	va_end(vl);
	return res;
}

jint freerdp_callback_int_result(const char* callback, const char* signature, ...)
{
	va_list vl;
	va_start(vl, signature);
	jint res = java_callback_int(callback, signature, vl);
	va_end(vl);
	return res;
}
