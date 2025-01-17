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
import androidx.annotation.Keep
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
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
import java.nio.ByteOrder
import kotlin.concurrent.Volatile

class RecorderViewModel : ViewModel() {
    private var recorder: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var amplitudeCalculator: AmplitudeCalculator? = null

    @Volatile
    private var stopRecord = false

    @Volatile
    private var stopPlayPcm = false
    val recordingStatus = mutableStateOf(false)
    val pcmPlayingStatus = mutableStateOf(false)
    val echoCanceler = mutableStateOf(false)
    val isStereo = mutableStateOf(true)  // true为立体声,false为单声道
    val sampleRate = mutableIntStateOf(48000) // 采样率选项
    val isFloat = mutableStateOf(false)  // true为float格式,false为short格式
    val useOboe = mutableStateOf(false)  // true使用oboe,false使用AudioRecord
    val selectedAudioSource = mutableIntStateOf(MediaRecorder.AudioSource.DEFAULT) // 选中的音频源
    val selectedAudioApi = mutableIntStateOf(0) // 选中的AudioApi: 0=Unspecified, 1=AAudio, 2=OpenSLES

    // 波形数据
    private val _leftChannelData = mutableStateOf<List<Float>>(emptyList())
    val leftChannelData: State<List<Float>> = _leftChannelData

    private val _rightChannelData = mutableStateOf<List<Float>>(emptyList())
    val rightChannelData: State<List<Float>> = _rightChannelData

    // 波形数据的最大采样点数，由View的宽度决定
    private var maxWaveformPoints = 150

    // 更新振幅计算策略
    private fun updateAmplitudeCalculator() {
        amplitudeCalculator = when {
            isFloat.value && isStereo.value -> FloatStereoAmplitudeCalculator()
            isFloat.value && !isStereo.value -> FloatMonoAmplitudeCalculator()
            !isFloat.value && isStereo.value -> ShortStereoAmplitudeCalculator()
            else -> ShortMonoAmplitudeCalculator()
        }
    }

    // 计算一组采样的振幅
    private fun calculateAmplitude(buffer: ByteBuffer, size: Int): Pair<Float, Float?> {
        if (amplitudeCalculator == null) {
            updateAmplitudeCalculator()
        }
        return amplitudeCalculator?.calculateAmplitude(buffer, size)
            ?: Pair(0f, null)
    }

    // 音频源列表
    data class AudioSourceInfo(
        val id: Int,
        val name: String
    )

    val audioSources = mutableListOf(
        AudioSourceInfo(MediaRecorder.AudioSource.DEFAULT, "默认"),
        AudioSourceInfo(MediaRecorder.AudioSource.MIC, "麦克风"),
        AudioSourceInfo(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "语音通话"),
        AudioSourceInfo(MediaRecorder.AudioSource.VOICE_RECOGNITION, "语音识别"),
        AudioSourceInfo(MediaRecorder.AudioSource.CAMCORDER, "摄像机"),
        AudioSourceInfo(MediaRecorder.AudioSource.UNPROCESSED, "未处理")
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(AudioSourceInfo(MediaRecorder.AudioSource.VOICE_PERFORMANCE, "演出"))
        }
    }

    val recordedFilePath = mutableStateOf<String?>(null)
    val selectedDeviceId = mutableIntStateOf(0) // 选中的录音设备ID

    // 录音设备列表
    data class AudioDevice(
        val id: Int,
        val name: String,
    )
    val audioDevices = mutableStateOf<List<AudioDevice>>(emptyList())

    // 音频API列表
    data class AudioApiInfo(
        val id: Int,
        val name: String
    )

    val audioApis = listOf(
        AudioApiInfo(0, "Unspecified"),
        AudioApiInfo(1, "AAudio"),
        AudioApiInfo(2, "OpenSLES")
    )

    private val currentSampleRate get() = sampleRate.intValue
    private val currentChannel get() = if(isStereo.value) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private val currentFormat get() = if(isFloat.value) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    init {
        // 加载保存的设置
        loadSettings(App.context)
        refreshAudioDevices(App.context)
    }

    private fun loadSettings(context: Context) {
        val settings = PreferenceManager.loadSettings(context)
        useOboe.value = settings.useOboe
        isStereo.value = settings.isStereo
        sampleRate.intValue = settings.sampleRate
        isFloat.value = settings.isFloat
        echoCanceler.value = settings.echoCanceler
        selectedAudioSource.intValue = settings.audioSource
        selectedAudioApi.intValue = settings.audioApi
        updateAmplitudeCalculator()
    }

    private fun saveSettings(context: Context) {
        val settings = RecorderSettings(
            useOboe = useOboe.value,
            isStereo = isStereo.value,
            sampleRate = sampleRate.intValue,
            isFloat = isFloat.value,
            echoCanceler = echoCanceler.value,
            audioSource = selectedAudioSource.intValue,
            audioApi = selectedAudioApi.intValue
        )
        PreferenceManager.saveSettings(context, settings)
    }

    // 在参数变化时保存设置
    private fun onSettingsChanged() {
        saveSettings(App.context)
    }

    // 修改现有的状态设置方法，添加录制状态检查
    fun setUseOboe(value: Boolean) {
        if (recordingStatus.value) {
            return
        }
        useOboe.value = value
        onSettingsChanged()
    }

    fun setIsStereo(value: Boolean) {
        if (recordingStatus.value) {
            return
        }
        isStereo.value = value
        updateAmplitudeCalculator()
        onSettingsChanged()
    }

    fun setSampleRate(value: Int) {
        if (recordingStatus.value) {
            return
        }
        sampleRate.intValue = value
        onSettingsChanged()
    }

    fun setIsFloat(value: Boolean) {
        if (recordingStatus.value) {
            return
        }
        isFloat.value = value
        updateAmplitudeCalculator()
        onSettingsChanged()
    }

    fun setEchoCanceler(value: Boolean) {
        if (recordingStatus.value) {
            return
        }
        echoCanceler.value = value
        onSettingsChanged()
    }

    fun setAudioSource(value: Int) {
        if (recordingStatus.value) {
            return
        }
        selectedAudioSource.intValue = value
        onSettingsChanged()
    }

    fun setAudioApi(value: Int) {
        if (recordingStatus.value) {
            return
        }
        selectedAudioApi.intValue = value
        onSettingsChanged()
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
        if (deviceList.none { it.id == selectedDeviceId.intValue } && deviceList.isNotEmpty()) {
            selectedDeviceId.intValue = deviceList[0].id
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
        // 只在开始录音时重置波形数据
        _leftChannelData.value = emptyList()
        _rightChannelData.value = emptyList()
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
        Log.d(TAG, "startAudioRecord: bufferSize=$bufferSizeInBytes, sampleRate=$currentSampleRate")

        recorder = AudioRecord.Builder()
            .setAudioSource(selectedAudioSource.intValue)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(currentSampleRate)
                    .setChannelMask(currentChannel)
                    .setEncoding(currentFormat)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeInBytes)
            .build()

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recordingStatus.value = false
            // 初始化失败时清空波形数据
            _leftChannelData.value = emptyList()
            _rightChannelData.value = emptyList()
            return
        }
        stopRecord = false

        viewModelScope.launch(newSingleThreadContext("record-thread")) {
            Log.d(TAG, "Recording thread started")
            recorder?.startRecording()

            if (File(pcmPath).exists()) {
                File(pcmPath).delete()
            }

            val audioBuffer = ByteBuffer.allocateDirect(bufferSizeInBytes)
            val os: OutputStream = FileOutputStream(pcmPath)
            val bos = BufferedOutputStream(os)
            val dos = DataOutputStream(bos)

            try {
                var accumulatedSamples = 0
                val samplesPerUpdate = (currentSampleRate * (SAMPLE_UPDATE_PERIOD_MS / 1000.0)).toInt() // 每20ms更新一次
                Log.d(TAG, "Recording loop started: samplesPerUpdate=$samplesPerUpdate")

                while (!stopRecord) {
                    audioBuffer.clear()
                    val frameBytes = recorder?.read(audioBuffer, bufferSizeInBytes)
                    if (frameBytes != null && frameBytes > 0) {
                        // 写入文件
                        if (isFloat.value) {
                            val floatBuffer = audioBuffer.asFloatBuffer()
                            val floatArray = FloatArray(frameBytes / 4)
                            floatBuffer.get(floatArray)

                            // 写入文件
                            val byteBuffer = ByteBuffer.allocate(frameBytes)
                            byteBuffer.asFloatBuffer().put(floatArray)
                            dos.write(byteBuffer.array())

                            accumulatedSamples += floatArray.size
                        } else {
                            // 写入文件
                            for (i in 0 until frameBytes) {
                                dos.writeByte(audioBuffer[i].toInt())
                            }

                            accumulatedSamples += (frameBytes / 2)
                        }

                        // 每收集到足够的样本就更新一次波形
                        if (accumulatedSamples >= samplesPerUpdate) {
                            // 计算这一帧数据中的振幅值
                            audioBuffer.position(0)
                            val amplitudes = calculateAmplitude(audioBuffer, frameBytes)
                            
                            if (isStereo.value) {
                                // 立体声：更新左右声道数据
                                _leftChannelData.value = (listOf(amplitudes.first) + _leftChannelData.value).take(maxWaveformPoints)
                                amplitudes.second?.let { rightAmplitude ->
                                    _rightChannelData.value = (listOf(rightAmplitude) + _rightChannelData.value).take(maxWaveformPoints)
                                }
                            } else {
                                // 单声道：左右声道使用相同数据
                                _leftChannelData.value = (listOf(amplitudes.first) + _leftChannelData.value).take(maxWaveformPoints)
                                _rightChannelData.value = _leftChannelData.value
                            }
                            
                            // 重置状态
                            accumulatedSamples = 0
                        }
                    } else {
                        Log.w(TAG, "No data read from AudioRecord: frameBytes=$frameBytes")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording exception", e)
            } finally {
                Log.d(TAG, "Recording stopped")
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
        deviceId: Int,
        audioSource: Int,
        audioApi: Int,
        sampleUpdatePeriodMs: Int
    ): Boolean
    private external fun native_stop_record()

    private fun startOboeRecord(pcmPath: String) {
        stopRecord = false
        viewModelScope.launch(newSingleThreadContext("oboe-record-thread")) {
            try {
                recordingStatus.value = native_start_record(
                    pcmPath,
                    currentSampleRate,
                    isStereo.value,
                    isFloat.value,
                    selectedDeviceId.intValue,
                    selectedAudioSource.intValue,
                    selectedAudioApi.intValue,
                    SAMPLE_UPDATE_PERIOD_MS
                )
                // 初始化失败时清空波形数据
                _leftChannelData.value = emptyList()
                _rightChannelData.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Oboe record failed", e)
                recordingStatus.value = false
                // 初始化失败时清空波形数据
                _leftChannelData.value = emptyList()
                _rightChannelData.value = emptyList()
            }
        }
    }

    fun stopRecord() {
        stopRecord = true
        if (useOboe.value) {
            native_stop_record()
            recordingStatus.value = false
            // 移除停止录音时清空波形数据的代码
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
        // 设置系统音量为80%
//        val audioManager = App.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
//        val targetVolume = (maxVolume * 0.8).toInt()
//        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
//        Log.d(TAG, "max volume $maxVolume, set volume $targetVolume")

        val bufferSizeInBytes = AudioRecord.getMinBufferSize(
            currentSampleRate,
            currentChannel,
            currentFormat
        )
        val audioTrackBuilder = AudioTrack.Builder()
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
            ).setBufferSizeInBytes(bufferSizeInBytes * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val audioTrack = audioTrackBuilder.build()
        stopPlayPcm = false
        pcmPlayingStatus.value = true
        viewModelScope.launch(newSingleThreadContext("play-pcm-thread")) {
            val fi = FileInputStream(pcmPath)
            val buffer = if (isFloat.value) {
                ByteArray(size = bufferSizeInBytes)
            } else {
                ByteArray(size = bufferSizeInBytes)
            }
            var count: Int
//            audioTrack.setVolume(1f)
            audioTrack.play()

            while (!stopPlayPcm) {
                count = fi.read(buffer)
//                Log.d(TAG, "play pcm read: $count")
                if (count > 0) {
                    val ret = if (isFloat.value) {
                        // 对float格式的数据进行处理
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                        val floatBuffer = byteBuffer.asFloatBuffer()
                        val floatArray = FloatArray(count / 4)
                        floatBuffer.get(floatArray)
                        audioTrack.write(floatArray, 0, floatArray.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        audioTrack.write(buffer, 0, count)
                    }
                    if (ret == AudioTrack.ERROR_INVALID_OPERATION || 
                        ret == AudioTrack.ERROR_BAD_VALUE || 
                        ret == AudioTrack.ERROR_DEAD_OBJECT) {
                        Log.e(TAG, "AudioTrack write error: $ret")
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
        val sampleRateStr = "${sampleRate.intValue/1000}kHz"
        val formatStr = if (isFloat.value) "float" else "short"
        return "record_${channelStr}_${sampleRateStr}_${formatStr}.pcm"
    }

    // 供native层调用的方法，用于更新波形数据
    @Keep
    private fun updateWaveformFromNative(leftAmplitude: Float, rightAmplitude: Float) {
        viewModelScope.launch(Dispatchers.Main) {
            _leftChannelData.value = (listOf(leftAmplitude) + _leftChannelData.value).take(maxWaveformPoints)
            _rightChannelData.value = (listOf(rightAmplitude) + _rightChannelData.value).take(maxWaveformPoints)
        }
    }

    fun setMaxWaveformPoints(points: Int) {
        maxWaveformPoints = points
    }

    fun setSelectedDeviceId(value: Int) {
        if (recordingStatus.value) {
            return
        }
        selectedDeviceId.intValue = value
    }

    companion object {
        private const val TAG = "RecorderViewModel"
        // 声音波形展示采样周期，单位：毫秒
        const val SAMPLE_UPDATE_PERIOD_MS = 20
    }
}