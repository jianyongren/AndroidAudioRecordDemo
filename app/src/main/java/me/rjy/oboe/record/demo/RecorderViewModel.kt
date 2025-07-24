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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import me.rjy.oboe.record.demo.OboePlayer.OnPlaybackCompleteListener
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile
import kotlin.math.abs

class RecorderViewModel : ViewModel() {
    private var recorder: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var amplitudeCalculator: AmplitudeCalculator? = null
    private var oboePlayer: OboePlayer? = null

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
    val useOboe = mutableStateOf(true)  // true使用oboe,false使用AudioRecord
    val useOboePlayback = mutableStateOf(true)  // true使用oboe播放,false使用AudioTrack播放
    val selectedAudioSource = mutableIntStateOf(MediaRecorder.AudioSource.DEFAULT) // 选中的音频源
    val selectedAudioApi = mutableIntStateOf(0) // 选中的AudioApi: 0=Unspecified, 1=AAudio, 2=OpenSLES

    // 波形数据
    private val _leftChannelBuffer = WaveformBuffer(150)
    val leftChannelBuffer: WaveformBuffer = _leftChannelBuffer

    private val _rightChannelBuffer = WaveformBuffer(150)
    val rightChannelBuffer: WaveformBuffer = _rightChannelBuffer

    // 波形数据的最大采样点数，由View的宽度决定
    private var maxWaveformPoints = 150

    // 添加播放波形相关的状态
    data class PlaybackWaveform(
        val leftChannel: List<Float>,
        val rightChannel: List<Float>?,  // 单声道时为null
        val totalSamples: Int
    )
    val playbackWaveform = mutableStateOf<PlaybackWaveform?>(null)
    val playbackProgress = mutableFloatStateOf(0f)  // 0.0 ~ 1.0

    // 更新振幅计算策略
    private fun updateAmplitudeCalculator() {
        amplitudeCalculator = when {
            isFloat.value && isStereo.value -> FloatStereoAmplitudeCalculator(samplesPerUpdate)
            isFloat.value && !isStereo.value -> FloatMonoAmplitudeCalculator(samplesPerUpdate)
            !isFloat.value && isStereo.value -> ShortStereoAmplitudeCalculator(samplesPerUpdate)
            else -> ShortMonoAmplitudeCalculator(samplesPerUpdate)
        }
    }

    // 计算一组采样的振幅
    private fun calculateAmplitude(buffer: ByteBuffer, size: Int, callback: (Float, Float?)->Unit) {
        if (amplitudeCalculator == null) {
            updateAmplitudeCalculator()
        }
        amplitudeCalculator?.calculateAmplitude(buffer, size, callback)
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

    // 添加 PCM 文件列表状态
    data class PcmFileInfo(
        val file: File,
        val name: String,
        val lastModified: Long
    )
    val pcmFileList = mutableStateOf<List<PcmFileInfo>>(emptyList())
    
    // 添加编辑模式状态
    val isEditMode = mutableStateOf(false)
    val selectedFiles = mutableStateOf<Set<File>>(emptySet())

    private val currentSampleRate get() = sampleRate.intValue
    private val currentChannel get() = if(isStereo.value) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private val currentFormat get() = if(isFloat.value) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    // 添加播放参数数据类
    data class PlaybackParams(
        val isStereo: Boolean,
        val sampleRate: Int,
        val isFloat: Boolean
    )

    init {
        // 加载保存的设置
        loadSettings(App.context)
        refreshAudioDevices(App.context)
    }

    private fun loadSettings(context: Context) {
        val settings = PreferenceManager.loadSettings(context)
        useOboe.value = settings.useOboe
        useOboePlayback.value = settings.useOboePlayback
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
            useOboePlayback = useOboePlayback.value,
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

    fun setUseOboePlayback(value: Boolean) {
        if (pcmPlayingStatus.value) {
            return
        }
        useOboePlayback.value = value
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
        _leftChannelBuffer.clear()
        _rightChannelBuffer.clear()
        recordingStatus.value = true
        recordedFilePath.value = pcmPath
        Log.d(TAG, "startRecord")

        if (useOboe.value) {
            startOboeRecord(pcmPath)
        } else {
            startAudioRecord(pcmPath)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
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
            _leftChannelBuffer.clear()
            _rightChannelBuffer.clear()
            return
        }
        stopRecord = false

        // 创建一个单线程调度器并设置线程优先级
        val singleThreadDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply {
                name = "RecordThread"
                priority = Thread.MAX_PRIORITY // 设置线程优先级为最高
            }
        }.asCoroutineDispatcher()

        viewModelScope.launch(/*newSingleThreadContext("record-thread")*/) {
            withContext(singleThreadDispatcher) {
                Log.d(TAG, "Recording thread started")
                recorder?.startRecording()

                if (File(pcmPath).exists()) {
                    File(pcmPath).delete()
                }

                // 必须用DirectBuffer，写文件时用get拷贝数据
                val audioBuffer = ByteBuffer.allocateDirect(bufferSizeInBytes)
                val byteArray = ByteArray(bufferSizeInBytes) // 循环外分配
                val os: OutputStream = FileOutputStream(pcmPath)
                val bos = BufferedOutputStream(os)
                val dos = DataOutputStream(bos)

                try {
                    Log.d(TAG, "Recording loop started: samplesPerUpdate=$samplesPerUpdate")

                    while (!stopRecord) {
                        audioBuffer.clear() // 每次读取前清空buffer
                        val frameBytes = recorder?.read(audioBuffer, bufferSizeInBytes)
                        if (frameBytes != null && frameBytes > 0) {
                            audioBuffer.position(0)
                            audioBuffer.get(byteArray, 0, frameBytes)
                            dos.write(byteArray, 0, frameBytes)
                            // 计算这一帧数据中的振幅值
                            audioBuffer.position(0)
                            calculateAmplitude(audioBuffer, frameBytes) { leftAmplitude, rightAmplitude ->
                                _leftChannelBuffer.write(leftAmplitude)
                                if (rightAmplitude != null) {
                                    _rightChannelBuffer.write(rightAmplitude)
                                }
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
        }.invokeOnCompletion {
            singleThreadDispatcher.close()
        }
    }

    private val samplesPerUpdate: Int
        get() = (currentSampleRate * (SAMPLE_UPDATE_PERIOD_MS / 1000.0)).toInt()

    private external fun native_start_record(
        path: String,
        sampleRate: Int,
        isStereo: Boolean,
        isFloat: Boolean,
        deviceId: Int,
        audioSource: Int,
        audioApi: Int,
    ): Boolean
    private external fun native_stop_record()

    @OptIn(DelicateCoroutinesApi::class)
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
                )
                // 初始化失败时清空波形数据
                _leftChannelBuffer.clear()
                _rightChannelBuffer.clear()
            } catch (e: Exception) {
                Log.e(TAG, "Oboe record failed", e)
                recordingStatus.value = false
                // 初始化失败时清空波形数据
                _leftChannelBuffer.clear()
                _rightChannelBuffer.clear()
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

    // 从文件名解析播放参数
    private fun parsePlaybackParams(fileName: String): PlaybackParams {
        // 默认参数
        var isStereo = false
        var sampleRate = 48000
        var isFloat = false

        // 解析文件名中的参数
        fileName.split("_").forEach { part ->
            when {
                part == "stereo" -> isStereo = true
                part == "mono" -> isStereo = false
                part == "float" -> isFloat = true
                part == "short" -> isFloat = false
                part.endsWith("Hz") -> {
                    val rate = part.replace("Hz", "").toIntOrNull()
                    if (rate != null) {
                        sampleRate = rate
                    }
                }
            }
        }

        return PlaybackParams(isStereo, sampleRate, isFloat)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun playPcm(pcmPath: String) {
        Log.d(TAG, "playPcm $pcmPath")

        // 从文件名解析播放参数
        val fileName = File(pcmPath).name
        val playbackParams = parsePlaybackParams(fileName)
        
        // 加载波形数据
        loadWaveformFromPcm(pcmPath, playbackParams)
        playbackProgress.floatValue = 0f

        if (useOboePlayback.value) {
            startOboePlayback(pcmPath, playbackParams)
        } else {
            startAudioTrackPlayback(pcmPath, playbackParams)
        }
    }

    private fun startOboePlayback(pcmPath: String, playbackParams: PlaybackParams) {
        stopPlayPcm = false
        pcmPlayingStatus.value = true
        
        // 创建新的OboePlayer实例
        oboePlayer = OboePlayer(
            filePath = pcmPath,
            sampleRate = playbackParams.sampleRate,
            isStereo = playbackParams.isStereo,
            isFloat = playbackParams.isFloat,
            audioApi = selectedAudioApi.intValue
        ).apply {
            // 设置播放完成回调
            setOnPlaybackCompleteListener(object : OnPlaybackCompleteListener {
                override fun onPlaybackComplete() {
                    viewModelScope.launch(Dispatchers.Main) {
                        pcmPlayingStatus.value = false
                        playbackWaveform.value = null
                        playbackProgress.floatValue = 0f
                        oboePlayer?.release()
                        oboePlayer = null
                    }
                }
            })
        }

        // 开始播放
        try {
            val success = oboePlayer?.start() ?: false
            if (!success) {
                Log.e(TAG, "Failed to start Oboe playback")
                pcmPlayingStatus.value = false
                playbackWaveform.value = null
                playbackProgress.floatValue = 0f
                oboePlayer?.release()
                oboePlayer = null
            } else {
                // 启动进度更新协程
                viewModelScope.launch {
                    while (pcmPlayingStatus.value) {
                        oboePlayer?.let { player ->
                            playbackProgress.floatValue = player.getPlaybackProgress()
                        }
                        delay(20) // 每100毫秒更新一次
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Oboe playback failed", e)
            pcmPlayingStatus.value = false
            playbackWaveform.value = null
            playbackProgress.floatValue = 0f
            oboePlayer?.release()
            oboePlayer = null
        }
    }

    private fun stopPlayback() {
        stopPlayPcm = true
        oboePlayer?.stop()
        oboePlayer?.release()
        oboePlayer = null
        pcmPlayingStatus.value = false
        playbackWaveform.value = null
        playbackProgress.floatValue = 0f
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startAudioTrackPlayback(pcmPath: String, playbackParams: PlaybackParams) {
        val bufferSizeInBytes = AudioRecord.getMinBufferSize(
            playbackParams.sampleRate,
            if (playbackParams.isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
            if (playbackParams.isFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            ).setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackParams.sampleRate)
                    .setChannelMask(getOutChannel(
                        if (playbackParams.isStereo) AudioFormat.CHANNEL_IN_STEREO 
                        else AudioFormat.CHANNEL_IN_MONO
                    ))
                    .setEncoding(
                        if (playbackParams.isFloat) AudioFormat.ENCODING_PCM_FLOAT 
                        else AudioFormat.ENCODING_PCM_16BIT
                    )
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
            val buffer = if (playbackParams.isFloat) {
                ByteArray(size = bufferSizeInBytes)
            } else {
                ByteArray(size = bufferSizeInBytes)
            }
            var count: Int
            var totalBytesRead = 0L
            val totalBytes = File(pcmPath).length()
            audioTrack.play()

            while (!stopPlayPcm) {
                count = fi.read(buffer)
                if (count > 0) {
                    totalBytesRead += count
                    // 更新播放进度
                    playbackProgress.floatValue = totalBytesRead.toFloat() / totalBytes
                    
                    val ret = if (playbackParams.isFloat) {
                        // 直接读取float格式的数据
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, count)
                            .order(ByteOrder.LITTLE_ENDIAN)
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
                // 清空波形数据
                playbackWaveform.value = null
                playbackProgress.floatValue = 0f
            }
        }
    }

    fun stopPcm() {
        stopPlayPcm = true
        if (oboePlayer != null) {
            stopPlayback()
        }
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
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val channelStr = if (isStereo.value) "stereo" else "mono"
        val sampleRateStr = "${sampleRate.intValue}Hz"
        val formatStr = if (isFloat.value) "float" else "short"
        val recorderStr = if (useOboe.value) "oboe" else "audiorecord"
        val parts = mutableListOf(recorderStr, channelStr, sampleRateStr, formatStr)

        // 只有在非默认值时才添加音频API
        if (selectedAudioApi.intValue != 0) {
            val apiName = audioApis.find { it.id == selectedAudioApi.intValue }?.name ?: "Unknown"
            parts.add(apiName)
        }

        // 只有在非默认值时才添加音频源
        if (selectedAudioSource.intValue != MediaRecorder.AudioSource.DEFAULT) {
            val sourceName = audioSources.find { it.id == selectedAudioSource.intValue }?.name ?: "Unknown"
            parts.add(sourceName)
        }

        // 只有在非默认值时才添加录音设备
        if (selectedDeviceId.intValue != 0) {
            val deviceName = audioDevices.value.find { it.id == selectedDeviceId.intValue }?.name ?: "Unknown"
            parts.add(deviceName)
        }

        // 添加日期到最后
        parts.add(dateStr)

        return parts.joinToString("_") + ".pcm"
    }

    // 供native层调用的方法，用于处理音频数据
    @Keep
    private fun onAudioData(audioData: ByteArray, size: Int) {
        val buffer = ByteBuffer.wrap(audioData)
        calculateAmplitude(buffer, size) { leftAmplitude, rightAmplitude ->
            viewModelScope.launch(Dispatchers.Main) {
                _leftChannelBuffer.write(leftAmplitude)
                if (rightAmplitude != null) {
                    _rightChannelBuffer.write(rightAmplitude)
                } else {
                    _rightChannelBuffer.write(leftAmplitude)  // 单声道时左右声道使用相同数据
                }
            }
        }
    }

    fun setMaxWaveformPoints(points: Int) {
        _leftChannelBuffer.resize(points)
        _rightChannelBuffer.resize(points)
    }

    fun setSelectedDeviceId(value: Int) {
        if (recordingStatus.value) {
            return
        }
        selectedDeviceId.intValue = value
    }

    // 刷新 PCM 文件列表
    fun refreshPcmFileList(context: Context) {
        val filesDir = context.filesDir
        val files = filesDir.listFiles { file ->
            file.isFile && file.name.endsWith(".pcm")
        } ?: emptyArray()
        
        pcmFileList.value = files.map { file ->
            PcmFileInfo(
                file = file,
                name = file.name,
                lastModified = file.lastModified()
            )
        }.sortedByDescending { it.lastModified }
    }

    // 切换编辑模式
    fun toggleEditMode() {
        isEditMode.value = !isEditMode.value
        if (!isEditMode.value) {
            // 退出编辑模式时清空选中状态
            selectedFiles.value = emptySet()
        }
    }

    // 切换文件选中状态
    fun toggleFileSelection(file: File) {
        val currentSelected = selectedFiles.value.toMutableSet()
        if (currentSelected.contains(file)) {
            currentSelected.remove(file)
        } else {
            currentSelected.add(file)
        }
        selectedFiles.value = currentSelected
    }

    // 删除选中的文件
    fun deleteSelectedFiles(context: Context, onAllFilesDeleted: () -> Unit) {
        selectedFiles.value.forEach { file ->
            file.delete()
        }
        // 刷新文件列表
        refreshPcmFileList(context)
        // 清空选中状态并退出编辑模式
        selectedFiles.value = emptySet()
        isEditMode.value = false
        
        // 如果文件列表为空，调用回调
        if (pcmFileList.value.isEmpty()) {
            onAllFilesDeleted()
        }
    }

    // 从PCM文件加载波形数据
    private fun loadWaveformFromPcm(pcmPath: String, playbackParams: PlaybackParams) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(pcmPath)
                val totalBytes = file.length()
                val bytesPerSample = if (playbackParams.isFloat) 4 else 2
                val channelCount = if (playbackParams.isStereo) 2 else 1
                val totalSamples = (totalBytes / (bytesPerSample * channelCount)).toInt()
                
                // 每个像素对应的采样点数
                val samplesPerPixel = (totalSamples / MAX_WAVEFORM_POINTS).coerceAtLeast(1)
                val leftChannel = mutableListOf<Float>()
                val rightChannel = if (playbackParams.isStereo) mutableListOf<Float>() else null

                // 创建合适的振幅计算器
                val amplitudeCalculator = when {
                    playbackParams.isFloat && playbackParams.isStereo -> FloatStereoAmplitudeCalculator(samplesPerPixel)
                    playbackParams.isFloat && !playbackParams.isStereo -> FloatMonoAmplitudeCalculator(samplesPerPixel)
                    !playbackParams.isFloat && playbackParams.isStereo -> ShortStereoAmplitudeCalculator(samplesPerPixel)
                    else -> ShortMonoAmplitudeCalculator(samplesPerPixel)
                }
                
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(samplesPerPixel * bytesPerSample * channelCount)
                    
                    while (true) {
                        val bytesRead = fis.read(buffer)
                        if (bytesRead <= 0) break
                        
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                        amplitudeCalculator.calculateAmplitude(byteBuffer, bytesRead) { left, right ->
                            leftChannel.add(left)
                            right?.let { rightChannel?.add(it) }
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    playbackWaveform.value = PlaybackWaveform(
                        leftChannel = leftChannel,
                        rightChannel = rightChannel,
                        totalSamples = totalSamples
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading waveform", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        oboePlayer?.release()
        oboePlayer = null
    }

    companion object {
        private const val TAG = "RecorderViewModel"
        // 声音波形展示采样周期，单位：毫秒
        const val SAMPLE_UPDATE_PERIOD_MS = 10
        // 最大波形点数
        const val MAX_WAVEFORM_POINTS = 1000
    }
}