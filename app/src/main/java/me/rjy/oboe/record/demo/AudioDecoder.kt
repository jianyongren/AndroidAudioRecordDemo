package me.rjy.oboe.record.demo

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 音频解码器，用于从音频文件（如M4A）中提取波形数据
 */
class AudioDecoder {
    
    data class AudioInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val totalSamples: Long,
        val durationMs: Long
    )
    
    data class WaveformData(
        val leftChannel: List<Float>,
        val rightChannel: List<Float>?,
        val audioInfo: AudioInfo
    )
    
    companion object {
        private const val TAG = "AudioDecoder"
        private const val MAX_WAVEFORM_POINTS = 1000
        private const val TIMEOUT_US = 10000L
    }
    
    /**
     * 从音频文件提取波形数据
     * @param filePath 音频文件路径
     * @param maxPoints 最大波形点数，默认为1000
     * @return 波形数据
     */
    suspend fun extractWaveform(
        filePath: String,
        maxPoints: Int = MAX_WAVEFORM_POINTS
    ): WaveformData? = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            
            // 查找音频轨道
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex == -1) {
                Log.e(TAG, "No audio track found in file: $filePath")
                return@withContext null
            }
            
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            
            // 获取音频信息
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationMs = durationUs / 1000
            val totalSamples = (durationUs * sampleRate) / 1_000_000L
            
            Log.d(TAG, "Audio info: sampleRate=$sampleRate, channels=$channelCount, duration=${durationMs}ms, totalSamples=$totalSamples")
            
            // 计算每个波形点对应的采样数
            val samplesPerPoint = max(1, totalSamples / maxPoints)
            
            // 创建解码器
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            
            // 解码并提取波形数据
            val waveformData = decodeAndExtractWaveform(
                extractor, 
                decoder, 
                channelCount, 
                samplesPerPoint,
                maxPoints
            )
            
            return@withContext WaveformData(
                leftChannel = waveformData.first,
                rightChannel = waveformData.second,
                audioInfo = AudioInfo(sampleRate, channelCount, totalSamples, durationMs)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting waveform from $filePath", e)
            return@withContext null
        } finally {
            decoder?.stop()
            decoder?.release()
            extractor?.release()
        }
    }
    
    /**
     * 查找音频轨道
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
    
    /**
     * 解码并提取波形数据
     */
    private fun decodeAndExtractWaveform(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        channelCount: Int,
        samplesPerPoint: Long,
        maxPoints: Int
    ): Pair<List<Float>, List<Float>?> {
        val leftChannel = mutableListOf<Float>()
        val rightChannel = if (channelCount > 1) mutableListOf<Float>() else null
        
        var sampleCount = 0L
        var currentPointSamples = 0L
        var currentLeftMax = 0f
        var currentRightMax = 0f
        
        val inputBuffers = decoder.inputBuffers
        val outputBuffers = decoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        
        while (!sawOutputEOS) {
            // 输入数据
            if (!sawInputEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = inputBuffers[inputBufferIndex]
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0, 
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, sampleSize, presentationTimeUs, 0
                        )
                        extractor.advance()
                    }
                }
            }
            
            // 输出数据
            val outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // 输出缓冲区改变，重新获取
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 输出格式改变
                    val newFormat = decoder.outputFormat
                    Log.d(TAG, "Output format changed: $newFormat")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 超时，继续尝试
                }
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = outputBuffers[outputBufferIndex]
                        
                        if (info.size > 0) {
                            // 处理解码后的PCM数据
                            processPcmData(
                                outputBuffer, 
                                info, 
                                channelCount,
                                samplesPerPoint,
                                leftChannel,
                                rightChannel,
                                currentPointSamples,
                                currentLeftMax,
                                currentRightMax
                            )
                        }
                        
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                }
            }
        }
        
        // 处理最后一批数据
        if (currentPointSamples > 0) {
            leftChannel.add(currentLeftMax)
            rightChannel?.add(currentRightMax)
        }
        
        Log.d(TAG, "Extracted waveform: left=${leftChannel.size}, right=${rightChannel?.size}")
        return Pair(leftChannel, rightChannel)
    }
    
    /**
     * 处理PCM数据并计算波形振幅
     * 采用正负交替的峰值收集策略，与RecorderViewModel保持一致
     */
    private fun processPcmData(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channelCount: Int,
        samplesPerPoint: Long,
        leftChannel: MutableList<Float>,
        rightChannel: MutableList<Float>?,
        currentPointSamples: Long,
        currentLeftMax: Float,
        currentRightMax: Float
    ) {
        var localCurrentPointSamples = currentPointSamples
        var localCurrentLeftMax = currentLeftMax
        var localCurrentRightMax = currentRightMax
        
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        
        // 假设是16位PCM数据（MediaCodec解码后通常是16位）
        val sampleCount = info.size / (2 * channelCount)
        val shortBuffer = buffer.asShortBuffer()
        val shorts = ShortArray(sampleCount * channelCount)
        shortBuffer.get(shorts)
        
        // 使用正负交替策略
        // 奇数点收集正值，偶数点收集负值
        val isPositiveValue = (leftChannel.size % 2 == 0)
        
        for (i in 0 until sampleCount) {
            val leftSample = shorts[i * channelCount].toFloat() / Short.MAX_VALUE.toFloat()
            localCurrentLeftMax = if (isPositiveValue) {
                max(leftSample, localCurrentLeftMax)
            } else {
                min(leftSample, localCurrentLeftMax)
            }
            
            if (channelCount > 1) {
                val rightSample = shorts[i * channelCount + 1].toFloat() / Short.MAX_VALUE.toFloat()
                localCurrentRightMax = if (isPositiveValue) {
                    max(rightSample, localCurrentRightMax)
                } else {
                    min(rightSample, localCurrentRightMax)
                }
            }
            
            localCurrentPointSamples++
            
            // 当达到每个波形点对应的采样数时，保存当前峰值并重置
            if (localCurrentPointSamples >= samplesPerPoint) {
                // 确保正值周期结果>=0，负值周期结果<=0
                val finalLeftMax = if (isPositiveValue) {
                    max(0f, localCurrentLeftMax).coerceIn(-1.0f, 1.0f)
                } else {
                    min(0f, localCurrentLeftMax).coerceIn(-1.0f, 1.0f)
                }
                
                val finalRightMax = if (channelCount > 1) {
                    if (isPositiveValue) {
                        max(0f, localCurrentRightMax).coerceIn(-1.0f, 1.0f)
                    } else {
                        min(0f, localCurrentRightMax).coerceIn(-1.0f, 1.0f)
                    }
                } else {
                    null
                }
                
                leftChannel.add(finalLeftMax)
                rightChannel?.add(finalRightMax ?: finalLeftMax)
                
                localCurrentPointSamples = 0
                localCurrentLeftMax = 0f
                localCurrentRightMax = 0f
                
                // 如果达到最大点数，提前退出
                if (leftChannel.size >= MAX_WAVEFORM_POINTS) {
                    return
                }
            }
        }
    }
}
