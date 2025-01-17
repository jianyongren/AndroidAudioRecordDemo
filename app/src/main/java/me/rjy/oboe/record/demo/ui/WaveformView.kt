package me.rjy.oboe.record.demo.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val TAG = "WaveformView"

@Composable
fun WaveformView(
    waveformData: List<Float>,
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
    onMaxPointsCalculated: (Int) -> Unit
) {
    var lastSize by remember { mutableIntStateOf(0) }
    var maxPoints by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(waveformData) {
        if (waveformData.size != lastSize) {
            lastSize = waveformData.size
//            Log.d(TAG, "Waveform data size changed: $lastSize")
        }
    }

    LaunchedEffect(maxPoints) {
        if (maxPoints > 0) {
            onMaxPointsCalculated(maxPoints)
            Log.d(TAG, "Max points calculated: $maxPoints")
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // 固定每个采样点之间的间距为2dp
        val pointSpacing = 2.dp.toPx()
        // 计算最大可显示的采样点数
        maxPoints = (width / pointSpacing).toInt()

        // 绘制中心线
        drawLine(
            color = waveColor.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        if (waveformData.isEmpty()) {
            Log.d(TAG, "No waveform data to draw")
            return@Canvas
        }

        // 绘制采样点的竖线
        waveformData.forEachIndexed { index, amplitude ->
            // 计算x坐标，最新的数据点从最右边开始
            val x = width - (index * pointSpacing)
            
            // 如果x坐标小于0，说明已经超出了可视区域，停止绘制
            if (x < -pointSpacing) {
                return@forEachIndexed
            }
            
            // 绘制竖线
            drawLine(
                color = waveColor,
                start = Offset(x, centerY),
                end = Offset(x, centerY - (amplitude * centerY)),
                strokeWidth = 1.5f
            )
        }
    }
} 