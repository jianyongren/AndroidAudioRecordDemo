package me.rjy.oboe.record.demo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import me.rjy.oboe.record.demo.RecorderViewModel.PlaybackWaveform

@Composable
fun WaveformPlayView(
    waveform: PlaybackWaveform?,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val progressColor = MaterialTheme.colorScheme.tertiary
    val channelHeight = 120.dp  // 固定每个声道的高度为120dp

    // 根据是否是立体声来决定总高度
    val totalHeight = if (waveform?.rightChannel.isNullOrEmpty()) channelHeight else channelHeight * 2

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)  // 在这里指定固定高度
    ) {
        if (waveform == null) return@Canvas

        val width = size.width
        val height = size.height
        val centerY = if (waveform.rightChannel.isNullOrEmpty()) {
            // 单声道时，波形居中显示
            height
        } else {
            // 双声道时，第一个声道从顶部开始，第二个声道从中间开始
            height / 2
        }

        // 绘制左声道波形
        drawWaveform(
            amplitudes = waveform.leftChannel,
            color = primaryColor,
            startY = 0f,
            height = centerY
        )

        // 绘制右声道波形（如果有）
        waveform.rightChannel?.let { rightChannel ->
            if (rightChannel.isNotEmpty()) {
                drawWaveform(
                    amplitudes = rightChannel,
                    color = secondaryColor,
                    startY = centerY,  // 右声道从中间开始
                    height = centerY
                )
            }
        }

        // 绘制播放进度线
        val progressX = width * progress
        drawLine(
            color = progressColor,
            start = Offset(progressX, 0f),
            end = Offset(progressX, height),
            strokeWidth = 2f
        )
    }
}

private fun DrawScope.drawWaveform(
    amplitudes: List<Float>,
    color: Color,
    startY: Float,
    height: Float
) {
    if (amplitudes.isEmpty()) return

    val width = size.width
    val centerY = startY + height / 2
    val path = Path()
    val pointWidth = width / amplitudes.size

    // 移动到起始点
    path.moveTo(0f, centerY)

    amplitudes.forEachIndexed { index, amplitude ->
        val x = index * pointWidth
        val y = centerY - (amplitude * height / 2)
        path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = color.copy(alpha = 0.5f),
        style = Stroke(width = 1f)
    )
} 