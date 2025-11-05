package me.rjy.oboe.record.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rjy.oboe.record.demo.ui.theme.OboeRecordDemoTheme

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OboeRecordDemoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LauncherScreen(
                        onGoRecord = {
                            startActivity(Intent(this, AudioRecorderActivity::class.java))
                        },
                        onGoLocalPlayer = {
                            startActivity(Intent(this, LocalPlayerActivity::class.java))
                        },
                        onGoLatencyTester = {
                            startActivity(Intent(this, LatencyTesterActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherScreen(
    onGoRecord: () -> Unit,
    onGoLocalPlayer: () -> Unit,
    onGoLatencyTester: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(text = stringResource(id = R.string.launcher_select_function), style = MaterialTheme.typography.titleLarge)
        Button(onClick = onGoRecord) { Text(text = stringResource(id = R.string.launcher_go_record)) }
        Button(onClick = onGoLocalPlayer) { Text(text = stringResource(id = R.string.launcher_go_local_player)) }
        Button(onClick = onGoLatencyTester) { Text(text = stringResource(id = R.string.launcher_go_latency_tester)) }
    }
}


