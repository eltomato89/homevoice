# HomeVoice

*[Deutsche Version](README.de.md)*

Voice assistant and smart display for Android devices running LineageOS – built and tested on an
**Echo Show 5 (2nd gen) with LineageOS 18.1 / Android 11** (MT8163, 1 GB RAM, 960×480).

> The assistant's prompts, web interface and subtitles are currently in **German**.

- **Gemini Live API** with a native audio model: speech goes straight in and comes straight out (no STT/TTS)
- **Local wake word** ([openWakeWord](https://github.com/dscripka/openWakeWord)) – nothing is sent to Gemini until the wake word is heard
- **Smart home** via Home Assistant (MCP), with the current house status in the prompt
- **Music & radio**: Spotify, YouTube Music, internet radio, now-playing widget
- **On-device speaker recognition**: Gemini knows who is talking ("my appointments")
- **Memory**, **scheduled jobs** (reminders, push notifications, events) and **Google Search**
- **Launcher UI**: photo slideshow with face-aware camera pans, large clock, timers, microphone button
- **Setup in the browser**, not on the device (it sits out in the open)

## What you need

| What | Where / notes |
|---|---|
| Gemini API key | [aistudio.google.com/apikey](https://aistudio.google.com/apikey). A Gemini subscription (AI Pro/Ultra) does **not** include API access. Without billing, a lot works within the free tier; **Google Search** requires billing to be enabled (5,000 searches/month free, then $14/1,000) – at which point conversations are billed too. |
| Wake word | Nothing: "Alexa" (default), "Hey Jarvis", "Hey Mycroft" and "Hey Rhasspy" are built in. Custom openWakeWord models (`.onnx`) can be uploaded, e.g. from the [Home Assistant wake word collection](https://github.com/fwartner/home-assistant-wakewords-collection). |
| Home Assistant (optional) | **HA-MCP**: webhook URL (`http://<ha>:8123/api/webhook/mcp_…`), no token. Or the **built-in MCP server**: base URL plus a long-lived access token. House status and event jobs require HA-MCP (`ha_eval_template`). |
| Spotify (optional) | **Premium** and your own app in the [Spotify dashboard](https://developer.spotify.com/dashboard) (Premium-only since Feb 2026). The Spotify app must be installed on the device. |
| YouTube Music (optional) | App on the device, **Premium** for background playback. |

## Build & install

Requirements: JDK 17+ and the Android SDK (platform 35); `local.properties` contains `sdk.dir`.

```bash
./gradlew assembleDebug test
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Permissions for media control and "back to the clock" after opening music apps:
adb shell cmd notification allow_listener de.homevoice.assistant/de.homevoice.assistant.media.MediaListenerService
adb shell appops set de.homevoice.assistant SYSTEM_ALERT_WINDOW allow
```

Then on the device:
1. Start the app, allow the microphone, press Home → set **HomeVoice** as the default launcher ("Always").
2. The display shows `Einrichtung im Browser: http://<IP>:8080 · PIN ……` (later: **long-press** shows address and PIN again).
3. Open the address on your computer, enter the PIN, add your Gemini key – done. Everything else is configured there as well.

There are deliberately **no settings** on the device itself.

## Usage

- **Wake word** → tone → speak. Gemini answers by voice and can be interrupted (in half-duplex mode via the wake word).
- The session stays open as long as someone is speaking (local voice activity detection with [Silero VAD](https://github.com/snakers4/silero-vad)) and ends **5 seconds** after the last word or when Gemini says goodbye ("Danke, tschüss"). Background noise does not keep it open.
- **Tap**: start/end a conversation, stop a ringing timer.
- **Microphone button** top right: recording fully off (red, red bar at the bottom); survives reboots.
- **Long-press**: address and PIN of the web interface.
- Status bar at the bottom: white = connecting, blue = listening, purple = speaking, red = error.
- Subtitles show numbers, dates and times as digits ("am 24. Oktober um 10:30 Uhr").

## Features

### Smart home (Home Assistant)
- Gemini only gets **safe HA-MCP tools** (states, switching, history, calendars, to-dos, listing services). Admin tools (restart, delete, HACS, backups …) only when explicitly enabled.
- **House status**: a Jinja template (editable in the web interface, with preview) that Home Assistant renders every minute. The result – e.g. lights per room, energy/PV, car, weather, calendar – goes into the system prompt, so Gemini can answer without a lookup and knows the entity IDs to switch. The per-minute query only goes to Home Assistant, not to Gemini.

### Music & radio
- **Spotify**: OAuth in the web interface (PKCE, redirect `http://127.0.0.1:8080/spotify/callback` – the page won't load afterwards, copy the address back). Search via the Web API (artists, tracks, albums, your playlists, liked songs), playback in the Spotify app on the device.
- **YouTube Music**: search via the (unofficial) search endpoint of music.youtube.com, playback via deep link in the app.
- **Radio** without an extra app (Media3/ExoPlayer, MP3/AAC/HLS, track info from the stream). Your own station list in the web interface; unknown stations are looked up in the [radio-browser.info](https://www.radio-browser.info) directory and saved.
- Volume in percent, 1–10 or "louder/quieter"; pause, next, "What's playing?".
- **Now-playing widget** with cover art and buttons; music is ducked when the wake word is heard.

### Speaker recognition
- Enroll each person with 3–5 sentences on the device (web interface → "Personen"); only a voiceprint is stored ([CAM++ / 3D-Speaker](https://github.com/modelscope/3D-Speaker)), all on the device.
- Recognition starts at the wake word (provisionally in the system prompt), then per utterance; tool `who_is_speaking` for "Who am I?".
- Each person can have a **personal calendar**: "What's on **for me**?" queries that one, "What's on today?" queries all calendars.
- Uncertain cases (similar voices) are reported as "unknown" rather than guessed. Not a security feature.

### Memory
- "Remember …" / "Forget …"; entries per person or for the household. Everything remembered is in the system prompt (newest first, older ones via search). Editable in the web interface.

### Scheduled jobs
- "Remind me tomorrow at 10 …", "Send the weather to my iPhone every weekday at 7", "Tell me when the dishwasher is done".
- A job is a prompt plus a trigger (time: once/daily/weekdays/weekly, or an event as a Jinja condition, checked locally every minute). Executed by HomeVoice with Gemini Flash and all tools – announcement on the device or push notification via an HA `notify` service. Home Assistant only supplies values; HomeVoice manages the jobs.

### Google Search
- Built-in *Grounding with Google Search* in conversations and jobs (Google searches server-side). Can be turned off.

### Slideshow
- Upload photos through the web interface (HEIC too); they are scaled down to max. 2560 px.
- **Face detection on upload** on the device ([UltraFace](https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB)): the Ken Burns pan keeps all (or the largest) faces in frame instead of showing feet for minutes.
- Gallery with thumbnails and individual deletion. Night mode (dimmed clock).

### Timers
- Set, cancel, list, stop the alarm (tap or wake word); survive reboots.

## Web interface (port 8080, PIN-protected)

Gemini (key, models, voice, prompt, Google Search, microphone) · Jobs · Memory · People · Radio · Music/Spotify ·
Wake word (model, threshold with live score) · Home Assistant (URL, token, tool permissions, house status template) · Slideshow & display.

## Audio notes

- The Echo Show 5 reports **no acoustic echo cancellation (AEC)**. That's why **half-duplex** is the default there: while Gemini speaks, the microphone is muted for Gemini; interrupt via the wake word.
- Set the wake word threshold in the web interface (live score: say the wake word, put the threshold just below the peak).
- When idle, wake word detection skips the expensive model during silence; the slideshow renders at 25 fps. Idle load on the MT8163: roughly 20–40 % of one core depending on ambient noise.

## Data & privacy

| Stays on the device | Sent to Google (Gemini) |
|---|---|
| Wake word and voice activity detection, voiceprints, face detection, photos | The conversation (audio) from the wake word on |
| Memory, jobs, station list, settings, Spotify tokens | System prompt: house status, memory, household, station names |
| Per-minute house status/event queries (to Home Assistant only) | Executed jobs (prompt + tool results) |

Keys are stored unencrypted in the app's private data (acceptable for a private device).

## Architecture

```
MainActivity (launcher, Compose)   AssistantService (foreground)   SettingsWebServer (:8080)
        │ StateFlows                        │                            │
        ▼                                   ▼                            ▼
ConversationController ── AudioInput (16 kHz) ──┬─> WakeWordDetector (openWakeWord)   [idle]
   │                                            ├─> SileroVad + SpeakerRecognizer     [conversation]
   │                                            └─> GeminiLiveSession (WebSocket)     [conversation]
   ├── AudioOutput (24 kHz)       ├── MediaSessions / MusicPlayer / RadioPlayer
   └── ToolRegistry ── timers, music, radio, memory, jobs, speakers
                    └─ ha_* → HomeAssistantMcpClient (Streamable HTTP)
TaskRunner ── GeminiTextClient (generateContent + tool loop) ── system alarms / event checks
```

| Path | Contents |
|---|---|
| `service/ConversationController.kt` | Sessions, session end, announcements, speaker recognition, system prompt |
| `gemini/` | Live protocol (WebSocket) and text client for jobs |
| `mcp/` | MCP client (JSON/SSE, cancellable) and JSON Schema → Gemini schema |
| `media/`, `radio/` | Spotify, YouTube Music, media sessions, radio player and directory |
| `speaker/`, `memory/`, `tasks/` | People/voiceprints, memory, scheduled jobs |
| `tools/` | All Gemini tools |
| `slideshow/`, `ui/` | Photos (scaling, faces), home screen, widget |
| `web/`, `assets/web/` | Web interface (server and page) |
| `wakeword/` (module) | Pure Kotlin + ONNX: openWakeWord, Silero VAD, Fbank + speaker embedding, face detection, camera pan planning |

Tests: `./gradlew test` (unit tests; the `wakeword` module tests wake word, VAD and speaker recognition with macOS `say` voices,
and face detection with public-domain Wikimedia photos). Logs: `adb logcat -s VoiceAssistant`.

## Known limitations

- The Echo Show's camera is not detected by this LineageOS build (no camera feature).
- Gemini sessions are limited to 10 minutes.
- YouTube Music search and Radio Browser are unofficial or community services and may go down.
- Spotify only via the Spotify app (no official playback SDK without the app).

## License

The source code is licensed under the [MIT License](LICENSE). The bundled models in `app/src/main/assets/wakeword/` keep their own licenses:

openWakeWord models: CC BY-NC-SA 4.0 (non-commercial) · Silero VAD: MIT · UltraFace: MIT · 3D-Speaker CAM++: Apache 2.0.
Because of the openWakeWord models, the bundled wake word files – and builds containing them – may not be used commercially.
