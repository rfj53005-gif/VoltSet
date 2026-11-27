/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <glob.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifndef SYSFS_ROOT
#define SYSFS_ROOT "/sys"
#endif
#define POWER_MONITOR SYSFS_ROOT "/class/powercap/qpt"
#define CPU_POLICIES SYSFS_ROOT "/devices/system/cpu/cpufreq/policy[0-9]*"
#define GPU_CLOCK SYSFS_ROOT "/class/kgsl/kgsl-3d0/gpuclk"
#define THERMAL_ZONES SYSFS_ROOT "/class/thermal/thermal_zone[0-9]*"
#define MAX_THERMAL_ZONES 16
#define SECOND_NS UINT64_C(1000000000)
#define LEASE_NS (15 * SECOND_NS)

struct rail {
    const char *directory;
    char name[64];
};

struct lease {
    const char *path;
    struct timespec modified;
    uint64_t refreshed_ns;
};

static volatile sig_atomic_t stopping;

static void stop_monitor(int signum) {
    (void)signum;
    stopping = 1;
}

static uint64_t sample_time(void) {
    struct timespec now;
    clock_gettime(CLOCK_BOOTTIME, &now);
    return (uint64_t)now.tv_sec * SECOND_NS + (uint64_t)now.tv_nsec;
}

static bool read_text(const char *path, char *text, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t length = read(fd, text, size - 1);
    int error = errno;
    close(fd);
    if (length <= 0) {
        errno = length < 0 ? error : EIO;
        return false;
    }
    while (length > 0 && isspace((unsigned char)text[length - 1])) --length;
    text[length] = '\0';
    return true;
}

static bool read_number(const char *path, uint64_t *value) {
    char text[32];
    if (!read_text(path, text, sizeof(text))) return false;
    char *end;
    errno = 0;
    unsigned long long parsed = strtoull(text, &end, 10);
    if (!isdigit((unsigned char)text[0]) || *end || errno) {
        errno = EINVAL;
        return false;
    }
    *value = parsed;
    return true;
}

static bool read_attribute(const char *directory, const char *name, uint64_t *value) {
    char path[PATH_MAX];
    int length = snprintf(path, sizeof(path), "%s/%s", directory, name);
    return length > 0 && (size_t)length < sizeof(path) && read_number(path, value);
}

static bool set_enabled(const char *text) {
    int fd = open(POWER_MONITOR "/enabled", O_WRONLY | O_CLOEXEC);
    if (fd < 0) return false;
    bool written = write(fd, text, 2) == 2;
    close(fd);
    return written;
}

static bool lease_is_live(struct lease *lease) {
    if (!lease->path) return true;
    struct stat info;
    if (stat(lease->path, &info) != 0 || !S_ISREG(info.st_mode)) return false;
    uint64_t now = sample_time();
    if (!lease->refreshed_ns || lease->modified.tv_sec != info.st_mtim.tv_sec ||
        lease->modified.tv_nsec != info.st_mtim.tv_nsec) {
        lease->refreshed_ns = now;
        lease->modified = info.st_mtim;
    }
    return now - lease->refreshed_ns < LEASE_NS;
}

static bool wait_until(uint64_t deadline, bool watch_stdin) {
    uint64_t now = sample_time();
    int timeout_ms = deadline > now ? (int)((deadline - now + 999999) / 1000000) : 0;
    struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN };
    return !stopping && poll(&input, watch_stdin ? 1 : 0, timeout_ms) == 0 && !stopping;
}

static size_t load_rails(const glob_t *zones, struct rail *rails) {
    size_t count = 0;
    for (size_t i = 0; i < zones->gl_pathc; ++i) {
        char path[PATH_MAX];
        struct rail *rail = &rails[count];
        rail->directory = zones->gl_pathv[i];
        int length = snprintf(path, sizeof(path), "%s/name", rail->directory);
        if (length < 0 || (size_t)length >= sizeof(path) ||
            !read_text(path, rail->name, sizeof(rail->name))) continue;
        const char *end = rail->name + strspn(rail->name,
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.");
        if (!rail->name[0] || *end || strncmp(rail->name, "debug-", 6) == 0) continue;
        ++count;
    }
    return count;
}

static void pick_cpu_policies(const glob_t *policies, const char **mid, const char **large) {
    uint64_t mid_max = 0, large_max = 0;
    for (size_t i = 0; i < policies->gl_pathc; ++i) {
        const char *policy = policies->gl_pathv[i];
        uint64_t current, max;
        if (!read_attribute(policy, "scaling_cur_freq", &current)) continue;
        if ((!read_attribute(policy, "cpuinfo_max_freq", &max) || !max) &&
            !read_attribute(policy, "scaling_max_freq", &max)) continue;
        if (max > large_max) {
            *mid = *large;
            mid_max = large_max;
            *large = policy;
            large_max = max;
        } else if (max > mid_max) {
            *mid = policy;
            mid_max = max;
        }
    }
}

static bool starts_with_ci(const char *str, const char *prefix) {
    while (*prefix) {
        if (!*str || tolower((unsigned char)*str) != tolower((unsigned char)*prefix)) return false;
        ++str;
        ++prefix;
    }
    return true;
}

static size_t load_thermal_zones(const glob_t *zones, const char **out_dirs, size_t max_zones) {
    size_t cpu_count = 0;
    for (int pass = 0; pass < 2 && cpu_count == 0; ++pass) {
        for (size_t i = 0; i < zones->gl_pathc && cpu_count < max_zones; ++i) {
            char path[PATH_MAX], type[64];
            int length = snprintf(path, sizeof(path), "%s/type", zones->gl_pathv[i]);
            if (length < 0 || (size_t)length >= sizeof(path) ||
                !read_text(path, type, sizeof(type))) continue;
            bool match = pass == 0
                ? starts_with_ci(type, "cpu")
                : (starts_with_ci(type, "socd") || starts_with_ci(type, "nspss"));
            if (match) out_dirs[cpu_count++] = zones->gl_pathv[i];
        }
    }
    return cpu_count;
}

static bool emit_sample(const struct rail *rails, size_t count, const char *mid, const char *large,
                        const char *const *thermal_dirs, size_t thermal_count, uint64_t now) {
    bool has_energy = false;
    for (size_t i = 0; i < count; ++i) {
        uint64_t energy, power;
        const struct rail *rail = &rails[i];
        if (!read_attribute(rail->directory, "energy_uj", &energy)) continue;
        if (!has_energy) printf("QPT_SAMPLE_NS=%" PRIu64, now);
        has_energy = true;
        printf("\tQPT_RAIL=%s:%" PRIu64, rail->name, energy);
        if (read_attribute(rail->directory, "power_uw", &power)) {
            printf("\tQPT_POWER_UW=%s:%" PRIu64, rail->name, power);
        }
    }
    if (!has_energy) {
        fprintf(stderr, "ERROR 无法读取 QPT 能量计数器\n");
        return false;
    }
    uint64_t cpu_mid = 0, cpu_large = 0, gpu = 0;
    if (mid) read_attribute(mid, "scaling_cur_freq", &cpu_mid);
    if (large) read_attribute(large, "scaling_cur_freq", &cpu_large);
    read_number(GPU_CLOCK, &gpu);
    printf("\tFREQ_CPU0_KHZ=%" PRIu64 "\tFREQ_CPU1_KHZ=%" PRIu64 "\tFREQ_GPU_HZ=%" PRIu64,
           cpu_mid, cpu_large, gpu);
    uint64_t max_temp = 0;
    bool has_temp = false;
    for (size_t i = 0; i < thermal_count; ++i) {
        uint64_t temp = 0;
        if (read_attribute(thermal_dirs[i], "temp", &temp) && temp >= 1000 && temp <= 130000) {
            if (!has_temp || temp > max_temp) max_temp = temp;
            has_temp = true;
        }
    }
    if (has_temp) printf("\tTEMP_CPU_MC=%" PRIu64, max_temp);
    putchar('\n');
    return fflush(stdout) == 0;
}

static int monitor(const char *lease_path) {
    struct lease lease = { .path = lease_path };
    if (!lease_is_live(&lease) || !wait_until(0, !lease_path)) return 0;

    uint64_t enabled = 1;
    if (!read_number(POWER_MONITOR "/enabled", &enabled) && errno != ENOENT) {
        fprintf(stderr, "ERROR 无法读取 QPT 开关\n");
        return 1;
    }
    if (!enabled && !set_enabled("1\n")) {
        fprintf(stderr, "ERROR 无法开启 QPT 硬件功耗遥测\n");
        return 1;
    }

    glob_t zones = {0}, policies = {0}, thermals = {0};
    glob(POWER_MONITOR ":0:[0-9]*", 0, NULL, &zones);
    glob(CPU_POLICIES, 0, NULL, &policies);
    glob(THERMAL_ZONES, 0, NULL, &thermals);
    struct rail *rails = calloc(zones.gl_pathc, sizeof(*rails));
    size_t count = rails ? load_rails(&zones, rails) : 0;
    const char *mid = NULL, *large = NULL;
    pick_cpu_policies(&policies, &mid, &large);
    const char *thermal_dirs[MAX_THERMAL_ZONES];
    size_t thermal_count = load_thermal_zones(&thermals, thermal_dirs, MAX_THERMAL_ZONES);

    int result = 0;
    while (!stopping && lease_is_live(&lease)) {
        uint64_t now = sample_time();
        if (!emit_sample(rails, count, mid, large, thermal_dirs, thermal_count, now)) {
            result = stopping ? 0 : 1;
            break;
        }
        if (!wait_until(now + SECOND_NS, !lease_path)) break;
    }
    free(rails);
    globfree(&zones);
    globfree(&policies);
    globfree(&thermals);
    if (!enabled && !set_enabled("0\n")) {
        fprintf(stderr, "ERROR 无法恢复 QPT 开关\n");
        result = 1;
    }
    return result;
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: vsetqpt LOCK [LEASE]\n");
        return 2;
    }
    int lock = open(argv[1], O_WRONLY | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) != 0) {
        fprintf(stderr, "ERROR 无法启动 QPT 采样，可能已有监测会话\n");
        if (lock >= 0) close(lock);
        return 1;
    }

    struct sigaction action = { .sa_handler = stop_monitor };
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    sigaction(SIGPIPE, &action, NULL);
    int result = monitor(argc == 3 ? argv[2] : NULL);
    close(lock);
    return result;
}
