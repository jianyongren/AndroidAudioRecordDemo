package me.rjy.oboe.record.demo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme
import java.io.File

class MainActivity : ComponentActivity() {

//    private external fun native_start_record(path: String)
//    private external fun native_stop_record()

    private val viewModel: RecorderViewModel by viewModels<RecorderViewModel>()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)
            val hasSource = addedDevices?.find {
                it.isSource
            }
            if (hasSource != null) {
                viewModel.refreshAudioDevices(this@MainActivity)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)
            val hasSource = removedDevices?.find {
                it.isSource
            }
            if (hasSource != null) {
                viewModel.refreshAudioDevices(this@MainActivity)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(audioDeviceCallback, null)

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

                                val configuration = LocalConfiguration.current
                                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    // 横屏布局：两列
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        // 左列
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // 录音方式设置
                                            RecordMethodSection(viewModel)
                                            // 音频源选择
                                            AudioSourceSection(viewModel)
                                            // 录音设备选择
                                            RecordDeviceSection(viewModel)
                                        }

                                        // 右列
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // 声道设置
                                            ChannelSection(viewModel)
                                            // 采样率设置
                                            SampleRateSection(viewModel)
                                            // 数据格式设置
                                            DataFormatSection(viewModel)
                                            // 回声消除设置
                                            EchoCancelSection(viewModel)
                                        }
                                    }
                                } else {
                                    // 竖屏布局：单列
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // 录音方式设置
                                        RecordMethodSection(viewModel)
                                        // 音频源选择
                                        AudioSourceSection(viewModel)
                                        // 录音设备选择
                                        RecordDeviceSection(viewModel)
                                        // 声道设置
                                        ChannelSection(viewModel)
                                        // 采样率设置
                                        SampleRateSection(viewModel)
                                        // 数据格式设置
                                        DataFormatSection(viewModel)
                                        // 回声消除设置
                                        EchoCancelSection(viewModel)
                                    }
                                }

                                // 观察useOboe的值变化
                                LaunchedEffect(viewModel.useOboe.value) {
                                    viewModel.refreshAudioDevices(this@MainActivity)
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

    override fun onDestroy() {
        super.onDestroy()
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(audioDeviceCallback)
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

@Composable
private fun RecordMethodSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "录音方式:", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = !viewModel.useOboe.value,
                    onClick = { viewModel.setUseOboe(false) }
                )
                Text(
                    text = "AudioRecord",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setUseOboe(false) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = viewModel.useOboe.value,
                    onClick = { viewModel.setUseOboe(true) }
                )
                Text(
                    text = "Oboe",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setUseOboe(true) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSourceSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "音频源:", style = MaterialTheme.typography.bodyMedium)
        var audioSourceExpanded by remember { mutableStateOf(false) }
        val currentSource = viewModel.audioSources.find {
            it.id == viewModel.selectedAudioSource.value
        }
        ExposedDropdownMenuBox(
            expanded = audioSourceExpanded,
            onExpandedChange = { audioSourceExpanded = it },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            TextField(
                value = currentSource?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioSourceExpanded) },
                modifier = Modifier.menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = audioSourceExpanded,
                onDismissRequest = { audioSourceExpanded = false }
            ) {
                viewModel.audioSources.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source.name) },
                        onClick = {
                            viewModel.setAudioSource(source.id)
                            audioSourceExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDeviceSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "录音设备:", style = MaterialTheme.typography.bodyMedium)
        var expanded by remember { mutableStateOf(false) }
        val currentDevice = viewModel.audioDevices.value.find {
            it.id == viewModel.selectedDeviceId.value
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            TextField(
                value = currentDevice?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                viewModel.audioDevices.value.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.name) },
                        onClick = {
                            viewModel.selectedDeviceId.value = device.id
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "声道:", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = !viewModel.isStereo.value,
                    onClick = { viewModel.setIsStereo(false) }
                )
                Text(
                    text = "单声道",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setIsStereo(false) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = viewModel.isStereo.value,
                    onClick = { viewModel.setIsStereo(true) }
                )
                Text(
                    text = "立体声",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setIsStereo(true) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleRateSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "采样率:", style = MaterialTheme.typography.bodyMedium)
        var sampleRateExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = sampleRateExpanded,
            onExpandedChange = { sampleRateExpanded = it },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            TextField(
                value = "${viewModel.sampleRate.value/1000}kHz",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded) },
                modifier = Modifier.menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = sampleRateExpanded,
                onDismissRequest = { sampleRateExpanded = false }
            ) {
                listOf(8000, 16000, 44100, 48000).forEach { rate ->
                    DropdownMenuItem(
                        text = { Text("${rate/1000}kHz") },
                        onClick = {
                            viewModel.setSampleRate(rate)
                            sampleRateExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DataFormatSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "数据格式:", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = !viewModel.isFloat.value,
                    onClick = { viewModel.setIsFloat(false) }
                )
                Text(
                    text = "16BIT",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setIsFloat(false) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = viewModel.isFloat.value,
                    onClick = { viewModel.setIsFloat(true) }
                )
                Text(
                    text = "FLOAT",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setIsFloat(true) }
                )
            }
        }
    }
}

@Composable
private fun EchoCancelSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "回声消除:", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Switch(
                checked = viewModel.echoCanceler.value,
                onCheckedChange = { viewModel.setEchoCanceler(it) }
            )
        }
    }
}
