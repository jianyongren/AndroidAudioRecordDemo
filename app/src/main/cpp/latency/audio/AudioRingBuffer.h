#pragma once

#include <cstdint>
#include <vector>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstring>
#include "RingBuffer.h"

extern "C" {
#include <libswresample/swresample.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
}

struct AudioFormatCfg {
    int sampleRate{48000};
    int channels{1};
    bool isFloat{false};
};

// AudioRingBuffer: 存储原始输入格式数据；读取时按输出配置进行实时转换。
// 支持：采样率转换（线性插值）、float/i16转换、单声道处理。
class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t capacityBytes)
        : rb_(capacityBytes) {}
    ~AudioRingBuffer() {
        if (swr_) swr_free(&swr_);
        av_channel_layout_uninit(&in_ch_layout_);
        av_channel_layout_uninit(&out_ch_layout_);
    }

    // 初始化：设置输入/输出格式并创建 SwrContext
    bool init(const AudioFormatCfg& in, const AudioFormatCfg& out) {
        inFmt_ = in;
        outFmt_ = out;
        if (swr_) { swr_free(&swr_); }
        av_channel_layout_uninit(&in_ch_layout_);
        av_channel_layout_uninit(&out_ch_layout_);
        // 规范化参数：确保采样率、通道数为合法正值
        int inCh = inFmt_.channels > 0 ? inFmt_.channels : 1;
        int outCh = outFmt_.channels > 0 ? outFmt_.channels : 1;
        int inSr = inFmt_.sampleRate > 0 ? inFmt_.sampleRate : 48000;
        int outSr = outFmt_.sampleRate > 0 ? outFmt_.sampleRate : 48000;
        enum AVSampleFormat in_fmt = inFmt_.isFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;
        enum AVSampleFormat out_fmt = outFmt_.isFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;
        av_channel_layout_default(&in_ch_layout_, inCh);
        av_channel_layout_default(&out_ch_layout_, outCh);
        if (swr_alloc_set_opts2(&swr_, &out_ch_layout_, out_fmt, outSr,
                                 &in_ch_layout_,  in_fmt,  inSr,
                                 0, nullptr) < 0 || !swr_) {
            return false;
        }
        if (swr_init(swr_) < 0) {
            swr_free(&swr_);
            return false;
        }
        // 将规范化后的值写回配置，后续读取无需再次默认/校验
        inFmt_.channels = inCh;
        outFmt_.channels = outCh;
        inFmt_.sampleRate = inSr;
        outFmt_.sampleRate = outSr;
        hasLast_ = false;
        lastFrame_.assign(outCh, 0.0f);
        return true;
    }

    // 直接写入字节（按照输入格式原样存储）
    size_t writeBytes(const uint8_t* data, size_t bytes) {
        return rb_.write(data, bytes);
    }

    void clear() {
        rb_.clear();
        hasLast_ = false;
        // 依赖 init() 已完成规范化，直接使用 outFmt_.channels
        lastFrame_.assign(outFmt_.channels, 0.0f);
        if (swr_) {
            // flush internal delay if any
            swr_convert(swr_, nullptr, 0, nullptr, 0);
        }
    }

    // 转换读取：输出为 outFmt_ 配置的交错格式；outFrames 为目标帧数
    // 返回写入到 out 的 frame 数量
    size_t readConvert(void* out, size_t outFrames) {
        if (outFrames <= 0) return 0;
        // 计算需要的输入帧数（粗略估算）并从内部 RingBuffer 读取输入块
        const int inCh = inFmt_.channels;
        const bool inFloat = inFmt_.isFloat;
        const double inSr = static_cast<double>(inFmt_.sampleRate);
        const double outSr = static_cast<double>(outFmt_.sampleRate);
        const double ratio = inSr / outSr;
        size_t needInFrames = static_cast<size_t>(outFrames * ratio) + 1;
        const size_t inBytesPerSample = inFloat ? sizeof(float) : sizeof(int16_t);
        const size_t inBytesPerFrame = inBytesPerSample * inCh;
        const size_t needInBytes = needInFrames * inBytesPerFrame;

        // 仅在当前缓冲区大小不足时扩容，避免每次都触发resize
        if (tmpIn_.size() < needInBytes) {
            tmpIn_.resize(needInBytes);
        }
        size_t gotBytes = rb_.read(tmpIn_.data(), needInBytes);
        if (gotBytes == 0) return 0;
        size_t gotInFrames = gotBytes / inBytesPerFrame;

        // 要求在 init 中已初始化 SwrContext
        if (!swr_) return 0;

        // 执行重采样与格式/通道转换（交错）
        const uint8_t* inData[1] = { reinterpret_cast<const uint8_t*>(tmpIn_.data()) };
        uint8_t* outData[1] = { reinterpret_cast<uint8_t*>(out) };
        int conv = swr_convert(swr_, outData, static_cast<int>(outFrames), inData, static_cast<int>(gotInFrames));
        return static_cast<size_t>(conv);
    }

private:
    RingBuffer rb_;
    AudioFormatCfg inFmt_{};
    AudioFormatCfg outFmt_{};
    bool hasLast_{false};
    std::vector<float> lastFrame_{};
    std::vector<uint8_t> tmpIn_;
    std::vector<float> workIn_;
    std::vector<float> resampled_;
    SwrContext* swr_{nullptr};
    AVChannelLayout in_ch_layout_{};
    AVChannelLayout out_ch_layout_{};
};