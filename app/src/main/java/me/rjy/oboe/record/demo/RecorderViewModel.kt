package me.rjy.oboe.record.demo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile


class RecorderViewModel : ViewModel() {
    private var bufferSizeInBytes = -1
    private var recorder: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var stopRecord = false
    val recordingStatus = mutableStateOf(false)

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun startRecord(context: Context, pcmPath: String) {
        if (recordingStatus.value) {
            return
        }
        recordingStatus.value = true
        Log.d(TAG, "startRecord")

        val sampleRate = 48000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRate, channel, format)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channel,
            format,
            bufferSizeInBytes
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("DemoRecorder", "AudioRecord init failed")
            recordingStatus.value = false
            return
        }
        stopRecord = false

        if (AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "AcousticEchoCanceler.create()")
            AcousticEchoCanceler.create(recorder!!.audioSessionId);
        } else {
            Log.e(TAG, "not support AcousticEchoCanceler")
        }

        viewModelScope.launch(newSingleThreadContext("record-thread")) {
            recorder?.startRecording()

            if (File(pcmPath).exists()) {
                File(pcmPath).delete()
            }

            playMusic(context = context)

            val audioBuffer = ByteBuffer.allocateDirect(bufferSizeInBytes)
            val os: OutputStream = FileOutputStream(pcmPath)
            val bos = BufferedOutputStream(os)
            val dos = DataOutputStream(bos)

            try {
                while (!stopRecord) {
                    audioBuffer.clear()
                    val frameBytes = recorder?.read(audioBuffer, bufferSizeInBytes)
                    if (frameBytes != null && frameBytes > 0) {
                        for (i in 0..<frameBytes) {
                            dos.writeByte(audioBuffer[i].toInt())
                        }
                    }

                }
            } catch (e: Exception) {
                Log.e("DemoRecorder", "cache exception $e")
                e.printStackTrace()
            } finally {
                dos.flush()
                dos.close()
                bos.close()
                os.close()
                recorder?.release()
                mediaPlayer?.release()
                recordingStatus.value = false
            }
        }
    }

    private fun playMusic(context: Context) {
        mediaPlayer = MediaPlayer()
        mediaPlayer?.let {
            val musicFd = context.assets.openFd("1710923469779.m4a")
            it.setDataSource(musicFd.fileDescriptor, musicFd.startOffset, musicFd.length)
            it.isLooping = true
            it.prepare()
            it.start()
        }
    }

    fun stopRecord() {
        stopRecord = true
    }

    companion object {
        private const val TAG = "RecorderViewModel"
    }
}