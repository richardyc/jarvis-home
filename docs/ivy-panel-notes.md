# Jarvis / Ivy panel — work log through 2026-07-13

Consolidated record of the debugging + fixes on the Lenovo CD-17302F "Ivy" panel, ending with the
decision to migrate off it.

> Published as-is. This is a working log, not a guide — it is here because the failure analysis is
> the useful part: how a CPU-governor bug and a WiFi scan storm both masqueraded as "flaky radio",
> and how far you can get bringing up audio hardware the firmware never initialized. Most fixes
> described here are specific to this SKU.

## Outcome / decision
The panel's **MT7668 WiFi is terminally flaky under load** and no software change fixed it.
Jarvis migrates to a **Lenovo ThinkSmart View CD-18781Y** (Snapdragon 624, Qualcomm WiFi). The Ivy
unit is being **reverted to locked demo firmware (green verified boot) and returned**.

## What got FIXED and is verified (all in the Jarvis app / device config)
- **Grey-screen-on-tap** — WakeWordDetector SIGSEGV (OrtSession closed mid-inference); join thread
  before close + guard inference.
- **Dead mic** — debug ROM's empty `oem` partition never inits the TLV320 ADC; `IvyHw.kt` replays
  the retail I2C init. Mic is on TDM slot 1 (slot 0 is garbage) → stereo capture, channel 1.
- **Dead speaker** — same cause; `IvyHw.kt` inits the TAS5805M amp.
- **All peripherals mapped** — vol±, mic-mute switch, camera-cover, VCNL4200 ambient-light sensor
  (`Peripherals.kt`), auto-brightness/warmth + settings UI (`Panel.kt`/`PanelUi.kt`).
- **CPU cores parked** — MediaTek hotplug governor ran the panel on 1 of 4 cores (this masqueraded
  as "WiFi" stalls). `/vendor/etc/init/jarvis-cpu.rc` keeps all 4 online at `performance`. Power
  saving fully off.
- **Echo cancellation** — WebRTC AEC3 via prebuilt lib + JNI (`Aec.kt`/`aec.cpp`/`FarEnd.kt`),
  ~27dB, enables full-duplex barge-in.
- **Fonts (Chinese/emoji rendered as tofu)** — `/system/fonts` CJK+emoji are symlinks into the
  empty `oem` partition; extracted retail `oem.img` `/fonts/*` (NotoSansCJK, NotoColorEmoji, +24)
  into `/oem/fonts/`. Needs a full reboot (zygote builds the font table at boot).
- **Web search** (`Search.kt`, Responses API), **weather tool**, iMessage-style chat bubbles,
  90s idle session timeout, UI scaled +30%.
- **WiFi watchdog rewrite** — old one judged "down" from `WifiInfo.ipAddress` (reads 0 for ~40s
  during boot DHCP) and radio-bounced healthy links, turning a 40s boot into 90s of thrash and any
  2s blip into a 15s outage. Now trusts `ConnectivityManager` (`NET_CAPABILITY_VALIDATED`), never
  touches the radio while the OS says connected; 30s boot grace, gentle reconnect ladder, radio
  bounce only after ~75s truly-down (which also auto-cures the MT7668 cold-boot "0 APs" wedge).
- **Scan storms** — framework did a full off-channel scan + ANQP/Passpoint storm every 5 min;
  `settings put global wifi_framework_scan_interval_ms 3600000` (hourly). Persists.
- **Barge-in is keyword-gated** — background TV/friends no longer cut Jarvis off. Auto-interrupt
  disabled (`turn_detection.interrupt_response=false`); interruption is explicit: a stop phrase
  ("stop"/"shut up"/别说了/闭嘴/安静/够了) heard in the live transcription, "Hey Jarvis"
  (on-device, instant), or a tap. Also fixed a real bug where any room noise flushed the audio
  queue mid-answer.

## The unfixable: WiFi load-collapse (see memory `ivy-panel-wifi-load-collapse`)
Under sustained traffic the MT7668 delivers 2-10 KB/s with 40-90% packet loss; idle pings are
clean (8-35ms) at -29..-46 dBm from 2m away. A **Mac on the same AP/channel at the same moment
does 50 MB/s, 0% loss** — so air + routers are fine. Eliminated: both routers, both bands, app on
/ off, warm + COLD-POWER-CYCLE reboots, all 4 AMPDU combos, KeepFullPwr 0/1, retail-vs-debug RF
calibration (`EEPROM_MT7668.bin`), retail-vs-debug **kernel driver** (retail `.ko` rejected:
`module_layout` CRC mismatch — needs retail kernel, which won't boot with AVB off), ANQP storms,
power save, SDIO bus (clean SDR104), CPU, thermal. The chip did 8.9 MB/s once (16:44) and 30 Mbps
two days ago → capable, now collapsed. Points at RF hardware (this unit's factory RF cal was wiped
in the original flash) or a localized interferer. The proper fix for a 24/7 panel was always
USB-Ethernet — instead we're switching hardware.

**Landmine recorded:** never `echo ... > /proc/net/wlan/cfg` — it kernel-panics + reboots this chip.

## Backups kept (`backups/wifi/`)
`wifi.cfg.before-ampdu-fix`, `EEPROM_MT7668.bin.debug-orig`, `wlan_drv_gen4_mt7668.ko.debug-orig`.

## Next: ThinkSmart View CD-18781Y (memory `thinksmart-view-migration`)
Qualcomm WiFi, 8×A53, mature XDA root (unlock+TWRP+Magisk), LineageOS 15.1. Port the app; delete
IvyHw/Peripherals/Pio (Android-Things-only). Likely reuse the HAL's own AEC.
