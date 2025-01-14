package me.rjy.oboe.record.demo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme
import java.io.File

class MainActivity : ComponentActivity() {

//    private external fun native_start_record(path: String)
//    private external fun native_stop_record()

    private val viewModel: RecorderViewModel by viewModels<RecorderViewModel>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OboeRecordDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // 录音参数控制部分
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "录音参数设置",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                Divider(modifier = Modifier.padding(vertical = 8.dp))
                                
                                // 声道设置
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "声道:", style = MaterialTheme.typography.bodyMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "单声道", style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = viewModel.isStereo.value,
                                            onCheckedChange = {
                                                viewModel.isStereo.value = it
                                            }
                                        )
                                        Text(text = "立体声", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                
                                // 采样率设置
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "采样率:", style = MaterialTheme.typography.bodyMedium)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(8000, 16000, 44100, 48000).forEach { rate ->
                                            FilterChip(
                                                selected = viewModel.sampleRate.value == rate,
                                                onClick = { viewModel.sampleRate.value = rate },
                                                label = { Text("${rate/1000}kHz") }
                                            )
                                        }
                                    }
                                }
                                
                                // 数据格式设置
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "数据格式:", style = MaterialTheme.typography.bodyMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "PCM_16BIT", style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = viewModel.isFloat.value,
                                            onCheckedChange = {
                                                viewModel.isFloat.value = it
                                            }
                                        )
                                        Text(text = "PCM_FLOAT", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                
                                // 回声消除设置
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "回声消除:", style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = viewModel.echoCanceler.value,
                                        onCheckedChange = {
                                            viewModel.echoCanceler.value = it
                                        }
                                    )
                                }
                            }
                        }
                        
                        // 操作按钮部分
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Button(
                                    onClick = { startRecord() },
                                    enabled = (!viewModel.recordingStatus.value && !viewModel.pcmPlayingStatus.value)
                                ) {
                                    Text(text = "开始录制")
                                }
                                
                                Button(
                                    onClick = { viewModel.stopRecord() },
                                    enabled = (viewModel.recordingStatus.value && !viewModel.pcmPlayingStatus.value)
                                ) {
                                    Text(text = "停止录制")
                                }
                                
                                Button(
                                    onClick = {
                                        if (viewModel.pcmPlayingStatus.value) {
                                            viewModel.stopPcm()
                                        } else {
                                            val pcmFile = getRecordFilePath()
                                            if (File(pcmFile).exists()) {
                                                viewModel.playPcm(getRecordFilePath())
                                            } else {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    R.string.file_not_exists,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    enabled = !viewModel.recordingStatus.value
                                ) {
                                    Text(text = if (viewModel.pcmPlayingStatus.value) "停止播放" else "播放PCM")
                                }
                            }
                        }

                        // 添加文件路径显示
                        viewModel.recordedFilePath.value?.let { path ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "录音文件路径:",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = path,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                        IconButton(onClick = {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("录音文件路径", path)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(this@MainActivity, "路径已复制", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(
                                                imageVector = Icons.Outlined.ContentCopy,
                                                contentDescription = "复制路径"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                realStartRecord()
            }
        }

    private fun startRecord() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        realStartRecord()
    }

    private fun realStartRecord() {
        viewModel.startRecord(getRecordFilePath())
    }

    private fun getRecordFilePath(): String {
        return "${filesDir}/${viewModel.generateRecordFileName()}"
    }

    companion object {
        private const val TAG = "MainActivity"

        init {
            System.loadLibrary("demo")
        }
    }
}
