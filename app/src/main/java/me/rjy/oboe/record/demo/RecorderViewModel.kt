package me.rjy.oboe.record.demo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile


class RecorderViewModel : ViewModel() {
    private var recorder: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var stopRecord = false

    @Volatile
    private var stopPlayPcm = false
    val recordingStatus = mutableStateOf(false)
    val pcmPlayingStatus = mutableStateOf(false)
    val echoCanceler = mutableStateOf(false)
    val isStereo = mutableStateOf(true)  // true为立体声,false为单声道
    val sampleRate = mutableStateOf(48000) // 采样率选项
    val isFloat = mutableStateOf(false)  // true为float格式,false为short格式

    private val currentSampleRate get() = sampleRate.value
    private val currentChannel get() = if(isStereo.value) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private val currentFormat get() = if(isFloat.value) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun startRecord(pcmPath: String) {
        if (recordingStatus.value) {
            return
        }
        recordingStatus.value = true
        Log.d(TAG, "startRecord")

        val bufferSizeInBytes = AudioRecord.getMinBufferSize(
            currentSampleRate,
            currentChannel, 
            currentFormat
        )

        recorder = AudioRecord(
            if (echoCanceler.value) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.DEFAULT,
            currentSampleRate,
            currentChannel,
            currentFormat,
            bufferSizeInBytes
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("DemoRecorder", "AudioRecord init failed")
            recordingStatus.value = false
            return
        }
        stopRecord = false

//        if (echoCanceler.value) {
//            if (AcousticEchoCanceler.isAvailable()) {
//                Log.i(TAG, "AcousticEchoCanceler.create()")
//                AcousticEchoCanceler.create(recorder!!.audioSessionId);
//            } else {
//                Log.e(TAG, "not support AcousticEchoCanceler")
//            }
//        }

        viewModelScope.launch(newSingleThreadContext("record-thread")) {
            recorder?.startRecording()

            if (File(pcmPath).exists()) {
                File(pcmPath).delete()
            }

//            playBackgroundMusic(context = context)

            val audioBuffer = ByteBuffer.allocateDirect(bufferSizeInBytes)
            val os: OutputStream = FileOutputStream(pcmPath)
            val bos = BufferedOutputStream(os)
            val dos = DataOutputStream(bos)

            try {
                while (!stopRecord) {
                    audioBuffer.clear()
                    val frameBytes = recorder?.read(audioBuffer, bufferSizeInBytes)
                    if (frameBytes != null && frameBytes > 0) {
                        if (isFloat.value) {
                            val floatBuffer = audioBuffer.asFloatBuffer()
                            for (i in 0 until frameBytes / 4) {
                                val floatValue = floatBuffer.get(i)
                                val intBits = java.lang.Float.floatToIntBits(floatValue)
                                dos.write((intBits shr 24).toByte().toInt())
                                dos.write((intBits shr 16).toByte().toInt())
                                dos.write((intBits shr 8).toByte().toInt())
                                dos.write(intBits.toByte().toInt())
                            }
                        } else {
                            for (i in 0 until frameBytes) {
                                dos.writeByte(audioBuffer[i].toInt())
                            }
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

    private fun playBackgroundMusic(context: Context) {
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

    fun playPcm(pcmPath: String) {
        Log.d(TAG, "playPcm $pcmPath")
        val bufferSizeInBytes = AudioRecord.getMinBufferSize(
            currentSampleRate,
            currentChannel,
            currentFormat
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            ).setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(currentSampleRate)
                    .setChannelMask(getOutChannel(channel = currentChannel))
                    .setEncoding(currentFormat)
                    .build()
            ).setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        stopPlayPcm = false
        pcmPlayingStatus.value = true
        viewModelScope.launch(newSingleThreadContext("play-pcm-thread")) {
            val fi = FileInputStream(pcmPath)
            val buffer = if (isFloat.value) {
                ByteArray(size = bufferSizeInBytes * 2) // float格式需要更大的buffer
            } else {
                ByteArray(size = bufferSizeInBytes)
            }
            var count: Int
            audioTrack.play()

            while (!stopPlayPcm) {
                count = fi.read(buffer)
                if (count > 0) {
                    val ret = if (isFloat.value) {
                        // float格式时需要调整写入大小
                        audioTrack.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
                    } else {
                        audioTrack.write(buffer, 0, count)
                    }
                    if (ret == AudioTrack.ERROR_INVALID_OPERATION || 
                        ret == AudioTrack.ERROR_BAD_VALUE || 
                        ret == AudioTrack.ERROR_DEAD_OBJECT) {
                        break
                    }
                } else {
                    break
                }
            }
            Log.d(TAG, "pcm play finish")
            audioTrack.release()
            withContext(Dispatchers.Main) {
                pcmPlayingStatus.value = false
            }
        }
    }

    fun stopPcm() {
        stopPlayPcm = true
    }

    private fun getOutChannel(channel: Int): Int {
        return when(channel) {
            AudioFormat.CHANNEL_IN_MONO -> AudioFormat.CHANNEL_OUT_MONO
            AudioFormat.CHANNEL_IN_STEREO -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                throw RuntimeException("channel $channel not support")
            }
        }
    }

    companion object {
        private const val TAG = "RecorderViewModel"
    }
}