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
import me.rjy.oboe.record.demo.WaveformBuffer

private const val TAG = "WaveformView"

@Composable
fun WaveformView(
    waveformBuffer: WaveformBuffer,
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // 固定每个采样点之间的间距为2dp
        val pointSpacing = 1.dp.toPx()
        // 计算最大可显示的采样点数
        val maxPoints = (width / pointSpacing).toInt()
        if (maxPoints != waveformBuffer.size) {
            waveformBuffer.resize(maxPoints)
        }

        // 绘制中心线
        drawLine(
            color = waveColor.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        // 绘制采样点的竖线
        for (i in 0 until maxPoints) {
            val amplitude = waveformBuffer.get(i)
            // 计算x坐标，最新的数据点从最右边开始
            val x = width - (i * pointSpacing)
            
            // 如果x坐标小于0，说明已经超出了可视区域，停止绘制
            if (x < -pointSpacing) {
                break
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