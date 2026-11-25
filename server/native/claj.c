/*
 * This file is part of CLaJ. Copyright (c) 2026  Xpdustry. GPL-3.0-or-later, see the Java sources.
 *
 * libclaj and its JNI binding.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <jni.h>

/* The result of a call that sets errno: itself, or -errno. */
#define RESULT(call) int n; do n = (call); while (n < 0 && errno == EINTR); return n < 0 ? -errno : n

int claj_recvmmsg(int fd, void *vector, unsigned count) {
  RESULT(recvmmsg(fd, vector, count, MSG_DONTWAIT, NULL));
}

int claj_sendmmsg(int fd, void *vector, unsigned count) {
  RESULT(sendmmsg(fd, vector, count, MSG_DONTWAIT));
}

int claj_setsockopt_int(int fd, int level, int name, int value) {
  RESULT(setsockopt(fd, level, name, &value, sizeof(value)));
}

int claj_getsockopt_int(int fd, int level, int name, int *value) {
  socklen_t length = sizeof(*value);
  RESULT(getsockopt(fd, level, name, value, &length));
}

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
