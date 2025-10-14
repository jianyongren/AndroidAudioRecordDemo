package me.rjy.oboe.record.demo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme

data class MediaItem(
    val uri: Uri,
    val title: String,
    val mimeType: String?,
    val durationMs: Long?
)

class LocalPlayerViewModel : ViewModel() {
    val mediaItems = mutableStateListOf<MediaItem>()
    val isLoading = mutableStateOf(false)

    fun loadMedia(context: Context, audio: Boolean, video: Boolean) {
        viewModelScope.launch {
            Log.d(TAG, "loadMedia() called: audio=$audio, video=$video")
            isLoading.value = true
            val items = withContext(Dispatchers.IO) {
                try {
                    queryMedia(context, audio, video)
                } catch (t: Throwable) {
                    Log.e(TAG, "queryMedia() exception: ${t.message}", t)
                    emptyList()
                }
            }
            Log.d(TAG, "loadMedia() finished, items=${items.size}")
            mediaItems.clear()
            mediaItems.addAll(items)
            isLoading.value = false
        }
    }

    private fun queryMedia(context: Context, audio: Boolean, video: Boolean): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        if (audio) results += queryAudio(context)
        if (video) results += queryVideo(context)
        return results.sortedBy { it.title.lowercase() }
    }

    private fun queryAudio(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION
        )
        // 仅保留音乐，排除系统铃声/通知/闹钟，过滤 ogg，排除常见系统目录，且时长>2.5s
        val selectionParts = mutableListOf(
            "${MediaStore.Audio.Media.IS_MUSIC}!=0",
            "${MediaStore.Audio.Media.IS_RINGTONE}=0",
            "${MediaStore.Audio.Media.IS_NOTIFICATION}=0",
            "${MediaStore.Audio.Media.IS_ALARM}=0",
            "${MediaStore.Audio.Media.DURATION}>2500",
            "(${MediaStore.Audio.Media.MIME_TYPE} IS NULL OR (${MediaStore.Audio.Media.MIME_TYPE} NOT LIKE ? AND ${MediaStore.Audio.Media.MIME_TYPE} NOT LIKE ?))"
        )
        val selectionArgs = mutableListOf(
            "audio/ogg%",
            "application/ogg%"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selectionParts += listOf(
                "(${MediaStore.Audio.Media.RELATIVE_PATH} IS NULL OR (" +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE ? AND " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE ? AND " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE ? AND " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE ? AND " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE ?))"
            )
            selectionArgs += listOf(
                "Ringtones/%",
                "Notifications/%",
                "Alarms/%",
                "System/%",
                "UI/%"
            )
        } else {
            // 旧版使用 DATA 路径过滤
            @Suppress("DEPRECATION")
            run {
                selectionParts += listOf(
                    "(${MediaStore.Audio.Media.DATA} IS NULL OR (" +
                            "${MediaStore.Audio.Media.DATA} NOT LIKE ? AND " +
                            "${MediaStore.Audio.Media.DATA} NOT LIKE ? AND " +
                            "${MediaStore.Audio.Media.DATA} NOT LIKE ? AND " +
                            "${MediaStore.Audio.Media.DATA} NOT LIKE ? AND " +
                            "${MediaStore.Audio.Media.DATA} NOT LIKE ?))"
                )
                selectionArgs += listOf(
                    "%/Ringtones/%",
                    "%/Notifications/%",
                    "%/Alarms/%",
                    "%/System/%",
                    "%/UI/%"
                )
            }
        }
        val selection = selectionParts.joinToString(" AND ")
        val collections: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buildList {
                add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
                runCatching { MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
                    .onSuccess { add(it) }
                runCatching { MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_INTERNAL) }
                    .onSuccess { add(it) }
            }
        } else {
            listOf(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI
            )
        }
        collections.forEach { collection ->
            Log.d(TAG, "queryAudio() start on $collection selection=[$selection] args=$selectionArgs")
            resolver.query(collection, projection, selection, selectionArgs.toTypedArray(), MediaStore.Audio.Media.TITLE + " ASC")?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val durIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                var added = 0
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val title = cursor.getString(titleIdx) ?: ""
                    val mime = cursor.getString(mimeIdx)
                    val dur = cursor.getLong(durIdx)
                    list += MediaItem(uri, title, mime, dur)
                    added++
                }
                Log.d(TAG, "queryAudio() from $collection added=$added")
            }
        }
        Log.d(TAG, "queryAudio() end, total=${list.size}")
        return list
    }

    private fun queryVideo(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )
        val collections: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buildList {
                add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
                runCatching { MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
                    .onSuccess { add(it) }
                runCatching { MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_INTERNAL) }
                    .onSuccess { add(it) }
            }
        } else {
            listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.INTERNAL_CONTENT_URI
            )
        }
        collections.forEach { collection ->
            Log.d(TAG, "queryVideo() start on $collection")
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val durIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                var added = 0
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val title = cursor.getString(titleIdx) ?: ""
                    val mime = cursor.getString(mimeIdx)
                    val dur = cursor.getLong(durIdx)
                    list += MediaItem(uri, title, mime, dur)
                    added++
                }
                Log.d(TAG, "queryVideo() from $collection added=$added")
            }
        }
        Log.d(TAG, "queryVideo() end, total=${list.size}")
        return list
    }
}

class LocalPlayerActivity : ComponentActivity() {
    private val vm: LocalPlayerViewModel by viewModels()
    private var mediaPlayer: MediaPlayer? = null

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "requestAudioPermission callback: granted=$granted")
            if (granted) {
                vm.loadMedia(this, audio = true, video = false)
            } else {
                Toast.makeText(this, "音频读取权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    private val requestVideoPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "requestVideoPermission callback: granted=$granted")
            if (granted) {
                vm.loadMedia(this, audio = false, video = true)
            } else {
                Toast.makeText(this, "视频读取权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    private val requestMediaPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val audioGranted = result[Manifest.permission.READ_MEDIA_AUDIO] == true
            val videoGranted = result[Manifest.permission.READ_MEDIA_VIDEO] == true
            Log.d(TAG, "requestMediaPermissions callback: audio=$audioGranted, video=$videoGranted")
            if (audioGranted || videoGranted) {
                vm.loadMedia(this, audio = audioGranted, video = videoGranted)
            } else {
                Toast.makeText(this, "媒体读取权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OboeRecordDemoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 自定义非实验性标题栏
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "本地播放", style = MaterialTheme.typography.titleLarge)
                                IconButton(onClick = { finish() }) {
                                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭")
                                }
                            }
                        }
                        // 主体内容
                        LocalPlayerScreen(
                            vm = vm,
                            onPlay = { uri -> play(uri) }
                        )
                    }
                }
            }
        }
        // 进入页面后自动加载媒体
        ensureMediaPermissionsAndLoadAll()
    }

    private fun ensureAudioPermissionAndLoad() {
        Log.d(TAG, "ensureAudioPermissionAndLoad() clicked")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val has = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "SDK>=33, READ_MEDIA_AUDIO granted=$has")
            if (has) {
                vm.loadMedia(this, audio = true, video = false)
            } else {
                requestAudioPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
                Toast.makeText(this, "请求音频读取权限", Toast.LENGTH_SHORT).show()
            }
        } else {
            val has = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "SDK<33, READ_EXTERNAL_STORAGE granted=$has")
            if (has) {
                vm.loadMedia(this, audio = true, video = false)
            } else {
                requestAudioPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                Toast.makeText(this, "请求存储读取权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureVideoPermissionAndLoad() {
        Log.d(TAG, "ensureVideoPermissionAndLoad() clicked")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val has = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "SDK>=33, READ_MEDIA_VIDEO granted=$has")
            if (has) {
                vm.loadMedia(this, audio = false, video = true)
            } else {
                requestVideoPermission.launch(Manifest.permission.READ_MEDIA_VIDEO)
                Toast.makeText(this, "请求视频读取权限", Toast.LENGTH_SHORT).show()
            }
        } else {
            val has = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "SDK<33, READ_EXTERNAL_STORAGE granted=$has")
            if (has) {
                vm.loadMedia(this, audio = false, video = true)
            } else {
                requestVideoPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                Toast.makeText(this, "请求存储读取权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureMediaPermissionsAndLoadAll() {
        Log.d(TAG, "ensureMediaPermissionsAndLoadAll() clicked")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val audioGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            val videoGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "SDK>=33, audio=$audioGranted, video=$videoGranted")
            if (audioGranted || videoGranted) {
                vm.loadMedia(this, audio = audioGranted, video = videoGranted)
            } else {
                requestMediaPermissions.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO))
                Toast.makeText(this, "请求媒体读取权限", Toast.LENGTH_SHORT).show()
            }
        } else {
            val has = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "SDK<33, READ_EXTERNAL_STORAGE granted=$has")
            if (has) {
                vm.loadMedia(this, audio = true, video = true)
            } else {
                requestAudioPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                Toast.makeText(this, "请求存储读取权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun play(uri: Uri) {
        Log.d(TAG, "play() uri=$uri")
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@LocalPlayerActivity, uri)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stop() }
            prepareAsync()
        }
    }

    private fun stop() {
        Log.d(TAG, "stop() called")
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Throwable) {}
            mp.reset()
            mp.release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        stop()
    }
}

@Composable
private fun LocalPlayerScreen(
    vm: LocalPlayerViewModel,
    onPlay: (Uri) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 自动加载，无需按钮
        if (vm.isLoading.value) {
            Text(text = "加载中…", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(vm.mediaItems) { item ->
                val context = LocalContext.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 如果是音频，跳转到 AudioPlayerActivity；视频仍旧在当前页用 MediaPlayer 播放
                            if (item.mimeType?.startsWith("audio") == true) {
                                context.startActivity(
                                    android.content.Intent(context, AudioPlayerActivity::class.java).setData(item.uri)
                                )
                            } else {
                                context.startActivity(
                                    android.content.Intent(context, VideoPlayerActivity::class.java).setData(item.uri)
                                )
                            }
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = buildString {
                            item.mimeType?.let { append(it) }
                            item.durationMs?.let { if (isNotEmpty()) append(" · "); append(formatDuration(it)) }
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(text = "播放", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private const val TAG = "LocalPlayer"


