/*
 * This file is part of CLaJ. Copyright (c) 2026  Xpdustry. GPL-3.0-or-later, see the Java sources.
 *
 * libclaj: the Linux system calls a relay needs and Java lacks, on descriptors the caller owns. Plain C, no JNI
 * types, so other languages can bind the same file. Every call returns a count or 0 on success, or -errno; none
 * waits unless asked to, and each retries EINTR. Nothing here allocates, closes or keeps anything.
 */
#ifndef CLAJ_H
#define CLAJ_H

/* recvmmsg(fd, vector, count, MSG_DONTWAIT, NULL): messages received, -EAGAIN when the socket is empty. */
int claj_recvmmsg(int fd, void *vector, unsigned count);
/* sendmmsg(fd, vector, count, MSG_DONTWAIT): how many messages from the start of the vector the socket took. */
int claj_sendmmsg(int fd, void *vector, unsigned count);

/* setsockopt and getsockopt of an int option. */
int claj_setsockopt_int(int fd, int level, int name, int value);
int claj_getsockopt_int(int fd, int level, int name, int *value);

#endif
