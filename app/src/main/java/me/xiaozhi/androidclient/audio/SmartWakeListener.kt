package me.xiaozhi.androidclient.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Pendeteksi suara mandiri untuk "siaga selalu".
 *
 * Cara kerja: loop AudioRecord ringan yang hanya menghitung energi (RMS)
 * tiap frame. Tidak memakai layanan speech Google sehingga:
 * - tidak ada bunyi beep,
 * - ikut prioritas mic aplikasi (perbaikan mic),
 * - murah (tanpa jaringan, tanpa ML).
 *
 * Ketika energi suara melewati ambang beberapa frame berturut-turut,
 * [onSpeechDetected] dipanggil. Penentuan kata kunci dilakukan di level
 * ViewModel via transkrip server (lebih cerdas daripada cocok-teks lokal).
 */
class SmartWakeListener(
    private val context: Context,
    private val onSpeechDetected: () -> Unit,
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 960 // 60ms @ 16kHz
        private const val SPEECH_RMS_THRESHOLD = 900.0
        private const val SPEECH_FRAMES_REQUIRED = 6 // ~360ms suara kontinyu
        private const val COOLDOWN_MS = 3_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var listenJob: Job? = null
    private var lastTriggerMs = 0L
    @Volatile private var paused = false

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun start() {
        if (listenJob != null) return
        listenJob = scope.launch {
            val audioRecord = createAudioRecord() ?: return@launch
            try {
                audioRecord.startRecording()
                val frame = ShortArray(FRAME_SIZE)
                var speechFrames = 0
                while (isActive) {
                    val read = audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    if (paused) {
                        speechFrames = 0
                        continue
                    }
                    val rms = calculateRms(frame, read)
                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        speechFrames++
                    } else {
                        speechFrames = 0
                    }
                    if (speechFrames >= SPEECH_FRAMES_REQUIRED) {
                        speechFrames = 0
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerMs >= COOLDOWN_MS) {
                            lastTriggerMs = now
                            onSpeechDetected()
                            // Jeda sejenak agar tidak trigger beruntun dari ucapan yang sama
                            kotlinx.coroutines.delay(1_500L)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { audioRecord.stop() }
                audioRecord.release()
            }
        }
    }

    suspend fun stop() {
        listenJob?.cancelAndJoin()
        listenJob = null
    }

    private fun createAudioRecord(): AudioRecord? {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            FRAME_SIZE * 8,
        )
        // VOICE_COMMUNICATION cenderung memilih routing mic yang benar di HP multi-mic
        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in candidates) {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    applyPreferredInputDevice(record)
                    return record
                }
                record.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun applyPreferredInputDevice(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // Sama seperti perbaikan mic: kabel > bluetooth > default sistem
        val wired = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val bt = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val chosen = wired ?: bt ?: return
        runCatching { record.preferredDevice = chosen }
    }

    private fun calculateRms(frame: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }
}
