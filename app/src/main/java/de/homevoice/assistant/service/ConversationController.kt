package de.homevoice.assistant.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import de.homevoice.assistant.audio.AudioInput
import de.homevoice.assistant.audio.AudioOutput
import de.homevoice.assistant.audio.Earcons
import de.homevoice.assistant.audio.toLittleEndianBytes
import de.homevoice.assistant.gemini.GeminiLiveSession
import de.homevoice.assistant.gemini.LiveEvent
import de.homevoice.assistant.gemini.LiveProtocol
import de.homevoice.assistant.gemini.ServerEvent
import de.homevoice.assistant.gemini.SessionConfig
import de.homevoice.assistant.mcp.HomeAssistantMcpClient
import de.homevoice.assistant.media.MediaSessions
import de.homevoice.assistant.memory.MemoryStore
import de.homevoice.assistant.speaker.Identification
import de.homevoice.assistant.speaker.SpeakerProfiles
import de.homevoice.assistant.speaker.SpeakerRecognizer
import de.homevoice.assistant.settings.Settings
import de.homevoice.assistant.settings.SettingsRepository
import de.homevoice.assistant.timer.TimerManager
import de.homevoice.assistant.tools.HA_GROUP
import de.homevoice.assistant.tools.ToolRegistry
import de.homevoice.assistant.tools.loadHomeAssistantTools
import de.homevoice.assistant.tools.renderHomeAssistantTemplate
import de.homevoice.assistant.wakeword.WakeWordDetector
import de.homevoice.wakeword.SileroVad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AssistantState { IDLE, CONNECTING, LISTENING, SPEAKING, ERROR }

data class AssistantStatus(
    val state: AssistantState = AssistantState.IDLE,
    val running: Boolean = false,
    val micMuted: Boolean = false,
    val hotword: String? = null,
    val hotwordError: String? = null,
    val homeAssistant: String = "nicht konfiguriert",
    val homeContext: String = "aus",
    /** Voice enrollment in progress: person's name and the sentence to read (shown on the display). */
    val enrollment: Pair<String, String>? = null,
    /** Last speaker recognition result, for tuning in the web interface. */
    val lastSpeaker: String = "",
    val error: String? = null,
    val userText: String = "",
    val modelText: String = "",
)

/**
 * Owns microphone, wake word and the Gemini Live session and drives the conversation:
 * IDLE (only local wake word) → CONNECTING → LISTENING/SPEAKING → back to IDLE after silence.
 */
class ConversationController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val timers: TimerManager,
    private val tools: ToolRegistry,
    private val media: MediaSessions,
    private val profiles: SpeakerProfiles,
    private val speakers: SpeakerRecognizer,
    private val memory: MemoryStore,
    private val wsHttp: OkHttpClient,
    private val restHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(AssistantStatus())
    val status: StateFlow<AssistantStatus> = _status.asStateFlow()

    private val wake = WakeWordDetector(context)
    private val audioInput = AudioInput(WakeWordDetector.FRAME_LENGTH, ::onFrame)
    private val output = AudioOutput(LiveProtocol.OUTPUT_SAMPLE_RATE)
    private val earcons = Earcons()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var session: GeminiLiveSession? = null
    @Volatile private var sessionReady = false
    /** Last real activity: words from the user or model speech. Noise and status messages do not count. */
    @Volatile private var lastActivity = 0L
    /** Last frame in which the local voice activity detector heard the user speak. */
    @Volatile private var lastSpeechAt = 0L
    @Volatile private var vadResetPending = false
    private var vad: SileroVad? = null
    /** Set when the user said something and the model has not started answering yet (0 = not waiting). */
    @Volatile private var awaitingResponseSince = 0L
    /** When end_conversation (or goAway) asked to finish the session (0 = not requested). */
    @Volatile private var endRequestedAt = 0L

    /** Set by a wake word barge-in: drop the rest of the model's current answer. */
    @Volatile private var muteModelAudio = false
    private var sessionStartedAt = 0L
    private var sessionJob: Job? = null
    private var newUserTurn = true
    private val toolJobs = mutableMapOf<String, Job>()
    private val toolStartedAt = mutableMapOf<String, Long>()

    /** A tool keeps the session open while it runs, but never longer than [TOOL_MAX_MS]. */
    private fun toolRunning(now: Long): Boolean = toolJobs.any { (id, job) ->
        job.isActive && now - (toolStartedAt[id] ?: now) < TOOL_MAX_MS
    }
    private val runningJobs = mutableListOf<Job>()

    // Audio captured between wake word and setupComplete, plus the chunk being assembled for sending.
    private val bufferLock = Any()
    private val preBuffer = ByteArrayOutputStream()
    private val chunk = ByteArrayOutputStream()

    private val settings: Settings get() = settingsRepository.current

    fun start() {
        if (_status.value.running) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _status.update { it.copy(error = "Mikrofon-Berechtigung fehlt") }
            return
        }
        _status.update { it.copy(running = true, error = null) }
        runningJobs += scope.launch {
            initVad()
            initWakeWord()
            // Follow the mute switch; a muted microphone is not recording at all.
            settingsRepository.settings.map { it.micMuted }.distinctUntilChanged().collect { muted ->
                applyMicMuted(muted)
            }
        }
        runningJobs += scope.launch {
            // Re-init the wake word when its settings change.
            settingsRepository.settings
                .map { listOf(it.wakeModel, it.customWakeModelPath, it.wakeThreshold) }
                .distinctUntilChanged().drop(1)
                .collect { initWakeWord() }
        }
        runningJobs += scope.launch {
            settingsRepository.settings.map { listOf(it.haUrl, it.haToken, it.haAllTools) }.distinctUntilChanged().collect {
                refreshHomeAssistantTools()
                refreshHomeContext()
            }
        }
        runningJobs += scope.launch {
            settingsRepository.settings.map { it.haContextTemplate }.distinctUntilChanged().drop(1).collect {
                refreshHomeContext()
            }
        }
        runningJobs += scope.launch {
            while (isActive) {
                delay(HA_REFRESH_INTERVAL_MS)
                if (session == null) refreshHomeAssistantTools()
            }
        }
        runningJobs += scope.launch {
            while (isActive) {
                delay(HOME_CONTEXT_INTERVAL_MS)
                if (session == null) refreshHomeContext()
            }
        }
        runningJobs += scope.launch { watchdog() }
    }

    fun stop() {
        endSession(null, silent = true)
        runningJobs.forEach { it.cancel() }
        runningJobs.clear()
        audioInput.stop() // before releasing the models the audio thread uses
        wake.release()
        vad?.close()
        vad = null
        _status.update { it.copy(running = false) }
    }

    /** Tap on the screen: stop alarm, end conversation or start one (push-to-talk). */
    fun toggle() {
        when {
            timers.stopRinging() -> earcons.play(Earcons.Tone.END)
            session != null -> endSession(null)
            settings.micMuted -> earcons.play(Earcons.Tone.ERROR)
            else -> startSession()
        }
    }

    /** The microphone button on the display. */
    fun setMicMuted(muted: Boolean) {
        settingsRepository.update { it.copy(micMuted = muted) }
        earcons.play(if (muted) Earcons.Tone.END else Earcons.Tone.WAKE)
    }

    private fun applyMicMuted(muted: Boolean) {
        if (muted) {
            endSession(null, silent = true)
            audioInput.stop()
            Log.i(TAG, "Microphone muted")
        } else {
            try {
                wake.reset()
                audioInput.start()
                Log.i(TAG, "Microphone on")
            } catch (e: Exception) {
                Log.e(TAG, "Microphone start failed", e)
                _status.update { it.copy(error = "Mikrofon: ${e.message}") }
            }
        }
        _status.update { it.copy(micMuted = muted) }
    }

    /** Called by the end_conversation tool: close once the current answer has been spoken. */
    fun requestEnd() {
        if (endRequestedAt == 0L) {
            endRequestedAt = System.currentTimeMillis()
            // Anything the model says after deciding to end is dropped (it tends to narrate its
            // reasoning, e.g. "the user is wrapping up"); a goodbye spoken before still plays out.
            muteModelAudio = true
            Log.i(TAG, "End of conversation requested")
        }
    }

    private suspend fun initVad() {
        if (vad != null) return
        vad = try {
            withContext(Dispatchers.IO) {
                SileroVad(context.assets.open("wakeword/silero_vad.onnx").use { it.readBytes() })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice activity detector could not be loaded", e)
            null
        }
    }

    private suspend fun initWakeWord() {
        val error = withContext(Dispatchers.IO) { wake.init(settings) }
        _status.update {
            it.copy(hotword = if (error == null) wake.keywordName else null, hotwordError = error)
        }
        if (error != null) Log.w(TAG, error)
    }

    // ---- Audio thread ---------------------------------------------------------------------------

    private fun onFrame(frame: ShortArray) {
        val s = session
        if (s == null) {
            enrollFrames?.let { collectEnrollment(frame, it); return }
            preRoll.addLast(frame)
            if (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()
            if (wake.process(frame)) {
                identifyWakeWord()
                scope.launch { onWakeWord() }
            }
            return
        }
        if (settings.halfDuplex && output.isPlaying) {
            // Mic audio is not sent while the model speaks, but the local wake word still allows barge-in.
            if (wake.process(frame)) scope.launch { bargeIn() }
            return
        }
        detectSpeech(frame)
        val bytes = frame.toLittleEndianBytes()
        synchronized(bufferLock) {
            if (!sessionReady) {
                if (preBuffer.size() < MAX_PREBUFFER_BYTES) preBuffer.write(bytes)
                return
            }
            chunk.write(bytes)
            if (chunk.size() >= SEND_CHUNK_BYTES) {
                s.sendAudio(chunk.toByteArray())
                chunk.reset()
            }
        }
    }

    /** Runs the local VAD so that ongoing speech keeps the session open before any transcript arrives. */
    private fun detectSpeech(frame: ShortArray) {
        val v = vad ?: return
        if (vadResetPending) {
            v.reset()
            vadResetPending = false
        }
        val probability = try {
            v.process(frame)
        } catch (e: Exception) {
            Log.w(TAG, "VAD failed", e)
            return
        }
        if (probability > VAD_THRESHOLD) lastSpeechAt = System.currentTimeMillis()
        trackUtterance(frame, probability > VAD_THRESHOLD)
    }

    // ---- Speaker recognition (on-device) ------------------------------------------------------

    /** Last ~2 s of audio while idle, so the wake word itself can be used to recognize the speaker. */
    private val preRoll = ArrayDeque<ShortArray>()
    /** Speaker recognized from the wake word; awaited (briefly) before the session setup is sent. */
    @Volatile private var wakeIdentification: CompletableDeferred<Identification?>? = null

    /** Audio thread, at wake word detection: identify the speaker from "Alexa" while connecting. */
    private fun identifyWakeWord() {
        val v = vad
        if (!speakerRecognitionReady() || v == null) {
            wakeIdentification = null
            synchronized(utteranceLock) { utterance.clear() }
            return
        }
        v.reset()
        val speech = preRoll.filter { f -> runCatching { v.process(f) }.getOrDefault(0f) > ENROLL_VAD_THRESHOLD }
        preRoll.clear()
        vadResetPending = true
        // The wake word starts the first utterance, so even a short question has enough material.
        synchronized(utteranceLock) {
            utterance.clear()
            utterance.addAll(speech)
            utteranceIdentified = false
        }
        val deferred = CompletableDeferred<Identification?>()
        wakeIdentification = deferred
        if (speech.size < MIN_IDENTIFY_FRAMES / 2) {
            deferred.complete(null)
            return
        }
        val pcm = concat(speech)
        scope.launch(Dispatchers.Default) {
            deferred.complete(runCatching { speakers.identify(pcm, settings.speakerThreshold) }.getOrNull())
        }
    }

    private fun speakerRecognitionReady() =
        settings.speakerRecognition && profiles.profiles.value.any { it.embeddings.isNotEmpty() }

    private fun concat(frames: List<ShortArray>): ShortArray {
        val pcm = ShortArray(frames.size * WakeWordDetector.FRAME_LENGTH)
        frames.forEachIndexed { i, f -> f.copyInto(pcm, i * WakeWordDetector.FRAME_LENGTH) }
        return pcm
    }

    private val utteranceLock = Any()
    /** Speech of the previous utterance, for the who_is_speaking tool. */
    private var lastUtterance: List<ShortArray> = emptyList()

    /** Name of the speaker the model was last told about in this session, if any. */
    fun currentSpeakerName(): String? = currentSpeakerId?.let { profiles.get(it)?.name }

    /** Identifies whoever spoke last (whole last utterance). Used by the who_is_speaking tool. */
    suspend fun identifyCurrentSpeaker(): Identification {
        val frames = synchronized(utteranceLock) { if (utterance.size >= MIN_IDENTIFY_FRAMES) utterance.toList() else lastUtterance }
        check(speakerRecognitionReady()) { "Die Stimmerkennung ist nicht eingerichtet" }
        check(frames.size >= MIN_IDENTIFY_FRAMES / 2) { "Zu wenig Sprache für eine Stimmerkennung" }
        val result = withContext(Dispatchers.Default) { speakers.identify(concat(frames), settings.speakerThreshold) }
        session?.let { onIdentified(it, result, notify = false) }
        return result
    }

    /** Speech frames of the current utterance (audio thread; guarded by [utteranceLock]). */
    private val utterance = ArrayList<ShortArray>()
    private var silentFrames = 0
    private var utteranceIdentified = false
    /** Person the model was last told about in this session. */
    @Volatile private var currentSpeakerId: String? = null

    private fun trackUtterance(frame: ShortArray, speech: Boolean) {
        if (!speakerRecognitionReady()) return
        var toIdentify: List<ShortArray>? = null
        synchronized(utteranceLock) {
            if (speech) {
                utterance += frame
                silentFrames = 0
            } else if (++silentFrames > UTTERANCE_GAP_FRAMES && utterance.isNotEmpty()) {
                // A pause ends the utterance. Short ones are identified now; the next utterance is
                // identified again (another person may speak).
                if (!utteranceIdentified && utterance.size >= MIN_IDENTIFY_FRAMES) toIdentify = utterance.toList()
                lastUtterance = utterance.toList()
                utterance.clear()
                utteranceIdentified = false
            }
            if (!utteranceIdentified && utterance.size >= IDENTIFY_FRAMES) {
                utteranceIdentified = true
                toIdentify = utterance.toList()
            }
        }
        val frames = toIdentify ?: return
        run {
            val pcm = concat(frames)
            val live = session ?: return
            scope.launch(Dispatchers.Default) {
                val started = System.currentTimeMillis()
                val result = try {
                    speakers.identify(pcm, settings.speakerThreshold).also {
                        Log.d(TAG, "Speaker recognition took ${System.currentTimeMillis() - started} ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speaker recognition failed", e)
                    return@launch
                }
                withContext(Dispatchers.Main) { onIdentified(live, result) }
            }
        }
    }

    private fun onIdentified(live: GeminiLiveSession, result: Identification, notify: Boolean = true) {
        if (result.scores.isEmpty()) return
        val summary = (result.profile?.name ?: "unbekannt") + " · " +
            result.scores.joinToString { (n, s) -> "$n %.2f".format(s) }
        Log.i(TAG, "Speaker: $summary")
        _status.update { it.copy(lastSpeaker = summary) }
        if (session !== live || !notify) {
            result.profile?.let { currentSpeakerId = it.id }
            return
        }
        val profile = result.profile
        when {
            profile != null && profile.id != currentSpeakerId -> {
                currentSpeakerId = profile.id
                live.sendContext(
                    "[Systemhinweis der Stimmerkennung, nicht vorlesen: Gerade spricht ${profile.name}." +
                        (if (profile.calendar.isNotBlank()) " Persönlicher Kalender: ${profile.calendar}." else "") + "]"
                )
            }
            profile == null && currentSpeakerId != null -> {
                currentSpeakerId = null
                live.sendContext("[Systemhinweis der Stimmerkennung, nicht vorlesen: Der aktuelle Sprecher ist nicht sicher erkannt.]")
            }
        }
    }

    /** Frames recorded for a voice enrollment (audio thread adds, main thread takes). */
    @Volatile private var enrollFrames: MutableList<ShortArray>? = null

    private fun collectEnrollment(frame: ShortArray, frames: MutableList<ShortArray>) {
        val v = vad ?: return
        if (vadResetPending) { v.reset(); vadResetPending = false }
        val probability = try { v.process(frame) } catch (_: Exception) { 0f }
        if (probability > ENROLL_VAD_THRESHOLD) synchronized(frames) { frames += frame }
    }

    /**
     * Records [seconds] of the person reading [sentence] near the device and stores the voice
     * print. Returns how many seconds of speech were used.
     */
    suspend fun recordEnrollment(profileId: String, sentence: String, seconds: Int = 8): Float {
        val profile = profiles.get(profileId) ?: throw IllegalArgumentException("Person nicht gefunden")
        check(session == null) { "Gerade läuft ein Gespräch" }
        check(!settings.micMuted) { "Das Mikrofon ist ausgeschaltet" }
        check(enrollFrames == null) { "Es läuft schon eine Aufnahme" }
        val frames = mutableListOf<ShortArray>()
        _status.update { it.copy(enrollment = profile.name to sentence) }
        earcons.play(Earcons.Tone.WAKE)
        delay(400) // let the tone fade before recording
        vadResetPending = true
        enrollFrames = frames
        try {
            delay(seconds * 1000L)
        } finally {
            enrollFrames = null
            _status.update { it.copy(enrollment = null) }
            earcons.play(Earcons.Tone.END)
        }
        val speech = synchronized(frames) { frames.toList() }
        val speechSeconds = speech.size * WakeWordDetector.FRAME_LENGTH / AudioInput.SAMPLE_RATE.toFloat()
        if (speechSeconds < MIN_ENROLL_SECONDS) {
            throw IllegalStateException("Zu wenig Sprache erkannt (%.1f s) – bitte näher ans Gerät und lauter lesen".format(speechSeconds))
        }
        val pcm = ShortArray(speech.size * WakeWordDetector.FRAME_LENGTH)
        speech.forEachIndexed { i, f -> f.copyInto(pcm, i * WakeWordDetector.FRAME_LENGTH) }
        val embedding = withContext(Dispatchers.Default) { speakers.embed(pcm) }
        profiles.addSample(profileId, embedding)
        Log.i(TAG, "Voice sample for ${profile.name}: %.1f s speech".format(speechSeconds))
        return speechSeconds
    }

    // ---- Session lifecycle (main thread) --------------------------------------------------------

    private fun onWakeWord() {
        Log.i(TAG, "Wake word detected")
        if (timers.stopRinging()) {
            earcons.play(Earcons.Tone.END)
            return
        }
        startSession()
    }

    /** Wake word spoken while the model talks (half-duplex): silence it and listen again. */
    private fun bargeIn() {
        if (session == null || !output.isPlaying) return
        Log.i(TAG, "Barge-in via wake word")
        muteModelAudio = true
        output.flush()
        earcons.play(Earcons.Tone.WAKE)
        touch()
        setState(AssistantState.LISTENING)
    }

    private fun startSession() {
        if (session != null) return
        val s = settings
        if (s.geminiApiKey.isBlank()) {
            earcons.play(Earcons.Tone.ERROR)
            showError("Kein Gemini API-Key hinterlegt")
            return
        }
        earcons.play(Earcons.Tone.WAKE)
        media.duck()
        if (s.communicationAudioMode) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        output.start(communicationUsage = s.communicationAudioMode)

        val config = SessionConfig(
            model = s.model.trim(),
            voice = s.voice.trim(),
            systemInstruction = systemInstruction(s),
            functionDeclarations = tools.declarations(),
        )
        val pendingSpeaker = wakeIdentification
        wakeIdentification = null
        val live = GeminiLiveSession(wsHttp, s.geminiApiKey.trim(), config) { cfg ->
            // Runs on the socket thread once connected; recognition usually finished by then.
            val hint = pendingSpeaker?.let { d ->
                runBlocking { withTimeoutOrNull(WAKE_ID_WAIT_MS) { d.await() } }
            }
            hint?.let { id ->
                Log.i(TAG, "Speaker from wake word: ${id.profile?.name ?: "unbekannt"} " + id.scores.joinToString { (n, v) -> "$n %.2f".format(v) })
                id.profile?.let { currentSpeakerId = it.id }
            }
            if (hint?.profile != null) cfg.copy(systemInstruction = systemInstruction(s, hint)) else cfg
        }
        synchronized(bufferLock) {
            preBuffer.reset()
            chunk.reset()
            sessionReady = false
        }
        endRequestedAt = 0L
        awaitingResponseSince = 0L
        currentSpeakerId = null
        lastSpeechAt = 0L
        vadResetPending = true
        muteModelAudio = false
        newUserTurn = true
        sessionStartedAt = System.currentTimeMillis()
        touch()
        session = live
        _status.update { it.copy(state = AssistantState.CONNECTING, error = null, userText = "", modelText = "") }
        Log.i(TAG, "Connecting to Gemini Live (${config.model}, ${config.functionDeclarations.size} tools)")
        live.connect()
        sessionJob = scope.launch {
            var failure: String? = null
            live.events.collect { event ->
                when (event) {
                    is LiveEvent.Server -> handle(live, event.event)?.let { failure = it }
                    is LiveEvent.Failure -> failure = "Verbindung fehlgeschlagen: ${event.message}"
                    is LiveEvent.Closed -> if (event.code != 1000 && session === live) {
                        failure = "Gemini hat die Verbindung beendet (${event.code}): ${event.reason}"
                    }
                }
            }
            if (session === live) endSession(failure)
        }
    }

    /** Handles one server event; returns an error message if the session should fail. */
    private fun handle(live: GeminiLiveSession, event: ServerEvent): String? {
        if (session !== live) return null
        when (event) {
            ServerEvent.SetupComplete -> {
                synchronized(bufferLock) {
                    val buffered = preBuffer.toByteArray()
                    var offset = 0
                    while (offset < buffered.size) {
                        val end = minOf(buffered.size, offset + SEND_CHUNK_BYTES * 4)
                        live.sendAudio(buffered.copyOfRange(offset, end))
                        offset = end
                    }
                    preBuffer.reset()
                    sessionReady = true
                }
                touch()
                setState(AssistantState.LISTENING)
                Log.i(TAG, "Session ready")
            }
            is ServerEvent.Audio -> {
                if (muteModelAudio) return null
                output.enqueue(event.pcm)
                touch()
                awaitingResponseSince = 0L
                setState(AssistantState.SPEAKING)
            }
            is ServerEvent.InputTranscript -> {
                Log.d(TAG, "User: ${event.text}")
                // Only actual words count as the user talking; noise must not keep the session open.
                if (event.text.any { it.isLetterOrDigit() }) {
                    touch()
                    awaitingResponseSince = System.currentTimeMillis()
                }
                _status.update {
                    if (newUserTurn) it.copy(userText = event.text.trimStart(), modelText = "")
                    else it.copy(userText = it.userText + event.text)
                }
                newUserTurn = false
            }
            is ServerEvent.OutputTranscript ->
                if (!muteModelAudio) _status.update { it.copy(modelText = it.modelText + event.text) }
            ServerEvent.Interrupted -> {
                if (endRequestedAt == 0L) muteModelAudio = false
                output.flush()
                touch()
                newUserTurn = true
                setState(AssistantState.LISTENING)
            }
            ServerEvent.TurnComplete -> {
                if (endRequestedAt == 0L) muteModelAudio = false
                touch()
                awaitingResponseSince = 0L
                newUserTurn = true
            }
            ServerEvent.GenerationComplete -> Unit
            is ServerEvent.ToolCall -> event.calls.forEach { call ->
                touch()
                awaitingResponseSince = 0L
                Log.i(TAG, "Tool call ${call.name} ${call.args}")
                // LAZY: the main dispatcher runs launch immediately, and a tool that finishes
                // synchronously would otherwise remove itself before it is registered.
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    val result = tools.execute(call.name, call.args)
                    Log.i(TAG, "Tool result ${call.name}: $result")
                    if (session === live) live.sendToolResponses(listOf(Triple(call.id, call.name, result)))
                    toolJobs.remove(call.id)
                    touch()
                    // The model continues its turn after the tool result.
                    if (endRequestedAt == 0L) awaitingResponseSince = System.currentTimeMillis()
                }
                toolJobs[call.id] = job
                toolStartedAt[call.id] = System.currentTimeMillis()
                job.start()
            }
            is ServerEvent.ToolCallCancellation -> event.ids.forEach { toolJobs.remove(it)?.cancel() }
            is ServerEvent.GoAway -> requestEnd()
            ServerEvent.VoiceActivity -> Unit
            is ServerEvent.Error -> return event.message
        }
        return null
    }

    /** Recent peak wake word score (decays slowly), shown in the web UI to tune the threshold. */
    @Volatile var wakePeak = 0f
        private set

    private suspend fun watchdog() {
        while (true) {
            delay(250)
            wakePeak = maxOf(wake.takePeakScore(), wakePeak - 0.01f)
            if (session == null) continue
            val now = System.currentTimeMillis()
            val playing = output.isPlaying
            if (playing) touch() else if (_status.value.state == AssistantState.SPEAKING) setState(AssistantState.LISTENING)
            val idleMs = now - maxOf(lastActivity, lastSpeechAt)
            val endAt = endRequestedAt
            val waitingForAnswer = awaitingResponseSince != 0L && now - awaitingResponseSince < RESPONSE_WAIT_MS
            when {
                !sessionReady && now - sessionStartedAt > CONNECT_TIMEOUT_MS ->
                    endSession("Zeitüberschreitung beim Verbinden mit Gemini")
                now - sessionStartedAt > MAX_SESSION_MS -> endSession(null)
                endAt != 0L && now - endAt > END_HARD_LIMIT_MS -> endSession(null)
                // end_conversation: finish as soon as the already queued goodbye has been played.
                endAt != 0L && !playing && now - endAt > END_GRACE_MS -> endSession(null)
                playing || toolRunning(now) -> Unit
                endAt != 0L || waitingForAnswer -> Unit
                idleMs > settings.followUpSeconds * 1000L -> {
                    Log.i(TAG, "No speech for ${settings.followUpSeconds} s")
                    endSession(null)
                }
            }
        }
    }

    /** Ends the conversation (if any) and returns to wake word listening. */
    fun endSession(error: String?, silent: Boolean = false) {
        val live = session ?: return
        session = null
        synchronized(bufferLock) {
            sessionReady = false
            preBuffer.reset()
            chunk.reset()
        }
        live.close()
        sessionJob?.cancel()
        sessionJob = null
        toolJobs.values.forEach { it.cancel() }
        toolJobs.clear()
        toolStartedAt.clear()
        output.stop()
        media.unduck()
        wake.reset()
        if (settings.communicationAudioMode) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        Log.i(TAG, "Session ended${error?.let { ": $it" } ?: ""}")
        if (!silent) earcons.play(if (error == null) Earcons.Tone.END else Earcons.Tone.ERROR)
        if (error != null) showError(error) else setState(AssistantState.IDLE)
    }

    private fun showError(message: String) {
        Log.w(TAG, message)
        _status.update { it.copy(state = AssistantState.ERROR, error = message) }
        scope.launch {
            delay(ERROR_DISPLAY_MS)
            if (session == null && _status.value.state == AssistantState.ERROR) setState(AssistantState.IDLE)
        }
    }

    private fun setState(state: AssistantState) {
        if (_status.value.state != state) _status.update { it.copy(state = state) }
    }

    private fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    private fun systemInstruction(s: Settings, wakeSpeaker: Identification? = null): String = buildString {
        append(s.systemPrompt.trim())
        append("\n\nAktuelles Datum und Uhrzeit: ")
        val now = ZonedDateTime.now()
        append(now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMAN)))
        append(" (Zeitzone ${now.zone.id}).")
        val running = timers.timers.value
        if (running.isNotEmpty()) {
            append("\nLaufende Timer: ")
            append(running.joinToString { t ->
                "${t.label ?: "Timer ${t.id}"} (noch ${timers.remainingSeconds(t)} s)"
            })
        }
        if (tools.groupSize(HA_GROUP) == 0) {
            append("\nDie Smart-Home-Steuerung ist gerade nicht verfügbar.")
        }
        val household = profiles.profiles.value
        if (s.speakerRecognition && household.any { it.embeddings.isNotEmpty() }) {
            append("\n\nHaushalt (Stimmerkennung): ")
            append(household.joinToString { p -> p.name + (if (p.calendar.isNotBlank()) " – Kalender ${p.calendar}" else "") })
            append(".\nDie Stimmerkennung schickt Systemhinweise, wer gerade spricht. \"Ich\", \"mein\", \"für mich\" ")
            append("beziehen sich auf diese Person (z.B. \"meine Termine\" = nur ihr persönlicher Kalender). ")
            append("Allgemeine Fragen wie \"Was steht heute an?\" beziehen ALLE Kalender ein (Familienkalender und alle persönlichen). ")
            append("Bei \"Wer bin ich?\" oder persönlichen Fragen ohne sicheren Hinweis: who_is_speaking aufrufen; ")
            append("erst wenn das niemanden erkennt, kurz nachfragen. Die Hinweise nie vorlesen.")
            wakeSpeaker?.profile?.let { p ->
                append("\nSprecher laut Stimmerkennung beim Hotword (vorläufig): ${p.name}")
                if (p.calendar.isNotBlank()) append(" – persönlicher Kalender ${p.calendar}")
                append(".")
            }
        }
        append("\n\n# Gedächtnis\n")
        append("Merke dir mit remember, was der Nutzer dir sagt, dass du es dir merken sollst, und dauerhafte Vorlieben ")
        append("oder Fakten über den Haushalt (kurz bestätigen). Mit forget löschen, wenn etwas nicht mehr stimmt. ")
        append("Einträge mit Namen in Klammern gelten nur für diese Person.\n")
        append(memory.promptSection() ?: "(noch nichts gemerkt)")
        if (s.radioStations.isNotEmpty()) {
            append("\nGespeicherte Radiosender (für play_radio genau diesen Namen verwenden): ")
            append(s.radioStations.joinToString { it.name })
        }
        homeContext?.takeIf { System.currentTimeMillis() - it.second < HOME_CONTEXT_MAX_AGE_MS }?.let { (text, at) ->
            append("\n\n# Smart Home – Stand ")
            // (LocalTime.ofInstant needs API 31; the device runs Android 11)
            append(DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(ZonedDateTime.now().zone)))
            append(" (für aktuelle Werte ggf. ha_get_state)\n")
            append(text.trim())
        }
    }

    /** Last rendered home status (text, time) for the system prompt. */
    @Volatile private var homeContext: Pair<String, Long>? = null
    @Volatile private var haClient: HomeAssistantMcpClient? = null

    /** Renders the configured home status template in Home Assistant and caches it. */
    suspend fun refreshHomeContext() {
        val template = settings.haContextTemplate
        val client = haClient
        if (template.isBlank() || client == null) {
            homeContext = null
            _status.update { it.copy(homeContext = if (template.isBlank()) "aus" else "Home Assistant nicht verbunden") }
            return
        }
        val message = try {
            val text = renderHomeAssistantTemplate(client, template)
            homeContext = text to System.currentTimeMillis()
            "${text.length} Zeichen, Stand ${DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.LocalTime.now())}"
        } catch (e: Exception) {
            Log.w(TAG, "Home context could not be rendered", e)
            "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
        _status.update { it.copy(homeContext = message) }
    }

    /** Calendar entities in Home Assistant as (entity_id, name), for assigning them to people. */
    suspend fun listCalendars(): List<Pair<String, String>> {
        val client = haClient ?: throw IllegalStateException("Home Assistant ist nicht verbunden")
        val text = renderHomeAssistantTemplate(
            client,
            "{% for s in states.calendar %}{{ s.entity_id }}|{{ s.name }}\n{% endfor %}",
        )
        return text.lines().mapNotNull { line ->
            line.split('|', limit = 2).takeIf { it.size == 2 && it[0].startsWith("calendar.") }?.let { it[0].trim() to it[1].trim() }
        }
    }

    /** Renders [template] without saving it (preview in the web interface). */
    suspend fun previewHomeContext(template: String): String {
        val client = haClient ?: throw IllegalStateException("Home Assistant ist nicht verbunden")
        return renderHomeAssistantTemplate(client, template)
    }

    // ---- Home Assistant -------------------------------------------------------------------------

    /** Reloads the Home Assistant tools. Returns a short human-readable result. */
    suspend fun refreshHomeAssistantTools(): String {
        val s = settings
        if (s.haUrl.isBlank()) {
            haClient = null
            tools.setGroup(HA_GROUP, emptyList())
            return "nicht konfiguriert".also { msg -> _status.update { it.copy(homeAssistant = msg) } }
        }
        val client = HomeAssistantMcpClient(restHttp, s.haUrl, s.haToken.trim())
        val message = try {
            val loaded = loadHomeAssistantTools(client, allowAll = s.haAllTools)
            tools.setGroup(HA_GROUP, loaded)
            haClient = client
            Log.i(TAG, "Loaded ${loaded.size} Home Assistant tools: ${loaded.joinToString { it.name }}")
            "${loaded.size} Tools: ${loaded.joinToString { it.name.removePrefix("ha_") }}"
        } catch (e: Exception) {
            Log.w(TAG, "Home Assistant tools could not be loaded", e)
            "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
        _status.update { it.copy(homeAssistant = message) }
        return message
    }

    private companion object {
        const val TAG = "VoiceAssistant"
        const val SEND_CHUNK_BYTES = 3 * WakeWordDetector.FRAME_LENGTH * 2 // ~96 ms
        const val MAX_PREBUFFER_BYTES = AudioInput.SAMPLE_RATE * 2 * 8 // 8 s
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val MAX_SESSION_MS = 10 * 60_000L
        const val END_GRACE_MS = 600L
        const val VAD_THRESHOLD = 0.5f
        const val ENROLL_VAD_THRESHOLD = 0.35f
        const val MIN_ENROLL_SECONDS = 2.5f
        /** ~1 s of speech (512-sample frames) before identifying the speaker mid-utterance. */
        const val IDENTIFY_FRAMES = 31
        /** ~0.6 s: shortest utterance worth identifying at its end. */
        const val MIN_IDENTIFY_FRAMES = 19
        /** ~2 s of audio kept while idle (contains the wake word). */
        const val PREROLL_FRAMES = 62
        const val WAKE_ID_WAIT_MS = 800L
        /** ~0.8 s pause ends an utterance. */
        const val UTTERANCE_GAP_FRAMES = 25
        const val END_HARD_LIMIT_MS = 15_000L
        const val RESPONSE_WAIT_MS = 10_000L
        const val TOOL_MAX_MS = 30_000L
        const val ERROR_DISPLAY_MS = 5_000L
        const val HA_REFRESH_INTERVAL_MS = 30 * 60_000L
        const val HOME_CONTEXT_INTERVAL_MS = 60_000L
        const val HOME_CONTEXT_MAX_AGE_MS = 15 * 60_000L
    }
}
