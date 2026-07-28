# Jarvis Home

An always-listening, speech-to-speech AI assistant that runs entirely as a custom APK on a
**repurposed Lenovo smart display** — a $30 second-hand video-calling panel whose vendor backend
went dark years ago.

Say **"Hey Jarvis"**, hold a real interruptible conversation with OpenAI's Realtime API, let it call
tools, and watch it render answers as native cards on the screen. The wake word runs on-device, so
nothing streams to the cloud until you actually address it.

**▶ [Watch the demo](https://youtube.com/shorts/ax9ee9M-XW8?feature=share)**

---

## Why this is interesting

The fun of this project isn't the API call — it's making a locked-down, abandoned appliance behave
like a good product again. The device ships with Android 8.1 (API 27), a 32-bit MediaTek/Snapdragon
SoC, no Play Services, a signed bootloader, and a vendor firmware whose audio DSP is half-configured.
Most of the work was fighting that:

- **Real echo cancellation.** Android's platform AEC on this board is broken — the HAL's echo
  reference reads back the microphone's own PCM, so it cancels nothing. Jarvis ships **WebRTC AEC3**
  as a prebuilt native lib with a small JNI bridge, pacing the far-end reference against
  `AudioTrack.playbackHeadPosition`. Measured **~27 dB** of suppression, which is what makes
  barge-in work at all.
- **On-device wake word.** [openWakeWord](https://github.com/dscripka/openWakeWord) via ONNX
  Runtime, ~3.5 MB of models, running continuously on a decade-old ARM core. Realtime audio costs
  real money per minute; gating it behind a local wake word is the whole cost model.
- **Bringing up dead hardware.** The debug firmware's `oem` partition is empty, so the TLV320 ADC
  and TAS5805M amplifier are never initialized — mic and speaker are simply dead. The app replays
  the retail I2C init tables itself at startup (`assets/hw/*.bin`).
- **Chasing a "WiFi" bug that wasn't.** Weeks of audio stalls blamed on the radio turned out to be
  the MediaTek hotplug governor parking 3 of 4 CPU cores, plus the framework firing an
  ANQP/Passpoint scan storm every 5 minutes. Both are documented in [`docs/`](docs/).

## Architecture

```
  ┌──────────────── Android 8.1 APK (Kotlin + Compose) ───────────────┐
  │                                                                   │
  │   mic ──► AEC3 (JNI) ──► openWakeWord ──► "Hey Jarvis"?           │
  │                │                              │                   │
  │                │                              ▼                   │
  │                └──────────────────►  OpenAI Realtime API          │
  │                    (16 kHz PCM over WebSocket, full duplex)       │
  │                                               │                   │
  │                        ┌──────────────────────┼──────────┐        │
  │                        ▼                      ▼          ▼        │
  │                   tool calls            audio out    transcript   │
  │                        │                      │          │        │
  │                        ▼                      ▼          ▼        │
  │                  native cards ────────►  Compose UI  ◄────┘        │
  └───────────────────────────────────────────────────────────────────┘
```

Everything runs on the device. There is no server, no relay, and no companion app.

### Tools

Tools are a one-file-per-capability plugin system (see `Tools.kt`) — write the file, add it to
`Tools.all`, and the model can call it. Included: `web_search`, `get_weather`, `get_scores`,
`show_guide`, `remember`, `set_volume`, and timers.

## Layout

| Path | What |
|---|---|
| `app/` | The assistant APK — Kotlin, Jetpack Compose, ~5.5k LOC |
| `app/src/main/cpp/` | JNI bridge to WebRTC AEC3 |
| `app/src/main/assets/oww/` | openWakeWord ONNX models |
| `app/src/main/assets/hw/` | I2C init tables for the audio codec + amplifier |
| `echolab/` | Standalone harness for measuring echo-cancellation performance |
| `docs/design.md` | Full product + technical spec, and the running build log |
| `docs/ivy-panel-notes.md` | Reverse-engineering notes for the Lenovo CD-17302F panel |
| `docs/hardware-setup.md` | Getting a shell on the hardware, with credits |
| `scripts/` | Firmware extraction and flashing helpers |

## Build

Requires **JDK 17** on your `PATH` (or `JAVA_HOME`) and the Android SDK — API 27+ platform, plus
the NDK and CMake for the JNI bridge. Gradle itself is fetched by the wrapper.

```bash
git clone https://github.com/richardyc/jarvis-home
cd jarvis-home

# 1. API keys
cp secrets.properties.example secrets.properties
$EDITOR secrets.properties          # add your OpenAI key (needs Realtime API access)

# 2. Runtime config — model, voice, wake word, optional WiFi auto-join
cp app/src/main/assets/config.example.yaml app/src/main/assets/config.yaml
$EDITOR app/src/main/assets/config.yaml

# 3. Build and install
./gradlew :app:installDebug
```

Both `secrets.properties` and `config.yaml` are gitignored — `config.yaml` holds a WiFi password,
so keep it that way.

The app targets `armeabi-v7a` only, since the panel is 32-bit. It runs on any Android 8.1+ device
with a microphone, but the hardware bring-up in `assets/hw/` is specific to this panel and is
skipped elsewhere.

## Hardware

Built and tested on two panels:

- **Lenovo Smart Display 7 / CD-17302F** ("Ivy") — MediaTek MT8167S, Android Things 8.1.
  Fully working *except* its MT7668 WiFi, which collapses under sustained load; see
  [`docs/ivy-panel-notes.md`](docs/ivy-panel-notes.md) for the full autopsy.
- **Lenovo ThinkSmart View / CD-18781Y** — Snapdragon 624, Qualcomm WiFi, easy to root.
  The recommended target.

See [`docs/hardware-setup.md`](docs/hardware-setup.md) for getting adb and root.

## Status

A working personal project, not a product. It has run for weeks on a kitchen counter. Expect rough
edges: no tests, the tool set is whatever I wanted on a given day, and anything touching the panel's
firmware is specific to these two SKUs.

## License

[MIT](LICENSE). Third-party components and attributions are listed in [NOTICE](NOTICE).
