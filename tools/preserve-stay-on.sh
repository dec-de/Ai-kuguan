#!/usr/bin/env bash
# Source this before temporarily changing the device's stay-awake setting.

remember_stay_on() {
    ORIGINAL_STAY_ON=$("$ADB" -s "$DEVICE" shell settings get global stay_on_while_plugged_in 2>/dev/null) || return 1
    ORIGINAL_STAY_ON=${ORIGINAL_STAY_ON//$'\r'/}
    if [[ "$ORIGINAL_STAY_ON" != null && ! "$ORIGINAL_STAY_ON" =~ ^[0-9]+$ ]]; then
        echo "Cannot read original stay-awake setting" >&2
        return 1
    fi
    trap restore_stay_on EXIT
}

restore_stay_on() {
    local original_status=$?
    trap - EXIT
    case "$ORIGINAL_STAY_ON" in
        15) "$ADB" -s "$DEVICE" shell svc power stayon true >/dev/null 2>&1 ;;
        0) "$ADB" -s "$DEVICE" shell svc power stayon false >/dev/null 2>&1 ;;
        null) "$ADB" -s "$DEVICE" shell settings delete global stay_on_while_plugged_in >/dev/null 2>&1 ;;
        *) "$ADB" -s "$DEVICE" shell settings put global stay_on_while_plugged_in "$ORIGINAL_STAY_ON" >/dev/null 2>&1 ;;
    esac || echo "Warning: could not restore stay-awake setting to $ORIGINAL_STAY_ON" >&2
    exit "$original_status"
}
