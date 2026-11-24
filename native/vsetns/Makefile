NDK_BIN ?= /home/ncf/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin
CC := $(NDK_BIN)/aarch64-linux-android30-clang
STRIP := $(NDK_BIN)/llvm-strip

CFLAGS := -O2 -Wall -Wextra -Werror -fPIE -ffunction-sections -fdata-sections
LDFLAGS := -pie -Wl,--gc-sections -Wl,-z,max-page-size=16384

OUT := prebuilt/arm64-v8a/vsetns

.PHONY: all clean

all: $(OUT)

$(OUT): vsetns.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<
	$(STRIP) --strip-all $@

clean:
	rm -rf prebuilt
