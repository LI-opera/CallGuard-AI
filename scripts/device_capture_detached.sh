#!/system/bin/sh
# WP-01 selected-tag diagnostics. Must run under setsid; no APK changes.
set -eu
base="$1"
mode="${2:-capture}"
case "$base" in /data/local/tmp/callguard-wp01-*) ;; *) exit 2 ;; esac
case "$mode" in capture|probe) ;; *) exit 2 ;; esac
read own_pid comm state parent_id group_id session_id rest < "/proc/$$/stat"
[ "$group_id" = "$$" ] && [ "$session_id" = "$$" ] || exit 4
if [ -e "$base.log" ] || [ -e "$base.pid" ]; then exit 3; fi
umask 077
set -C
: > "$base.log"
echo "$$" > "$base.pid"
set +C
trap '' HUP
cleanup() {
    trap - EXIT
    trap '' TERM INT
    printf '%s\n' 'capture_helper_stopped' >> "$base.status"
    # This process verified it owns a new, isolated group/session above.
    kill -TERM "-$$" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 0' TERM INT
(sleep 900; kill -TERM "$$" 2>/dev/null || true) &
if [ "$mode" = probe ]; then
    (
        i=0
        while [ "$i" -lt 60 ]; do
            /system/bin/log -p i -t CallGuardCapture "detached_capture_dryrun_tick_$i"
            i=$((i+1))
            sleep 10
        done
    ) &
fi
printf 'session=%s mode=%s\n' "$$" "$mode" > "$base.status"
# Anonymous pipe avoids forbidden named FIFOs on this device.
/system/bin/logcat -b main -v threadtime -T 1 'CallGuard:I' 'CallGuardCapture:I' '*:S' |
while IFS= read -r line; do
    printf '%s\n' "$line" >> "$base.log"
done
