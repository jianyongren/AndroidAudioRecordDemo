package me.rjy.oboe.record.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme
import java.io.File

class MainActivity : ComponentActivity() {

//    private external fun native_start_record(path: String)
//    private external fun native_stop_record()

    private val viewModel: RecorderViewModel by viewModels<RecorderViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OboeRecordDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TextButton(
                            onClick = {
                                startRecord()
                            },
                            modifier = Modifier.padding(4.dp),
                            enabled = (!viewModel.recordingStatus.value && !viewModel.pcmPlayingStatus.value)
                        ) {
                            Text(text = "开始录制", fontSize = 20.sp)
                        }
                        TextButton(
                            onClick = {
                                viewModel.stopRecord()
                            },
                            modifier = Modifier.padding(4.dp),
                            enabled = (viewModel.recordingStatus.value && !viewModel.pcmPlayingStatus.value)
                        ) {
                            Text(text = "停止录制", fontSize = 20.sp)
                        }
                        TextButton(
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
                            modifier = Modifier.padding(4.dp),
                            enabled = !viewModel.recordingStatus.value
                        ) {
                            Text(
                                text = if (viewModel.pcmPlayingStatus.value) "停止播放" else "播放PCM",
                                fontSize = 20.sp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "回声消除:")
                            Checkbox(checked = viewModel.echoCanceler.value, onCheckedChange = {
                                viewModel.echoCanceler.value = !viewModel.echoCanceler.value
                            })
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
        return "${filesDir}/record_demo.pcm"
    }

    companion object {
        private const val TAG = "MainActivity"

        init {
            System.loadLibrary("demo")
        }
    }
}
