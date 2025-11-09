package me.rjy.oboe.record.demo

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rjy.oboe.record.demo.ui.WaveformPlayView
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme
import java.io.File
import java.util.*

class AudioPlayerActivity : ComponentActivity() {
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filePath = intent.getStringExtra("file_path") ?: run {
            finish()
            return
        }
        
        if (!File(filePath).exists()) {
            finish()
            return
        }
        
        enableEdgeToEdge()
        setContent {
            OboeRecordDemoTheme {
                AudioPlayerScreen(
                    filePath = filePath,
                    onBack = { finish() },
                    onMediaPlayerCreated = { mp -> mediaPlayer = mp },
                    onMediaPlayerDestroyed = { 
                        mediaPlayer?.release()
                        mediaPlayer = null
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateProgressRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        // 暂停时停止播放
        mediaPlayer?.pause()
    }
}

@Composable
private fun AudioPlayerScreen(
    filePath: String,
    onBack: () -> Unit,
    onMediaPlayerCreated: (MediaPlayer) -> Unit,
    onMediaPlayerDestroyed: () -> Unit,
) {
    val isPlaying = remember { mutableStateOf(false) }
    val currentPosition = remember { mutableStateOf(0) }
    val duration = remember { mutableStateOf(0) }
    val fileName = remember { File(filePath).name }
    var mediaPlayer: MediaPlayer? by remember { mutableStateOf(null) }
    var errorMessage: String? by remember { mutableStateOf(null) }
    val context = LocalContext.current
    
    // 波形数据状态
    val waveformData = remember { mutableStateOf<AudioDecoder.WaveformData?>(null) }
    val playbackProgress = remember { mutableFloatStateOf(0f) }
    
    
    // 加载波形数据
    LaunchedEffect(filePath) {
        try {
            val decoder = AudioDecoder()
            waveformData.value = decoder.extractWaveform(filePath)
            Log.d("AudioPlayerActivity", "Waveform data loaded: ${waveformData.value?.audioInfo}")
        } catch (e: Exception) {
            Log.e("AudioPlayerActivity", "Failed to load waveform data", e)
        }
    }
    
    // 更新播放进度
    LaunchedEffect(isPlaying.value, mediaPlayer, currentPosition.value, duration.value) {
        if (duration.value > 0) {
            playbackProgress.floatValue = currentPosition.value.toFloat() / duration.value
        }
    }

    // 初始化 MediaPlayer
    LaunchedEffect(filePath) {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener { preparedMp ->
                    duration.value = preparedMp.duration
                    preparedMp.start()
                    isPlaying.value = true
                }
                setOnCompletionListener {
                    isPlaying.value = false
                    currentPosition.value = 0
                }
                setOnErrorListener { _, what, extra ->
                    errorMessage = context.getString(R.string.audio_error_playback, what, extra)
                    false
                }
                prepareAsync()
            }
            mediaPlayer = mp
            onMediaPlayerCreated(mp)
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.audio_error_cannot_play, e.message ?: "")
            Log.e("AudioPlayerActivity", "Failed to initialize MediaPlayer", e)
        }
    }

    // 更新播放进度
    LaunchedEffect(isPlaying.value, mediaPlayer) {
        while (isActive) {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    currentPosition.value = mp.currentPosition
                }
            }
            delay(100) // 每100ms更新一次
        }
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            onMediaPlayerDestroyed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 自定义顶部栏（避免使用实验性 API）
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(id = R.string.common_back)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // 内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 错误信息
            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // 播放/暂停按钮
            IconButton(
                onClick = {
                    mediaPlayer?.let { mp ->
                        if (isPlaying.value) {
                            mp.pause()
                            isPlaying.value = false
                        } else {
                            mp.start()
                            isPlaying.value = true
                        }
                    }
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying.value) stringResource(id = R.string.common_pause) else stringResource(id = R.string.common_play),
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 波形显示
            waveformData.value?.let { data ->
                // 创建 PlaybackWaveform 数据
                val playbackWaveform = RecorderViewModel.PlaybackWaveform(
                    leftChannel = data.leftChannel,
                    rightChannel = data.rightChannel,
                    totalSamples = data.audioInfo.totalSamples.toInt()
                )
                
                WaveformPlayView(
                    waveform = playbackWaveform,
                    progress = playbackProgress.floatValue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 播放进度
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 进度条
                Slider(
                    value = if (duration.value > 0) currentPosition.value.toFloat() / duration.value else 0f,
                    onValueChange = { newValue ->
                        mediaPlayer?.let { mp ->
                            val newPosition = (newValue * duration.value).toInt()
                            mp.seekTo(newPosition)
                            currentPosition.value = newPosition
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = duration.value > 0
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 时间显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentPosition.value),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatTime(duration.value),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * 格式化时间为 mm:ss 格式
 */
private fun formatTime(milliseconds: Int): String {
    if (milliseconds < 0) return "00:00"
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
