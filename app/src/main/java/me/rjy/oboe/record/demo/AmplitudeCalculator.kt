package me.rjy.oboe.record.demo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs


// 振幅计算策略接口
interface AmplitudeCalculator {
    fun calculateAmplitude(buffer: ByteBuffer, size: Int): Pair<Float, Float?>
}

// Float格式立体声策略
class FloatStereoAmplitudeCalculator : AmplitudeCalculator {
    override fun calculateAmplitude(buffer: ByteBuffer, size: Int): Pair<Float, Float?> {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val numSamples = size / 4
        var maxLeftAmplitude = 0.0
        var maxRightAmplitude = 0.0

        val samplesPerChannel = numSamples / 2
        for (i in 0 until samplesPerChannel) {
            val leftSample = floatBuffer.get(i * 2)
            val rightSample = floatBuffer.get(i * 2 + 1)

            if (abs(leftSample) > abs(maxLeftAmplitude)) {
                maxLeftAmplitude = leftSample.toDouble()
            }

            if (abs(rightSample) > abs(maxRightAmplitude)) {
                maxRightAmplitude = rightSample.toDouble()
            }
        }

        return Pair(
            maxLeftAmplitude.coerceIn(-1.0, 1.0).toFloat(),
            maxRightAmplitude.coerceIn(-1.0, 1.0).toFloat()
        )
    }
}

// Float格式单声道策略
class FloatMonoAmplitudeCalculator : AmplitudeCalculator {
    override fun calculateAmplitude(buffer: ByteBuffer, size: Int): Pair<Float, Float?> {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val numSamples = size / 4
        var maxAmplitude = 0.0

        for (i in 0 until numSamples) {
            val sample = floatBuffer.get(i)
            if (abs(sample) > maxAmplitude) {
                maxAmplitude = sample.toDouble()
            }
        }

        return Pair(
            maxAmplitude.coerceIn(-1.0, 1.0).toFloat(),
            null
        )
    }
}

// Short格式立体声策略
class ShortStereoAmplitudeCalculator : AmplitudeCalculator {
    override fun calculateAmplitude(buffer: ByteBuffer, size: Int): Pair<Float, Float?> {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()
        val numSamples = size / 2
        var maxLeftAmplitude = 0.0
        var maxRightAmplitude = 0.0

        val samplesPerChannel = numSamples / 2
        for (i in 0 until samplesPerChannel) {
            val leftSample = shortBuffer.get(i * 2) / 32768.0
            val rightSample = shortBuffer.get(i * 2 + 1) / 32768.0

            if (abs(leftSample) > abs(maxLeftAmplitude)) {
                maxLeftAmplitude = leftSample
            }

            if (abs(rightSample) > abs(maxRightAmplitude)) {
                maxRightAmplitude = rightSample
            }
        }

        return Pair(
            maxLeftAmplitude.coerceIn(-1.0, 1.0).toFloat(),
            maxRightAmplitude.coerceIn(-1.0, 1.0).toFloat()
        )
    }
}

// Short格式单声道策略
class ShortMonoAmplitudeCalculator : AmplitudeCalculator {
    override fun calculateAmplitude(buffer: ByteBuffer, size: Int): Pair<Float, Float?> {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()
        val numSamples = size / 2
        var maxAmplitude = 0.0

        for (i in 0 until numSamples) {
            val sample = shortBuffer.get(i) / 32768.0
            if (abs(sample) > abs(maxAmplitude)) {
                maxAmplitude = sample
            }
        }

        return Pair(
            maxAmplitude.coerceIn(-1.0, 1.0).toFloat(),
            null
        )
    }
}