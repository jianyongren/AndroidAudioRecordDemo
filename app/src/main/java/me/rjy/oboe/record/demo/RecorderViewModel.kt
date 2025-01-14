package me.rjy.oboe.record.demo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val useOboe = mutableStateOf(false)  // true使用oboe,false使用AudioRecord

    val recordedFilePath = mutableStateOf<String?>(null)
    val selectedDeviceId = mutableStateOf(0) // 选中的录音设备ID

    // 录音设备列表
    data class AudioDevice(
        val id: Int,
        val name: String,
    )
    val audioDevices = mutableStateOf<List<AudioDevice>>(emptyList())

    private val currentSampleRate get() = sampleRate.value
    private val currentChannel get() = if(isStereo.value) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private val currentFormat get() = if(isFloat.value) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    init {
        refreshAudioDevices(App.context)
    }

    fun refreshAudioDevices(context: Context) {
        val deviceList = mutableListOf<AudioDevice>()

        // 添加默认设备
        deviceList.add(AudioDevice(
            id = 0,  // 对应 oboe::kUnspecified
            name = "默认设备",
        ))

        if (useOboe.value) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            // 添加其他设备
            devices.forEach { device ->
                deviceList.add(AudioDevice(
                    id = device.id,
                    name = getInputDeviceName(device),
                ))
            }
        }

        audioDevices.value = deviceList
        // 如果当前选择的设备不在列表中，选择第一个设备
        if (deviceList.none { it.id == selectedDeviceId.value } && deviceList.isNotEmpty()) {
            selectedDeviceId.value = deviceList[0].id
        }
    }

    private fun getInputDeviceName(device: AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address
        } else {
            ""
        }
        val name = when(device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙设备"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM调频"
            AudioDeviceInfo.TYPE_TELEPHONY -> "电话通话"
            else -> "其他${device.type}"
        }
        return "${device.productName}-$name$address"
    }

    @SuppressLint("MissingPermission")
    fun startRecord(pcmPath: String) {
        if (recordingStatus.value) {
            return
        }
        recordingStatus.value = true
        recordedFilePath.value = pcmPath
        Log.d(TAG, "startRecord")

        if (useOboe.value) {
            startOboeRecord(pcmPath)
        } else {
            startAudioRecord(pcmPath)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(pcmPath: String) {
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

        viewModelScope.launch(newSingleThreadContext("record-thread")) {
            recorder?.startRecording()

            if (File(pcmPath).exists()) {
                File(pcmPath).delete()
            }

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

    private external fun native_start_record(
        path: String,
        sampleRate: Int,
        isStereo: Boolean,
        isFloat: Boolean,
        deviceId: Int
    )
    private external fun native_stop_record()

    private fun startOboeRecord(pcmPath: String) {
        stopRecord = false
        viewModelScope.launch(newSingleThreadContext("oboe-record-thread")) {
            try {
                native_start_record(
                    pcmPath,
                    currentSampleRate,
                    isStereo.value,
                    isFloat.value,
                    selectedDeviceId.value
                )
            } catch (e: Exception) {
                Log.e(TAG, "Oboe record failed", e)
            } finally {
                recordingStatus.value = false
            }
        }
    }

    fun stopRecord() {
        stopRecord = true
        if (useOboe.value) {
            native_stop_record()
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

    fun generateRecordFileName(): String {
        val channelStr = if (isStereo.value) "stereo" else "mono"
        val sampleRateStr = "${sampleRate.value/1000}kHz"
        val formatStr = if (isFloat.value) "float" else "short"
        return "record_${channelStr}_${sampleRateStr}_${formatStr}.pcm"
    }

    companion object {
        private const val TAG = "RecorderViewModel"
    }
}