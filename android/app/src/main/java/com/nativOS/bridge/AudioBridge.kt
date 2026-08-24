package com.nativOS.bridge

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject

/**
 * Audio bridge — handles audio input (microphone) and output (speaker)
 * between Android's AudioManager and Linux's PipeWire/PulseAudio.
 *
 * PCM audio is streamed over a separate socket for performance.
 * This handler manages the control plane (volume, routing, start/stop).
 */
class AudioBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.Audio"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false

    var onAudioData: ((ByteArray, Int) -> Unit)? = null

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            "start_recording" -> handleStartRecording(request)
            "stop_recording" -> handleStopRecording(request)
            "start_playback" -> handleStartPlayback(request)
            "stop_playback" -> handleStopPlayback(request)
            "set_volume" -> handleSetVolume(request)
            "get_volume" -> handleGetVolume(request)
            "get_audio_info" -> handleGetAudioInfo(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown audio action: ${request.action}")
            )
        }
    }

    @Suppress("MissingPermission")
    private fun handleStartRecording(request: BridgeRequest): String {
        if (isRecording) {
            return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("message", "Already recording"))
        }

        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize * 2
            )

            audioRecord?.startRecording()
            isRecording = true

            // Read audio in background thread and forward to callback
            Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        onAudioData?.invoke(buffer.copyOf(read), read)
                    }
                }
            }.start()

            Log.i(TAG, "Audio recording started (${SAMPLE_RATE}Hz)")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("sample_rate", SAMPLE_RATE).put("channels", 1).put("format", "pcm_16bit"))
        } catch (e: SecurityException) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "RECORD_AUDIO permission not granted"))
        }
    }

    private fun handleStopRecording(request: BridgeRequest): String {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Audio recording stopped")
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }

    private fun handleStartPlayback(request: BridgeRequest): String {
        // Playback is managed by PulseAudio/PipeWire inside the chroot
        // This is a control endpoint for routing audio to specific outputs
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
            JSONObject().put("message", "Audio playback managed by Linux audio stack"))
    }

    private fun handleStopPlayback(request: BridgeRequest): String {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }

    private fun handleSetVolume(request: BridgeRequest): String {
        val stream = request.params.optString("stream", "media")
        val level = request.params.optInt("level", -1)

        val streamType = when (stream) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        return try {
            if (level >= 0) {
                audioManager.setStreamVolume(streamType, level, 0)
            }
            val currentLevel = audioManager.getStreamVolume(streamType)
            val maxLevel = audioManager.getStreamMaxVolume(streamType)

            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("stream", stream).put("level", currentLevel).put("max", maxLevel))
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to set volume: ${e.message}"))
        }
    }

    private fun handleGetVolume(request: BridgeRequest): String {
        val volumes = JSONObject()
        mapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "call" to AudioManager.STREAM_VOICE_CALL,
        ).forEach { (name, streamType) ->
            volumes.put(name, JSONObject().apply {
                put("level", audioManager.getStreamVolume(streamType))
                put("max", audioManager.getStreamMaxVolume(streamType))
            })
        }
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, volumes)
    }

    private fun handleGetAudioInfo(request: BridgeRequest): String {
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, JSONObject().apply {
            put("ringer_mode", when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            put("music_active", audioManager.isMusicActive)
            put("speaker_on", audioManager.isSpeakerphoneOn)
            put("bluetooth_sco", audioManager.isBluetoothScoOn)
        })
    }

    fun cleanup() {
        isRecording = false
        isPlaying = false
        audioRecord?.release()
        audioTrack?.release()
    }
}
