#!/usr/bin/env bash
set -euo pipefail

# Run from the Nix dev shell. Keep this project's AVD separate from the user's
# other Android projects and address only this emulator's serial.
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_android="$project_root/.android-local"
avd_name="OpenCode_M0_API36"
serial="emulator-5558"
port=5558
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"

if [[ -z "$sdk" ]]; then
  echo "Enter the project Nix dev shell before launching the emulator." >&2
  exit 1
fi

shopt -s nullglob
avdmanagers=("$sdk"/cmdline-tools/*/bin/avdmanager)
shopt -u nullglob
if (( ${#avdmanagers[@]} != 1 )) || [[ ! -x "${avdmanagers[0]:-}" || ! -x "$adb" || ! -x "$emulator" ]]; then
  echo "Enter the project Nix dev shell before launching the emulator." >&2
  exit 1
fi
avdmanager="${avdmanagers[0]}"

export ANDROID_USER_HOME="$local_android"
export ANDROID_AVD_HOME="$local_android/avd"
export ANDROID_EMULATOR_HOME="$local_android/emulator"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_EMULATOR_HOME" "$local_android/logs"

if [[ ! -e "$ANDROID_AVD_HOME/$avd_name.ini" ]]; then
  if [[ -e "$ANDROID_AVD_HOME/$avd_name.avd" ]]; then
    echo "Incomplete local AVD exists at $ANDROID_AVD_HOME/$avd_name.avd; inspect it before retrying." >&2
    exit 1
  fi
  device_args=()
  device_list="$("$avdmanager" list device)"
  if rg -q '"pixel_5"' <<<"$device_list"; then
    device_args=(--device pixel_5)
  else
    echo "Pixel 5 device profile is unavailable; using avdmanager's default profile."
  fi
  printf '\n' | "$avdmanager" create avd \
    --name "$avd_name" \
    --package 'system-images;android-36;google_apis;x86_64' \
    --path "$ANDROID_AVD_HOME/$avd_name.avd" \
    "${device_args[@]}"
fi

if [[ ! -f "$ANDROID_AVD_HOME/$avd_name.avd/config.ini" ]]; then
  echo "Local AVD is incomplete: $ANDROID_AVD_HOME/$avd_name.avd/config.ini is missing." >&2
  exit 1
fi

if timeout 10 "$adb" -s "$serial" get-state 2>/dev/null | tr -d '\r' | rg -xq device; then
  actual_avd="$(timeout 10 "$adb" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)"
  if [[ "$actual_avd" != "$avd_name" ]]; then
    echo "$serial belongs to another AVD ($actual_avd); leave it untouched." >&2
    exit 1
  fi
  echo "$avd_name is already running on $serial."
else
  if ss -H -ltn '( sport = :5558 or sport = :5559 )' | rg -q .; then
    echo "Emulator ports 5558/5559 are occupied; leave the existing process untouched." >&2
    exit 1
  fi

  log="$local_android/logs/emulator-$(date +%Y%m%d-%H%M%S).log"
  nohup "$emulator" -avd "$avd_name" -port "$port" \
    -accel on -gpu swiftshader -no-window -no-snapshot -no-boot-anim \
    -no-audio -no-metrics >"$log" 2>&1 </dev/null &
  emulator_pid=$!
  echo "$emulator_pid" >"$local_android/emulator.pid"
  echo "Started $avd_name on $serial (PID $emulator_pid; log: $log)."
fi

deadline=$((SECONDS + 180))
while (( SECONDS < deadline )); do
  if [[ -n "${emulator_pid:-}" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo "$serial exited before boot. Check $log." >&2
    exit 1
  fi
  if [[ "$(timeout 10 "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == "1" ]]; then
    echo "$serial boot completed."
    exit 0
  fi
  sleep 2
done

echo "$serial did not finish booting within 180 seconds. Check $local_android/logs/." >&2
exit 1
