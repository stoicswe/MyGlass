#!/usr/bin/env bash
#
# Flash a Glass Explorer Edition (glass_1) factory image via fastboot.
#
# Usage:
#   ./flash-glass.sh /path/to/signed_signed-glass_1-img-XXXXXXXX
#   ./flash-glass.sh /path/to/image --system-only     # only OS partitions (setup fix)
#
# Put Glass into fastboot first, e.g.:
#   adb reboot bootloader
#   # or: hold the camera button, tap power, keep holding the camera button for ~10s,
#   #     then choose "Reboot into fastboot".
#
# Notes:
#  * Images are signed, so a locked bootloader should accept them.
#  * Flashing bootloader/xloader/fpga is riskier; use --system-only to flash just the
#    OS (boot/recovery/system), which is what usually contains the setup fix.
#  * If fastboot hangs on transfers (a known old-bootloader + macOS USB issue), try a
#    different cable/port (direct, no hub), or use Linux/Windows with the Google USB
#    driver. Bootloader: use platform-tools 28.x if 3x misbehaves.

set -euo pipefail

IMG_DIR="${1:-}"
MODE="${2:-all}"

if [[ -z "$IMG_DIR" || ! -d "$IMG_DIR" ]]; then
  echo "usage: $0 <image-dir> [--system-only]" >&2
  exit 1
fi

cd "$IMG_DIR"
echo "Using image dir: $IMG_DIR"
echo "Devices:"; fastboot devices

flash() {
  local part="$1" file="$2"
  if [[ -f "$file" ]]; then
    echo "=== flashing $part ($file) ==="
    fastboot flash "$part" "$file"
  else
    echo "!! missing $file, skipping"
  fi
}

if [[ "$MODE" != "--system-only" ]]; then
  flash xloader xloader.img
  flash bootloader bootloader.img
  echo "=== rebooting into bootloader after bootloader flash ==="
  fastboot reboot-bootloader
  sleep 8
  flash fpga fpga.img
fi

flash boot boot.img
flash recovery recovery.img

echo "=== flashing system (large) ==="
fastboot flash system system.img

echo "=== wiping userdata/cache so setup runs clean ==="
fastboot erase cache || true

echo "=== rebooting ==="
fastboot reboot
echo "Done. Glass should boot with the new image."
