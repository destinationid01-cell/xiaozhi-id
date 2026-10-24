package me.xiaozhi.androidclient.model

import org.json.JSONObject

data class OtaRequest(
    val otaUrl: String,
    val deviceId: String,
    val clientId: String,
    val serialNumber: String?,
    val appVersion: String,
)

data class WebsocketConfig(
    val url: String,
    val token: String?,
    val version: Int,
)

data class ActivationInfo(
    val message: String?,
    val code: String?,
    val challenge: String?,
    val timeoutMs: Int?,
)

data class OtaConfigResult(
    val websocket: WebsocketConfig?,
    val activation: ActivationInfo?,
    val rawBody: String,
)

data class ConnectParams(
    val url: String,
    val token: String?,
    val protocolVersion: Int,
    val deviceId: String,
    val clientId: String,
)

data class ServerHello(
    val sessionId: String?,
    val sampleRate: Int?,
    val frameDuration: Int?,
    val raw: JSONObject,
)

enum class ListeningMode(val wireValue: String) {
    AUTO("auto"),
    MANUAL("manual"),
    REALTIME("realtime"),
}

enum class ConnectionStatus {
    DISCONNECTED,
    FETCHING_CONFIG,
    ACTIVATING,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class LogLine(
    val timestamp: String,
    val message: String,
)

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val timestamp: String,
)

data class UiState(
    val otaUrl: String = "",
    val deviceId: String = "",
    val clientId: String = "",
    val serialNumber: String = "",
    val assistantAvatarPath: String = "",
    val websocketUrl: String = "",
    val authToken: String = "",
    val protocolVersion: String = "1",
    val mcpPayload: String = "",
    val activationMessage: String = "",
    val activationCode: String = "",
    val activationPending: Boolean = false,
    val activated: Boolean = false,
    val sessionId: String = "",
    val serverSampleRate: String = "",
    val serverFrameDuration: String = "",
    val lastIncomingType: String = "",
    val lastSttText: String = "",
    val lastTtsText: String = "",
    val audioRouteStatus: String = "Output: Speaker / Input: Mic HP",
    val isRecording: Boolean = false,
    val isAssistantSpeaking: Boolean = false,
    val isTurnActive: Boolean = false,
    val activeListeningMode: String = "",
    val wakeWordEnabled: Boolean = false,
    val wakeWords: String = "",
    val wakeWordStatus: String = "",
    /** Level mic 0.0–1.0 (normalisasi RMS) untuk grafik dB & orb. */
    val micLevel: Float = 0f,
    /** Ambang RMS sensitivitas VAD (potensio pengaturan, default 700). */
    val vadThreshold: Double = 700.0,
    /** Overlay orb mengambang aktif. */
    val overlayEnabled: Boolean = false,
    val termuxEnabled: Boolean = false,
    val pythonPath: String = "",
    val pythonScriptPath: String = "",
    val pythonWorkdir: String = "",
    val pythonRuntimeStatus: String = "",
    val termuxApiCommand: String = "",
    val termuxApiArguments: String = "",
    val termuxApiStatus: String = "",
    val debugLoggingEnabled: Boolean = false,
    val debugWavDumpEnabled: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val draftMessage: String = "",
    val chatMessages: List<ChatMessage> = emptyList(),
    val logs: List<LogLine> = emptyList(),
)
