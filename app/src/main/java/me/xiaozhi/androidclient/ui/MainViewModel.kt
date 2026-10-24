package me.xiaozhi.androidclient.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.audio.XiaozhiAudioEngine
import me.xiaozhi.androidclient.data.AppPreferences
import me.xiaozhi.androidclient.data.StoredConfig
import me.xiaozhi.androidclient.integration.TermuxCommandEvents
import me.xiaozhi.androidclient.integration.TermuxCommandResult
import me.xiaozhi.androidclient.integration.TermuxRunner
import me.xiaozhi.androidclient.model.ActivationInfo
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import me.xiaozhi.androidclient.model.ConnectParams
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.LogLine
import me.xiaozhi.androidclient.model.OtaRequest
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.network.OtaConfigService
import me.xiaozhi.androidclient.network.XiaozhiRealtimeClient
import okhttp3.OkHttpClient
import org.json.JSONObject

private const val APP_VERSION = "0.3.0"
private const val LOG_TAG = "XiaozhiClient"
private const val UNBURNED_SERIAL_NUMBER = "Belum di-flash"
private const val WAKE_WORD_DISABLED = "Nonaktif"
private const val WAKE_WORD_STANDBY = "Siaga"
private const val DEFAULT_AUDIO_ROUTE = "Output: Speaker / Input: Mikrofon"

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val storedConfig = preferences.load()

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val otaConfigService = OtaConfigService(okHttpClient)
    private val realtimeClient = XiaozhiRealtimeClient(okHttpClient)
    private val audioEngine = XiaozhiAudioEngine(application)
    private val termuxRunner = TermuxRunner(application)

    private var pendingActivationInfo: ActivationInfo? = null
    private var pendingListeningMode: ListeningMode? = null
    private var pendingWakePhrase: String? = null
    private val pendingTextPrompts = ArrayDeque<String>()
    /** True jika turn saat ini dipicu deteksi suara (perlu verifikasi kata kunci di transkrip). */
    @Volatile private var isWakeTurn = false
    /** Job jendela follow-up 8 detik setelah TTS selesai. */
    private var followUpJob: kotlinx.coroutines.Job? = null

    /**
     * Buka jendela follow-up: setelah asisten selesai bicara, mic tetap
     * terbuka 8 detik agar pengguna bisa bertanya lanjutan TANPA kata kunci.
     * Jika tidak ada ucapan dalam 8 detik, kembali siaga.
     */
    private fun startFollowUpWindow() {
        followUpJob?.cancel()
        followUpJob = null
        val state = uiState.value
        if (!state.wakeWordEnabled) return
        if (state.connectionStatus != ConnectionStatus.CONNECTED) return
        if (state.isRecording || state.isAssistantSpeaking) return
        startListening(ListeningMode.AUTO)
        followUpJob = viewModelScope.launch {
            kotlinx.coroutines.delay(8_000)
            if (!uiState.value.isAssistantSpeaking && uiState.value.isRecording) {
                stopListening()
                addLog("Jendela follow-up habis — kembali siaga")
            }
        }
    }

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState = _uiState.asStateFlow()

    init {
        audioEngine.setRouteStatusListener(::updateAudioRouteStatus)
        audioEngine.setDebugListener(::addLog)
        audioEngine.setDebugOptions(
            loggingEnabled = storedConfig.debugLoggingEnabled,
            wavDumpEnabled = storedConfig.debugWavDumpEnabled,
        )
        viewModelScope.launch {
            realtimeClient.events.collect(::handleRealtimeEvent)
        }
        viewModelScope.launch {
            TermuxCommandEvents.events.collect(::handleTermuxCommandResult)
        }
        addLog("Klien siap")
        addLog("Menggunakan mode perangkat belum di-flash")
    }

    fun updateOtaUrl(value: String) = updateAndPersist { copy(otaUrl = value) }

    fun updateDeviceId(value: String) = updateAndPersist { copy(deviceId = value) }

    fun updateClientId(value: String) = updateAndPersist { copy(clientId = value) }

    fun updateWebsocketUrl(value: String) = updateAndPersist { copy(websocketUrl = value) }

    fun updateAuthToken(value: String) = updateAndPersist { copy(authToken = value) }

    fun updateProtocolVersion(value: String) = updateAndPersist { copy(protocolVersion = value) }

    fun updateMcpPayload(value: String) = updateAndPersist { copy(mcpPayload = value) }

    fun updateDraftMessage(value: String) {
        updateState { copy(draftMessage = value) }
    }

    fun importAssistantAvatar(uri: Uri) {
        runCatching {
            val avatarDir = File(getApplication<Application>().filesDir, "avatar").apply { mkdirs() }
            val targetFile = File(avatarDir, "assistant-avatar")
            val resolver = getApplication<Application>().contentResolver
            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Tidak bisa membaca gambar yang dipilih")
            targetFile.absolutePath
        }.onSuccess { path ->
            updateAndPersist { copy(assistantAvatarPath = path) }
            addLog("Avatar diperbarui")
        }.onFailure { error ->
            addLog("Gagal memperbarui avatar: ${error.message.orEmpty()}")
        }
    }

    fun updateWakeWordEnabled(enabled: Boolean) {
        updateAndPersist {
            copy(
                wakeWordEnabled = enabled,
                wakeWordStatus = if (enabled) "Siaga aktif" else WAKE_WORD_DISABLED,
            )
        }
        val app = getApplication<Application>()
        if (enabled) {
            me.xiaozhi.androidclient.XiaozhiVoiceService.start(app)
            addLog("Siaga selalu aktif — sebut kata kunci untuk bicara")
        } else {
            me.xiaozhi.androidclient.XiaozhiVoiceService.stop(app)
            addLog("Siaga selalu dimatikan")
        }
    }

    /**
     * Dipanggil saat WakeWordRecognizer (via XiaozhiVoiceService) mendeteksi
     * kata kunci secara lokal. Audio BARU dikirim ke server setelah kata
     * kunci terverifikasi — tidak ada turn palsu.
     */
    fun onWakeWordDetected(phrase: String) {
        val state = uiState.value
        if (state.isRecording || state.isAssistantSpeaking) return
        if (state.connectionStatus != ConnectionStatus.CONNECTED) {
            ensureReadyForConversation(trigger = "Kata kunci \"$phrase\"")
            return
        }
        // Kata kunci sudah diverifikasi lokal; tidak perlu verifikasi ulang via STT.
        isWakeTurn = false
        updateState { copy(wakeWordStatus = "Aktif — silakan bicara") }
        addLog("Kata kunci terdeteksi: $phrase")
        startListening(ListeningMode.AUTO)
    }

    /**
     * Dipakai saat activity sendiri yang mendeteksi (fallback bila service mati).
     * @deprecated Gunakan [onWakeWordDetected] — verifikasi lokal via service.
     */
    @Deprecated("Deteksi via XiaozhiVoiceService")
    fun onWakeSpeechDetected() {
        val state = uiState.value
        if (state.isRecording || state.isAssistantSpeaking) return
        if (state.connectionStatus != ConnectionStatus.CONNECTED) {
            ensureReadyForConversation(trigger = "Deteksi suara")
            return
        }
        isWakeTurn = true
        updateState { copy(wakeWordStatus = "Mendengarkan…") }
        addLog("Suara terdeteksi — memeriksa kata kunci")
        startListening(ListeningMode.AUTO)
    }

    /** Cek apakah transkrip mengandung salah satu kata kunci (case-insensitive). */
    private fun matchesWakeWord(text: String): Boolean {
        val normalized = text.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.isBlank()) return false
        return uiState.value.wakeWords
            .split(',', '，', ';', '；', '\n')
            .map { it.trim().lowercase().filter(Char::isLetterOrDigit) }
            .filter { it.isNotBlank() }
            .any { normalized.contains(it) }
    }

    fun updateWakeWords(value: String) = updateAndPersist { copy(wakeWords = value) }.also {
        // Service membaca kata kunci saat pertama nyala — restart agar pakai yang baru.
        if (uiState.value.wakeWordEnabled) {
            val app = getApplication<Application>()
            me.xiaozhi.androidclient.XiaozhiVoiceService.stop(app)
            me.xiaozhi.androidclient.XiaozhiVoiceService.start(app)
            addLog("Kata kunci diubah — service siaga di-restart")
        }
    }

    /** Potensio sensitivitas VAD (ambang RMS). */
    fun updateVadThreshold(value: Double) = updateAndPersist { copy(vadThreshold = value) }

    /** Overlay orb mengambang. */
    fun updateOverlayEnabled(enabled: Boolean) = updateAndPersist { copy(overlayEnabled = enabled) }.also {
        val app = getApplication<Application>()
        if (enabled) {
            me.xiaozhi.androidclient.HoloOrbService.start(app)
        } else {
            me.xiaozhi.androidclient.HoloOrbService.stop(app)
        }
    }

    /** Status orb untuk UI hologram. */
    fun orbStatus(): me.xiaozhi.androidclient.ui.theme.OrbStatus {
        val s = uiState.value
        return when {
            s.isAssistantSpeaking -> me.xiaozhi.androidclient.ui.theme.OrbStatus.SPEAKING
            s.isRecording -> me.xiaozhi.androidclient.ui.theme.OrbStatus.LISTENING
            s.isTurnActive -> me.xiaozhi.androidclient.ui.theme.OrbStatus.THINKING
            s.wakeWordEnabled -> me.xiaozhi.androidclient.ui.theme.OrbStatus.STANDBY
            else -> me.xiaozhi.androidclient.ui.theme.OrbStatus.IDLE
        }
    }

    fun updateTermuxEnabled(enabled: Boolean) {
        updateAndPersist {
            copy(
                termuxEnabled = enabled,
                pythonRuntimeStatus = termuxRunner.statusLabel(enabled),
                termuxApiStatus = termuxRunner.termuxApiStatusLabel(enabled),
            )
        }
        addLog(if (enabled) "Python/MCP diaktifkan" else "Python/MCP dinonaktifkan")
    }

    fun updatePythonPath(value: String) = updateAndPersist { copy(pythonPath = value) }

    fun updatePythonScriptPath(value: String) = updateAndPersist { copy(pythonScriptPath = value) }

    fun updatePythonWorkdir(value: String) = updateAndPersist { copy(pythonWorkdir = value) }

    fun updateTermuxApiCommand(value: String) = updateAndPersist { copy(termuxApiCommand = value) }

    fun updateTermuxApiArguments(value: String) = updateAndPersist { copy(termuxApiArguments = value) }

    fun updateDebugLoggingEnabled(enabled: Boolean) {
        audioEngine.setDebugOptions(
            loggingEnabled = enabled,
            wavDumpEnabled = uiState.value.debugWavDumpEnabled,
        )
        updateAndPersist { copy(debugLoggingEnabled = enabled) }
        addLog(if (enabled) "Log debug diaktifkan" else "Log debug dinonaktifkan")
    }

    fun updateDebugWavDumpEnabled(enabled: Boolean) {
        audioEngine.setDebugOptions(
            loggingEnabled = uiState.value.debugLoggingEnabled,
            wavDumpEnabled = enabled,
        )
        updateAndPersist { copy(debugWavDumpEnabled = enabled) }
        addLog(if (enabled) "Ekspor audio TTS diaktifkan" else "Ekspor audio TTS dinonaktifkan")
    }

    fun updateWakeWordStatus(status: String) {
        updateState { copy(wakeWordStatus = status) }
    }

    fun refreshPythonRuntimeStatus() {
        updateState {
            copy(
                pythonRuntimeStatus = termuxRunner.statusLabel(termuxEnabled),
                termuxApiStatus = termuxRunner.termuxApiStatusLabel(termuxEnabled),
            )
        }
    }

    fun runPythonScript() {
        val state = uiState.value
        termuxRunner.runPythonScript(
            pythonPath = state.pythonPath,
            scriptPath = state.pythonScriptPath,
            workdir = state.pythonWorkdir,
        ).onSuccess { message ->
            refreshPythonRuntimeStatus()
            addLog(message)
        }.onFailure { error ->
            refreshPythonRuntimeStatus()
            addLog("Gagal menjalankan Python: ${error.message.orEmpty()}")
        }
    }

    fun runTermuxApiCommand() {
        val state = uiState.value
        termuxRunner.runTermuxApiCommand(
            commandPath = state.termuxApiCommand,
            arguments = state.termuxApiArguments,
            workdir = state.pythonWorkdir,
        ).onSuccess { message ->
            refreshPythonRuntimeStatus()
            addLog(message)
        }.onFailure { error ->
            refreshPythonRuntimeStatus()
            addLog("Gagal memanggil termux-api: {error.message.orEmpty()}")
        }
    }

    fun updateAudioRouteStatus(status: String) {
        val normalized = status.ifBlank { DEFAULT_AUDIO_ROUTE }
        val previous = uiState.value.audioRouteStatus
        updateState { copy(audioRouteStatus = normalized) }
        if (previous != normalized) {
            addLog("Rute audio diperbarui: $normalized")
        }
    }

    fun onMicrophonePermissionDenied(reason: String) {
        addLog("Izin rekam ditolak: $reason")
        if (reason == "wake_word") {
            updateState {
                copy(
                    wakeWordEnabled = false,
                    wakeWordStatus = "Butuh izin mikrofon",
                )
            }
            persist()
        }
    }

    fun fetchOfficialConfig() {
        persist()
        val state = uiState.value
        updateState {
            copy(
                connectionStatus = ConnectionStatus.FETCHING_CONFIG,
                activationPending = false,
            )
        }
        addLog("Mengambil konfigurasi resmi: ${state.otaUrl}")

        viewModelScope.launch {
            otaConfigService.fetchConfig(
                OtaRequest(
                    otaUrl = state.otaUrl,
                    deviceId = state.deviceId,
                    clientId = state.clientId,
                    serialNumber = null,
                    appVersion = APP_VERSION,
                ),
            ).onSuccess { result ->
                pendingActivationInfo = result.activation

                val websocketConfig = result.websocket
                val activationPending = result.activation != null && websocketConfig == null

                updateState {
                    copy(
                        websocketUrl = websocketConfig?.url ?: websocketUrl,
                        authToken = websocketConfig?.token ?: authToken,
                        protocolVersion = (websocketConfig?.version
                            ?: protocolVersion.toIntOrNull()
                            ?: 1).toString(),
                        activationMessage = result.activation?.message.orEmpty(),
                        activationCode = result.activation?.code.orEmpty(),
                        activationPending = activationPending,
                        activated = websocketConfig != null,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                    )
                }

                if (websocketConfig != null) {
                    addLog("Konfigurasi WebSocket diterima: ${websocketConfig.url}")
                } else {
                    addLog("OTA tidak mengembalikan konfigurasi WebSocket")
                }

                if (result.activation != null && websocketConfig == null) {
                    addLog("Perlu aktivasi perangkat: ${result.activation.code.orEmpty()}")
                    if (result.activation.challenge.isNullOrBlank()) {
                        addLog("Selesaikan aktivasi di xiaozhi.me dulu, lalu ambil konfigurasi lagi")
                    } else {
                        addLog("Server meminta aktivasi challenge, tapi Android tidak punya kunci HMAC")
                    }
                }

                persist()

                if ((pendingWakePhrase != null || pendingTextPrompts.isNotEmpty()) && websocketConfig != null) {
                    connect()
                }
            }.onFailure { error ->
                updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
                addLog("Gagal mengambil konfigurasi OTA: ${error.message.orEmpty()}")
            }
        }
    }

    fun retryActivation() {
        val activationInfo = pendingActivationInfo
        if (activationInfo == null) {
            addLog("Tidak ada info aktivasi yang tertunda")
            return
        }
        if (activationInfo.challenge.isNullOrBlank()) {
            addLog("Mengambil ulang konfigurasi OTA")
            fetchOfficialConfig()
            return
        }
        addLog("Aktivasi challenge butuh serial & HMAC yang sudah di-flash, tidak tersedia di klien Android")
    }

    fun connect() {
        val state = uiState.value
        if (state.connectionStatus == ConnectionStatus.CONNECTING) {
            addLog("Koneksi sedang berjalan")
            return
        }
        if (state.connectionStatus == ConnectionStatus.CONNECTED) {
            addLog("WebSocket tersambung")
            flushPendingActions()
            return
        }
        val protocolVersion = state.protocolVersion.toIntOrNull()
        if (state.websocketUrl.isBlank()) {
            addLog("Alamat WebSocket kosong, ambil konfigurasi resmi dulu")
            updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
            return
        }
        if (protocolVersion == null) {
            addLog("Versi protokol harus angka bulat")
            updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
            return
        }
        if (state.activationPending) {
            addLog("Perangkat belum selesai diaktivasi")
            return
        }

        persist()
        updateState {
            copy(
                connectionStatus = ConnectionStatus.CONNECTING,
                sessionId = "",
                serverSampleRate = "",
                serverFrameDuration = "",
                lastIncomingType = "",
                lastSttText = "",
                lastTtsText = "",
                isAssistantSpeaking = false,
                isTurnActive = false,
            )
        }
        addLog("Menghubungkan: ${state.websocketUrl}")

        viewModelScope.launch {
            realtimeClient.connect(
                ConnectParams(
                    url = state.websocketUrl,
                    token = state.authToken,
                    protocolVersion = protocolVersion,
                    deviceId = state.deviceId,
                    clientId = state.clientId,
                ),
            ).onFailure { error ->
                updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
                addLog("Koneksi gagal: ${error.message.orEmpty()}")
            }
        }
    }

    fun disconnect() {
        clearPendingConversation()
        realtimeClient.disconnect()
        finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Terputus")
        audioEngine.clearPlayback {
            updateState { copy(isAssistantSpeaking = it) }
        }
        updateState {
            copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                sessionId = "",
                isAssistantSpeaking = false,
                isTurnActive = false,
            )
        }
    }

    fun startListening(mode: ListeningMode) {
        if (uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            addLog("Hubungkan ke server dulu")
            pendingListeningMode = mode
            ensureReadyForConversation(trigger = "Rekaman")
            return
        }
        if (audioEngine.isCapturing()) {
            addLog("Rekaman sudah berjalan")
            return
        }

        // Jeda pendengar kata kunci selama sesi (agar mute tidak membungkam TTS)
        me.xiaozhi.androidclient.XiaozhiVoiceService.pauseListening(getApplication())
        interruptCurrentTurn(reason = "listen_start")
        pendingListeningMode = null

        if (!realtimeClient.sendStartListening(mode)) {
            return
        }

        audioEngine.startCapture(
            mode = mode,
            onEncodedFrame = realtimeClient::sendAudioFrame,
            onAutoStop = {
                finishListening(
                    sendStop = true,
                    stopCapture = false,
                    keepTurnActive = true,
                    reason = "Mode otomatis mendeteksi diam, rekaman dihentikan",
                )
            },
            onRecordingChanged = { isRecording ->
                updateState {
                    copy(
                        isRecording = isRecording,
                        isTurnActive = isRecording || isTurnActive,
                        activeListeningMode = if (isRecording) mode.wireValue else "",
                        wakeWordStatus = when {
                            wakeWordEnabled && isRecording -> "Dalam sesi"
                            wakeWordEnabled -> WAKE_WORD_STANDBY
                            else -> wakeWordStatus
                        },
                    )
                }
            },
            onError = { message ->
                addLog(message)
                updateState {
                    copy(
                        isRecording = false,
                        isTurnActive = false,
                        activeListeningMode = "",
                    )
                }
            },
            onLevelChanged = { rms ->
                // Normalisasi RMS ke 0..1 untuk grafik dB (logaritmik kasar).
                val level = (kotlin.math.ln(1 + rms / 300.0) / kotlin.math.ln(1 + 8000.0 / 300.0)).toFloat()
                    .coerceIn(0f, 1f)
                updateState { copy(micLevel = level) }
            },
            vadThreshold = uiState.value.vadThreshold,
        )
        addLog("Mulai merekam: ${mode.wireValue}")
    }

    fun stopListening() {
        pendingListeningMode = null
        finishListening(sendStop = true, stopCapture = true, keepTurnActive = true, reason = "Rekaman berhenti")
        me.xiaozhi.androidclient.XiaozhiVoiceService.resumeListening(getApplication())
    }

    fun abortSpeaking() {
        clearPendingConversation()
        finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Interupsi diminta")
        me.xiaozhi.androidclient.XiaozhiVoiceService.resumeListening(getApplication())
        audioEngine.clearPlayback {
            updateState { copy(isAssistantSpeaking = it) }
        }
        updateState { copy(isAssistantSpeaking = false) }
        if (realtimeClient.sendAbort()) {
            addLog("Permintaan interupsi terkirim")
        }
    }

    fun sendDraftMessage() {
        val prompt = uiState.value.draftMessage.trim()
        if (prompt.isBlank()) {
            return
        }
        updateState { copy(draftMessage = "") }
        addChatMessage(ChatRole.USER, prompt)
        pendingTextPrompts.addLast(prompt)
        updateState { copy(isTurnActive = true) }
        addLog("Menyiapkan pesan teks")
        ensureReadyForConversation(trigger = "Pesan teks")
    }

    fun sendMcp() {
        if (realtimeClient.sendMcp(uiState.value.mcpPayload)) {
            addLog("Request MCP terkirim")
        }
    }

    fun clearLogs() {
        updateState { copy(logs = emptyList()) }
        addLog("Log dibersihkan")
    }

    override fun onCleared() {
        realtimeClient.disconnect(notify = false)
        audioEngine.release()
        super.onCleared()
    }

    private fun finishListening(
        sendStop: Boolean,
        stopCapture: Boolean,
        keepTurnActive: Boolean,
        reason: String,
    ) {
        // Turn selesai (pengguna sudah bicara) → tutup jendela follow-up lama;
        // jendela baru dibuka lagi setelah TTS berikutnya selesai.
        followUpJob?.cancel()
        followUpJob = null
        if (stopCapture) {
            viewModelScope.launch {
                audioEngine.stopCapture()
            }
        }
        if (sendStop) {
            realtimeClient.sendStopListening()
        }
        updateState {
            copy(
                isRecording = false,
                isTurnActive = isAssistantSpeaking || keepTurnActive,
                activeListeningMode = "",
                wakeWordStatus = if (wakeWordEnabled) WAKE_WORD_STANDBY else wakeWordStatus,
            )
        }
        addLog(reason)
    }

    private fun handleRealtimeEvent(event: XiaozhiRealtimeClient.RealtimeEvent) {
        when (event) {
            is XiaozhiRealtimeClient.RealtimeEvent.Log -> addLog(event.message)

            is XiaozhiRealtimeClient.RealtimeEvent.Connected -> {
                audioEngine.configurePlayback(
                    sampleRate = event.hello.sampleRate,
                    frameDurationMs = event.hello.frameDuration,
                )
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        sessionId = event.hello.sessionId.orEmpty(),
                        serverSampleRate = event.hello.sampleRate?.toString().orEmpty(),
                        serverFrameDuration = event.hello.frameDuration?.toString().orEmpty(),
                        lastIncomingType = "hello",
                        isTurnActive = pendingWakePhrase != null || pendingTextPrompts.isNotEmpty() || pendingListeningMode != null,
                    )
                }
                flushPendingActions()
            }

            is XiaozhiRealtimeClient.RealtimeEvent.JsonMessage -> {
                parseServerMessage(event.type, event.rawText)
            }

            is XiaozhiRealtimeClient.RealtimeEvent.BinaryMessage -> {
                updateState { copy(lastIncomingType = "binary") }
                audioEngine.playOpusFrame(
                    opusFrame = event.payload,
                    onPlaybackChanged = { isPlaying ->
                        val wasSpeaking = uiState.value.isAssistantSpeaking
                        updateState { copy(isAssistantSpeaking = isPlaying) }
                        if (wasSpeaking && !isPlaying) {
                            // TTS selesai → buka jendela follow-up 8 detik
                            startFollowUpWindow()
                        }
                    },
                    onError = ::addLog,
                )
            }

            is XiaozhiRealtimeClient.RealtimeEvent.Disconnected -> {
                finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Socket ditutup")
                audioEngine.clearPlayback {
                    updateState { copy(isAssistantSpeaking = it) }
                }
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        sessionId = "",
                        isAssistantSpeaking = false,
                        isTurnActive = false,
                    )
                }
                addLog("Socket ditutup: ${event.code} ${event.reason}")
            }

            is XiaozhiRealtimeClient.RealtimeEvent.Error -> {
                finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Kanal real-time bermasalah")
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.FAILED,
                        isAssistantSpeaking = false,
                        isTurnActive = false,
                    )
                }
                addLog(event.message)
            }
        }
    }

    private fun parseServerMessage(type: String?, rawText: String) {
        updateState { copy(lastIncomingType = type.orEmpty()) }

        when (type) {
            "tts" -> handleTtsMessage(rawText)
            "stt" -> handleSttMessage(rawText)
            else -> addLog("<= $rawText")
        }
    }

    private fun handleTtsMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText) ?: return
        val state = root.optString("state")
        val text = root.optString("text")

        when (state) {
            "start" -> {
                audioEngine.beginPlaybackSession()
                updateState { copy(isAssistantSpeaking = true, isTurnActive = true) }
                val shouldReleaseCaptureForPlayback =
                    uiState.value.activeListeningMode != ListeningMode.REALTIME.wireValue ||
                        isBluetoothMicActive()
                if (shouldReleaseCaptureForPlayback) {
                    finishListening(
                        sendStop = false,
                        stopCapture = true,
                        keepTurnActive = true,
                        reason = if (isBluetoothMicActive()) {
                            "Mic Bluetooth sedang dipakai, rekaman dilepas sebelum pemutaran agar kualitas audio kembali"
                        } else {
                            "Server mulai berbicara, rekaman lokal dihentikan"
                        },
                    )
                }
            }

            "sentence_start" -> {
                if (text.isNotBlank()) {
                    updateState { copy(lastTtsText = text) }
                    addChatMessage(ChatRole.ASSISTANT, text)
                }
            }

            "stop" -> {
                audioEngine.finishPlayback {
                    updateState {
                        copy(
                            isAssistantSpeaking = it,
                            isTurnActive = false,
                        )
                    }
                    addLog("Pemutaran selesai")
                }
            }
        }
    }

    private fun handleSttMessage(rawText: String) {
        addLog("<= $rawText")
        val text = parseJson(rawText)?.optString("text").orEmpty()
        if (text.isNotBlank() && text != uiState.value.lastSttText) {
            // Verifikasi kata kunci untuk turn yang dipicu deteksi suara.
            // Jika transkrip tidak mengandung kata kunci -> bukan panggilan untuk kita.
            if (isWakeTurn) {
                isWakeTurn = false
                if (!matchesWakeWord(text)) {
                    addLog("Bukan kata kunci — kembali siaga")
                    updateState { copy(wakeWordStatus = "Siaga aktif") }
                    abortSpeaking()
                    return
                }
                addLog("Kata kunci cocok: $text")
                updateState { copy(wakeWordStatus = "Aktif — silakan bicara") }
            }
            updateState { copy(lastSttText = text, isTurnActive = true) }
            addChatMessage(ChatRole.USER, text)
        }
    }

    private fun ensureReadyForConversation(trigger: String) {
        when (uiState.value.connectionStatus) {
            ConnectionStatus.CONNECTED -> flushPendingActions()
            ConnectionStatus.CONNECTING,
            ConnectionStatus.FETCHING_CONFIG -> addLog("$trigger antre, menunggu koneksi")
            else -> {
                if (uiState.value.websocketUrl.isBlank()) {
                    addLog("$trigger butuh ambil konfigurasi resmi dulu")
                    fetchOfficialConfig()
                } else {
                    addLog("$trigger butuh koneksi dulu")
                    connect()
                }
            }
        }
    }

    private fun flushPendingActions() {
        if (uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            return
        }

        pendingWakePhrase?.let { phrase ->
            interruptCurrentTurn(reason = "wake_word_interrupt")
            if (!realtimeClient.sendDetectText(phrase)) {
                return
            }
            addLog("Wake word terkirim: $phrase")
            pendingWakePhrase = null
        }

        while (pendingTextPrompts.isNotEmpty()) {
            interruptCurrentTurn(reason = "text_interrupt")
            val nextPrompt = pendingTextPrompts.peekFirst() ?: break
            if (!realtimeClient.sendDetectText(nextPrompt)) {
                return
            }
            pendingTextPrompts.removeFirst()
            addLog("Pesan teks terkirim")
        }

        pendingListeningMode?.let(::startListening)
    }

    private fun interruptCurrentTurn(reason: String) {
        var interrupted = false
        if (uiState.value.isRecording) {
            finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Beralih ke input baru")
            interrupted = true
        }
        if (uiState.value.isAssistantSpeaking) {
            audioEngine.clearPlayback {
                updateState { copy(isAssistantSpeaking = it) }
            }
            updateState { copy(isAssistantSpeaking = false) }
            interrupted = true
        }
        if (interrupted && uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
            realtimeClient.sendAbort(reason)
        }
    }

    private fun clearPendingConversation() {
        pendingListeningMode = null
        pendingWakePhrase = null
        pendingTextPrompts.clear()
        updateState { copy(isTurnActive = false) }
    }

    private fun isBluetoothMicActive(): Boolean {
        return uiState.value.audioRouteStatus.contains("Mic Bluetooth")
    }

    private fun addChatMessage(role: ChatRole, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return
        }
        val timestamp = timestamp()
        updateState {
            val lastMessage = chatMessages.lastOrNull()
            if (lastMessage?.role == role && lastMessage.text == trimmed) {
                this
            } else {
                copy(
                    chatMessages = (chatMessages + ChatMessage(
                        id = System.currentTimeMillis(),
                        role = role,
                        text = trimmed,
                        timestamp = timestamp,
                    )).takeLast(100),
                )
            }
        }
    }

    private fun parseJson(rawText: String): JSONObject? {
        return runCatching { JSONObject(rawText) }.getOrNull()
    }

    private fun handleTermuxCommandResult(result: TermuxCommandResult) {
        val label = result.label.ifBlank { "Termux" }
        val exitCode = result.exitCode?.toString() ?: "?"
        val errorCode = result.errorCode?.let { ", err=$it" }.orEmpty()
        addLog("$label  selesai, exit=$exitCode$errorCode")
        if (result.stdout.isNotBlank()) {
            addLog("$label stdout: ${result.stdout.trim()}")
        }
        if (result.stderr.isNotBlank()) {
            addLog("$label stderr: ${result.stderr.trim()}")
        }
        if (!result.errorMessage.isNullOrBlank()) {
            addLog("$label error: ${result.errorMessage}")
        }
    }

    private fun loadInitialState(): UiState {
        return UiState(
            otaUrl = storedConfig.otaUrl,
            deviceId = storedConfig.deviceId,
            clientId = storedConfig.clientId,
            serialNumber = UNBURNED_SERIAL_NUMBER,
            assistantAvatarPath = storedConfig.assistantAvatarPath,
            websocketUrl = storedConfig.websocketUrl,
            authToken = storedConfig.authToken,
            protocolVersion = storedConfig.protocolVersion,
            mcpPayload = storedConfig.mcpPayload,
            activated = storedConfig.websocketUrl.isNotBlank(),
            wakeWordEnabled = storedConfig.wakeWordEnabled,
            wakeWords = storedConfig.wakeWords,
            wakeWordStatus = if (storedConfig.wakeWordEnabled) WAKE_WORD_STANDBY else WAKE_WORD_DISABLED,
            termuxEnabled = storedConfig.termuxEnabled,
            pythonPath = storedConfig.pythonPath,
            pythonScriptPath = storedConfig.pythonScriptPath,
            pythonWorkdir = storedConfig.pythonWorkdir,
            pythonRuntimeStatus = termuxRunner.statusLabel(storedConfig.termuxEnabled),
            termuxApiCommand = storedConfig.termuxApiCommand,
            termuxApiArguments = storedConfig.termuxApiArguments,
            termuxApiStatus = termuxRunner.termuxApiStatusLabel(storedConfig.termuxEnabled),
            debugLoggingEnabled = storedConfig.debugLoggingEnabled,
            debugWavDumpEnabled = storedConfig.debugWavDumpEnabled,
            vadThreshold = storedConfig.vadThreshold,
            overlayEnabled = storedConfig.overlayEnabled,
        )
    }

    private fun persist() {
        preferences.save(
            StoredConfig(
                otaUrl = uiState.value.otaUrl,
                deviceId = uiState.value.deviceId,
                clientId = uiState.value.clientId,
                assistantAvatarPath = uiState.value.assistantAvatarPath,
                websocketUrl = uiState.value.websocketUrl,
                authToken = uiState.value.authToken,
                protocolVersion = uiState.value.protocolVersion,
                mcpPayload = uiState.value.mcpPayload,
                wakeWordEnabled = uiState.value.wakeWordEnabled,
                wakeWords = uiState.value.wakeWords,
                termuxEnabled = uiState.value.termuxEnabled,
                pythonPath = uiState.value.pythonPath,
                pythonScriptPath = uiState.value.pythonScriptPath,
                pythonWorkdir = uiState.value.pythonWorkdir,
                termuxApiCommand = uiState.value.termuxApiCommand,
                termuxApiArguments = uiState.value.termuxApiArguments,
                debugLoggingEnabled = uiState.value.debugLoggingEnabled,
                debugWavDumpEnabled = uiState.value.debugWavDumpEnabled,
                vadThreshold = uiState.value.vadThreshold,
                overlayEnabled = uiState.value.overlayEnabled,
            ),
        )
    }

    private fun addLog(message: String) {
        val timestamp = timestamp()
        Log.d(LOG_TAG, "[$timestamp] $message")
        updateState {
            copy(logs = (logs + LogLine(timestamp, message)).takeLast(300))
        }
    }

    private fun updateAndPersist(update: UiState.() -> UiState) {
        updateState(update)
        persist()
    }

    private fun updateState(update: UiState.() -> UiState) {
        _uiState.update(update)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
    }
}
