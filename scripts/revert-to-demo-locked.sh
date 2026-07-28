#!/bin/bash
#
# NOTE: This script will not run as-is from a fresh clone. It is specific to the Lenovo CD-17302F
# ("Ivy") panel and expects vendor firmware images under firmware/ and a local toolchain under
# tools/, neither of which is redistributed in this repository (see docs/hardware-setup.md).
# It is kept for the flashing ORDER it encodes — in particular that the factory persistent digest
# must be cleared BEFORE re-locking, which is the part that is easy to get wrong and unrecoverable.
#
# Revert the Ivy panel to factory demo firmware, GREEN verified-boot, LOCKED — for return.
# macOS port of firmware/Ivy-combined/UserImageFlash.cmd. Device MUST already be in fastboot.
#
# Layout it restores (the original combined/demo unit): A slot = Debug images, B slot = User
# images, active = B, AVB locked. B_User vbmeta key matches this unit's fused AVB key → green.
#
# SAFETY: flashes BOTH slots and clears the factory persistent digest BEFORE locking. Never lock
# without the digest clear (→ Red State, both slots unbootable). Verify every fastboot step.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
export PATH="$ROOT/tools/platform-tools:$PATH"
FB="$ROOT/tools/platform-tools/fastboot"
B="$ROOT/firmware/Ivy-user"        # B_User slot
A="$ROOT/firmware/Ivy-debug"       # A_Debug slot
PYAVB="$ROOT/tools/avb-venv/bin/python"
DIGEST="$ROOT/firmware/Ivy-combined/at_write_persistent_digest.py"

echo "== waiting for fastboot device =="
"$FB" getvar product 2>&1 | head -1 || { echo "NO FASTBOOT DEVICE — see fastboot-entry note"; exit 1; }
echo "== current vboot state =="; "$FB" getvar at-vboot-state 2>&1 | grep -i avb || true

# --- flash bootloader/partition (shared) ---
"$FB" flash boot0     "$B/boot0.img"
"$FB" flash partition "$B/partition-table.img"
"$FB" flash tee_a     "$B/tee.img"
"$FB" flash tee_b     "$B/tee.img"
"$FB" flash lk_a      "$B/lk.img"
"$FB" flash lk_b      "$B/lk.img"
"$FB" erase misc

# --- A slot = Debug, B slot = User ---
"$FB" flash boot_a    "$A/boot.img"
"$FB" flash boot_b    "$B/boot.img"
"$FB" flash system_a  "$A/system.img"
"$FB" flash system_b  "$B/system.img"
"$FB" flash vendor_a  "$A/vendor.img"
"$FB" flash vendor_b  "$B/vendor.img"

"$FB" flash logo      "$B/logo.img"

"$FB" flash oem_a     "$A/oem_mt8167s_sp2.img"
"$FB" flash oem_b     "$B/oem.img"
"$FB" flash vbmeta_a  "$A/vbmeta_mt8167s_sp2.img"
"$FB" flash vbmeta_b  "$B/vbmeta.img"

"$FB" flash oem_bootloader_a "$A/oem_bootloader_mt8167s_sp2.img"
"$FB" flash oem_bootloader_b "$B/oem_bootloader.img"

# --- THE KEY STEP: clear the factory persistent digest before flashing factory + locking ---
echo "== clearing factory persistent digest (RPMB) =="
"$PYAVB" "$DIGEST" --name factory --clear_digest
"$FB" flash factory   "$B/factory.img"

"$FB" set_active b

# --- lock only if currently unlocked ---
if "$FB" getvar at-vboot-state 2>&1 | grep -qiE "avb-locked: *0"; then
  echo "== AVB unlocked → locking (final) =="
  "$FB" oem at-lock-vboot
else
  echo "== AVB already locked, skipping lock =="
fi

"$FB" reboot bootloader
sleep 6
"$FB" format userdata
echo "== DONE. Rebooting into the demo OS. =="
"$FB" reboot
