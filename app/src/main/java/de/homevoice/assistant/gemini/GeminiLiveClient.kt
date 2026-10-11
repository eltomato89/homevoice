package de.homevoice.assistant.gemini

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed interface LiveEvent {
    data class Server(val event: ServerEvent) : LiveEvent
    data class Closed(val code: Int, val reason: String) : LiveEvent
    data class Failure(val message: String) : LiveEvent
}

/**
 * One Gemini Live session over a raw WebSocket. Create a new instance per conversation.
 * Events are delivered in order through [events].
 */
class GeminiLiveSession(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val config: SessionConfig,
    /** Last chance to adjust the setup right before it is sent (runs on the socket thread, may block briefly). */
    private val beforeSetup: (SessionConfig) -> SessionConfig = { it },
) {
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    val events: Flow<LiveEvent> = channel.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set

    fun connect() {
        val request = Request.Builder()
            .url("${LiveProtocol.ENDPOINT}?key=$apiKey")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                webSocket.send(LiveProtocol.setupMessage(beforeSetup(config)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = dispatch(text)

            // The Live API frequently sends JSON in binary frames.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = dispatch(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                channel.trySend(LiveEvent.Closed(code, reason))
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                val detail = response?.let { " (HTTP ${it.code})" } ?: ""
                Log.w(TAG, "WebSocket failure$detail", t)
                channel.trySend(LiveEvent.Failure((t.message ?: t.javaClass.simpleName) + detail))
                channel.close()
            }
        })
    }

    private fun dispatch(text: String) {
        for (event in LiveProtocol.parse(text)) channel.trySend(LiveEvent.Server(event))
    }

    fun sendAudio(pcm16le: ByteArray) {
        if (isOpen) socket?.send(LiveProtocol.audioMessage(pcm16le))
    }

    fun sendToolResponses(responses: List<Triple<String, String, kotlinx.serialization.json.JsonObject>>) {
        if (isOpen) socket?.send(LiveProtocol.toolResponseMessage(responses))
    }

    /** Context for the model, e.g. who is speaking; does not trigger an answer. */
    fun sendContext(text: String) {
        if (isOpen) socket?.send(LiveProtocol.contextMessage(text))
    }

    fun close() {
        val s = socket ?: return
        if (isOpen) s.send(LiveProtocol.audioStreamEndMessage())
        s.close(1000, "conversation finished")
        isOpen = false
    }

    private companion object {
        const val TAG = "VoiceAssistant"
    }
}
