package com.kazumi.port.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

/**
 * Syncplay 客户端（对应原版 services/player/syncplay_client.dart）
 *
 * 协议：https://syncplay.pl/about/protocol/
 * - 明文 TCP 或 STARTTLS（官方端点 syncplay.pl:8995/8996/8997 使用 TLS）
 * - 消息为 JSON 行（\r\n 分隔）
 * - Hello / State / Set / Chat 四类消息
 */
class SyncplayClient(
    private val host: String,
    private val port: Int
) {

    open class SyncplayException(message: String) : Exception(message)
    class SyncplayConnectionException(message: String) : SyncplayException(message)

    /** 房间事件流：Map 含 type=joined/left/init 等 */
    private val _roomMessages = MutableSharedFlow<Map<String, Any>>(extraBufferCapacity = 32)
    val roomMessages: SharedFlow<Map<String, Any>> = _roomMessages.asSharedFlow()

    /** 文件切换事件流 */
    private val _fileChangedMessages = MutableSharedFlow<Map<String, Any>>(extraBufferCapacity = 32)
    val fileChangedMessages: SharedFlow<Map<String, Any>> = _fileChangedMessages.asSharedFlow()

    /** 聊天事件流 */
    private val _chatMessages = MutableSharedFlow<Map<String, Any>>(extraBufferCapacity = 32)
    val chatMessages: SharedFlow<Map<String, Any>> = _chatMessages.asSharedFlow()

    /** 播放位置同步事件流 */
    private val _positionMessages = MutableSharedFlow<Map<String, Any>>(extraBufferCapacity = 32)
    val positionMessages: SharedFlow<Map<String, Any>> = _positionMessages.asSharedFlow()

    /** 连接错误/断开事件流 */
    private val _errorMessages = MutableSharedFlow<Map<String, Any>>(extraBufferCapacity = 16)
    val errorMessages: SharedFlow<Map<String, Any>> = _errorMessages.asSharedFlow()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var closed = false
    @Volatile
    private var connected = false

    private var scope: CoroutineScope? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private var username: String? = null
    private var currentRoom: String? = null
    private var currentFileName: String? = null

    @Volatile
    private var currentPosition = 0.0
    @Volatile
    private var isPaused = true

    private var clientRtt = 0.0
    private var serverRtt = 0.0
    private var avrRtt = 0.0
    private var fd = 0.0
    private var clientIgnoringOnTheFly = 0
    private var serverIgnoringOnTheFly = 0
    private var lastLatencyCalculation: Double? = null

    val isConnected: Boolean get() = connected && !closed
    val currentUsername: String? get() = username
    val currentFileNameValue: String? get() = currentFileName

    /**
     * 连接（官方端点自动 STARTTLS，其余走明文）
     */
    suspend fun connect(enableTLS: Boolean = false) {
        if (closed) throw SyncplayConnectionException("client closed")
        withContext(Dispatchers.IO) {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 10_000)
            var s: Socket = raw
            if (enableTLS) {
                // STARTTLS：先发 TLS 请求，读回 server 确认后升级
                writeLine(raw, """{"TLS":{"startTLS":"send"}}""")
                val resp = BufferedReader(InputStreamReader(raw.getInputStream())).readLine()
                if (resp == null || !resp.contains("\"true\"")) {
                    raw.close()
                    throw SyncplayConnectionException("server rejected TLS upgrade")
                }
                val factory = SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                val ssl = factory.createSocket(raw, host, port, true) as Socket
                (ssl as javax.net.ssl.SSLSocket).startHandshake()
                s = ssl
            }
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            connected = true
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = ioScope
            ioScope.launch { readLoop() }
        }
    }

    private fun writeLine(socket: Socket, line: String) {
        val w = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        w.write(line)
        w.write("\r\n")
        w.flush()
    }

    @Volatile
    private var writerLock = Any()

    private fun send(json: String) {
        val s = socket ?: return
        try {
            synchronized(writerLock) {
                val w = writer ?: BufferedWriter(OutputStreamWriter(s.getOutputStream())).also { writer = it }
                w.write(json)
                w.write("\r\n")
                w.flush()
            }
        } catch (e: Exception) {
            failConnection("socket write failed: ${e.message}")
        }
    }

    private fun readLoop() {
        try {
            val r = reader ?: return
            var buffer = StringBuilder()
            while (!closed) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                buffer.append(line)
                // 逐条解析（一帧可能含多条 JSON，简单按行处理即可）
                handleLine(buffer.toString())
                buffer = StringBuilder()
            }
        } catch (e: Exception) {
            if (!closed) failConnection("socket read failed: ${e.message}")
        } finally {
            if (!closed) failConnection("connection closed")
        }
    }

    private fun handleLine(line: String) {
        val json = try {
            com.google.gson.JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            emitError("parse failed: $line")
            return
        }
        when {
            json.has("TLS") -> {
                val tls = json.getAsJsonObject("TLS")
                if (tls.has("startTLS") && tls.get("startTLS").asString == "true") {
                    // 已在 connect() 中处理升级
                }
            }
            json.has("Hello") -> {
                val hello = json.getAsJsonObject("Hello")
                username = hello.get("username")?.asString ?: ""
                currentRoom = hello.getAsJsonObject("room")?.get("name")?.asString ?: ""
                emitRoom(mapOf("type" to "hello", "username" to (username ?: "")))
                setReady()
            }
            json.has("State") -> {
                val state = json.getAsJsonObject("State")
                if (state.has("ping")) {
                    val ping = state.getAsJsonObject("ping")
                    lastLatencyCalculation = ping.get("latencyCalculation")?.takeIf { !it.isJsonNull }?.asDouble
                    if (ping.has("serverRtt")) serverRtt = ping.get("serverRtt").asDouble
                    updateClientRttAndFd(
                        ping.get("clientLatencyCalculation")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                        serverRtt
                    )
                }
                if (state.has("ignoringOnTheFly")) {
                    val ig = state.getAsJsonObject("ignoringOnTheFly")
                    if (ig.has("server")) {
                        serverIgnoringOnTheFly = ig.get("server").asInt
                        clientIgnoringOnTheFly = 0
                    } else if (ig.has("client")) {
                        if (ig.get("client").asInt == clientIgnoringOnTheFly) {
                            clientIgnoringOnTheFly = 0
                        }
                    }
                }
                if (clientIgnoringOnTheFly == 0) {
                    val playstate = state.getAsJsonObject("playstate")
                    val paused = playstate.get("paused")?.asBoolean ?: true
                    val position = playstate.get("position")?.asDouble ?: 0.0
                    currentPosition = if (paused) position else position + fd
                    isPaused = paused
                    emitPosition(
                        mapOf(
                            "calculatedPositon" to currentPosition,
                            "position" to position,
                            "paused" to paused,
                            "doSeek" to (playstate.get("doSeek")?.asBoolean ?: false),
                            "setBy" to (playstate.get("setBy")?.asString ?: ""),
                            "clientRtt" to clientRtt,
                            "serverRtt" to serverRtt,
                            "fd" to fd
                        )
                    )
                }
                sendState()
            }
            json.has("Set") -> {
                val set = json.getAsJsonObject("Set")
                if (set.has("playlistIndex")) {
                    val user = set.getAsJsonObject("playlistIndex").get("user")?.asString ?: ""
                    emitRoom(mapOf("type" to "init", "username" to user))
                }
                if (set.has("user")) {
                    val userMap = set.getAsJsonObject("user")
                    userMap.entrySet().forEach { (name, detail) ->
                        if (detail.isJsonObject && detail.asJsonObject.has("event")) {
                            val event = detail.asJsonObject.getAsJsonObject("event").keySet().firstOrNull() ?: "unknown"
                            emitRoom(mapOf("type" to event, "username" to name))
                        }
                        if (detail.isJsonObject && detail.asJsonObject.has("file")) {
                            val fileData = detail.asJsonObject.getAsJsonObject("file")
                            val fileName = fileData.get("name")?.asString ?: ""
                            currentFileName = fileName
                            emitFileChanged(mapOf("name" to fileName, "setBy" to name))
                        }
                    }
                }
            }
            json.has("Chat") -> {
                val chat = json.getAsJsonObject("Chat")
                if (chat.has("message") && chat.has("username")) {
                    emitChat(
                        mapOf(
                            "message" to chat.get("message").asString,
                            "username" to chat.get("username").asString
                        )
                    )
                }
            }
            else -> emitError("unknown message type")
        }
    }

    fun joinRoom(room: String, username: String) {
        send(
            """{"Hello":{"username":"${escape(username)}","room":{"name":"${escape(room)}"},"version":"1.7.0","features":{"sharedPlaylists":true,"chat":true,"featureList":true,"readiness":true,"managedRooms":false}}}"""
        )
    }

    fun sendChatMessage(message: String) {
        if (currentRoom == null || username == null) {
            emitError("not in a room")
            return
        }
        send("""{"Chat":"${escape(message)}"}""")
    }

    fun setSyncPlayPlaying(fileName: String, duration: Double, size: Int) {
        if (currentRoom == null || username == null) {
            emitError("not in a room")
            return
        }
        val setBy = username ?: ""
        val room = currentRoom ?: ""
        send(
            """{"Set":{"file":{"duration":$duration,"name":"${escape(fileName)}","size":$size},"user":{"${escape(setBy)}":{"room":{"name":"${escape(room)}"}}}}}"""
        )
    }

    fun sendSyncPlaySyncRequest(doSeek: Boolean?) {
        sendState(doSeek = doSeek, stateChange = true)
    }

    fun setPosition(position: Double) {
        currentPosition = position
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    private fun setReady() {
        val uname = username ?: return
        val room = currentRoom ?: return
        send("""{"Set":{"${escape(room)}":{"room":{"name":"${escape(room)}"},"event":{"joined":true}}}}""")
        send("""{"Set":{"ready":{"isReady":true,"manuallyInitiated":false}}}""")
    }

    private fun sendState(doSeek: Boolean? = null, stateChange: Boolean = false) {
        var clientAck: Int? = null
        var serverAck: Int? = null
        if (stateChange) clientIgnoringOnTheFly++
        if (serverIgnoringOnTheFly > 0) {
            serverAck = serverIgnoringOnTheFly
            serverIgnoringOnTheFly = 0
        }
        if (clientIgnoringOnTheFly > 0) {
            clientAck = clientIgnoringOnTheFly
        }
        val sb = StringBuilder("""{"State":{""")
        if (clientAck != null || serverAck != null) {
            sb.append(""""ignoringOnTheFly":{""")
            if (clientAck != null) sb.append(""""client":$clientAck""")
            if (clientAck != null && serverAck != null) sb.append(",")
            if (serverAck != null) sb.append(""""server":$serverAck""")
            sb.append("},")
        }
        val latency = lastLatencyCalculation
        sb.append(""""ping":{"clientRtt":$clientRtt,"clientLatencyCalculation":${System.currentTimeMillis() / 1000.0}""")
        if (latency != null) sb.append(""","latencyCalculation":$latency""")
        sb.append("},")
        sb.append(""""playstate":{"position":$currentPosition,"paused":$isPaused""")
        username?.let { sb.append(""","setBy":"${escape(it)}"""") }
        if (doSeek != null) sb.append(""","doSeek":$doSeek""")
        sb.append("}}}")
        send(sb.toString())
    }

    private fun updateClientRttAndFd(timestamp: Double, senderRtt: Double) {
        if (timestamp <= 0) return
        val now = System.currentTimeMillis() / 1000.0
        val newClientRtt = now - timestamp
        if (newClientRtt < 0 || senderRtt < 0) return
        clientRtt = newClientRtt
        if (avrRtt == 0.0) avrRtt = clientRtt
        avrRtt = avrRtt * 0.85 + clientRtt * 0.15
        fd = if (senderRtt < clientRtt) avrRtt / 2 + (clientRtt - senderRtt) else avrRtt / 2
    }

    private fun failConnection(message: String) {
        if (closed) return
        closed = true
        connected = false
        emitError(message)
        try { socket?.close() } catch (_: Exception) {}
        scope?.cancel()
        scope = null
    }

    fun disconnect() {
        if (closed) return
        closed = true
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        scope?.cancel()
        scope = null
        username = null
        currentRoom = null
        currentFileName = null
        currentPosition = 0.0
        isPaused = true
        lastLatencyCalculation = null
        clientIgnoringOnTheFly = 0
        serverIgnoringOnTheFly = 0
        clientRtt = 0.0
        serverRtt = 0.0
        avrRtt = 0.0
        fd = 0.0
    }

    private fun emitRoom(map: Map<String, Any>) {
        try {
            _roomMessages.tryEmit(map)
        } catch (_: Exception) {}
    }

    private fun emitFileChanged(map: Map<String, Any>) {
        try {
            _fileChangedMessages.tryEmit(map)
        } catch (_: Exception) {}
    }

    private fun emitChat(map: Map<String, Any>) {
        try {
            _chatMessages.tryEmit(map)
        } catch (_: Exception) {}
    }

    private fun emitPosition(map: Map<String, Any>) {
        try {
            _positionMessages.tryEmit(map)
        } catch (_: Exception) {}
    }

    private fun emitError(message: String) {
        try {
            _errorMessages.tryEmit(mapOf("message" to message))
        } catch (_: Exception) {}
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
}