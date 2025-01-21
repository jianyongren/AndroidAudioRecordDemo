package me.rjy.oboe.record.demo

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf

/**
 * 波形数据缓冲区
 * 使用环形缓冲区存储波形数据
 */
class WaveformBuffer(initialSize: Int) {
    // 波形数据
    private var buffer: FloatArray = FloatArray(initialSize)
    // 当前写入位置
    private val _writePosition = mutableIntStateOf(0)
    val writePosition: MutableState<Int> = _writePosition

    // 缓冲区大小
    var size: Int = initialSize
        private set

    /**
     * 写入一个采样点
     */
    fun write(amplitude: Float) {
        buffer[_writePosition.intValue] = amplitude
        _writePosition.intValue = (_writePosition.intValue + 1) % size
    }

    /**
     * 获取指定位置的数据
     * @param position 从写入位置往前数的位置
     */
    fun get(position: Int): Float {
        val index = (_writePosition.intValue - position - 1 + size) % size
        return buffer[index]
    }

    /**
     * 调整缓冲区大小
     */
    fun resize(newSize: Int) {
        if (newSize == size) return

        val newBuffer = FloatArray(newSize)
        val copySize = minOf(newSize, size)
        
        // 从写入位置开始，复制最新的数据
        for (i in 0 until copySize) {
            val oldIndex = (_writePosition.intValue - i - 1 + size) % size
            val newIndex = (newSize - i - 1)
            newBuffer[newIndex] = buffer[oldIndex]
        }

        buffer = newBuffer
        size = newSize
        _writePosition.intValue = 0  // 重置写入位置到开头
    }

    /**
     * 清空缓冲区
     */
    fun clear() {
        buffer.fill(0f)
        _writePosition.intValue = 0
    }
} 