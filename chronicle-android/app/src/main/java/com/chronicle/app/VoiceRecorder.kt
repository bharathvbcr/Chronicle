package com.chronicle.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records AAC/M4A voice notes to the app cache; caller copies into vault audio/yyyy/MM/.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null
    val currentFile: File? get() = outputFile

    /** Max amplitude (0..32767) since the previous call; 0 when not recording or on failure. */
    fun pollMaxAmplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    fun start(): File? {
        stopInternal(discard = true)
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            file
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            null
        }
    }

    /** Stop and return the recorded file path, or null if failed/empty. */
    fun stop(): String? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            val f = outputFile
            outputFile = null
            if (f != null && f.exists() && f.length() > 0) f.absolutePath else {
                f?.delete()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopInternal(discard = true)
            null
        }
    }

    fun cancel() {
        stopInternal(discard = true)
    }

    private fun stopInternal(discard: Boolean) {
        try {
            recorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
        } catch (_: Exception) {
        }
        recorder = null
        if (discard) {
            outputFile?.delete()
            outputFile = null
        }
    }
}
