package me.rjy.oboe.record.demo

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme

class AudioPlayerActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        setContent {
            OboeRecordDemoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (uri == null) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "无效的音频地址")
                        }
                    } else {
                        AudioPlayerScreen(
                            uri = uri,
                            onFinish = { finish() },
                            provideMediaPlayer = { ensurePlayer(uri) },
                            releasePlayer = { releasePlayer() },
                            seekTo = { pos -> mediaPlayer?.seekTo(pos) }
                        )
                    }
                }
            }
        }
    }

    private fun ensurePlayer(uri: Uri): MediaPlayer {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AudioPlayerActivity, uri)
                setOnCompletionListener { }
                prepare()
            }
        }
        return mediaPlayer!!
    }

    private fun releasePlayer() {
        mediaPlayer?.let { mp ->
            try { if (mp.isPlaying) mp.stop() } catch (_: Throwable) {}
            mp.reset()
            mp.release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}

@Composable
private fun AudioPlayerScreen(
    uri: Uri,
    onFinish: () -> Unit,
    provideMediaPlayer: () -> MediaPlayer,
    releasePlayer: () -> Unit,
    seekTo: (Int) -> Unit,
) {
    val player = remember { provideMediaPlayer() }
    val isPlaying = remember { mutableStateOf(false) }
    val duration = remember { mutableStateOf(player.duration.coerceAtLeast(0)) }
    val position = remember { mutableStateOf(0) }
    val sliderPos = remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    LaunchedEffect(Unit) {
        isPlaying.value = player.isPlaying
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "音频播放", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onFinish) { Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭") }
        }

        Text(text = uri.toString(), style = MaterialTheme.typography.bodySmall)

        Slider(
            value = sliderPos.floatValue,
            onValueChange = { v ->
                sliderPos.floatValue = v
            },
            onValueChangeFinished = {
                val target = (sliderPos.floatValue * duration.value).toInt()
                seekTo(target)
            }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatMs(position.value))
            Text(text = formatMs(duration.value))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (!player.isPlaying) {
                    player.start()
                    isPlaying.value = true
                }
            }) { Text(text = "播放/恢复") }

            Button(onClick = {
                if (player.isPlaying) {
                    player.pause()
                    isPlaying.value = false
                }
            }) { Text(text = "暂停") }
        }
    }

    // 进度刷新
    LaunchedEffect(isPlaying.value) {
        while (true) {
            val pos = try { player.currentPosition } catch (_: Throwable) { 0 }
            position.value = pos
            sliderPos.floatValue = if (duration.value > 0) pos.toFloat() / duration.value else 0f
            kotlinx.coroutines.delay(200)
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}


