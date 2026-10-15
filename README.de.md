# HomeVoice

*[English version](README.md)*

Sprachassistent und Smart Display für Android-Geräte mit LineageOS – entwickelt und getestet auf einem
**Echo Show 5 (2. Gen.) mit LineageOS 18.1 / Android 11** (MT8163, 1 GB RAM, 960×480).

- **Gemini Live API** mit nativem Audio-Modell: Sprache geht direkt rein und kommt direkt raus (kein STT/TTS)
- **Lokales Hotword** ([openWakeWord](https://github.com/dscripka/openWakeWord)) – bis zum Hotword besteht keine Verbindung zu Gemini
- **Smart Home** über Home Assistant (MCP), mit aktuellem Hausstatus im Prompt
- **Musik & Radio**: Spotify, YouTube Music, Internetradio, Now-Playing-Widget
- **Stimmerkennung** auf dem Gerät: Gemini weiß, wer spricht („meine Termine“)
- **Gedächtnis**, **geplante Jobs** (Erinnerungen, Push-Nachrichten, Ereignisse) und **Google-Suche**
- **Launcher-Oberfläche**: Fotodiashow mit gesichtsbasierter Kamerafahrt, große Uhr, Timer, Mikrofon-Taste
- **Einrichtung im Browser**, nicht am Gerät (es steht offen herum)

## Was du brauchst

| Was | Woher / Hinweis |
|---|---|
| Gemini API-Key | [aistudio.google.com/apikey](https://aistudio.google.com/apikey). Ein Gemini-Abo (AI Pro/Ultra) enthält **keinen** API-Zugang. Ohne Abrechnung läuft vieles im Freikontingent; die **Google-Suche** braucht eine aktivierte Abrechnung (5.000 Suchen/Monat frei, dann 14 $/1.000) – dann sind auch Gespräche kostenpflichtig. |
| Hotword | Nichts: eingebaut sind „Alexa“ (Standard), „Hey Jarvis“, „Hey Mycroft“, „Hey Rhasspy“. Eigene openWakeWord-Modelle (`.onnx`) lassen sich hochladen, z.B. aus der [Home-Assistant-Wakeword-Sammlung](https://github.com/fwartner/home-assistant-wakewords-collection). |
| Home Assistant (optional) | **HA-MCP**: Webhook-URL (`http://<ha>:8123/api/webhook/mcp_…`), kein Token. Oder **eingebauter MCP-Server**: Basis-URL plus Long-Lived Access Token. Hausstatus und Ereignis-Jobs brauchen HA-MCP (`ha_eval_template`). |
| Spotify (optional) | **Premium** und eine eigene App im [Spotify-Dashboard](https://developer.spotify.com/dashboard) (seit Feb. 2026 nur mit Premium). Die Spotify-App muss auf dem Gerät installiert sein. |
| YouTube Music (optional) | App auf dem Gerät, **Premium** für Wiedergabe im Hintergrund. |

## Bauen & installieren

Voraussetzungen: JDK 17+ und Android SDK (Platform 35); `local.properties` enthält `sdk.dir`.

```bash
./gradlew assembleDebug test
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Rechte für Mediensteuerung und "zurück zur Uhr" nach dem Öffnen von Musik-Apps:
adb shell cmd notification allow_listener de.homevoice.assistant/de.homevoice.assistant.media.MediaListenerService
adb shell appops set de.homevoice.assistant SYSTEM_ALERT_WINDOW allow
```

Danach auf dem Gerät:
1. App starten, Mikrofon erlauben, Home-Taste → **HomeVoice** als Standard-Launcher („Immer“).
2. Das Display zeigt `Einrichtung im Browser: http://<IP>:8080 · PIN ……` (später: **lange tippen** zeigt Adresse und PIN erneut).
3. Adresse am Computer öffnen, PIN eingeben, Gemini-Key eintragen – fertig. Alles Weitere ebenfalls dort.

Auf dem Gerät selbst gibt es bewusst **keine Einstellungen**.

## Bedienung

- **Hotword** → Ton → sprechen. Gemini antwortet per Sprache und lässt sich unterbrechen (bei Halbduplex per Hotword).
- Die Sitzung bleibt offen, solange jemand spricht (lokale Spracherkennung mit [Silero VAD](https://github.com/snakers4/silero-vad)), und endet **5 Sekunden** nach dem letzten Wort oder wenn Gemini sich verabschiedet („Danke, tschüss“). Geräusche halten sie nicht offen.
- **Tippen**: Gespräch starten/beenden, klingelnden Timer stoppen.
- **Mikrofon-Taste** oben rechts: Aufnahme komplett aus (rot, roter Balken unten); übersteht Neustarts.
- **Lange tippen**: Adresse und PIN der Weboberfläche.
- Statusleiste unten: weiß = verbindet, blau = hört zu, violett = spricht, rot = Fehler.
- Untertitel zeigen Zahlen, Daten und Uhrzeiten als Ziffern („am 24. Oktober um 10:30 Uhr“).

## Funktionen

### Smart Home (Home Assistant)
- Gemini bekommt nur **sichere HA-MCP-Tools** (Zustände, Schalten, Verlauf, Kalender, To-dos, Dienste auflisten). Admin-Tools (Neustart, Löschen, HACS, Backups …) nur per ausdrücklicher Freigabe.
- **Hausstatus**: ein Jinja-Template (in der Weboberfläche bearbeitbar, mit Vorschau), das Home Assistant jede Minute rendert. Das Ergebnis – z.B. Lichter je Raum, Energie/PV, Auto, Wetter, Kalender – steht im System-Prompt; Gemini antwortet so ohne Abfrage und kennt die Entitäts-IDs zum Schalten. Die minütliche Abfrage geht nur an Home Assistant, nicht an Gemini.

### Musik & Radio
- **Spotify**: OAuth in der Weboberfläche (PKCE, Redirect `http://127.0.0.1:8080/spotify/callback` – die Seite lädt danach nicht, Adresse zurückkopieren). Suche über die Web API (Künstler, Titel, Alben, eigene Playlists, Lieblingssongs), Wiedergabe auf der Spotify-App des Geräts.
- **YouTube Music**: Suche über die (inoffizielle) Such-Schnittstelle von music.youtube.com, Wiedergabe per Deep Link in der App.
- **Radio** ohne zusätzliche App (Media3/ExoPlayer, MP3/AAC/HLS, Titelanzeige aus dem Stream). Eigene Senderliste in der Weboberfläche; unbekannte Sender sucht HomeVoice im Verzeichnis [radio-browser.info](https://www.radio-browser.info) und speichert sie.
- Lautstärke in Prozent, 1–10 oder „lauter/leiser“; Pause, Weiter, „Was läuft gerade?“.
- **Now-Playing-Widget** mit Cover und Tasten; beim Hotword wird Musik leiser gestellt.

### Stimmerkennung
- Pro Person 3–5 Sätze am Gerät einlernen (Weboberfläche → „Personen“); gespeichert wird nur ein Stimmabdruck ([CAM++ / 3D-Speaker](https://github.com/modelscope/3D-Speaker)), alles auf dem Gerät.
- Erkennung schon am Hotword (vorläufig im System-Prompt), danach je Äußerung; Tool `who_is_speaking` für „Wer bin ich?“.
- Jede Person kann einen **persönlichen Kalender** bekommen: „Was steht **für mich** an?“ fragt diesen, „Was steht heute an?“ alle Kalender.
- Unsichere Fälle (ähnliche Stimmen) werden als „unbekannt“ gemeldet statt geraten. Kein Sicherheitsmerkmal.

### Gedächtnis
- „Merk dir …“ / „Vergiss …“; Einträge pro Person oder für den Haushalt. Alles Gemerkte steht im System-Prompt (neueste zuerst, ältere per Suche). Bearbeiten in der Weboberfläche.

### Geplante Jobs
- „Erinnere mich morgen um 10 …“, „Schick mir werktags um 7 das Wetter aufs iPhone“, „Sag Bescheid, wenn die Spülmaschine fertig ist“.
- Ein Job ist ein Prompt plus Auslöser (Uhrzeit einmalig/täglich/werktags/wöchentlich oder Ereignis als Jinja-Bedingung, minütlich lokal geprüft). Ausgeführt von HomeVoice mit Gemini Flash und allen Werkzeugen – Durchsage am Gerät oder Push-Nachricht über einen HA-`notify`-Dienst. Home Assistant liefert nur Werte, die Jobs verwaltet HomeVoice.

### Google-Suche
- Eingebautes *Grounding with Google Search* im Gespräch und in Jobs (Google sucht serverseitig). Abschaltbar.

### Diashow
- Fotos über die Weboberfläche hochladen (auch HEIC); sie werden auf max. 2560 px verkleinert.
- **Gesichtserkennung beim Upload** auf dem Gerät ([UltraFace](https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB)): Die Ken-Burns-Fahrt hält alle bzw. die größten Gesichter im Bild, statt minutenlang Füße zu zeigen.
- Galerie mit Vorschaubildern und Einzel-Löschen. Nachtmodus (gedimmte Uhr).

### Timer
- Stellen, abbrechen, auflisten, Alarm stoppen (Tippen oder Hotword); überstehen Neustarts.

## Weboberfläche (Port 8080, PIN-geschützt)

Gemini (Key, Modelle, Stimme, Prompt, Google-Suche, Mikrofon) · Jobs · Gedächtnis · Personen · Radio · Musik/Spotify ·
Hotword (Modell, Schwelle mit Live-Score) · Home Assistant (URL, Token, Tool-Freigabe, Hausstatus-Template) · Diashow & Anzeige.

## Audio-Hinweise

- Das Echo Show 5 meldet **keine Echo-Unterdrückung (AEC)**. Darum ist dort **Halbduplex** Standard: Während Gemini spricht, ist das Mikrofon für Gemini stumm; unterbrechen geht per Hotword.
- Hotword-Schwelle in der Weboberfläche einstellen (Live-Score: Wake Word sagen, Schwelle knapp unter die Spitze legen).
- Im Leerlauf überspringt die Hotword-Erkennung bei Stille das teure Modell; die Diashow zeichnet mit 25 fps. Leerlauflast auf dem MT8163: grob 20–40 % eines Kerns je nach Geräuschkulisse.

## Daten & Datenschutz

| Bleibt auf dem Gerät | Geht an Google (Gemini) |
|---|---|
| Hotword- und Spracherkennung, Stimmabdrücke, Gesichtserkennung, Fotos | Das Gespräch (Audio) ab dem Hotword |
| Gedächtnis, Jobs, Senderliste, Einstellungen, Spotify-Tokens | System-Prompt: Hausstatus, Gedächtnis, Haushalt, Sendernamen |
| Minütliche Hausstatus-/Ereignis-Abfragen (nur an Home Assistant) | Ausgeführte Jobs (Prompt + Tool-Ergebnisse) |

Keys liegen unverschlüsselt in den privaten App-Daten (für ein privates Gerät vertretbar).

## Architektur

```
MainActivity (Launcher, Compose)   AssistantService (Foreground)   SettingsWebServer (:8080)
        │ StateFlows                        │                            │
        ▼                                   ▼                            ▼
ConversationController ── AudioInput (16 kHz) ──┬─> WakeWordDetector (openWakeWord)   [Leerlauf]
   │                                            ├─> SileroVad + SpeakerRecognizer     [Gespräch]
   │                                            └─> GeminiLiveSession (WebSocket)     [Gespräch]
   ├── AudioOutput (24 kHz)       ├── MediaSessions / MusicPlayer / RadioPlayer
   └── ToolRegistry ── Timer, Musik, Radio, Gedächtnis, Jobs, Sprecher
                    └─ ha_* → HomeAssistantMcpClient (Streamable HTTP)
TaskRunner ── GeminiTextClient (generateContent + Tool-Schleife) ── Systemwecker / Ereignis-Prüfung
```

| Pfad | Inhalt |
|---|---|
| `service/ConversationController.kt` | Sitzungen, Sitzungsende, Durchsagen, Stimmerkennung, System-Prompt |
| `gemini/` | Live-Protokoll (WebSocket) und Text-Client für Jobs |
| `mcp/` | MCP-Client (JSON/SSE, abbrechbar) und JSON-Schema → Gemini-Schema |
| `media/`, `radio/` | Spotify, YouTube Music, Media-Sessions, Radio-Player und -Verzeichnis |
| `speaker/`, `memory/`, `tasks/` | Personen/Stimmabdrücke, Gedächtnis, geplante Jobs |
| `tools/` | Alle Gemini-Werkzeuge |
| `slideshow/`, `ui/` | Fotos (Verkleinerung, Gesichter), Home-Screen, Widget |
| `web/`, `assets/web/` | Weboberfläche (Server und Seite) |
| `wakeword/` (Modul) | Reines Kotlin + ONNX: openWakeWord, Silero VAD, Fbank + Sprecher-Embedding, Gesichtserkennung, Kamerafahrt-Planung |

Tests: `./gradlew test` (Unit-Tests; das Modul `wakeword` testet Hotword, VAD und Sprechererkennung mit `say`-Stimmen auf macOS
und die Gesichtserkennung mit gemeinfreien Wikimedia-Fotos). Logs: `adb logcat -s VoiceAssistant`.

## Bekannte Grenzen

- Die Kamera des Echo Show wird von diesem LineageOS-Build nicht erkannt (kein Kamera-Feature).
- Gemini-Sitzungen sind auf 10 Minuten begrenzt.
- YouTube-Music-Suche und Radio-Browser sind inoffizielle bzw. Community-Dienste und können ausfallen.
- Spotify nur über die Spotify-App (kein offizielles Wiedergabe-SDK ohne App).

## Lizenz

Der Quellcode steht unter der [MIT-Lizenz](LICENSE). Die mitgelieferten Modelle in `app/src/main/assets/wakeword/` behalten ihre eigenen Lizenzen:

openWakeWord-Modelle: CC BY-NC-SA 4.0 (nicht kommerziell) · Silero VAD: MIT · UltraFace: MIT · 3D-Speaker CAM++: Apache 2.0.
Wegen der openWakeWord-Modelle dürfen die mitgelieferten Hotword-Dateien – und Builds, die sie enthalten – nicht kommerziell genutzt werden.
