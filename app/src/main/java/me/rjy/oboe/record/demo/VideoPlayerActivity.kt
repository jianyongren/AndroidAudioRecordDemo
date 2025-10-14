package me.rjy.oboe.record.demo

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme
import kotlinx.coroutines.delay

class VideoPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        setContent {
            OboeRecordDemoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (uri == null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = "无效的视频地址")
                        }
                    } else {
                        VideoPlayerScreen(uri = uri, onFinish = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerScreen(uri: Uri, onFinish: () -> Unit) {
    val videoViewState = remember { mutableStateOf<VideoView?>(null) }
    val isPrepared = remember { mutableStateOf(false) }
    val isPlaying = remember { mutableStateOf(false) }
    val duration = remember { mutableStateOf(0) }
    val position = remember { mutableStateOf(0) }
    val sliderPos = remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "视频播放", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onFinish) { Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(uri)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setOnPreparedListener { mp ->
                        isPrepared.value = true
                        duration.value = mp.duration
                        start()
                        isPlaying.value = true
                    }
                    start()
                    videoViewState.value = this
                }
            },
            update = { view ->
                // no-op
            }
        )
        // 进度条与时间
        Slider(
            value = sliderPos.floatValue,
            onValueChange = { v -> sliderPos.floatValue = v },
            onValueChangeFinished = {
                val vv = videoViewState.value ?: return@Slider
                val target = (sliderPos.floatValue * duration.value).toInt()
                vv.seekTo(target)
            }
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatMs(position.value))
            Text(text = formatMs(duration.value))
        }
        // 控制按钮
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Button(onClick = {
                val vv = videoViewState.value ?: return@Button
                if (!vv.isPlaying && isPrepared.value) {
                    vv.start()
                    isPlaying.value = true
                }
            }) { Text(text = "播放/恢复") }

            androidx.compose.material3.Button(onClick = {
                val vv = videoViewState.value ?: return@Button
                if (vv.isPlaying) {
                    vv.pause()
                    isPlaying.value = false
                }
            }) { Text(text = "暂停") }
        }
    }

    // 刷新进度
    LaunchedEffect(isPrepared.value) {
        while (true) {
            val vv = videoViewState.value
            if (vv != null && isPrepared.value) {
                val pos = vv.currentPosition
                position.value = pos
                val dur = duration.value.coerceAtLeast(1)
                sliderPos.floatValue = (pos.toFloat() / dur).coerceIn(0f, 1f)
            }
            delay(200)
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}


