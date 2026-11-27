#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH

MODDIR=${0%/*}
BUNDLE="$MODDIR/backend"
SH="$BUNDLE/voltset.sh"

[ -r "$SH" ] || exit 0
# The backend owns the configuration, safety checks and SDAM write under one lock.
exec /system/bin/timeout -k 2s 45s /system/bin/sh "$SH" "$BUNDLE" auto_restore "$@"
