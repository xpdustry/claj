/*
 * This file is part of CLaJ. Copyright (c) 2026  Xpdustry. GPL-3.0-or-later, see the Java sources.
 *
 * The natives of com.xpdustry.claj.natives.LibClaj: each forwards to claj.c, with native addresses as longs.
 * Registered from JNI_OnLoad, nothing exported but it.
 */
#include <stdint.h>
#include <jni.h>

#include "claj.h"

#define PTR(address) ((void *)(intptr_t)(address))

static jint JNICALL recvmmsg0(JNIEnv *env, jclass cls, jint fd, jlong vector, jint count) {
  return claj_recvmmsg(fd, PTR(vector), (unsigned)count);
}

static jint JNICALL sendmmsg0(JNIEnv *env, jclass cls, jint fd, jlong vector, jint count) {
  return claj_sendmmsg(fd, PTR(vector), (unsigned)count);
}

static jint JNICALL setsockopt0(JNIEnv *env, jclass cls, jint fd, jint level, jint name, jint value) {
  return claj_setsockopt_int(fd, level, name, value);
}

/* The value, or -errno: for options whose values aren't negative. */
static jint JNICALL getsockopt0(JNIEnv *env, jclass cls, jint fd, jint level, jint name) {
  int value, n = claj_getsockopt_int(fd, level, name, &value);
  return n < 0 ? n : value;
}

static jlong JNICALL address0(JNIEnv *env, jclass cls, jobject buffer) {
  return (jlong)(intptr_t)(*env)->GetDirectBufferAddress(env, buffer);
}

static const JNINativeMethod methods[] = {
  {"recvmmsg0", "(IJI)I", (void *)recvmmsg0},
  {"sendmmsg0", "(IJI)I", (void *)sendmmsg0},
  {"setsockopt0", "(IIII)I", (void *)setsockopt0},
  {"getsockopt0", "(III)I", (void *)getsockopt0},
  {"address0", "(Ljava/nio/Buffer;)J", (void *)address0},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = (*env)->FindClass(env, "com/xpdustry/claj/server/stream/LibClaj");
  if (cls == NULL || (*env)->RegisterNatives(env, cls, methods, sizeof(methods) / sizeof(methods[0])) != 0)
    return JNI_ERR;
  return JNI_VERSION_1_6;
}
