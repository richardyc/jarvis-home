# Jarvis Panel — Product, Design & Tech Spec

> A HER/Jarvis-style, always-listening, speech-to-speech AI companion **with a screen** — running as a custom APK on a repurposed Lenovo Smart Display 7 (CD-17302F "Ivy", MediaTek MT8167S, Android Things 8.1 / Android 8.1 / API 27). Wake on **"Hey Jarvis"**, talk to **GPT-Live** (OpenAI's full-duplex speech-to-speech voice API), let it **use tools** (Hue, Wyze, the Dreame vacuum, timers, proactive automations — with a Google-Home *voice bridge* only as a last resort), and **render what it wants to show you** (pretty markdown → rich native cards) on the display.

**Status of the device:** Already converted to green verified-boot retail Android Things 8.1. adb works. Mic confirmed working (physical mute switch caveat). This doc is the plan for the *software we build on top*.

> **Note for readers.** This is the original design document, written before the build started and
> kept as a living record — §17 is the running build log. It describes the Ivy panel; the project
> later moved to the ThinkSmart View (see [`ivy-panel-notes.md`](ivy-panel-notes.md)). Not every
> idea below shipped, and the roadmap sections are aspirational. Read it as the thinking behind the
> code, not as documentation of it.

---

## 0. TL;DR

| | |
|---|---|
| **What** | Custom Kotlin APK: "Hey Jarvis" wake word → **OpenAI GPT-Live / Realtime API** (full-duplex speech-to-speech) → tool calling → native markdown/card UI, controlling Hue + Wyze + the Dreame vacuum through their own APIs (a spoken "Hey Google" bridge only as a last resort). |
| **Feels like** | HER / Iron Man's JARVIS, but on a countertop display. Ambient orb at rest; conversational, barge-in-able, shows things on screen. |
| **Key architecture call** | **On-device brain** (wake word + Realtime API + Hue + UI all in the APK), plus a **tiny optional "tools relay"** (Python, on the old Windows PC or a Raspberry Pi) *only* for things that need Python/secrets/CV — chiefly **Wyze** and **proactive automations**. |
| **Cost discipline** | Wake word is 100% local; **nothing streams to OpenAI until "Hey Jarvis."** Realtime audio is ~$0.05–0.40/min live, so gating it behind the wake word is what makes this affordable. |
| **Last-resort bridge** | For a device with *no* API and *no* integration, Jarvis can speak "Hey Google, …" to a nearby Nest — but you'd hear it, so it's an opt-in fallback, never the main path. Real APIs/integrations come first. |

---

## 1. Product Vision

Jarvis Panel is an **ambient AI presence** in the home. At rest it's a calm screen (clock, weather, a breathing orb). Say **"Hey Jarvis"** and it comes alive — you hold a natural, interruptible spoken conversation with a genuinely smart model (GPT live), and when there's something worth *showing* — a recipe, a photo, a comparison, the state of your lights — it renders it on the display instead of just talking. It controls your actual home (Philips Hue, Wyze) and can reach anything else through Google Home by *speaking to it*. Over time it gains a **sense of time and context** — proactive routines, presence awareness — so it feels less like a command line and more like a housemate.

### Product pillars
1. **Conversational, not command-based.** Continuous conversation, barge-in, follow-ups without re-waking. Feels like talking to a person, per the Realtime speech-to-speech model.
2. **A screen that earns its keep.** Don't just talk — *show*. Start with ChatGPT-quality markdown (text, links, images); grow into rich native cards (weather, light controls, timers, media).
3. **Actually controls the house.** Direct APIs and real integrations (Hue local, Wyze, Dreame vacuum); a **voice bridge** to Google Home only as a last resort for anything with no other path.
4. **Proactive & time-aware.** Schedules, routines, gentle proactive suggestions ("it's 9am, run the vacuum?").
5. **Local-first & cheap-at-rest.** Wake word and UI are on-device; cloud (paid) only during active conversation.

---

## 2. The Device & Its Constraints (grounding reality)

| Constraint | Implication for the design |
|---|---|
| **Android Things 8.1 / API 27** | It's real Android 8.1 — normal APKs sideload via `adb install`. No Play Store / Play Services (fine, we don't need them). Old system WebView → **avoid WebView-based UI**; render natively. |
| **MediaTek MT8167S**, modest CPU/RAM | On-device ONNX wake word is fine (openWakeWord is tiny). Keep heavy lifting (LLM) in the cloud. No on-device Whisper/LLM. |
| **Front-facing camera** | Faces the *user*, not the floor. Good for presence/face/gesture/"show me this"; **not** for watching the floor get dirty. |
| **Physical mic-mute switch** | Hardware kill of the mic. UX must detect & surface "mic is muted" state; wake word can't work when muted. |
| **Single mic array, forward speaker** | Enables the "Hey Google" voice bridge (speaker → nearby Nest hears it). Must suppress self-triggering while speaking. |
| **Android Things app model** | We ship a **home/launcher APK** that boots into Jarvis. Foreground service + wake lock for always-on listening. |
| **No Google OOBE / dead backend** | Irrelevant now — we're not using Google's assistant stack at all. We *are* the assistant. |

---

## 3. Prior Art & Dependency Policy — Standing on Giants (without forking them)

**Ground rule (project constraint):** we **do not fork** third-party apps/repos, and we take a **code dependency only on first-party APIs or established libraries (~1k★+, actively maintained)**. The small/hobby *demo* repos below are **reference only** — we read their patterns and reimplement in our own Kotlin. Model *weights* and *vendor SDKs* are files/products, not forks.

**Depend on (first-party / established):** OpenAI GPT-Live/Realtime API · **OkHttp** (~47k★, WebSocket/HTTP) · **ONNX Runtime** (~14k★, on-device wake word) · **Markwon** (~2.9k★, markdown) · Philips Hue local REST (first-party). **Reference only (don't fork):** the demo apps tagged *(reference)* below.

### 3.1 The brain — OpenAI GPT-Live / Realtime API (full-duplex speech-to-speech)
- **GPT-Live** (launched Jul 8 2026) — OpenAI's newest voice generation, **full-duplex** (listens and speaks *simultaneously*), handing hard questions to GPT-5.5 in the background. Versions **GPT-Live-1** and **GPT-Live-1 mini**. Full-duplex is a gift for this product: it makes continuous conversation + barge-in *native* instead of something we hand-engineer.
- **API reality (important):** at launch, **GPT-Live is the consumer (ChatGPT) product; the developer/API voice model is `GPT-Realtime-2.1`** (GPT-5-class reasoning, function calling, server VAD). *Plan: build against `GPT-Realtime-2.1` now and swap to GPT-Live-1 the moment it reaches the API — the app treats the model as a single config value.* ([GPT-Live announcement](https://openai.com/index/introducing-gpt-live/), [API voice models](https://openai.com/index/advancing-voice-intelligence-with-new-models-in-the-api/), [TechCrunch](https://techcrunch.com/2026/07/08/openai-releases-new-voice-models-for-more-natural-live-conversations/))
- **Transports:** WebSocket (raw PCM, simplest on Android) or WebRTC (ephemeral key). First-party API — no third-party repo needed for the brain.
- *Reference only (read the event-flow, don't fork):* the OpenAI docs' samples and small community demos (e.g. [gbaeke/realtime-webrtc](https://github.com/gbaeke/realtime-webrtc)) show the tool-call loop — model emits a function call → app runs it → result returned into the live stream. We reimplement this ourselves in Kotlin.

### 3.2 Android realtime voice — *reference patterns* (read, don't fork)
- **[klomash/openai-realtimeapi-android-agent](https://github.com/klomash/openai-realtimeapi-android-agent)** *(reference)* — shows the native-Kotlin approach end-to-end: OkHttp **WebSocket** to the API, **24 kHz mono 16-bit PCM** capture/playback, Base64 framing, split into mic / playback / session-client. **We reimplement this shape on OkHttp ourselves** — it's ~200 lines of our own code, not a fork.
- **[just-ai/aimybox-android-assistant](https://github.com/just-ai/aimybox-android-assistant)** *(reference)* — a structured dialog/STT/TTS architecture worth studying for clean module boundaries.
- **[kepler296e/converso-gpt4](https://github.com/kepler296e/converso-gpt4)** *(reference)* — the simple STT→GPT→TTS shape, useful for a degraded/offline fallback mode.

### 3.3 Wake word — "Hey Jarvis", on-device
- **[openWakeWord `hey_jarvis` model](https://github.com/dscripka/openWakeWord/blob/main/docs/models/hey_jarvis.md)** — a pretrained **model file** (~200k synthetic clips, ONNX), MIT. This is *weights we load*, not a repo we fork — we run it through **ONNX Runtime (~14k★)** directly.
- *Reference:* **[Re-MENTIA/openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt)** shows the exact Kotlin/ONNX-Runtime wiring (mel → embedding → model, ring buffer, 50 ms hops, min SDK 23 ✓ our API 27). We reimplement that inference loop ourselves against ONNX Runtime.
- **Alternative (vendor SDK, not a fork):** **Picovoice Porcupine** — an official, supported wake-word engine (free tier) with a built-in "Jarvis"; a clean vendor SDK if we'd rather not hand-wire ONNX. Also **microWakeWord** (what the HA Android app uses, works locked/background).

### 3.4 Rendering what GPT wants to show — generative UI
- **[noties/Markwon](https://github.com/noties/Markwon)** (~2.9k★ — clears the 1k bar) — **the default renderer.** The mature de-facto Android markdown lib: CommonMark → native Spannables, **no WebView**, with plugins for **images** (`markwon-image-loader`), **tables**, **code + syntax highlight**, and links. Perfect for API 27 — gives the "ChatGPT view" essentially for free.
- **Tier 2 — structured cards:** for richer output the model calls a tool with a typed **UI intent** (`weather`, `light_control`, `timer`, …) mapped to a **native card** we own. Markdown for prose, native cards for interactive/visual — mixable in one response.
- *Reference patterns:* generative-UI projects — **[awesome-generative-ui](https://github.com/narrowin/awesome-generative-ui)**, **[thesysdev/openui](https://github.com/thesysdev/openui)**, **[mdocui](https://github.com/mdocui/mdocui)** (widgets *inside* markdown) — inform the "text + embedded card" mental model.

### 3.5 The vibe — HER/Jarvis companion devices
- Open "Her"-inspired persona engines, **Omi** (open-source AI wearable), **"Samuel"** (OpenAI Realtime, ambient presence), ESP32-S3 DIY assistants. We borrow **UX/feel**: ambient orb, always-there presence, minimal chrome.

### 3.6 Home control
- **Philips Hue** — **[new local CLIP API v2](https://developers.meethue.com/new-hue-api/)** over **HTTPS** (HTTP dropped for RED compliance, Aug 2025). Auth = press the bridge link button once → get an application key. Debugger at `http://<bridge>/debug/clip.html`. **Easy, local, low-latency — do this natively in the APK (OkHttp + TLS).**
- **Wyze** — **[shauntarves/wyze-sdk](https://github.com/shauntarves/wyze-sdk)** (Python, reverse-engineered; needs key id + api key + login; somewhat inactive, **may break at any time**). Also **[jfarmer08/wyze-api](https://github.com/jfarmer08/wyze-api)**. **Python → run it in the tools relay, not on-device.**
- **Everything else (Dreame vacuum, etc.)** — the **Google Home voice bridge** (§7.5). No per-device integration needed.

---

## 4. System Architecture

### 4.1 The recommended split — "fat device, thin relay"

```
┌───────────────────────────────────────────────────────────────┐
│  LENOVO PANEL  (Kotlin APK, Android 8.1)                        │
│                                                                │
│  [Mic] → openWakeWord ("Hey Jarvis")  ── local, always on, $0  │
│              │ wake                                             │
│              ▼                                                  │
│  ┌────────────────────────────────────────────────┐            │
│  │ Conversation Controller (state machine)         │            │
│  │  • AudioCapture 24kHz PCM ─┐                     │            │
│  │  • AudioPlay (barge-in)  ◄─┤ OkHttp WebSocket    │──────────────►  OpenAI
│  │  • VAD / follow-up window │  (Realtime API)      │            │   Realtime API
│  │  • Tool-call dispatcher ──┘                      │◄──────────────  (GPT live)
│  └───────┬───────────────────────────┬─────────────┘            │
│          │ render                     │ tool calls              │
│          ▼                            ▼                         │
│  ┌───────────────┐        ┌──────────────────────┐             │
│  │ UI Renderer   │        │ Tool Router          │             │
│  │ (Markwon +    │        │  • Hue  → local HTTPS │────────────────►  Hue Bridge (LAN)
│  │  card views)  │        │  • Speaker voice-bridge → "Hey Google…" ─►  Nest speaker (acoustic)
│  └───────────────┘        │  • Wyze/CV/schedule → relay │        │
│                           └───────────┬──────────┘             │
└───────────────────────────────────────┼────────────────────────┘
                                         │ HTTP/WS (LAN)
                                         ▼
                        ┌────────────────────────────────┐
                        │ TOOLS RELAY (Python)           │
                        │ old Windows PC / Raspberry Pi   │
                        │  • wyze-sdk (Wyze devices)      │──►  Wyze cloud
                        │  • scheduler / automations      │
                        │  • (optional) camera CV jobs    │
                        │  • secret vault (API keys)      │
                        └────────────────────────────────┘
```

### 4.2 Why this split (vs. two alternatives)

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **A. All on-device** | Lowest latency, no second box, no LAN dependency | Wyze is Python (can't run natively); API keys sit on device; no CV/scheduler host | Good for MVP brain, but Wyze/proactive need help |
| **B. Thin client + full backend** (LiveKit-style) | Secrets off device, easy Python tools, ephemeral keys | Second box always required; more moving parts; higher voice latency if brain is proxied | Overkill to start |
| **C. Fat device + thin relay (RECOMMENDED)** | Brain/Hue/UI native & fast; relay only for Wyze/CV/schedule/secrets; relay optional at first | One extra small process for full feature set | ✅ Best balance |

**Build order:** ship **A** (device-only: wake + GPT-Realtime brain + Hue + markdown UI) first — it's a complete, delightful product. Add the **relay** when you want Wyze, the vacuum, presence + proactive automations.

### 4.3 Key/secret handling
- MVP/personal: OpenAI key in the APK's encrypted prefs (acceptable for a personal home device; it never leaves your LAN except to OpenAI).
- Better: relay mints **ephemeral Realtime keys** so the long-lived key never touches the device (OpenAI supports ephemeral tokens for client use). Hue key is LAN-only. Wyze creds live only in the relay.

---

## 5. The Conversation Loop (the heart) — State Machine

```
        ┌──────────── IDLE / AMBIENT ─────────────┐
        │  clock · weather · breathing orb        │
        │  openWakeWord ONLY (local, no cloud)    │
        └───────────────┬─────────────────────────┘
                        │ "Hey Jarvis" detected
                        ▼
                ┌───── WAKING ─────┐  orb pulses + earcon, open WS session
                └────────┬─────────┘
                         ▼
        ┌───────── LISTENING ──────────┐  live waveform + partial transcript
        │  stream mic → Realtime API   │  (server VAD detects end-of-speech)
        └───────┬──────────────────────┘
                │ user turn ends
                ▼
        ┌────── THINKING ──────┐  subtle shimmer (may fire tool calls)
        └───────┬──────────────┘
                ▼
        ┌────── SPEAKING ──────┐  orb animates to TTS; render card if content
        │  BARGE-IN: user talks │──► cancel playback, back to LISTENING
        └───────┬──────────────┘
                │ assistant turn ends
                ▼
        ┌─ FOLLOW-UP WINDOW (~8s, VAD) ─┐  "still listening" ring
        │  speech? → LISTENING (no wake)│
        │  silence timeout → IDLE       │
        └───────────────────────────────┘
```

**Design rules that make it feel human (this is the Google-Continued-Conversation behavior, done better):**
- **Wake word only enters from IDLE.** Once in a conversation, follow-ups need no wake word — an **~8s VAD-gated follow-up window** after each response (configurable 5–15s; Google uses ~8s, Alexa ~5s).
- **Barge-in** everywhere the assistant is speaking. On `GPT-Realtime-2.1` we cancel local playback on detected speech; **on GPT-Live (full-duplex) this is native** — it listens *while* speaking, so the turn-taking dance largely disappears. Design the loop so moving to GPT-Live *removes* code, not adds it.
- **Self-trigger suppression:** while Jarvis (or the voice-bridge) is speaking, **pause the wake-word listener** and gate mic input so it doesn't hear itself / Google's reply.
- **Session warmth:** keep the WebSocket open through the follow-up window; tear down after timeout to stop billing. Optionally keep it "warm" a few extra seconds for rapid back-and-forth.
- **Graceful timeouts & "stop":** "stop"/"never mind"/"thanks" ends the turn and returns to IDLE.

---

## 6. Feature Catalog

### 6.1 Core (MVP)
- [ ] "Hey Jarvis" on-device wake word (openWakeWord `hey_jarvis`).
- [ ] `GPT-Realtime-2.1` live conversation (WebSocket + 24 kHz PCM), barge-in, follow-up window.
- [ ] Markdown rendering (Markwon) with **links + images**.
- [ ] Tool calling: **Hue** (local), **timers/alarms**. (Voice bridge deferred — later, opt-in, last-resort only.)
- [ ] Ambient idle screen (clock/weather/orb) + full state-machine UI.
- [ ] Mic-mute detection + visible state.

### 6.2 Phase 2
- [ ] **Wyze** control (via relay).
- [ ] **Dreame vacuum** control (start/dock/go-to-room) via its relay integration (§7.9).
- [ ] **Phone presence** (home/away geofence) → presence-aware automations (§7.8).
- [ ] Rich native **cards** beyond markdown (weather, light panel, timer, now-playing, image gallery).
- [ ] **Proactive / time-aware** automations + scheduler (§7.6).
- [ ] Presence detection via panel camera (wake/greet when someone approaches).
- [ ] Persona/voice selection, personalization, memory of preferences.

### 6.3 Phase 3 / stretch
- [ ] **Roaming camera** — dispatch the vacuum + pull a snapshot/stream ("go check the kitchen") (§7.9).
- [ ] **Away-mode patrol** — presence "away" → vacuum patrol + anomaly push to phone.
- [ ] Face-based personalization (who's talking).
- [ ] "Show me this" — hold an object/document to the panel camera, ask about it (vision tool).
- [ ] Multi-room / multiple panels; hand-off.
- [ ] Local fallback mode (offline STT/TTS) when internet is down.

---

## 7. Component Deep-Dives

### 7.1 Wake word
- **Engine:** our own ONNX-Runtime inference loop (pattern from `openwakeword-android-kt`) running the `hey_jarvis.onnx` model, threshold ~0.5 (tune for false-accept vs. miss). Foreground service, ring buffer, 50 ms hops. (Or Picovoice Porcupine's SDK.)
- **Always-on** with a partial wake lock; respects the hardware mute switch (surface "muted" if triggered while muted).
- **Suppressed** while Jarvis/voice-bridge is speaking (self-trigger guard).

### 7.2 Audio I/O
- **Capture:** `AudioRecord`, 24 kHz mono 16-bit PCM (the API's native rate), non-blocking buffer → Base64 → WS. (Our code; klomash's `AudioCapture` as reference.)
- **Playback:** `AudioTrack`, queued streaming of model audio; **flush/stop instantly on barge-in** (moot once on full-duplex GPT-Live). (klomash's `AudioPlay` as reference.)
- **Echo/self-hearing:** during playback, gate mic to the WS (or rely on server VAD + AEC). Hard-mute wake word.

### 7.3 The brain — GPT-Live / Realtime session
- **Model:** `GPT-Realtime-2.1` today; **swap to GPT-Live-1 (full-duplex) the moment it reaches the API** (see §5). The model name is a single config value.
- **Transport:** OkHttp WebSocket (simplest; PCM in/out). Consider WebRTC later for better jitter/AEC; WS is the proven Android path.
- **Session config:** system prompt (Jarvis persona + house/device registry), **tool/function schemas**, voice, `turn_detection` (server VAD, silence duration ≈ our follow-up window), input/output audio formats.
- **Event flow:** `input_audio_buffer` append → `response.audio.delta` (play) + `response.audio_transcript.delta` (caption) + `response.function_call_arguments.*` (tools) → we execute → `conversation.item.create` (tool result) → model continues. We implement this loop ourselves.
- **Cost control:** only connected during active conversation; enable prompt caching; keep tool outputs terse.

### 7.4 Tool / function calling — the router
The model is given a toolset; each tool is a function schema. The **Tool Router** on-device dispatches:

| Tool | Where it runs | Notes |
|---|---|---|
| `set_lights(room, on, brightness, color)` | On-device → Hue local HTTPS | Fast, LAN-only |
| `command_google_home(utterance)` | On-device → **speak via speaker** | Last-resort bridge, opt-in (§7.5) |
| `set_timer / set_alarm(duration/time)` | On-device | Local + UI card |
| `show_content(markdown | card_spec)` | On-device → UI renderer | Generative UI (§7.7) |
| `control_wyze(device, action)` | **Relay** (Python wyze-sdk) | May break; degrade gracefully |
| `control_vacuum(action, room?)` | **Relay** (Dreame/MIoT) | start/dock/go-to-room (§7.9) |
| `check_house(room)` | **Relay** → vacuum cam snapshot | Roaming camera (§7.9) |
| `get_presence()` / home-away events | **Relay** (geofence webhook) | Phone location (§7.8) |
| `schedule_automation(rule)` | **Relay** | Proactive engine (§7.6) |
| `get_weather / web lookups` | On-device or relay | e.g. Open-Meteo |
| `look(prompt)` (panel camera vision) | On-device capture → model/relay | Phase 3 |

**Routing rule for home control:** *always use a direct API/integration (Hue, Wyze, Dreame) when one exists.* `command_google_home` is a **last resort** — only for a device with no other tool, *and* only if you opted the bridge in for it; otherwise Jarvis says it can't reach that device rather than shouting across the room. It decides via the toolset + a device registry in the system prompt.

### 7.5 The Google Home voice bridge — *last-resort fallback only*
> It's *possible* for Jarvis to speak "Hey Google, …" to a nearby Nest and control anything in Google Home with zero integration. But it's a **poor experience** — you'd hear the AI talking at Google and Google talking back — so it's strictly a **last resort**: opt-in, for the rare device with **no API and no community integration**. **Direct APIs (Hue, Wyze) and real integrations (Dreame via MIoT) always come first.**

**When it may fire:** only when (a) the target has no direct tool, *and* (b) you've opted the bridge in for that device. Otherwise Jarvis just says it can't reach it.

**Implementation (if enabled):** `command_google_home(utterance)` → pause wake-word listener + gate mic (so it doesn't hear itself or Google's reply) → speak `"Hey Google, <utterance>"` with a consistent local TTS → optional confirmation listen → un-gate after a cooldown.

**Caveats:** needs a Google device in earshot (your working 10" Lenovo or a Nest — the repurposed 7" can't self-execute); ~1–3 s and can mishear; audibly clunky. Good *only* as the safety net beneath real integrations — e.g. before the Dreame integration is wired, or for a one-off Google-only gadget.

### 7.6 Proactive & time-aware engine ("sense of time")
A rules/scheduler layer (in the **relay** so it runs even if the screen sleeps):
- **Schedules:** cron-like — "weekdays 9am → `control_vacuum('start')`", "sunset → dim living room to 30%".
- **Event triggers:** presence (camera), time-of-day, sensor states (Hue motion, Wyze).
- **Proactive voice:** instead of silently acting, Jarvis can *offer*: "It's 9am — want me to run the vacuum?" (respect quiet hours; make proactivity opt-in per rule).
- **Natural-language rule creation:** "every night at 11 turn off all the lights" → model calls `schedule_automation(...)` → stored in relay.
- Note on the **vacuum-dirt idea:** trigger by **schedule** (reliable) rather than camera floor-vision (the panel camera faces you, not the floor). If you want true dirt-sensing, that's the vacuum's own sensors/app, not this device.

### 7.7 Generative UI — "render what GPT wants to show"
Two-tier, progressive:
- **Tier 1 (MVP): Markdown.** Model returns markdown in `show_content`; **Markwon** renders native (headings, lists, **links**, **images** via `markwon-image-loader`, tables, code). This already gives the "ChatGPT view" feel with images. No WebView.
- **Tier 2: Structured cards.** Model calls `show_content({type, data})` with a typed spec → app maps to a **native card**: `weather`, `light_control` (live sliders/toggles wired to Hue), `timer` (countdown), `image_gallery`, `now_playing`, `list/choices`. Borrow the mdocui idea — *markdown prose with embedded widget tags* — so the model can mix rich text and controls in one response.
- **Layout:** cards slide in over/beside the orb; auto-dismiss or persist; readable from across the room (large type, high contrast).

### 7.8 Presence & location awareness ("is Richard home?")
Jarvis knows home/away from **phone geofencing** — no Home Assistant needed:
- **[Locative](https://www.home-assistant.io/integrations/locative/)** (open-source iOS) or an **iOS Shortcuts "arrive/leave home" automation** fires a **webhook to the relay** on geofence enter/exit → relay holds `home | away | arriving` state.
- Exposed to the model as `get_presence()` and as **event triggers** for the proactive engine.
- **Presence-aware behavior:**
  - *Arriving:* lights on, warm greeting on the panel, "welcome back" + daily brief.
  - *Leaving / away:* away-mode — run the vacuum ("nobody home, good time to clean"), dim/off lights, arm a light "watch" mode, notify on anomalies.
  - *Away + something happens:* push to your phone rather than speak to an empty room.
- Jarvis stops talking to an empty house and tunes proactivity to who's actually there.

### 7.9 Roaming camera — the vacuum as a mobile eye
Your Dreame has a **camera on wheels** — a patrol cam you can send anywhere.
- **Control** (reliable, preferred): via the relay using the **[Tasshack/dreame-vacuum](https://github.com/Tasshack/dreame-vacuum)** approach (Mi Home / MIoT cloud) — 100+ entities, 24 services: **start / dock / go-to-room / spot / map**. `control_vacuum("go_to", "kitchen")` runs as a clean API call. (The voice bridge is only a stopgap if this integration isn't set up yet.)
- **Live camera / snapshot** (feasible but fragile, **model-dependent**): the same integration exposes **live camera streaming** on Mi Home-compatible models (needs Mi Home creds; some models — e.g. certain Ultra units — can't be added to Mi Home and lose the stream). Pattern: `check_house("living room")` → relay tells the vacuum to drive there → grabs a frame → returns it → Jarvis shows it on the panel and can *describe* it via the model's vision ("looks clear, no one's there").
- **UX:** "Jarvis, go check the kitchen" → the robot rolls out, a snapshot/stream lands on the display. A moving surveillance cam, on command or on an away-mode patrol schedule.
- **Honest caveat:** the camera stream is a reverse-engineered, proprietary path (these robots often use an Agora/WebRTC stream) — expect it to be the most brittle feature and to break on app/firmware updates. Control is durable; the video feed is best-effort.

---

## 8. UI / UX Design

### 8.1 Visual language — **ChatGPT, copied closely**
Decision: the panel follows **OpenAI's ChatGPT visual design** closely (reference screenshots — home, voice mode, weather answer — were used during development and are not redistributed here). Light, white, minimal, thin-line icons, pill shapes.

**Design tokens (lifted from the screenshots):**
| Token | Value | Used for |
|---|---|---|
| Ground | `#FFFFFF` | screen background |
| Text primary | `#0D0D0D` | headings, values |
| Text secondary | `#8F8F8F` / `#B4B4B4` | captions, placeholders |
| Hairline | `#ECECEC` | card borders, pill outlines |
| Tile gray | `#F7F7F8` | control tiles, mic ring |
| **Cream** | `#F7F2E9` | selected/active chip (ChatGPT's selected-day), lights-on state |
| **Blue** | `#3E68FF` | voice button, interactive accent |
| User bubble | `#DCE9FF` | user's words in thread |
| **Orb** | `#7D84F8 → #96A1FA → #E9EDFE` + white cloud puffs | the voice-mode cloud sphere |
| Radii | pills `999`, cards ~`24px` | everything rounded |
| Type | SF/system sans (≈ OpenAI's Söhne), weights 400/500/600 | greeting 400 large, values 500 |

**Structure copied from ChatGPT:**
- **Idle = ChatGPT home:** centered greeting ("Good to see you, Rich."), input pill with `+` / mic / blue voice button. No orb at idle. Small gray clock/status line on top.
- **Voice mode = the blue cloud orb**, centered on white, transcript in gray below, pill with `Type` / mic-ring / black ✕.
- **Answers = the chat pattern:** user's words in a blue bubble (right), then content — markdown or a native card (weather card: big light-weight temp, F/C toggle, 8-day row with cream-highlighted today, black temperature curve with cream under-fill, gray time axis).
- **Earcons:** subtle rising tone on wake, soft tone on end-of-turn. Never jarring.

### 8.2 State → visual mapping
| State | Visual | Screen | Audio |
|---|---|---|---|
| Idle/Ambient | no orb — ChatGPT-home greeting + pill | greeting · clock/status line | silent |
| Waking | cloud orb fades in | white voice screen | rising earcon |
| Listening | **blue cloud orb**, clouds drifting | live transcript in gray below orb | — |
| Thinking | orb clouds churn faster | keep orb | — |
| Speaking | orb pulses with voice | **content card/thread** slides in if visual | TTS |
| Follow-up window | orb stays, calmer | faded transcript | — |
| Muted (hw switch) | grayed orb + banner | "Mic muted" | — |
| Offline | desaturated orb | "offline" note, degraded mode | — |

### 8.3 Conversation surface
- Captions optional (accessibility / noisy room); off by default for the "just talk" feel.
- Content cards are the star — a recipe with the photo, a weather card, a light panel you can also touch.
- **Touch is complementary:** tap a light card to toggle, tap a link/image to expand. Voice-first, touch-second.

### 8.4 Continuous-conversation UX (matches Google, improved)
- After Jarvis speaks, the **"still listening" ring** shows the ~8s follow-up window — you just keep talking, no "Hey Jarvis."
- **Barge-in:** start talking any time it's speaking; it stops and listens.
- Visible, honest states so you always know if it's hearing you (vs. Google's ambiguity).

---

## 9. Tech Stack Summary

| Layer | Choice | Why |
|---|---|---|
| App | **Kotlin** APK, min/target API 27, foreground service + wake lock | Native, no WebView, sideload via adb |
| Wake word | `hey_jarvis` model on **ONNX Runtime** (~14k★), our own loop (or Porcupine SDK) | On-device, free, minSDK 23 ✓ |
| Audio | `AudioRecord`/`AudioTrack`, 24 kHz PCM (our code; klomash as reference) | The API's native format |
| Transport | **OkHttp** (~47k★) WebSocket → OpenAI GPT-Live/Realtime API | Simplest proven path on Android |
| Brain | **OpenAI GPT-Live / Realtime API** — `GPT-Realtime-2.1` now, `GPT-Live-1` (full-duplex) when API-ready | Speech-to-speech, GPT-5.x reasoning, barge-in |
| UI content | **Markwon** (~2.9k★) markdown + image-loader/tables → native cards | ChatGPT-quality, no WebView |
| Hue | Local **CLIP API v2** over HTTPS (OkHttp, on-device) | Fast, LAN, easy |
| Voice bridge *(last resort)* | Speaker TTS "Hey Google…" + mic gating | Only for devices with no API/integration; opt-in |
| Relay (opt.) | **Python** on old Windows PC / Raspberry Pi: `wyze-sdk`, scheduler, secrets, CV | Python-only tools, secrets off device |
| Wyze | **shauntarves/wyze-sdk** (in relay) | Only viable community path; may break |

---

## 10. Cost Model (why wake-word gating matters)
- **Idle:** $0 (wake word is local).
- **Active conversation:** ~**$0.05–0.40/min** on `GPT-Realtime-2.1` depending on caching/tool verbosity; the **`mini`** tier is ~⅓ the cost. Audio tokens: user ≈1 tok/100 ms, assistant ≈1 tok/50 ms.
- **Design levers:** wake-word gate (biggest), prompt caching (resend system prompt cheaply), terse tool outputs, tear down session on follow-up timeout, consider `mini` for casual chat and flagship for hard asks.
- Rough: a dozen ~1-min interactions/day ≈ a few dollars/month. Leaving a session open 24/7 would be **~$70–500/mo** — hence *never* do that.

---

## 11. Security & Privacy
- **Local-first:** wake word, Hue, UI never leave the LAN. Only active-conversation audio goes to OpenAI.
- **Keys:** prefer relay-minted **ephemeral** Realtime tokens; Hue key LAN-only; Wyze creds only in relay's vault.
- **Mic honesty:** always-visible listening state; honor hardware mute; a clear "privacy" gesture to fully sleep.
- **Panel camera:** off by default; explicit opt-in per feature (presence, vision); never streamed anywhere without a tool call.
- **Voice bridge:** logs what it says to Google (transparency); quiet-hours guard.
- **Home surveillance + location is high-sensitivity data.** Vacuum-cam frames and phone-location history are the most sensitive things here. Keep them in the LAN/relay only, never persist to third parties, encrypt the relay's store, lock the relay behind auth, and only send a camera frame to the cloud model on an explicit `check_house`/`look` call (not continuously). Treat "away-mode patrol" as opt-in.

---

## 12. Build Plan (phased)

**Milestone 0 — Skeleton (device-only, "it talks").**
Write our own OkHttp audio+WS layer (klomash as *reference* only) → connect `GPT-Realtime-2.1` → basic push-to-talk → confirm mic/playback/barge-in on the actual panel.

**Milestone 1 — "Hey Jarvis" + continuous conversation.**
Wire the `hey_jarvis` model on ONNX Runtime → wake gates the session → follow-up window + VAD + self-trigger suppression → ambient idle screen + state machine UI.

**Milestone 2 — It shows things + controls Hue.**
Markwon markdown/image rendering via `show_content` (+ native weather/light cards) → Hue local tool (on/off/dim/color) → live light card.

**Milestone 3 — Broader control + last-resort bridge.**
Timers/alarms → Dreame vacuum via its integration (relay) → and *only* as an opt-in last resort, the `command_google_home` voice bridge (mic gating, cooldown) for devices with no API.

**Milestone 4 — Relay: Wyze + proactive.**
Stand up Python relay (PC/Pi) → wyze-sdk tools → scheduler/automations + proactive voice → natural-language rule creation.

**Milestone 5 — Delight.**
Rich native cards, presence-wake via camera, persona/voice options, memory/personalization, offline fallback.

---

## 13. Risks & Open Questions
- **Wyze fragility:** `wyze-sdk` is reverse-engineered and can break without notice. Mitigate: isolate in relay, degrade gracefully; the voice bridge is a last-ditch backup only ("Hey Google, turn on the Wyze plug").
- **Voice-bridge reliability:** acoustic round-trip is slower and can mishear; needs a Google device in earshot and solid self-trigger suppression. It's a fallback, not the primary path.
- **Android Things quirks:** launcher/home-app behavior, wake locks, background mic longevity on 8.1 — validate early on the real unit.
- **Echo/AEC:** the panel hearing its own TTS / Google's replies. Gate mic during playback; test AEC.
- **Realtime cost creep:** enforce session teardown + caching; watch the meter.
- **Panel camera expectations:** front-facing → presence/personalization yes; floor-dirt sensing no. Vacuum = schedule + its own integration.
- **Vacuum-cam fragility:** the live stream is a reverse-engineered, model-dependent, proprietary (Agora/WebRTC) path — the most brittle feature; will likely break on firmware/app updates. Control (start/dock/go-to-room) is durable; treat the video as best-effort.
- **Surveillance + location weight:** always-on mic, a roving camera, and phone-location tracking is a lot of sensitive capability in one box. Keep it LAN-local, opt-in per feature, secured (see §11). Legitimate for your own home; design it so it can't be quietly repurposed.
- **Latency budget:** wake (local, ~instant) + WS setup + first-token audio. Keep the session warm within a conversation; pre-open on wake.
- **Persona & privacy tone:** how proactive is too proactive? Default conservative, opt-in per automation.

### 13.1 Absolute last-resort fallback (noted so we never wonder — **we don't want this**)
If the custom APK path somehow dead-ends, the lame backup is: sideload a **browser in kiosk mode → chatgpt.com** (or attempt the ChatGPT Android APK) on the panel. Recorded only for completeness:
- **ChatGPT APK:** likely *incompatible* — needs newer Android/Play Services than Things 8.1 has.
- **Browser route:** the system WebView/browser on 8.1 is ancient; chatgpt.com may not run, and **mic access through the browser** is another permission/compat gamble.
- **Loses the whole point:** ❌ no "Hey Jarvis" wake word (ChatGPT voice needs a tap), ❌ no Hue/Wyze/vacuum tools, ❌ no ambient idle screen, ❌ no proactive automations. It's a kiosk tab, not Jarvis.
- Verdict: **break-glass only.** The real fallback for a dead-end is different hardware running our APK, not this.

---

## 14. Appendix — Reference Repos & Links

**Realtime / brain**
- OpenAI Realtime API docs — https://developers.openai.com/api/docs/guides/realtime
- openai/realtime-voice-component — https://github.com/openai/realtime-voice-component
- gbaeke/realtime-webrtc (tool-calling example) — https://github.com/gbaeke/realtime-webrtc
- livekit/agents — https://github.com/livekit/agents

**Android voice**
- klomash/openai-realtimeapi-android-agent — https://github.com/klomash/openai-realtimeapi-android-agent
- just-ai/aimybox-android-assistant — https://github.com/just-ai/aimybox-android-assistant
- kepler296e/converso-gpt4 — https://github.com/kepler296e/converso-gpt4

**Wake word**
- dscripka/openWakeWord — https://github.com/dscripka/openWakeWord
- hey_jarvis model — https://github.com/dscripka/openWakeWord/blob/main/docs/models/hey_jarvis.md
- Re-MENTIA/openwakeword-android-kt — https://github.com/Re-MENTIA/openwakeword-android-kt

**Generative UI / rendering**
- noties/Markwon — https://github.com/noties/Markwon
- awesome-generative-ui — https://github.com/narrowin/awesome-generative-ui
- thesysdev/openui — https://github.com/thesysdev/openui
- mdocui — https://github.com/mdocui/mdocui
- CopilotKit/OpenGenerativeUI — https://github.com/CopilotKit/OpenGenerativeUI

**Home control**
- Philips Hue new local API — https://developers.meethue.com/new-hue-api/
- shauntarves/wyze-sdk — https://github.com/shauntarves/wyze-sdk
- jfarmer08/wyze-api — https://github.com/jfarmer08/wyze-api
- Tasshack/dreame-vacuum (control + live cam) — https://github.com/Tasshack/dreame-vacuum

**Presence / location**
- Locative (iOS geofence → webhook) — https://www.home-assistant.io/integrations/locative/

**Wake-word / continuous-conversation background**
- Home Assistant wake words — https://www.home-assistant.io/voice_control/about_wake_word/

---

## 15. UI Framework & Component Stack — what's new (2025/26) that *actually runs on API 27*

The constraint filters the field hard: much of the 2025/26 Android UI wave needs API 31–33 and is dead on Android 8.1.

| Tech (2025/26) | Verdict on API 27 | Use it for |
|---|---|---|
| **[Jetpack Compose](https://developer.android.com/jetpack/androidx/releases/compose-runtime)** | ✅ works — Compose minSdk is **23**, we're 27 | the whole UI; modern, least code, maps 1:1 to the mockup |
| **[Material 3 Expressive](https://developer.android.com/jetpack/androidx/releases/compose-material3)** (I/O 2025: 35 shapes, shape-morph, motion physics, 15 components) | ⚠️ components render, but the *look* is very "Google" and it complements Android 16 (cosmetic bits N/A on 8.1) | **cherry-pick primitives** — `androidx.graphics.shapes` morphing + Compose **spring** motion — for fluid orb/state transitions; skip the stock Material look |
| **[Haze](https://chrisbanes.me/posts/haze-1.0/)** (blur/glassmorphism) | ❌ real blur needs API 32/33; ≤31 falls back to a scrim | **no true frosted glass here** — fake it with translucent gradient + border + shadow (mockup already does this convincingly) |
| **Coil 3** (async images) | ✅ old APIs fine | images in answers, photos, camera frames |
| **Lottie** | ✅ old APIs fine | optional pre-baked animations; orb stays Compose Canvas |
| **[Markwon](https://github.com/noties/Markwon)** (~2.9k★) or a Compose-native markdown renderer | ✅ | render GPT's markdown (Markwon via `AndroidView`, or a Compose md lib for cleaner interop) |
| **Orb** = Compose **Canvas** (animated gradients) | ✅ | dependency-free; AGSL `RuntimeShader` would be nicer but needs API 33 (no) |

**Bottom line:** Kotlin + **Jetpack Compose**, **custom** composables in our Apple-minimal aesthetic, borrowing only the **shape-morph + spring** primitives from the 2025 "expressive" wave for motion. Real blur is off the table; faked glass is indistinguishable at a glance. **One caveat we must test first:** Compose on this exact deprecated Android Things / MT8167S unit is unproven — hence **Spike A** below. Guaranteed fallback if it misbehaves: classic Android **Views** (more code, bulletproof on 8.1).

> **Why not just upgrade the OS to get the API 31+ features?** *Evaluated and rejected — infeasible AND moot.*
> - **No newer Android exists for Ivy.** Lenovo/Google only ever shipped Android *Things* 8.1 (discontinued 2022). The XDA unlock work flashes only that same 8.1 firmware — no custom ROM / LineageOS / GSI exists for this device.
> - **A GSI won't save our MediaTek device:** (1) it's Android *Things* with a non-standard partition layout, not a clean Project-Treble device; (2) MediaTek MT8167 has poor GSI/Treble support and **no GSI has been demonstrated on Ivy**; (3) the general rule is you can only go **~one Android version above** what the device shipped with (the GSI reuses the original 8.1-era vendor blobs), so an 8.1 MTK device realistically tops out at a *buggy Android 9 (API 28)*.
> - **"But the Lenovo ThinkSmart View got Android 11!"** — that's a *different* Lenovo display: the **Qualcomm Snapdragon 624** CD-18781Y, which (unlike our MediaTek unit) has good Treble/GSI support — PHH AOSP 10/11 GSIs boot on it. Even there it **caps at Android 11 (API 30, still < 31)** and **loses WiFi on the GSI** — fatal for a cloud voice assistant. Doesn't transfer to Ivy (MT8167S), and wouldn't be worth it anyway.
> - **So even the best case (API 28) still doesn't reach API 31+** (blur / dynamic color / AGSL). Enormous effort + brick risk (losing the green verified-boot state) for **zero** feature gain.
> - **If a modern OS ever becomes a real blocker,** the fallback is *different hardware* (a cheap tablet / Pi + touchscreen running the same app), not upgrading this panel.

---

## 16. Plan of Action — de-risked, solo-dev order

**Principle:** prove the scary unknowns end-to-end *thin* before building anything pretty. Each early spike is a throwaway that answers one yes/no. Given this device's history (old Android Things, "00"/2.4GHz-only wifi), the risks are real — fail fast. *(This expands the milestone sketch in §12.)*

**Phase 0 — Setup (~½ day).** Android Studio project, Kotlin + Compose, `minSdk`/`targetSdk` 27. Build → sideload via our local `tools/platform-tools/adb`. OpenAI key with `GPT-Realtime-2.1` access.

**Phase 1 — De-risk spikes (the important part).** Four tiny probes; **all must pass to greenlight**:
- **A · Compose on the panel** — does a trivial Compose screen render smoothly on *this* unit? (No → fall back to Views.)
- **B · Mic capture** — `AudioRecord` records 3 s, `AudioTrack` plays it back. Confirms mic + hardware mute-switch behavior.
- **C · Realtime round-trip (biggest risk)** — OkHttp WebSocket → `GPT-Realtime-2.1`, stream mic PCM up, hear the spoken reply. Proves the whole audio pipeline **and** that the device's wifi holds a low-latency socket (the "00"/2.4GHz concern). Measure latency.
- **D · Wake word** — `hey_jarvis` on ONNX Runtime, log detections, watch CPU. Confirms always-on is feasible on this SoC.

**Phase 2 — Thin vertical slice: "it wakes and talks" (no polish).** Wire A+B+C+D into the state machine: wake → session → converse (barge-in) → ~8 s follow-up window → idle. Ugly UI is fine. *→ a working voice assistant.*

**Phase 3 — The screen.** Build the Compose UI from the mockup (idle/listening/answer/… states + the orb). Stand up the **tool-driven card system** and ship the **first native card end-to-end: Weather** (tool fetches Open-Meteo → renders `WeatherCard` → returns a spoken summary). Markdown (Markwon) for freeform answers. *→ "Jarvis with a screen."*

**Phase 4 — Control the house: Hue.** `set_lights`/`get_lights` tool → live `LightsCard`. First real action, on-device, no relay. *→ it does things.*

**Phase 5 — Relay + expansion.** Python relay on the old PC / a Pi: Wyze, Dreame vacuum (control, then camera), phone-presence webhook, proactive scheduler. Add cards/tools opportunistically (timer, now-playing, photo). *→ "smarter Google Home."*

**Phase 6 — Polish & live-in.** Persona/voice, earcons, motion polish, memory/personalization, mic-mute UX. Run it for real; fix what annoys you. **Swap the model to `GPT-Live-1` the day it hits the API** (deletes the barge-in code).

**Where I'd start tomorrow:** Phase 0 + **Spike C first** — highest-risk, highest-information (does this device's network + the realtime pipeline actually work?). If C passes, the rest is just building. If C struggles, we fix networking before writing a line of UI.

---

## 17. Build Log

**2026-07-12 — Phase 0 DONE + Spike A PASSED.**
- Fully local toolchain in `tools/` (JDK 17, Gradle 8.9, Android SDK 34, `GRADLE_USER_HOME=tools/gradle-home`) — nothing global, `~/.gradle` untouched.
- App scaffolded at `jarvis/` (Kotlin + Compose, `minSdk=targetSdk=27`, pkg `com.avera.jarvis`). Warm rebuild ≈ 8s.
- **Spike A PASSED:** the Compose UI (Canvas cloud orb + animation, ChatGPT design tokens) **renders on the real MT8167S / Android Things 8.1 (API 27) panel** — no crash, `Displayed …MainActivity: +1s132ms`. Views fallback not needed.
- **OOBE foreground-steal handled:** the `com.google.android.apps.mediashell` "Get the app" cast shell grabs focus. Fix (adb root available): `settings put global device_provisioned 1` + `settings put secure user_setup_complete 1` + `pm disable-user com.google.android.apps.mediashell`. Jarvis then holds foreground. TODO Phase 1: register our app as HOME so this is permanent across reboots.
- Install/run: `adb install -r jarvis/app/build/outputs/apk/debug/app-debug.apk` → `am start -n com.avera.jarvis/.MainActivity`.

**Next:** Spike C (realtime round-trip on the panel) + Spikes B (mic) & D (wake word).

**2026-07-12 — Spikes B + C PASSED. Jarvis talks on the panel. 🎉**
Full bidirectional realtime loop working on the real unit: **mic → server VAD → transcription → GPT (gpt-realtime-2.1-mini) → streamed audio → panel speaker**, with live on-screen transcript. One test session completed **15 request→response turns**. Spike B (mic) passed too — `AudioRecord` captures clean (`maxAmp` 600–1600 on speech).

**THE critical, non-obvious fix — `com.google.assistant.core` wrecks WiFi.** For hours the realtime stream would connect then wedge after ~2 audio chunks (`SocketTimeout: no pong`); throughput measured ~40 kbps while the app ran vs ~9 Mbps stopped. Root cause was **NOT** our app, the UI/animation, CPU, or the radio — it was the leftover **Google Assistant service hammering the single-radio WiFi with background scans**, flapping the connection. Fix:
```
adb shell pm disable com.google.assistant.core
adb shell pm disable com.google.android.apps.mediashell
adb shell settings put global captive_portal_mode 0
adb shell settings put global captive_portal_detection_enabled 0
```
→ throughput went rock-solid ~10–12 Mbps, 5/5 clean, and the stream completed instantly. **This must run on every boot** (persist via our HOME app or an init step). Captive-portal disable also stops DNS flaps on the deprovisioned device.

**GA Realtime API protocol (migrated off beta):**
- URL `wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini` — **no `OpenAI-Beta` header** (that triggers "Realtime Beta API is no longer supported").
- `session.update` → `session.type="realtime"`, `output_modalities=["audio"]`, audio nested: `audio.input.{format:{type:"audio/pcm",rate:24000}, turn_detection:{server_vad}, transcription:{model:"whisper-1"}}` and `audio.output.{format:{type:"audio/pcm",rate:24000}, voice}`. **`output.format.rate` is required** (docs omit it; API rejects without it).
- Events: `response.output_audio.delta` (audio), `response.output_audio_transcript.delta` (caption), `conversation.item.input_audio_transcription.completed` (user words), `input_audio_buffer.speech_started/stopped`, `response.done`.
- Audio: `input_audio_buffer.append` with base64 PCM16 24 kHz. Device captures at 48 kHz (widely supported) → decimate ×2 → 24 kHz.

**Working:** app code (`jarvis/`), local toolchain (8 s rebuilds), session safety caps (10-min hard, 60-s idle), `config.yaml` (model/voice/vad/mic/animate toggles), on-device WiFi join via `WifiManager`.

**2026-07-12 (later) — Echo FIXED + WiFi root cause nailed.**
- **Echo/feedback fixed** via half-duplex mic-gating: stop sending `input_audio_buffer.append` while Jarvis speaks, and **un-gate only after the AudioTrack actually drains** (playback lags `response.done` by *seconds* — e.g. +5960 ms for a long reply; a naive fixed 700 ms tail still looped). Plus an 8 s stall-guard so the mic can never hang gated, and hardware `AcousticEchoCanceler`/`NoiseSuppressor` enabled. Verified: one question → **exactly 2 responses** (greeting + answer), then silence. No runaway.
- **WiFi instability solved — it was NOT the radio, assistant.core, MTU, or our app.** The real cause: the 2.4GHz APs here sit on **congested channel 1**, and Android **band-steers/roams to the 2.4GHz BSSID** even when a strong 5GHz is available → stream dies. Fix: **pin `WifiConfiguration.BSSID` to a 5GHz AP** (a strong 5 GHz AP) + force a **fresh DHCP lease** (`svc wifi disable; ip addr flush wlan0; svc wifi enable` — else it keeps a stale IP from the other subnet). → rock-solid ~30 Mbps, streams flawlessly. 5 GHz works now (country=US, "00" gone). App supports `--es wifi_bssid` to pin. (Secondary hygiene: `pm uninstall -k --user 0 com.google.assistant.core`, captive-portal off — help but weren't the fix. For always-on reliability, **USB Ethernet** still the cleanest.)

**Known next steps:**
- **Spike D:** "Hey Jarvis" wake word (openWakeWord/ONNX) to replace tap-to-talk.
- Make our app the **HOME launcher** + boot-time init (5GHz-pin WiFi, captive-portal off, mic grant) so it all survives reboot.
- Barge-in: with AEC enabled, later allow interrupting Jarvis (relax the gate) instead of pure half-duplex.
- Swap model → `GPT-Live-1` when it reaches the API.

**2026-07-12 (later still) — Spike D PASSED: "Hey Jarvis" wake word works, fully hands-free.**
- **On-device openWakeWord** (3 ONNX models: melspectrogram → embedding → hey_jarvis) via **ONNX Runtime 1.17.1** (`armeabi-v7a` — device is 32-bit; all ORT versions still ship that ABI). Pipeline verified in a local Python prototype (`tools/oww-proto/`) **before** porting: "Hey Jarvis" = 0.998, negative = 0.001.
- **Algorithm** (16 kHz mono): each 80 ms → melspec on last 1760 samples (480 context + 1280) → **8 mel frames** (`frames=(N−480)/160`), transform `/10+2` → rolling 76-frame window → 1 embedding (96-d) → rolling 16-embedding window → wakeword score. Fires at ≥ `wake_threshold` (0.5).
- **Port bug found & fixed:** the melspec ONNX output is shaped `[1,1,T,32]` — time is **dim 2**, not dim 0. Reading `shape[0]` gave 1 frame/chunk (score always 0). A bundled self-test (`hey_test.wav`, runs once at startup) now confirms the Kotlin pipeline scores **0.998**, matching Python. Live "Hey Jarvis" via speaker scored 0.68 → fired.
- **Mic hand-off:** wake detector owns a 16 kHz `AudioRecord` while idle; on wake it **stops itself** (`running=false; break` — fixes a `closed OrtSession` crash race) and the coordinator starts `RealtimeSession` (48 kHz). On session end (`onClosed`), the wake detector re-arms. Tap-to-talk still works as a fallback.
- **Verified end-to-end on the panel:** "Hey Jarvis" → greeting → "What is the tallest mountain on Earth?" → correct spoken + on-screen answer → **exactly 2 responses, no runaway, echo suppression intact through the wake flow.** `config.yaml`: `wake_word`, `wake_threshold`.

**Known next steps:** make our app the **HOME launcher** + boot init (5 GHz-pin, service-disable, mic grant); "continued conversation" (skip greeting / keep listening after an answer); barge-in via AEC; swap to `GPT-Live-1` when it hits the API.

---

**2026-07-12 (final) — HOME launcher DONE; WiFi-on-boot is the one soft spot.**
- **Boots straight into Jarvis.** Manifest adds `HOME`/`DEFAULT`/`IOT_LAUNCHER` categories; `pm disable com.android.iotlauncher` + `cmd package set-home-activity com.avera.jarvis/.MainActivity`. Verified across clean reboots: `resolve-activity HOME` → our activity, and after boot the foreground app is `com.avera.jarvis` with the wake word listening + self-test 0.998 — **no adb, just power.**
- **What persists across reboot on its own (no action needed):** `pm uninstall -k --user 0 com.google.assistant.core`, `captive_portal_mode=0`, the `RECORD_AUDIO` grant, the disabled IoT launcher, and the HOME setting. Re-run `adb root` after reboot only for *your* shell commands (the app doesn't need it).
- **WiFi-on-boot — hard, not fully solved.** The app self-connects on boot (`ensureWifi`: delay 9 s → `WifiConnect` → verify DNS+routing → 1 retry). It *works* when the AP is reachable and freshly connected (we ran dozens of full conversations on stable 5 GHz). But a bulletproof boot connection fights three things: (1) the device's flaky single-radio WiFi; (2) **band-steering** to congested 2.4 GHz (which stalls the realtime stream) vs a **hard 5 GHz BSSID pin** that gets *stuck SCANNING* if that AP blips (DFS/channel change); (3) early-boot DHCP timing that can leave an IP with no route/DNS. Aggressive app re-connect loops just churn the framework into a bad state. Current config: `wifi_bssid` left **empty** (resilient — stays connected, may land on 2.4 GHz) rather than hard-pinned. **The robust production fix is a USB-Ethernet adapter into the service-port hub** — bypasses the radio entirely. (As of this writing the panel is unattended and the 5 GHz AP isn't associating — likely a router/range issue, not the device.)

**Known next steps:** USB-Ethernet for rock-solid connectivity; "continued conversation" (skip greeting, keep listening after an answer); barge-in via AEC; swap to `GPT-Live-1` when it hits the API.

---

**2026-07-12 — First rich card: the Weather widget (the "GPT shows you something" system).**
- **Tool calling works (GA):** `session.tools` = `[{type:function, name:get_weather, parameters:{location}}]`. Flow: model emits `response.function_call_arguments.done` (call_id + args) → app fetches → replies with a `conversation.item.create {type:function_call_output, call_id, output}` + `response.create` → model speaks the summary. This is the reusable pattern for *every* tool (lights/Wyze/vacuum next).
- **Weather:** `Weather.fetch()` uses **Open-Meteo** (free, no key, on-device): geocode name→lat/lon, then current + 7-day + hourly. `WeatherData` → **native `WeatherCard` composable** (location, big temp + °F, 7-day row w/ WMO-code emoji + cream "Today" chip, hourly **temperature curve** on Canvas). Near-pixel match to the ChatGPT weather card. `RealtimeSession.weather` state drives it; cleared on the next user turn.
- Verified the card UI on the panel (offline demo via `--es demo weather`); live fetch is 2 HTTPS calls, works once WiFi holds.
- **Template established:** new card = new tool schema + new fetch + new composable. Weather, then Hue (on-device, easy), then Wyze/Dreame (via the Python relay).

---

*Doc v7 — living design. Phase 0 ✓ · Spikes A/B/C/D ✓ · Echo ✓ · HOME launcher ✓ · **Weather card ✓ (first generative-UI tool)**. WiFi needs Ethernet/NVRAM for bulletproof. **A hands-free "Hey Jarvis" panel that boots into itself and shows you things.***
