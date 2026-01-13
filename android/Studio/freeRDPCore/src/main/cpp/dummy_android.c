#include <jni.h>

JNIEXPORT jstring JNICALL Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1get_1jni_1version(JNIEnv *env, jclass cls)
{
    return (*env)->NewStringUTF(env, "3.18.0");
}

