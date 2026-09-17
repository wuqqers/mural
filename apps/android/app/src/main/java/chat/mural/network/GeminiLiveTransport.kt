package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class GeminiLiveTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var onEvent: ((JsonObject) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onLevels: ((Double, Double) -> Unit)? = null

    private val generation = AtomicLong(0)
    private val started = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionStartTime: Long = 0

    val isStarted: Boolean get() = started.get()
    val isMuted: Boolean get() = muted.get()

    suspend fun connect(
        apiKey: String,
        model: String,
        instructions: String,
        language: String? = null,
    ) {
        val attemptGeneration = generation.incrementAndGet()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw GeminiTransportException.microphone
        }

        withContext(Dispatchers.IO) {
            configureAudio()

            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

            val client = OkHttpClient.Builder()
                .build()

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            val setupMessage = buildJsonObject {
                put("setup", buildJsonObject {
                    put("model", "models/$model")
                    put("generationConfig", buildJsonObject {
                        put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                        put("speechConfig", buildJsonObject {
                            put("voiceConfig", buildJsonObject {
                                put("prebuiltVoiceConfig", buildJsonObject {
                                    put("voiceName", "Kore")
                                })
                            })
                        })
                    })
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", instructions) })
                        })
                    })
                    put("inputAudioTranscription", buildJsonObject {})
                })
            }

            val deferred = CompletableDeferred<Unit>()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    android.util.Log.d("GeminiLive", "Connected (HTTP ${response.code})")
                    webSocket.send(setupMessage.toString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    val text = bytes.utf8()
                    try {
                        val msg = json.parseToJsonElement(text).jsonObject
                        handleServerMessage(msg, attemptGeneration)
                        if (msg.containsKey("setupComplete")) {
                            deferred.complete(Unit)
                        }
                    } catch (_: Exception) {}
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = json.parseToJsonElement(text).jsonObject
                        handleServerMessage(msg, attemptGeneration)
                        if (msg.containsKey("setupComplete")) {
                            deferred.complete(Unit)
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptGeneration == generation.get()) {
                        onFailure?.invoke(t.message ?: "WebSocket connection failed")
                    }
                    deferred.completeExceptionally(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptGeneration == generation.get()) {
                        started.set(false)
                        stopAudio()
                    }
                }
            })

            try {
                withTimeout(15_000L) { deferred.await() }
                started.set(true)
                sessionStartTime = System.currentTimeMillis()
                onEvent?.invoke(buildJsonObject {
                    put("type", JsonPrimitive("mural.session.created"))
                    put("session", buildJsonObject { put("id", JsonPrimitive("gemini-${System.currentTimeMillis()}")) })
                })
                onEvent?.invoke(buildJsonObject {
                    put("type", JsonPrimitive("session.started"))
                    put("session", buildJsonObject { put("id", JsonPrimitive("gemini-${System.currentTimeMillis()}")) })
                })
                startAudioStreaming(attemptGeneration)
            } catch (e: Exception) {
                webSocket?.close(1000, "Connection failed")
                throw GeminiTransportException.connection
            }
        }
    }

    private fun handleServerMessage(msg: JsonObject, expectedGeneration: Long) {
        if (generation.get() != expectedGeneration) return

        val serverContent = msg["serverContent"] as? JsonObject ?: return

        val inputTranscription = serverContent["inputTranscription"] as? JsonObject
        if (inputTranscription != null) {
            val text = (inputTranscription["text"] as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) {
                val elapsed = System.currentTimeMillis() - sessionStartTime
                onEvent?.invoke(buildJsonObject {
                    put("type", JsonPrimitive("session.input_transcript.delta"))
                    put("event_id", JsonPrimitive(java.util.UUID.randomUUID().toString()))
                    put("delta", JsonPrimitive(text))
                    put("start_ms", JsonPrimitive((elapsed - 500).coerceAtLeast(0).toInt()))
                    put("end_ms", JsonPrimitive(elapsed.toInt()))
                })
            }
        }

        val modelTurn = serverContent["modelTurn"] as? JsonObject
        if (modelTurn != null) {
            val parts = modelTurn["parts"] as? kotlinx.serialization.json.JsonArray ?: return
            for (part in parts) {
                val p = part as? JsonObject ?: continue
                val inlineData = p["inlineData"] as? JsonObject
                if (inlineData != null) {
                    val mimeType = (inlineData["mimeType"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val data = (inlineData["data"] as? JsonPrimitive)?.contentOrNull ?: continue
                    if (mimeType.startsWith("audio/")) {
                        val audioData = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                        playAudioData(audioData)
                    }
                    continue
                }
                val text = (p["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    val elapsed = System.currentTimeMillis() - sessionStartTime
                    onEvent?.invoke(buildJsonObject {
                        put("type", JsonPrimitive("session.output_transcript.delta"))
                        put("event_id", JsonPrimitive(java.util.UUID.randomUUID().toString()))
                        put("delta", JsonPrimitive(text))
                        put("start_ms", JsonPrimitive((elapsed - 500).coerceAtLeast(0).toInt()))
                        put("end_ms", JsonPrimitive(elapsed.toInt()))
                    })
                }
            }
        }

        val turnComplete = serverContent["turnComplete"]?.toString()?.toBooleanStrictOrNull() == true
        if (turnComplete) {
            val elapsed = System.currentTimeMillis() - sessionStartTime
            onEvent?.invoke(buildJsonObject {
                put("type", JsonPrimitive("session.output_transcript.done"))
                put("event_id", JsonPrimitive(java.util.UUID.randomUUID().toString()))
                put("start_ms", JsonPrimitive((elapsed - 1000).coerceAtLeast(0).toInt()))
                put("end_ms", JsonPrimitive(elapsed.toInt()))
            })
            onLevels?.invoke(0.0, 0.0)
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        if (muted.get() || !started.get()) return
        val ws = webSocket ?: return

        // Calculate input audio level (RMS of 16-bit PCM)
        var sum = 0.0
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF or (pcmData[i + 1].toInt() shl 8)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (pcmData.size / 2))
        val inputLevel = (rms / Short.MAX_VALUE).coerceIn(0.0, 1.0)
        onLevels?.invoke(inputLevel, 0.0)

        val base64Data = android.util.Base64.encodeToString(pcmData, android.util.Base64.NO_WRAP)
        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("audio", buildJsonObject {
                    put("mimeType", JsonPrimitive("audio/pcm;rate=16000"))
                    put("data", JsonPrimitive(base64Data))
                })
            })
        }
        ws.send(msg.toString())
    }

    fun sendText(text: String) {
        val ws = webSocket ?: return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turns", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", JsonPrimitive(text)) })
                        })
                    })
                })
                put("turnComplete", JsonPrimitive(true))
            })
        }
        ws.send(msg.toString())
    }

    fun setMute(value: Boolean) {
        muted.set(value)
    }

    fun disconnect() {
        generation.incrementAndGet()
        started.set(false)
        stopAudio()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private fun configureAudio() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        val outBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(outBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun startAudioStreaming(expectedGeneration: Long) {
        audioRecord?.startRecording()

        recordThread = Thread {
            val buffer = ByteArray(3200)
            try {
                while (generation.get() == expectedGeneration && started.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        sendAudio(buffer.copyOf(read))
                    }
                }
            } catch (_: InterruptedException) {}
        }.apply { start() }

        playThread = Thread {
            audioTrack?.play()
            try {
                while (generation.get() == expectedGeneration && started.get()) {
                    Thread.sleep(10)
                }
            } catch (_: InterruptedException) {}
        }.apply { start() }
    }

    private fun playAudioData(data: ByteArray) {
        audioTrack?.write(data, 0, data.size)
        var sum = 0.0
        var i = 0
        while (i < data.size - 1) {
            val sample = (data[i].toInt() and 0xFF or (data[i + 1].toInt() shl 8)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (data.size / 2))
        val outputLevel = (rms / Short.MAX_VALUE).coerceIn(0.0, 1.0)
        onLevels?.invoke(0.0, outputLevel)
    }

    private fun stopAudio() {
        recordThread?.interrupt()
        recordThread = null
        playThread?.interrupt()
        playThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    sealed class GeminiTransportException(message: String) : Exception(message) {
        data object microphone : GeminiTransportException("Allow microphone access in Settings.")
        data object connection : GeminiTransportException("Gemini voice connection failed. Check your API key and try again.")
    }
}
