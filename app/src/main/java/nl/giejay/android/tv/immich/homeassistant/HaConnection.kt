package nl.giejay.android.tv.immich.homeassistant

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

/**
 * Home Assistant websocket API client (https://developers.home-assistant.io/docs/api/websocket).
 * Authenticates, then lets the caller fetch the dashboard config and subscribe to just the
 * entities it shows ("subscribe_entities", HA's compressed state stream). Reconnects with backoff.
 *
 * All callbacks and state live on the main thread.
 */
class HaConnection(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val listener: Listener
) {
    interface Listener {
        /** Authenticated: (re)issue requests and subscriptions. Called again after a reconnect. */
        fun onConnected()
        fun onAuthFailed(message: String)
        fun onDisconnected(error: String?)
        fun onEntitiesChanged(ids: Set<String>)
    }

    val entities = HashMap<String, HaEntity>()

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var nextId = 1
    private val resultHandlers = HashMap<Int, (Boolean, JsonElement?, JsonObject?) -> Unit>()
    private val eventHandlers = HashMap<Int, (JsonObject) -> Unit>()
    private var stopped = true
    private var authenticated = false
    private var attempt = 0
    private var generation = 0

    private val reconnect = Runnable { open() }

    fun start() {
        if (!stopped) return
        stopped = false
        attempt = 0
        open()
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(reconnect)
        generation++
        socket?.close(1000, null)
        socket = null
        authenticated = false
        resultHandlers.clear()
        eventHandlers.clear()
    }

    private fun open() {
        if (stopped) return
        val gen = ++generation
        val url = baseUrl.trimEnd('/') + "/api/websocket"
        Timber.i("Connecting to Home Assistant at %s", baseUrl)
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            listener.onDisconnected("Invalid Home Assistant URL: $baseUrl")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try {
                    JsonParser.parseString(text)
                } catch (e: Exception) {
                    Timber.w(e, "Bad message from Home Assistant")
                    return
                }
                main.post { if (gen == generation) handle(webSocket, message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "Home Assistant connection failed")
                main.post { if (gen == generation) dropped(describe(t)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation) dropped(null) }
            }
        })
    }

    private fun describe(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        return when {
            chain.any { it is javax.net.ssl.SSLHandshakeException || it is java.security.cert.CertificateException } ->
                "Secure connection failed (certificate not trusted)"
            chain.any { it is java.net.UnknownHostException } -> "Can't find ${baseUrl.substringAfter("://")}"
            chain.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException } -> "Can't reach Home Assistant"
            else -> t.message ?: t.javaClass.simpleName
        }
    }

    private fun dropped(error: String?) {
        socket = null
        authenticated = false
        resultHandlers.clear()
        eventHandlers.clear()
        if (stopped) return
        listener.onDisconnected(error)
        val delays = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
        main.postDelayed(reconnect, delays[attempt.coerceAtMost(delays.size - 1)])
        attempt++
    }

    private fun handle(ws: WebSocket, element: JsonElement) {
        // HA may batch messages into an array
        if (element.isJsonArray) {
            element.asJsonArray.forEach { handle(ws, it) }
            return
        }
        if (!element.isJsonObject) return
        val msg = element.asJsonObject
        when (msg.get("type")?.asString) {
            "auth_required" -> ws.send(JsonObject().apply {
                addProperty("type", "auth")
                addProperty("access_token", token)
            }.toString())
            "auth_ok" -> {
                authenticated = true
                attempt = 0
                listener.onConnected()
            }
            "auth_invalid" -> {
                stopped = true
                ws.close(1000, null)
                listener.onAuthFailed(msg.get("message")?.asString ?: "Invalid access token")
            }
            "result" -> {
                val id = msg.get("id")?.asInt ?: return
                val handler = resultHandlers.remove(id) ?: return
                val success = msg.get("success")?.asBoolean == true
                handler(success, msg.get("result"), msg.get("error")?.takeIf { it.isJsonObject }?.asJsonObject)
            }
            "event" -> {
                val id = msg.get("id")?.asInt ?: return
                val event = msg.get("event")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                eventHandlers[id]?.invoke(event)
            }
        }
    }

    /** Sends a command; [onResult] gets (success, result, error). Returns the message id. */
    fun send(
        type: String,
        fields: JsonObject = JsonObject(),
        onResult: (Boolean, JsonElement?, JsonObject?) -> Unit = { _, _, _ -> }
    ): Int? {
        val ws = socket ?: return null
        if (!authenticated) return null
        val id = nextId++
        fields.addProperty("id", id)
        fields.addProperty("type", type)
        resultHandlers[id] = onResult
        ws.send(fields.toString())
        return id
    }

    /** Sends a subscription; [onEvent] receives each event payload. */
    fun subscribe(type: String, fields: JsonObject = JsonObject(), onEvent: (JsonObject) -> Unit): Int? {
        val id = send(type, fields) { success, _, error ->
            if (!success) Timber.w("Subscription %s failed: %s", type, error)
        } ?: return null
        eventHandlers[id] = onEvent
        return id
    }

    fun unsubscribe(id: Int) {
        if (eventHandlers.remove(id) != null) {
            send("unsubscribe_events", JsonObject().apply { addProperty("subscription", id) })
        }
    }

    /** Streams the given entities' states into [entities] (HA's compressed format). */
    fun subscribeEntities(ids: Collection<String>): Int? {
        if (ids.isEmpty()) return null
        val fields = JsonObject().apply { add("entity_ids", JsonArray().apply { ids.forEach { add(it) } }) }
        return subscribe("subscribe_entities", fields) { event -> applyEntityEvent(event) }
    }

    private fun applyEntityEvent(event: JsonObject) {
        val changed = HashSet<String>()
        event.getAsJsonObject("a")?.entrySet()?.forEach { (id, value) ->
            val s = value.asJsonObject
            val lastUpdated = s.get("lu")?.asDouble ?: 0.0
            entities[id] = HaEntity(
                id,
                s.get("s")?.asString ?: "unknown",
                s.getAsJsonObject("a") ?: JsonObject(),
                s.get("lc")?.asDouble ?: lastUpdated
            )
            changed += id
        }
        event.getAsJsonObject("c")?.entrySet()?.forEach { (id, value) ->
            val entity = entities[id] ?: return@forEach
            val diff = value.asJsonObject
            diff.getAsJsonObject("+")?.let { plus ->
                plus.get("s")?.let { entity.state = it.asString }
                plus.getAsJsonObject("a")?.entrySet()?.forEach { (k, v) -> entity.attributes.add(k, v) }
                // "lc" is only sent when it differs from "lu"; a state change without it changed at "lu"
                val changedAt = plus.get("lc") ?: plus.get("lu")?.takeIf { plus.has("s") }
                changedAt?.let { entity.lastChanged = it.asDouble }
            }
            diff.getAsJsonObject("-")?.getAsJsonArray("a")?.forEach { entity.attributes.remove(it.asString) }
            changed += id
        }
        event.getAsJsonArray("r")?.forEach {
            entities.remove(it.asString)
            changed += it.asString
        }
        if (changed.isNotEmpty()) listener.onEntitiesChanged(changed)
    }
}
