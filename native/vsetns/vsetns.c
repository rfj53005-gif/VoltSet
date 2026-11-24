/*
 * Copyright 2026 VoltSet Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Inspired by the L1 private mount-namespace debugfs shim from
 * articlepp/voltset-sm8850-patch (Apache-2.0).
 *
 * Re-executes the target command inside a private mount namespace with `/`
 * set to MS_REC | MS_PRIVATE and `debugfs` mounted at `/sys/kernel/debug`,
 * leaving the host `/proc/mounts` untouched once the command exits.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <sys/mount.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: vsetns CMD [ARG...]\n");
        return 2;
    }
    if (unshare(CLONE_NEWNS) != 0) {
        perror("vsetns: unshare");
        return 3;
    }
    if (mount("none", "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        perror("vsetns: make-private");
        return 4;
    }
    if (mount("debugfs", "/sys/kernel/debug", "debugfs",
              MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) != 0 &&
        errno != EBUSY) {
        (void)mount("debugfs", "/sys/kernel/debug", "debugfs", 0, NULL);
    }
    execv(argv[1], &argv[1]);
    perror("vsetns: exec");
    return 5;
}
