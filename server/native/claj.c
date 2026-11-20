/*
 * This file is part of CLaJ. Copyright (c) 2026  Xpdustry. GPL-3.0-or-later, see the Java sources.
 *
 * libclaj, see claj.h.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stddef.h>
#include <sys/socket.h>

#include "claj.h"

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
