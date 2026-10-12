#!/usr/bin/env bash
# Set up ADB port forwards between this container and the Android device.
# Container 127.0.0.1 and Android 127.0.0.1 are DIFFERENT loopback
# namespaces; these forwards bridge them. No custom relay, no LAN exposure.
set -euo pipefail
SHELL_PORT="${SHADOWAUTO_PORT:-43110}"
MODEL_PORT="${SHADOWAUTO_MODEL_PORT:-8410}"
SERIAL_ARGS=()
if [ -n "${ADB_SERIAL:-}" ]; then SERIAL_ARGS=(-s "$ADB_SERIAL"); fi
adb "${SERIAL_ARGS[@]}" forward "tcp:${SHELL_PORT}" "tcp:${SHELL_PORT}"
echo "forward: container 127.0.0.1:${SHELL_PORT} -> device 127.0.0.1:${SHELL_PORT} (shell RPC)"
if [ "${FORWARD_MODEL:-0}" = "1" ]; then
  adb "${SERIAL_ARGS[@]}" reverse "tcp:${MODEL_PORT}" "tcp:${MODEL_PORT}"
  echo "reverse: device 127.0.0.1:${MODEL_PORT} -> container 127.0.0.1:${MODEL_PORT} (model service)"
else
  echo "hint: start model_service.py, then run FORWARD_MODEL=1 $0 to expose it to the device"
fi
adb "${SERIAL_ARGS[@]}" devices -l
