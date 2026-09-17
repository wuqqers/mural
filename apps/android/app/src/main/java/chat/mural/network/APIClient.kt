package chat.mural.network

import chat.mural.core.SourceLink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class APIUsage(val input: Int = 0, val output: Int = 0, val searches: Int = 0)
data class APIResult(val text: String, val sources: List<SourceLink>, val usage: APIUsage)

class APIClient private constructor(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = API_BASE_URL,
    private val modelOverride: String? = null,
    val providerType: ProviderType = ProviderType.OpenAI,
) : TeachingClient, LiveSessionProvider {
    constructor(credentials: CredentialStore) : this(
        readCredential = credentials::read,
        baseUrl = credentials.readConfig().resolvedBaseURL.toHttpUrlOrNull() ?: API_BASE_URL,
        modelOverride = credentials.readModel(),
        providerType = credentials.readProviderType(),
    )

    internal constructor(key: String?, client: OkHttpClient, baseUrl: HttpUrl) :
        this({ key }, client, baseUrl)

    override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
        if (providerType == ProviderType.Gemini) {
            throw APIException.UseGeminiLiveTransport
        }
        if (providerType != ProviderType.OpenAI) {
            throw APIException.Refused
        }
        val result = post("live/sessions", buildJsonObject {
            put("session", buildJsonObject {
                put("model", "gpt-live-1"); put("instructions", request.instructions); put("input", request.history)
                put("store", false)
                put("delegation", buildJsonObject { put("type", "client") })
                put("audio", buildJsonObject { put("output", buildJsonObject { put("voice", "marin") }) })
            })
            put("transport", buildJsonObject { put("type", "webrtc"); put("sdp", request.sdp) })
        })
        val transport = result["transport"] as? JsonObject ?: throw APIException.InvalidResponse
        val answer = (transport["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (transport["type"] != JsonPrimitive("webrtc") || answer.isNullOrBlank()) throw APIException.InvalidResponse
        val id = ((result["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return LiveSessionConnection(answer, id)
    }

    suspend fun post(path: String, body: JsonObject): JsonObject {
        if (!VALID_PATH.matches(path) || path.contains("..") || path.startsWith('/')) {
            throw APIException.InvalidResponse
        }
        val key = readCredential() ?: throw APIException.MissingKey
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $key")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // Parse on OkHttp's worker while the continuation remains cancellable.
        // Cancellation closes a response even if the peer stalls halfway through its body.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code !in 200..299) throw APIException.Http(it.code)
                            val payload = it.readBoundedBody()
                            try { JSON.parseToJsonElement(payload).jsonObject }
                            catch (_: Exception) { throw APIException.InvalidResponse }
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult {
        val isOpenAI = providerType == ProviderType.OpenAI
        val isGemini = providerType == ProviderType.Gemini

        val body: JsonObject
        val endpoint: String
        val decoder: (JsonObject) -> APIResult

        if (isOpenAI) {
            endpoint = "responses"
            decoder = ::decodeTeachingResponse
            body = buildJsonObject {
                put("model", modelOverride ?: "gpt-5.6-luna")
                put("store", false)
                put("instructions", instructions)
                put("input", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", input)
                    })
                })
                put("max_output_tokens", if (schema == null) 1_400 else 2_200)
                put("reasoning", buildJsonObject { put("effort", "low") })
                if (schema != null) {
                    put("text", buildJsonObject {
                        put("format", buildJsonObject {
                            put("type", "json_schema")
                            put("name", "mural_result")
                            put("strict", true)
                            put("schema", schema)
                        })
                    })
                }
                if (search) {
                    put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
                    put("tool_choice", "auto")
                    put("max_tool_calls", 1)
                }
            }
        } else if (isGemini) {
            val model = modelOverride ?: "gemini-2.0-flash"
            endpoint = "models/${model}:generateContent"
            decoder = ::decodeGeminiResponse
            body = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", input) })
                        })
                    })
                })
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", instructions) })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.7)
                    put("maxOutputTokens", if (schema == null) 1_400 else 2_200)
                    if (schema != null) {
                        put("responseMimeType", "application/json")
                        put("responseSchema", schema)
                    }
                })
            }
        } else {
            endpoint = "chat/completions"
            decoder = ::decodeChatCompletionsResponse
            body = buildJsonObject {
                put("model", modelOverride ?: "gpt-3.5-turbo")
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", instructions)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", input)
                    })
                })
                put("max_tokens", if (schema == null) 1_400 else 2_200)
                put("temperature", 0.7)
                if (schema != null) {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", "mural_result")
                            put("strict", true)
                            put("schema", schema)
                        })
                    })
                }
            }
        }

        val response = post(endpoint, body)
        return decoder(response)
    }

    private fun Response.readBoundedBody(): String {
        val responseBody = body ?: throw APIException.InvalidResponse
        if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw APIException.InvalidResponse
        val source = responseBody.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val count = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - total))
            if (count == -1L) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw APIException.InvalidResponse
        }
        return buffer.readString(Charsets.UTF_8)
    }

    sealed class APIException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        data object MissingKey : APIException("Add your API key in Settings to begin.")
        data object InvalidResponse : APIException("The API returned an incomplete response. Please try again.")
        data object Incomplete : APIException("The API returned an incomplete response. Please try again.")
        data object Refused : APIException("Mural couldn't complete that request. Try a different topic.")
        data object UseGeminiLiveTransport : APIException("Gemini voice uses a separate transport.")
        class Http(val status: Int) : APIException(messageFor(status))

        companion object {
            private fun messageFor(status: Int): String = when (status) {
                401 -> "Your API key wasn't accepted. Check it in Settings."
                403, 404 -> "This API key may not have access to the requested model. Check your provider settings."
                429 -> "Usage or rate limit was reached. Check your provider's billing and limits."
                else -> "The API couldn't complete the request (HTTP $status). Please try again."
            }
        }
    }

    companion object {
        private val API_BASE_URL = HttpUrl.Builder()
            .scheme("https")
            .host("api.openai.com")
            .addPathSegment("v1")
            .addPathSegment("")
            .build()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VALID_PATH = Regex("[a-z0-9][a-z0-9_/-]*")
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()


    }
}
