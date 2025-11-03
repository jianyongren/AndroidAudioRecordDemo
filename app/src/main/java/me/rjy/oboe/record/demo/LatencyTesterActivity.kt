package me.rjy.oboe.record.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LatencyTesterActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("oboe_recorder_demo")
        }
        private const val TAG = "LatencyTester"
        private const val ASSET_NUMBERS_FILE = "numbers_1_to_30.mp3"
        private const val OUTPUT_FILE_PREFIX = "numbers_1_to_30_latency_"
        private const val OUTPUT_FILE_EXT = ".m4a"
    }

    private external fun createLatencyTester(): Long
    private external fun destroyLatencyTester(nativeHandle: Long)
    private external fun startLatencyTest(nativeHandle: Long, originalPath: String, cacheDirPath: String, outputM4aPath: String): Int
    private external fun stopLatencyTest(nativeHandle: Long): Int

    private var nativeLatencyTesterHandle: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeLatencyTesterHandle = createLatencyTester()
        // 清理历史输出文件，限制数量为 20
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupOldLatencyFiles(maxKeep = 20)
        }
        setContent {
            OboeRecordDemoTheme {
                val isRunning = remember { mutableStateOf(false) }
                val isBusy = remember { mutableStateOf(false) }
                val builtinAudioPath = remember { mutableStateOf<String?>(null) }
                val detectedDelay = remember { mutableStateOf<Double?>(null) }
                val top3Windows = remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
                val isDetecting = remember { mutableStateOf(false) }
                val errorMessage = remember { mutableStateOf<String?>(null) }
                val outputFilePath = remember { mutableStateOf<String?>(null) }
                val showActionDialog = remember { mutableStateOf<String?>(null) }

                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { }
                )

                LaunchedEffect(Unit) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    val assetPath = ASSET_NUMBERS_FILE
                    val outFile = File(cacheDir, assetPath)
                    withContext(Dispatchers.IO) {
                        if (!outFile.exists()) {
                            runCatching {
                                val ins: InputStream = assets.open(assetPath)
                                val os = FileOutputStream(outFile)
                                ins.copyTo(os)
                                ins.close(); os.close()
                            }
                        }
                        builtinAudioPath.value = outFile.absolutePath
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 注册回调
                    LaunchedEffect(Unit) {
                        LatencyEvents.detectingListener = {
                            runOnUiThread {
                                isDetecting.value = true
                                isBusy.value = true
                                errorMessage.value = null
                            }
                        }
                        LatencyEvents.listener = { path, _, avgDelay, d1, c1, d2, c2, d3, c3 ->
                            runOnUiThread {
                                isBusy.value = false
                                isRunning.value = false
                                isDetecting.value = false
                                outputFilePath.value = path
                                detectedDelay.value = if (avgDelay >= 0) avgDelay else null
                                val windows = mutableListOf<Pair<Double, Double>>()
                                if (d1 >= 0 && c1 >= 0) windows.add(Pair(d1, c1))
                                if (d2 >= 0 && c2 >= 0) windows.add(Pair(d2, c2))
                                if (d3 >= 0 && c3 >= 0) windows.add(Pair(d3, c3))
                                top3Windows.value = if (windows.isNotEmpty()) windows else null
                            }
                        }
                        LatencyEvents.errorListener = { msg, code ->
                            runOnUiThread {
                                isBusy.value = false
                                isRunning.value = false
                                isDetecting.value = false
                                errorMessage.value = "$msg (error code: $code)"
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { stopLatencyTest(nativeLatencyTesterHandle) }
                                }
                            }
                        }
                    }

                    LatencyTesterUI(
                        modifier = Modifier.padding(innerPadding),
                        isRunning = isRunning,
                        isBusy = isBusy,
                        detectedDelay = detectedDelay,
                        top3Windows = top3Windows,
                        isDetecting = isDetecting,
                        errorMessage = errorMessage,
                        outputFilePath = outputFilePath,
                        onStart = {
                            detectedDelay.value = null
                            top3Windows.value = null
                            isDetecting.value = false
                            errorMessage.value = null
                            outputFilePath.value = null
                            builtinAudioPath.value?.let { audioPath ->
                                val outPath = deriveOutputPath()
                                val code = startLatencyTest(nativeLatencyTesterHandle, audioPath, cacheDir.absolutePath, outPath)
                                if (code == 0) isRunning.value = true
                            }
                        },
                        onStop = {
                            if (!isBusy.value) {
                                isBusy.value = true
                                lifecycleScope.launch {
                                    val code = withContext(Dispatchers.IO) { stopLatencyTest(nativeLatencyTesterHandle) }
                                    if (code == 0) isRunning.value = false
                                    isBusy.value = false
                                }
                            }
                        },
                        onFileClick = { path -> showActionDialog.value = path }
                    )

                    showActionDialog.value?.let { path ->
                        ActionSelectionDialog(
                            filePath = path,
                            onDismiss = { showActionDialog.value = null },
                            onPlay = {
                                showActionDialog.value = null
                                startActivity(Intent(this@LatencyTesterActivity, AudioPlayerActivity::class.java).putExtra("file_path", path))
                            },
                            onShare = {
                                showActionDialog.value = null
                                shareFile(path)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (nativeLatencyTesterHandle != 0L) {
            destroyLatencyTester(nativeLatencyTesterHandle)
            nativeLatencyTesterHandle = 0
        }
    }

    private fun deriveOutputPath(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val stamp = sdf.format(Date())
        val baseName = "$OUTPUT_FILE_PREFIX$stamp$OUTPUT_FILE_EXT"
        val dir = getExternalFilesDir(null) ?: filesDir
        return dir.resolve(baseName).absolutePath
    }

    /**
     * 清理过多的延迟测试输出文件，只保留最新的 [maxKeep] 个。
     * 目标目录与生成输出一致：优先 external files 目录，否则使用内部 files 目录。
     * 在 IO 线程中调用。
     */
    private fun cleanupOldLatencyFiles(maxKeep: Int) {
        val dir = getExternalFilesDir(null) ?: filesDir
        if (!dir.exists() || !dir.isDirectory) return

        val matchedFiles = dir.listFiles { file ->
            file.isFile && file.name.startsWith(OUTPUT_FILE_PREFIX) && file.name.endsWith(OUTPUT_FILE_EXT)
        }?.toList().orEmpty()

        Log.d(TAG, "old latency files's count ${matchedFiles.size}")

        if (matchedFiles.size <= maxKeep) return

        val toDelete = matchedFiles
            .sortedBy { it.lastModified() } // 最老的在前
            .take(matchedFiles.size - maxKeep)

        toDelete.forEach { runCatching { it.delete() } }
    }

    private fun shareFile(filePath: String) {
        runCatching {
            val file = File(filePath)
            if (!file.exists()) return
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "audio/mp4"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享文件"))
        }
    }
}

@Composable
private fun LatencyTesterUI(
    modifier: Modifier = Modifier,
    isRunning: MutableState<Boolean>,
    isBusy: MutableState<Boolean>,
    detectedDelay: MutableState<Double?>,
    top3Windows: MutableState<List<Pair<Double, Double>>?>,
    isDetecting: MutableState<Boolean>,
    errorMessage: MutableState<String?>,
    outputFilePath: MutableState<String?>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFileClick: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.title_recording_latency_test),
            modifier = Modifier.padding(bottom = 24.dp),
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.using_builtin_audio),
            modifier = Modifier.padding(bottom = 16.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )

        errorMessage.value?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(bottom = 16.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        }

        if (isDetecting.value) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.detecting_latency),
                modifier = Modifier.padding(bottom = 16.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.secondary
            )
        }

        detectedDelay.value?.let { delay ->
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.average_delay, delay),
                modifier = Modifier.padding(bottom = 16.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
        } ?: run {
            if (!isRunning.value && !isDetecting.value) Spacer(modifier = Modifier.height(16.dp))
        }

        top3Windows.value?.let { windows ->
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.highest_correlation_windows),
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.secondary
            )
            windows.forEachIndexed { index, (d, c) ->
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.window_info, index + 1, d, c),
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } ?: run {
            if (!isRunning.value && detectedDelay.value == null) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        outputFilePath.value?.let { filePath ->
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.output_file, filePath),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp)
                    .clickable { onFileClick(filePath) },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                maxLines = 3
            )
        }

        if (!isRunning.value) {
            Button(
                onClick = onStart,
                enabled = !isBusy.value && !isDetecting.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                Text(text = androidx.compose.ui.res.stringResource(R.string.start_test), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Button(
                onClick = onStop,
                enabled = !isBusy.value && !isDetecting.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                Text(
                    text = if (isBusy.value || isDetecting.value) androidx.compose.ui.res.stringResource(R.string.processing) else androidx.compose.ui.res.stringResource(
                        R.string.stop_and_save
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionSelectionDialog(
    filePath: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择操作") },
        text = {
            Column {
                Text(text = File(filePath).name, modifier = Modifier.padding(bottom = 16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = onPlay) { Text("播放") }
                    Button(onClick = onShare) { Text("分享") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}


