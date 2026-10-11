# HomeVoice

Smart-Display-Sprachassistent für ein Android-Tablet (getestet als Ziel: LineageOS 18.1 / Android 11).

- **Gemini Live API** mit nativem Audio-Modell: Sprache geht direkt rein und kommt direkt raus (kein STT/TTS)
- **Lokales Hotword** mit [openWakeWord](https://github.com/dscripka/openWakeWord) (ONNX Runtime, kein Account) – solange niemand das Hotword sagt, besteht keine Verbindung zu Gemini
- **Tools**: Timer (stellen, abbrechen, auflisten, Alarm stoppen) und **Home Assistant über MCP**
- **Launcher-UI**: Foto-Diashow aus einem lokalen Ordner, große Uhr, Timer-Anzeige, Statusleiste, Nachtmodus

## Was du brauchst

| Was | Woher |
|---|---|
| Gemini API-Key | <https://aistudio.google.com/apikey> – ein Gemini-Abo (AI Pro/Ultra) enthält **keinen** API-Zugang; Free Tier mit Limits oder Pay-as-you-go |
| Hotword | Nichts – eingebaut sind „Alexa“ (Standard), „Hey Jarvis“, „Hey Mycroft“, „Hey Rhasspy“. Eigene openWakeWord-Modelle (`.onnx`) können hochgeladen werden, z.B. aus der [Home-Assistant-Wakeword-Sammlung](https://github.com/fwartner/home-assistant-wakewords-collection) oder selbst trainiert. Die vortrainierten Modelle stehen unter CC BY-NC-SA 4.0 (privat ok). |
| Home Assistant (optional) | **Variante A – HA-MCP:** die Webhook-URL der Integration (`http://<ha>:8123/api/webhook/mcp_…`) eintragen, kein Token nötig. **Variante B – eingebauter MCP-Server:** Integration „Model Context Protocol Server“ hinzufügen, Basis-URL (`http://<ha>:8123`) plus Long-Lived Access Token eintragen. |

## Bauen & installieren

Voraussetzungen: JDK 17+ und Android SDK (Platform 35). `local.properties` enthält `sdk.dir`.

```bash
./gradlew assembleDebug test
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Danach auf dem Tablet:
1. App starten und Mikrofon erlauben
2. Home-Taste drücken → **HomeVoice** als Standard-Launcher wählen („Immer“)
3. **Einstellungen am Computer:** Das Gerät zeigt oben links `Einrichtung im Browser: http://<IP>:8080 · PIN ……`. Diese Adresse im Browser öffnen und die PIN eingeben. Dort lassen sich alle Einstellungen bearbeiten, Hotword-Dateien und Fotos hochladen und Home Assistant testen. Der Hinweis verschwindet, sobald ein Gemini-Key gesetzt ist; Adresse und PIN stehen dann weiterhin oben in den Einstellungen auf dem Gerät.
4. Alternativ auf dem Gerät: **lange auf den Bildschirm tippen** → Einstellungen: Gemini-Key, Wake Word wählen oder `.onnx` importieren, Home Assistant URL (+ ggf. Token) („Speichern & Verbindung testen“), Fotoordner wählen → **Speichern**

Fotos z.B. per Syncthing oder USB in einen Ordner auf dem Tablet legen. Unterordner werden mit eingelesen, die Liste wird stündlich neu eingelesen.

## Bedienung

- **Hotword** → kurzer Ton → sprechen. Gemini antwortet per Sprache. Du kannst Gemini jederzeit unterbrechen.
- Nach der Antwort bleibt die Session einige Sekunden offen für Rückfragen (Einstellung „Nachlaufzeit“), danach Abschlusston. Gemini kann das Gespräch auch selbst beenden („Danke, das war's“).
- **Tippen** auf den Bildschirm: Gespräch starten (ohne Hotword) / laufendes Gespräch beenden / klingelnden Timer stoppen
- **Hotword während ein Timer klingelt** stoppt den Alarm
- **Lange tippen**: Einstellungen. Unter „System“ kommst du zu den Android-Einstellungen und zur Launcher-Auswahl.

Statusleiste unten: weiß = verbindet, blau = hört zu, violett = spricht, rot = Fehler.

## Audio-Tipps

- **Echo-Unterdrückung** (Standard an): nutzt den Kommunikations-Audiomodus von Android, damit die Echo-Unterdrückung (AEC) des Geräts greift und du Gemini ins Wort fallen kannst.
- Wenn Gemini sich selbst hört und unterbricht: **Halbduplex** einschalten. Dann ist das Mikrofon stumm, solange Gemini spricht (Unterbrechen geht dann nicht mehr).
- Hotword reagiert zu oft oder zu selten → **Schwelle** anpassen. Die Weboberfläche zeigt live den Hotword-Score; Wake Word sagen und die Schwelle knapp unter die erreichte Spitze legen.
- Rechenlast: Ist es im Raum ruhig, überspringt der Detektor das teure Embedding-Modell (adaptive Stille-Schwelle). Auf dem Echo Show 5 (MT8163, 32 Bit) liegt die Last im Leerlauf bei etwa 20 % eines Kerns statt über 80 %.

## Architektur

```
MainActivity (Launcher, Compose)          AssistantService (Foreground, Mikrofon)
        │ StateFlows                                │ start/stop
        ▼                                           ▼
ConversationController ── AudioInput (1× AudioRecord 16 kHz) ──┬─> WakeWordDetector (openWakeWord) [IDLE]
        │                                                      └─> GeminiLiveSession (WebSocket) [aktiv]
        ├── AudioOutput (AudioTrack 24 kHz, Flush bei Unterbrechung)
        └── ToolRegistry ── timer/end_conversation (lokal)
                         └─ ha_* → HomeAssistantMcpClient (Streamable HTTP, JSON-RPC)
```

| Pfad | Inhalt |
|---|---|
| `service/ConversationController.kt` | State Machine, Session-Lebenszyklus, Tool-Ausführung |
| `wakeword/` (Modul) | openWakeWord-Pipeline in reinem Kotlin (Spektrogramm → Embedding → Wake-Word-Modell), mit `say`-basierten Tests auf dem Mac |
| `gemini/LiveProtocol.kt` | Nachrichtenformat der Live API (Setup, Audio, Tool-Antworten, Parser) |
| `mcp/HomeAssistantMcpClient.kt` | MCP-Client (Initialisierung, Session-ID, JSON- und SSE-Antworten) |
| `mcp/SchemaConverter.kt` | JSON Schema → Gemini-Schema (OpenAPI-Teilmenge) |
| `timer/` | Timer mit AlarmManager, Persistenz, Alarmton |
| `ui/` | Home-Screen (Diashow, Uhr, Timer, Alarm) und Einstellungen |

Logs: `adb logcat -s VoiceAssistant`

## Bekannte Grenzen

- API-Keys liegen unverschlüsselt in den privaten App-Einstellungen. Für ein privates Gerät ist das ok.
- Eine Gemini-Session ist auf 10 Minuten begrenzt.
- Werkzeuge mit frei formbaren Objekt-Parametern werden Gemini als JSON-Text angeboten (Gemini erlaubt keine Objekte ohne Felder).
