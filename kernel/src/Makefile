# SPDX-License-Identifier: GPL-2.0-only
obj-m := qcom-scmi-ro.o
ifeq ($(SUN_TUNER_PLATFORM),sm8850)
CFLAGS_qcom-scmi-ro.o += -DSUNTUNER_SM8850
endif
CFLAGS_qcom-scmi-ro.o += -I$(SCMI_PROTOCOL_DIR)
