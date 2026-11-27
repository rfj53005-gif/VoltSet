NDK_HOME ?= $(ANDROID_NDK_HOME)
ifeq ($(strip $(NDK_HOME)),)
ifneq ($(strip $(ANDROID_HOME)),)
NDK_HOME := $(lastword $(sort $(wildcard $(ANDROID_HOME)/ndk/*)))
endif
endif
NDK_BIN ?= $(NDK_HOME)/toolchains/llvm/prebuilt/linux-x86_64/bin
CC := $(NDK_BIN)/aarch64-linux-android30-clang
STRIP := $(NDK_BIN)/llvm-strip

CFLAGS := -O2 -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections
LDFLAGS := -pie -Wl,--gc-sections -Wl,-z,max-page-size=16384

OUT := prebuilt/arm64-v8a/vsetns

.PHONY: all clean

all: $(OUT)

$(OUT): vsetns.c
	@test -x "$(CC)" || { echo "Missing NDK clang at $(CC); set ANDROID_NDK_HOME or NDK_BIN" >&2; exit 1; }
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<
	$(STRIP) --strip-all $@

clean:
	rm -rf prebuilt
