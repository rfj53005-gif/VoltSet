NDK_BIN ?= $(ANDROID_NDK_HOME)/toolchains/llvm/prebuilt/linux-x86_64/bin
CC := $(NDK_BIN)/aarch64-linux-android30-clang
STRIP := $(NDK_BIN)/llvm-strip

OUT := prebuilt/arm64-v8a/vsetqpt
CFLAGS := -O2 -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections
LDFLAGS := -pie -Wl,--gc-sections -Wl,-z,max-page-size=16384

.PHONY: all
all: $(OUT)

$(OUT): vsetqpt.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<
	$(STRIP) --strip-all $@
