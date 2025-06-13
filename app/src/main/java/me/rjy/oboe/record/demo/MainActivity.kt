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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import me.rjy.oboe.record.demo.ui.WaveformPlayView
import me.rjy.oboe.record.demo.ui.WaveformView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(audioDeviceCallback, null)

        setContent {
            OboeRecordDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(11.dp)
                    ) {
                        // 录音参数控制部分
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier.padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "录音参数设置",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                Divider(modifier = Modifier.padding(vertical = 6.dp))

                                val configuration = LocalConfiguration.current
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 1),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        RecordMethodSection(viewModel)
                                    }
                                    item {
                                        AudioApiSection(viewModel)
                                    }
                                    item {
                                        AudioSourceSection(viewModel)
                                    }
                                    item {
                                        RecordDeviceSection(viewModel)
                                    }
                                    item {
                                        ChannelSection(viewModel)
                                    }
                                    item {
                                        SampleRateSection(viewModel)
                                    }
                                    item {
                                        DataFormatSection(viewModel)
                                    }
                                    item {
                                        PlaybackMethodSection(viewModel)
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
                            Column(
                                modifier = Modifier.padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                if (viewModel.isStereo.value) {
                                    // 立体声模式：显示两个波形图
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (viewModel.pcmPlayingStatus.value) {
                                            // 播放状态：显示播放波形
                                            WaveformPlayView(
                                                waveform = viewModel.playbackWaveform.value,
                                                progress = viewModel.playbackProgress.value,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        } else {
                                            // 录音状态：显示实时波形
                                            Text(
                                                text = "左声道",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            WaveformView(
                                                waveformBuffer = viewModel.leftChannelBuffer,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                            )

                                            Text(
                                                text = "右声道",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            WaveformView(
                                                waveformBuffer = viewModel.rightChannelBuffer,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                            )
                                        }
                                    }
                                } else {
                                    // 单声道模式：显示一个波形图
                                    if (viewModel.pcmPlayingStatus.value) {
                                        // 播放状态：显示播放波形
                                        WaveformPlayView(
                                            waveform = viewModel.playbackWaveform.value,
                                            progress = viewModel.playbackProgress.value,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        // 录音状态：显示实时波形
                                        WaveformView(
                                            waveformBuffer = viewModel.leftChannelBuffer,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                            )
                                    }
                                }

                                // 使用新的操作按钮组件
                                OperationButtons(
                                    viewModel = viewModel,
                                    startRecord = { startRecord() },
                                    context = this@MainActivity
                                )
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
                                    modifier = Modifier.padding(11.dp)
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
                                                modifier = Modifier.padding(top = 3.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordMethodSection(viewModel: RecorderViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "录音方式：",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.Start,
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioApiSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "音频API:",
            style = MaterialTheme.typography.bodyMedium,
            color = if (viewModel.useOboe.value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        var audioApiExpanded by remember { mutableStateOf(false) }
        val currentApi = viewModel.audioApis.find {
            it.id == viewModel.selectedAudioApi.intValue
        }
        ExposedDropdownMenuBox(
            expanded = audioApiExpanded,
            onExpandedChange = { if (viewModel.useOboe.value) audioApiExpanded = it },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            TextField(
                value = currentApi?.name ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = viewModel.useOboe.value,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioApiExpanded) },
                modifier = Modifier.menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (viewModel.useOboe.value) 0.3f else 0.1f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (viewModel.useOboe.value) 0.5f else 0.1f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = audioApiExpanded,
                onDismissRequest = { audioApiExpanded = false }
            ) {
                viewModel.audioApis.forEach { api ->
                    DropdownMenuItem(
                        text = { Text(api.name) },
                        onClick = {
                            viewModel.setAudioApi(api.id)
                            audioApiExpanded = false
                        }
                    )
                }
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
                            viewModel.setSelectedDeviceId(device.id)
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
                value = "${viewModel.sampleRate.intValue}Hz",
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
                        text = { Text("${rate}Hz") },
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

@Composable
private fun PlaybackMethodSection(viewModel: RecorderViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "播放方式:", style = MaterialTheme.typography.bodyMedium)
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
                    selected = !viewModel.useOboePlayback.value,
                    onClick = { viewModel.setUseOboePlayback(false) }
                )
                Text(
                    text = "AudioTrack",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setUseOboePlayback(false) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = viewModel.useOboePlayback.value,
                    onClick = { viewModel.setUseOboePlayback(true) }
                )
                Text(
                    text = "Oboe",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { viewModel.setUseOboePlayback(true) }
                )
            }
        }
    }
}

// 添加文件选择对话框组件
@Composable
private fun PcmFileSelectDialog(
    onDismissRequest: () -> Unit,
    onFileSelected: (File) -> Unit,
    viewModel: RecorderViewModel,
    context: Context
) {
    AlertDialog(
        onDismissRequest = {
            if (viewModel.isEditMode.value) {
                viewModel.toggleEditMode()
            } else {
                onDismissRequest()
            }
        },
        title = { Text(if (viewModel.isEditMode.value) "选择要删除的文件" else "选择PCM文件") },
        text = {
            LazyColumn {
                items(viewModel.pcmFileList.value) { fileInfo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (viewModel.isEditMode.value) {
                                            viewModel.toggleFileSelection(fileInfo.file)
                                        } else {
                                            onFileSelected(fileInfo.file)
                                        }
                                    },
                                    onLongPress = {
                                        if (!viewModel.isEditMode.value) {
                                            viewModel.toggleEditMode()
                                            // 长按时自动选中当前项
                                            viewModel.toggleFileSelection(fileInfo.file)
                                        }
                                    }
                                )
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (viewModel.isEditMode.value) {
                                Checkbox(
                                    checked = viewModel.selectedFiles.value.contains(fileInfo.file),
                                    onCheckedChange = { viewModel.toggleFileSelection(fileInfo.file) }
                                )
                            }
                            Column {
                                // 去掉文件名最后的日期部分和.pcm扩展名
                                val displayName = fileInfo.name
                                    .replace("_\\d{8}_\\d{6}\\.pcm$".toRegex(), "")
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    softWrap = true,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                Text(
                                    text = java.text.SimpleDateFormat(
                                        "yyyy-MM-dd HH:mm:ss",
                                        java.util.Locale.getDefault()
                                    ).format(java.util.Date(fileInfo.lastModified)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (viewModel.isEditMode.value) {
                    TextButton(
                        onClick = {
                            viewModel.toggleEditMode()
                        }
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            viewModel.deleteSelectedFiles(context) {
                                // 当所有文件都被删除时，关闭对话框
                                onDismissRequest()
                            }
                        },
                        enabled = viewModel.selectedFiles.value.isNotEmpty()
                    ) {
                        Text("删除")
                    }
                } else {
                    TextButton(onClick = onDismissRequest) {
                        Text("取消")
                    }
                }
            }
        }
    )
}

// 修改操作按钮部分
@Composable
private fun OperationButtons(
    viewModel: RecorderViewModel,
    startRecord: () -> Unit,
    context: Context
) {
    var showFileSelectDialog by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = { 
                if (viewModel.recordingStatus.value) {
                    viewModel.stopRecord()
                } else {
                    startRecord()
                }
            },
            enabled = !viewModel.pcmPlayingStatus.value
        ) {
            Text(text = if (viewModel.recordingStatus.value) "停止录制" else "开始录制")
        }
        
        Button(
            onClick = {
                if (viewModel.pcmPlayingStatus.value) {
                    viewModel.stopPcm()
                } else {
                    viewModel.refreshPcmFileList(context)
                    showFileSelectDialog = true
                }
            },
            enabled = !viewModel.recordingStatus.value
        ) {
            Text(text = if (viewModel.pcmPlayingStatus.value) "停止播放" else "播放PCM")
        }
    }

    if (showFileSelectDialog) {
        PcmFileSelectDialog(
            onDismissRequest = { showFileSelectDialog = false },
            onFileSelected = { file ->
                showFileSelectDialog = false
                viewModel.playPcm(file.absolutePath)
            },
            viewModel = viewModel,
            context = context
        )
    }
}
