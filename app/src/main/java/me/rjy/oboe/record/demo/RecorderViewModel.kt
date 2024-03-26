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

    private val sampleRate = 48000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var stopRecord = false

    @Volatile
    private var stopPlayPcm = false
    val recordingStatus = mutableStateOf(false)
    val pcmPlayingStatus = mutableStateOf(false)
    val echoCanceler = mutableStateOf(false)

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun startRecord(context: Context, pcmPath: String) {
        if (recordingStatus.value) {
            return
        }
        recordingStatus.value = true
        Log.d(TAG, "startRecord")

        val bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRate, channel, audioFormat)

        recorder = AudioRecord(
            if (echoCanceler.value) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            sampleRate,
            channel,
            audioFormat,
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

            playBackgroundMusic(context = context)

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
        val bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRate, channel, audioFormat)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            ).setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(getOutChannel(channel = channel))
                    .setEncoding(audioFormat)
                    .build()
            ).setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        stopPlayPcm = false
        pcmPlayingStatus.value = true
        viewModelScope.launch(newSingleThreadContext("play-pcm-thread")) {
            val fi = FileInputStream(pcmPath)
            val buffer = ByteArray(size = bufferSizeInBytes)
            var count: Int
            while (!stopPlayPcm) {
                count = fi.read(buffer)
                if (count > 0) {
                    audioTrack.play()
                    val ret = audioTrack.write(buffer, 0, count)
                    if (ret == AudioTrack.ERROR_INVALID_OPERATION || ret == AudioTrack.ERROR_BAD_VALUE || ret == AudioTrack.ERROR_DEAD_OBJECT) {
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