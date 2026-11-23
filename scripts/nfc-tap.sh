#!/usr/bin/env bash
# Sends a fake NFC tag to an app that includes the debug-only :nfc-test module.
#
#   nfc-tap.sh <package> <json-file|-> [mode]
#   nfc-tap.sh --cold <package> <json-file|-> [--keep-process]
#
# mode: auto (default) | reader | foreground | implicit
# --cold starts NfcTestLaunchActivity from the shell (cold process) and waits
# for the `cold id=... result=...` logcat line. Honors ANDROID_SERIAL.
# Exits non-zero unless the result is 1.

set -u

COLD=0
KEEP_PROCESS=0

if [ "${1:-}" = "--cold" ]; then
    COLD=1
    shift
fi

if [ $# -lt 2 ]; then
    echo "usage: $0 [--cold] <package> <json-file|-> [mode|--keep-process]" >&2
    exit 2
fi

PACKAGE=$1
SOURCE=$2
shift 2

MODE=auto
if [ "$COLD" = "1" ]; then
    if [ "${1:-}" = "--keep-process" ]; then
        KEEP_PROCESS=1
    elif [ $# -gt 0 ]; then
        echo "error: --cold accepts only --keep-process after the json file" >&2
        exit 2
    fi
else
    MODE=${1:-auto}
fi

ADB=${ADB:-adb}

# Best-effort PID lookup: pidof first, then ps -A. Empty when unavailable.
pid_of() {
    local package=$1
    local pid
    pid=$("$ADB" shell pidof "$package" 2>/dev/null | tr -d '\r' | awk '{print $1}')
    if [ -n "$pid" ]; then
        echo "$pid"
        return
    fi
    "$ADB" shell ps -A 2>/dev/null | tr -d '\r' |
        awk -v pkg="$package" '$NF == pkg {print $2; exit}'
}

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

if [ "$COLD" = "1" ]; then
    ID=$(uuidgen 2>/dev/null | tr '[:upper:]' '[:lower:]' || true)
    if [ -z "${ID:-}" ]; then
        ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || true)
    fi
    if [ -z "${ID:-}" ]; then
        ID="$(date +%s)-$$"
    fi

    BEFORE=$(pid_of "$PACKAGE" || true)

    if [ "$KEEP_PROCESS" = "0" ]; then
        "$ADB" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    fi

    # No -W: when the trampoline closes without opening a destination, -W waits
    # forever for an activity that never becomes idle. The logcat line is the
    # result channel.
    OUTPUT=$("$ADB" shell am start \
        -n "$PACKAGE/es.igarciadelamo.nfctest.NfcTestLaunchActivity" \
        --es tag_b64 "$B64" \
        --es id "$ID" 2>&1)
    echo "$OUTPUT"

    LINE=""
    DEADLINE=$(( $(date +%s) + 5 ))
    while [ "$(date +%s)" -lt "$DEADLINE" ]; do
        LINE=$("$ADB" logcat -d -s NfcTest 2>/dev/null | grep -F "cold id=$ID " | tail -1)
        if [ -n "$LINE" ]; then
            break
        fi
        sleep 0.2
    done

    AFTER=$(pid_of "$PACKAGE" || true)
    echo "pid before=${BEFORE:-} after=${AFTER:-}"

    if [ -z "$LINE" ]; then
        echo "error: no cold log line for id=$ID within 5s" >&2
        exit 1
    fi

    echo "$LINE"
    RESULT=$(printf '%s' "$LINE" | sed -n 's/.*result=\(-\{0,1\}[0-9][0-9]*\).*/\1/p' | head -1)
    if [ "$RESULT" != "1" ]; then
        echo "error: cold result=${RESULT:-none} (expected 1)" >&2
        exit 1
    fi
    exit 0
fi

OUTPUT=$("$ADB" shell am broadcast \
    -a es.igarciadelamo.nfctest.TAP \
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
