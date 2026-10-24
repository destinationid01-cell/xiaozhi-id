package me.xiaozhi.androidclient.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

private const val RESTART_DELAY_MS = 650L
private const val BUSY_RESTART_DELAY_MS = 1_400L
private const val DETECTION_COOLDOWN_MS = 2_500L

/**
 * Deteksi kata kunci via SpeechRecognizer (on-device/Google) dengan
 * beep dimatikan (mute stream audio selama mendengarkan).
 *
 * Berbeda dengan SmartWakeListener (deteksi energi suara saja), class ini
 * benar-benar mengenali kata kunci secara lokal SEBELUM audio dikirim ke
 * server — sehingga tidak ada turn palsu yang terlanjur diproses server.
 */
class WakeWordRecognizer(
    context: Context,
    private val onWakeWordDetected: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private data class WakeWord(
        val displayText: String,
        val normalizedText: String,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var configuredWakeWords: List<WakeWord> = emptyList()
    private var shouldListen: Boolean = false
    private var released: Boolean = false
    private var lastDetectedAtMs: Long = 0L
    private var lastStatus: String = ""
    private var muted: Boolean = false

    fun start(wakeWords: String) {
        if (released) {
            return
        }

        val parsedWakeWords = wakeWords
            .split(',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { WakeWord(displayText = it, normalizedText = normalizeText(it)) }
            .filter { it.normalizedText.isNotBlank() }
            .distinctBy(WakeWord::normalizedText)

        if (parsedWakeWords.isEmpty()) {
            shouldListen = false
            stop(updateStatus = false)
            publishStatus("Isi kata kunci dulu di pengaturan")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            shouldListen = false
            publishStatus("Layanan pengenalan suara tidak tersedia")
            return
        }

        configuredWakeWords = parsedWakeWords
        shouldListen = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post(::startListeningInternal)
    }

    fun stop(updateStatus: Boolean = false) {
        shouldListen = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        unmute()
        if (updateStatus) {
            publishStatus("Nonaktif")
        }
    }

    fun release() {
        released = true
        stop(updateStatus = false)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /** Matikan beep SpeechRecognizer dengan mute stream audio. */
    private fun mute() {
        if (muted) return
        muted = true
        // Beep pengenalan suara biasanya di STREAM_MUSIC / STREAM_SYSTEM.
        // Kita mute selama fase dengar kata kunci saja.
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE, 0
            )
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_SYSTEM,
                AudioManager.ADJUST_MUTE, 0
            )
        }
    }

    private fun unmute() {
        if (!muted) return
        muted = false
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE, 0
            )
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_SYSTEM,
                AudioManager.ADJUST_UNMUTE, 0
            )
        }
    }

    private fun startListeningInternal() {
        if (!shouldListen || released) {
            return
        }
        val recognizer = ensureRecognizer() ?: return
        try {
            mute()
            publishStatus("Mendengarkan kata kunci…")
            recognizer.cancel()
            recognizer.startListening(buildIntent())
        } catch (error: Exception) {
            unmute() // Gagal mulai (mis. mic sibuk dipakai sesi) — jangan biarkan mute nyangkut
            onError("Gagal memulai pendengar kata kunci: ${error.message.orEmpty()}")
            scheduleRestart(BUSY_RESTART_DELAY_MS)
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) {
            return speechRecognizer
        }
        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
                speechRecognizer = it
            }
        }.getOrElse { error ->
            onError("Gagal membuat speech recognizer: ${error.message.orEmpty()}")
            null
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!shouldListen || released) {
            return
        }
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(::startListeningInternal, delayMs)
    }

    private fun handleRecognitionResults(results: Bundle?) {
        val matches = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val matchedPhrase = matches.firstNotNullOfOrNull(::matchWakeWord)
        if (matchedPhrase != null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDetectedAtMs < DETECTION_COOLDOWN_MS) {
                scheduleRestart()
                return
            }
            lastDetectedAtMs = now
            shouldListen = false
            speechRecognizer?.cancel()
            unmute() // Kembalikan suara sebelum sesi percakapan (agar TTS terdengar)
            publishStatus("Terdeteksi: $matchedPhrase")
            onWakeWordDetected(matchedPhrase)
        } else if (matches.isNotEmpty()) {
            publishStatus("Siaga — menunggu kata kunci")
            scheduleRestart()
        }
    }

    private fun matchWakeWord(text: String): String? {
        val normalized = normalizeText(text)
        if (normalized.isBlank()) {
            return null
        }
        return configuredWakeWords.firstOrNull { wakeWord ->
            normalized.contains(wakeWord.normalizedText)
        }?.displayText
    }

    private fun normalizeText(text: String): String {
        return text
            .lowercase(Locale("id", "ID"))
            .filter(Char::isLetterOrDigit)
    }

    private fun publishStatus(status: String) {
        if (lastStatus == status) {
            return
        }
        lastStatus = status
        onStatusChanged(status)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            publishStatus("Mendengarkan kata kunci…")
        }

        override fun onBeginningOfSpeech() {
            publishStatus("Ada suara — memeriksa…")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            publishStatus("Mengenali…")
        }

        override fun onError(error: Int) {
            if (!shouldListen || released) {
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    publishStatus("Siaga — menunggu kata kunci")
                    scheduleRestart(RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Mic kemungkinan dipakai sesi xiaozhi — unmute agar TTS terdengar,
                    // dan tunda retry lebih lama (sesi mungkin masih berjalan).
                    unmute()
                    publishStatus("Layanan sibuk, mencoba lagi…")
                    scheduleRestart(5_000L)
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    publishStatus("Layanan suara tidak tersedia")
                    scheduleRestart(BUSY_RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onError("Butuh izin mikrofon untuk siaga suara")
                }

                else -> {
                    publishStatus("Siaga — menunggu kata kunci")
                    scheduleRestart(BUSY_RESTART_DELAY_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            handleRecognitionResults(results)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            handleRecognitionResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
