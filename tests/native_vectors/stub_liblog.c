/**
 * Stubs for Android-specific symbols missing from glibc.
 * Allows Android NDK ARM binaries to be loaded in a standard
 * Linux armel environment under QEMU.
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

/* -- Bionic libc stubs -- */

/* Bionic's __sF: array of FILE structs for stdin/stdout/stderr */
FILE __sF[3];

__attribute__((constructor))
static void init_sF(void) {
    __sF[0] = *stdin;
    __sF[1] = *stdout;
    __sF[2] = *stderr;
}

/* Bionic's __errno() -> thread-local errno pointer */
extern int *__errno_location(void);
int *__errno(void) {
    return __errno_location();
}

/* Bionic fortify wrappers — just forward to the real functions */
ssize_t __write_chk(int fd, const void *buf, size_t count, size_t buf_size) {
    (void)buf_size;
    return write(fd, buf, count);
}

ssize_t __read_chk(int fd, void *buf, size_t count, size_t buf_size) {
    (void)buf_size;
    return read(fd, buf, count);
}

void *__memcpy_chk(void *dest, const void *src, size_t n, size_t dest_size) {
    (void)dest_size;
    return memcpy(dest, src, n);
}

void *__memmove_chk(void *dest, const void *src, size_t n, size_t dest_size) {
    (void)dest_size;
    return memmove(dest, src, n);
}

void *__memset_chk(void *s, int c, size_t n, size_t s_size) {
    (void)s_size;
    return memset(s, c, n);
}

char *__strcpy_chk(char *dest, const char *src, size_t dest_size) {
    (void)dest_size;
    return strcpy(dest, src);
}

char *__strcat_chk(char *dest, const char *src, size_t dest_size) {
    (void)dest_size;
    return strcat(dest, src);
}

int __snprintf_chk(char *s, size_t maxlen, int flags, size_t slen,
                   const char *format, ...) {
    (void)flags; (void)slen;
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(s, maxlen, format, ap);
    va_end(ap);
    return ret;
}

int __vsnprintf_chk(char *s, size_t maxlen, int flags, size_t slen,
                    const char *format, va_list ap) {
    (void)flags; (void)slen;
    return vsnprintf(s, maxlen, format, ap);
}

/* Bionic's __open_2 is open() with 2 args (no mode) */
int __open_2(const char *path, int flags) {
    return open(path, flags);
}

/* -- Android logging stubs -- */

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio; (void)tag; (void)fmt;
    return 0;
}

int __android_log_write(int prio, const char *tag, const char *text) {
    (void)prio; (void)tag; (void)text;
    return 0;
}

void __android_log_assert(const char *cond, const char *tag,
                          const char *fmt, ...) {
    (void)cond; (void)tag; (void)fmt;
    abort();
}

int __android_log_vprint(int prio, const char *tag, const char *fmt,
                         va_list ap) {
    (void)prio; (void)tag; (void)fmt; (void)ap;
    return 0;
}

/* Bionic's __assert2 (assertion failure handler) */
void __assert2(const char *file, int line, const char *func,
               const char *expr) {
    fprintf(stderr, "Assertion failed: %s (%s:%d in %s)\n",
            expr, file, line, func);
    abort();
}

/* Bionic's property_get (used by some NDK libs) */
int __system_property_get(const char *name, char *value) {
    (void)name;
    value[0] = '\0';
    return 0;
}
