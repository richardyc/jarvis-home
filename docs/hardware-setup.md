# Hardware setup — getting a shell on the panel

This is a short orientation to what these devices are and what it takes to run your own code on
them. **It is deliberately not a step-by-step flashing guide.** The procedures were worked out by
the XDA community over several years, they change as new firmware surfaces, and following a stale
copy is how people brick things. Use the primary sources linked below and treat this page as a map.

> **Warning.** Flashing firmware can permanently brick these devices. On the ThinkSmart View in
> particular, an unlocked bootloader will only boot EDL or fastboot — you must re-lock after
> flashing for the device to boot normally. Nothing here is endorsed by Lenovo, and doing any of it
> voids your warranty. You are on your own.

## The devices

| | Lenovo Smart Display 7 (CD-17302F, "Ivy") | Lenovo ThinkSmart View (CD-18781Y) |
|---|---|---|
| SoC | MediaTek MT8167S (32-bit, 4× Cortex-A35) | Qualcomm Snapdragon 624 |
| OS | Android Things 8.1 (API 27) | Android 8.1 (API 27) |
| WiFi | MediaTek MT7668 | Qualcomm |
| Flashing | MTK BROM / fastboot | Qualcomm EDL (firehose) |
| Getting adb | Requires firmware conversion | Built into some stock builds |
| Recommended? | No — see below | **Yes** |

Both were sold as video-calling appliances tied to a vendor cloud that has since been shut down,
which is why they turn up cheap and why running your own software on them is worth doing.

**Prefer the ThinkSmart View.** Certain stock builds ship with adb already enabled, or expose it
behind a developer tap in the Teams settings screen — no firmware surgery needed to start. Its
Qualcomm radio also just works. The Ivy panel's MT7668 WiFi collapses under sustained load in a way
no software change fixed; that investigation is written up in
[`ivy-panel-notes.md`](ivy-panel-notes.md) and is the reason this project moved off it.

## What the process looks like

1. **Get adb.** On the ThinkSmart View, check your firmware build first — some have adb on by
   default, and on others it's unlocked by rapidly tapping the firmware version in Teams →
   Settings → About. Only the "Teams & Others" builds support this; the Zoom builds do not.
2. **Replace the launcher.** These ship as kiosks. `adb install` any home-screen APK, then use
   `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` to pick it.
   There is no navigation bar by default, so a button-remap app (or one of the community nav-bar
   APKs) makes the device usable.
3. **Root, if you need it.** Jarvis needs root only for the audio-codec bring-up on the Ivy panel
   and for the CPU-governor fix. On the ThinkSmart View, Magisk installed via a patched `boot.img`
   is the standard path.
4. **Install Jarvis.** `./gradlew :app:installDebug`, then set it as the home activity so it starts
   on boot.

## Sources

Essentially all of the device-specific knowledge here comes from XDA. Please read the original
threads rather than any summary:

- **@deadman96385** — firmware archive for the ThinkSmart View, EDL/QFIL flashing procedure, and
  the adb-enable steps. The foundational thread for this device.
- **@Chewie610** — [GUIDE][ROOT] Unlocking and Improving Your Lenovo ThinkSmart View (CD-18781Y):
  TWRP, Magisk, Play Store via microG, and WebView updates.
- **@Xi07** — TWRP recovery image and the demonstration that Play Store could work.
- **@garnir4ik** — first to show the device could be rooted.
- **@WhyPartyPizza** (reddit) — the adb-enable tap sequence.
- **bkerler's [edl](https://github.com/bkerler/edl)** — the Qualcomm EDL toolkit, and what you want
  instead of QFIL if you are on macOS or Linux.
- **bkerler's [mtkclient](https://github.com/bkerler/mtkclient)** — the MediaTek equivalent, used
  for the Ivy panel.

Firmware images are not redistributed in this repository. Get them from the XDA threads above.

## Tools in this repo

- [`../scripts/fw-extract.py`](../scripts/fw-extract.py) — pulls files out of Android sparse ext4
  images, which is how the audio-codec init tables and the missing CJK/emoji fonts were recovered
  from a retail `vendor.img` / `oem.img`.
- [`../scripts/revert-to-demo-locked.sh`](../scripts/revert-to-demo-locked.sh) — restores an Ivy
  panel to locked, green-verified-boot factory firmware. Specific to that SKU, and it needs
  firmware images that are not included here. Kept because the ordering it encodes is
  non-obvious: the factory persistent digest must be cleared *before* re-locking, or the device
  lands in Red State with both slots unbootable.
