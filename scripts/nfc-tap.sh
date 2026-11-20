#!/usr/bin/env bash
# Sends a fake NFC tag to an app that includes the debug-only :nfc-test module.
#
#   nfc-tap.sh <package> <json-file|-> [mode]
#
# mode: auto (default) | reader | foreground | implicit
# Honors ANDROID_SERIAL. Exits non-zero unless the broadcast result is 1.

set -u

if [ $# -lt 2 ]; then
    echo "usage: $0 <package> <json-file|-> [auto|reader|foreground|implicit]" >&2
    exit 2
fi

PACKAGE=$1
SOURCE=$2
MODE=${3:-auto}
ADB=${ADB:-adb}

if [ "$SOURCE" = "-" ]; then
    JSON=$(cat)
else
    if [ ! -f "$SOURCE" ]; then
        echo "error: no such file: $SOURCE" >&2
        exit 2
    fi
    JSON=$(cat "$SOURCE")
fi

B64=$(printf '%s' "$JSON" | base64 | tr -d '\n')

OUTPUT=$("$ADB" shell am broadcast \
    -a dev.nfctest.TAP \
    -p "$PACKAGE" \
    -f 32 \
    --es tag_b64 "$B64" \
    --es mode "$MODE")

echo "$OUTPUT"

RESULT=$(printf '%s' "$OUTPUT" | sed -n 's/.*result=\(-\{0,1\}[0-9][0-9]*\).*/\1/p' | head -1)
if [ "$RESULT" != "1" ]; then
    echo "error: broadcast result=${RESULT:-none} (expected 1)" >&2
    exit 1
fi
