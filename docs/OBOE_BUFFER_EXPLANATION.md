# Oboe 音频缓冲区参数说明

## 三个关键参数的含义

### 1. **BufferCapacity（缓冲区容量）**
- **含义**：系统为音频流分配的最大缓冲区大小（总容量）
- **作用**：物理内存分配的界限，表示缓冲区可以存储的最大帧数
- **特点**：
  - 通常由系统根据设备能力自动设置
  - 可以通过 `setBufferCapacityInFrames()` 请求，但系统可能不满足
  - 总是 ≥ BufferSize

**你的配置**：
- 输出流：768 帧 = 16ms @ 48kHz
- 输入流：3072 帧 = 64ms @ 48kHz

### 2. **BufferSize（缓冲区大小）**
- **含义**：**实际参与音频处理的缓冲区大小**
- **作用**：这是**直接影响延迟的关键参数**
- **延迟计算**：`延迟时间 = BufferSize / SampleRate`
- **特点**：
  - 可以通过 `setBufferSizeInFrames()` 动态调整
  - 必须 ≤ BufferCapacity
  - **建议设置为 FramesPerBurst 的整数倍**以获得最佳性能

**你的配置**：
- 输出流：192 帧 = **4ms 延迟** @ 48kHz
- 输入流：2976 帧 = **62ms 延迟** @ 48kHz ⚠️

### 3. **FramesPerBurst（每突发帧数）**
- **含义**：硬件音频系统每次处理的基本单位帧数
- **作用**：
  - 硬件 DMA 传输的基本单位
  - 决定音频数据的"粒度"
  - 由硬件固定，无法更改
- **特点**：
  - 通常为 48、96、192 等值
  - BufferSize **应该**是 FramesPerBurst 的整数倍
  - 不同设备的 FramesPerBurst 可能不同

**你的配置**：
- 输出流：96 帧
- 输入流：96 帧 ✓（相同）

## 对延迟测试准确性的影响

### ⚠️ **当前问题分析**

根据你的配置：

```
输出流延迟 = 192 / 48000 = 4ms
输入流延迟 = 2976 / 48000 = 62ms
总延迟 = 4 + 62 = 66ms
```

**问题**：
1. **输入流延迟过大（62ms）**：这会显著影响延迟测试的准确性
2. **左右声道不对齐**：输出流 4ms，输入流 62ms，相差 58ms
3. **延迟测试结果**：实际测量的是 `真实延迟 + 62ms`，误差较大

### ✅ **优化建议**

1. **设置更小的输入流 BufferSize**：
   ```cpp
   // 在打开输入流后，尝试设置最小缓冲区
   int32_t optimalBufferSize = inputStream->getFramesPerBurst() * 2; // 或 * 4
   auto result = inputStream->setBufferSizeInFrames(optimalBufferSize);
   ```

2. **输出流也建议优化**：
   ```cpp
   // 输出流也可以设置为更小的值
   int32_t optimalOutBufferSize = outputStream->getFramesPerBurst() * 2;
   outputStream->setBufferSizeInFrames(optimalOutBufferSize);
   ```

3. **理想的延迟配置**：
   - 输出流：96 * 2 = 192 帧 = 4ms（当前已是最优）
   - 输入流：96 * 2 = 192 帧 = 4ms（从当前的 62ms 降到 4ms）
   - 总延迟：8ms（比当前的 66ms 低得多）

### 📊 **BufferSize 对延迟的影响表**

| BufferSize | 延迟（@48kHz） | 说明 |
|-----------|---------------|------|
| 96 (1 burst) | 2ms | 最小，可能不稳定 |
| 192 (2 bursts) | 4ms | 推荐最小值 |
| 288 (3 bursts) | 6ms | 平衡选择 |
| 384 (4 bursts) | 8ms | 稳定选择 |
| 2976 | 62ms | 当前输入流，过大 |

## 实现建议

建议在打开流后立即尝试优化 BufferSize：

```cpp
// 输出流优化
if (outputStream_) {
    int32_t framesPerBurst = outputStream_->getFramesPerBurst();
    int32_t targetBufferSize = framesPerBurst * 2; // 2倍突发大小
    auto result = outputStream_->setBufferSizeInFrames(targetBufferSize);
    if (result) {
        LOGI("Output buffer optimized: %d -> %d frames (%.2f ms)",
             outputStream_->getBufferSizeInFrames(),
             result.value(),
             result.value() * 1000.0 / kSampleRate);
    }
}

// 输入流优化
if (inputStream_) {
    int32_t framesPerBurst = inputStream_->getFramesPerBurst();
    int32_t targetBufferSize = framesPerBurst * 2; // 尽量小
    auto result = inputStream_->setBufferSizeInFrames(targetBufferSize);
    if (result) {
        LOGI("Input buffer optimized: %d -> %d frames (%.2f ms)",
             inputStream_->getBufferSizeInFrames(),
             result.value(),
             result.value() * 1000.0 / kSampleRate);
    } else {
        // 如果设置失败，尝试稍大一点的值
        targetBufferSize = framesPerBurst * 4;
        result = inputStream_->setBufferSizeInFrames(targetBufferSize);
        if (result) {
            LOGI("Input buffer set to 4x burst: %d frames (%.2f ms)",
                 result.value(), result.value() * 1000.0 / kSampleRate);
        }
    }
}
```

## 总结

1. **BufferSize 是影响延迟的关键因素**
2. **当前输入流 62ms 延迟过大**，需要优化
3. **建议设置为 FramesPerBurst 的 2-4 倍**
4. **优化后可以将总延迟从 66ms 降到 8ms 左右**，大大提高测试准确性

