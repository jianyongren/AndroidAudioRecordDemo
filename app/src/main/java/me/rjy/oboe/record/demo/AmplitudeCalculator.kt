package me.rjy.oboe.record.demo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs


// 振幅计算策略接口
interface AmplitudeCalculator {
    fun calculateAmplitude(buffer: ByteBuffer, size: Int, callback: (Float, Float?) -> Unit)
}

// Float格式立体声策略
class FloatStereoAmplitudeCalculator(private val samplesPerUpdate: Int) : AmplitudeCalculator {
    private var accumulatedSamples = 0
    private var maxLeftSample = 0f
    private var maxRightSample = 0f

    override fun calculateAmplitude(
        buffer: ByteBuffer,
        size: Int,
        callback: (Float, Float?) -> Unit
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val numSamples = size / 4
        val samplesPerChannel = numSamples / 2
        for (i in 0 until samplesPerChannel) {
            val leftSample = floatBuffer.get(i * 2)
            val rightSample = floatBuffer.get(i * 2 + 1)
            if (abs(leftSample) > abs(maxLeftSample)) {
                maxLeftSample = leftSample
            }
            if (abs(rightSample) > abs(maxRightSample)) {
                maxRightSample = rightSample
            }
            if (accumulatedSamples++ >= samplesPerUpdate) {
                callback(maxLeftSample.coerceIn(-1.0f, 1.0f), maxRightSample.coerceIn(-1.0f, 1.0f))
                accumulatedSamples = 0
                maxLeftSample = 0f
                maxRightSample = 0f
            }
        }
    }
}

// Float格式单声道策略
class FloatMonoAmplitudeCalculator(private val samplesPerUpdate: Int) : AmplitudeCalculator {
    private var accumulatedSamples = 0
    private var maxSample = 0f

    override fun calculateAmplitude(
        buffer: ByteBuffer,
        size: Int,
        callback: (Float, Float?) -> Unit
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val numSamples = size / 4

        for (i in 0 until numSamples) {
            val sample = floatBuffer.get(i)
            if (abs(sample) > abs(maxSample)) {
                maxSample = sample
            }
            if (accumulatedSamples++ >= samplesPerUpdate) {
                callback(maxSample.coerceIn(-1.0f, 1.0f), null)
                accumulatedSamples = 0
                maxSample = 0f
            }
        }
    }
}

// Short格式立体声策略
class ShortStereoAmplitudeCalculator(private val samplesPerUpdate: Int) : AmplitudeCalculator {
    private var accumulatedSamples = 0
    private var maxLeftSample = 0
    private var maxRightSample = 0

    override fun calculateAmplitude(
        buffer: ByteBuffer,
        size: Int,
        callback: (Float, Float?) -> Unit
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()
        val numSamples = size / 2

        val samplesPerChannel = numSamples / 2
        for (i in 0 until samplesPerChannel) {
            val leftSample = shortBuffer.get(i * 2).toInt()
            val rightSample = shortBuffer.get(i * 2 + 1).toInt()
            if (abs(leftSample) > abs(maxLeftSample)) {
                maxLeftSample = leftSample
            }
            if (abs(rightSample) > abs(maxRightSample)) {
                maxRightSample = rightSample
            }
            if (accumulatedSamples++ >= samplesPerUpdate) {
                callback(
                    (maxLeftSample/32768.0).coerceIn(-1.0, 1.0).toFloat(),
                    (maxRightSample/32768.0).coerceIn(-1.0, 1.0).toFloat()
                )
                accumulatedSamples = 0
                maxLeftSample = 0
                maxRightSample = 0
            }
        }
    }
}

// Short格式单声道策略
class ShortMonoAmplitudeCalculator(private val samplesPerUpdate: Int) : AmplitudeCalculator {
    private var accumulatedSamples = 0
    private var maxSample = 0

    override fun calculateAmplitude(
        buffer: ByteBuffer,
        size: Int,
        callback: (Float, Float?) -> Unit
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()
        val numSamples = size / 2

        for (i in 0 until numSamples) {
            val sample = shortBuffer.get(i).toInt()
            if (abs(sample) > abs(maxSample)) {
                maxSample = sample
            }
            if (accumulatedSamples++ >= samplesPerUpdate) {
                callback((maxSample / 32768.0).coerceIn(-1.0, 1.0).toFloat(), null)
                accumulatedSamples = 0
                maxSample = 0
            }
        }
    }
}