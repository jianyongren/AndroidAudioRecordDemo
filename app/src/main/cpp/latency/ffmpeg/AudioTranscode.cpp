#include "AudioTranscode.h"
#include <cstdio>
#include <cstring>
#include <vector>
#include "../logging.h"
#include "../config.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

#define LOG_TAG "AudioTranscode"

static std::string joinPath(const std::string& a, const std::string& b) {
    if (a.empty()) return b;
    if (a.back() == '/') return a + b;
    return a + "/" + b;
}

// Flexible decode: decode input audio to interleaved PCM (S16 or float)
// with specified sample rate and channel count. Allows custom output filename.
std::string decode_to_pcm_interleaved(const char* inputPath,
                                          const char* cacheDir,
                                          int outSampleRate,
                                          int outChannels,
                                          const char* outFileName,
                                          bool outputIsFloat) {
    LOGI("decode_to_pcm_interleaved in=%s cache=%s sr=%d ch=%d file=%s fmt=%s",
         inputPath ? inputPath : "(null)", cacheDir ? cacheDir : "(null)",
         outSampleRate, outChannels, outFileName ? outFileName : "(null)", outputIsFloat ? "f32" : "s16");
    AVFormatContext* fmt = nullptr;
    if (avformat_open_input(&fmt, inputPath, nullptr, nullptr) < 0) { LOGE("avformat_open_input failed"); return {}; }
    if (avformat_find_stream_info(fmt, nullptr) < 0) { avformat_close_input(&fmt); return {}; }

    int audioStreamIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audioStreamIndex < 0) { avformat_close_input(&fmt); return {}; }

    AVStream* st = fmt->streams[audioStreamIndex];
    const AVCodec* codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) { LOGE("decoder not found"); avformat_close_input(&fmt); return {}; }
    AVCodecContext* ctx = avcodec_alloc_context3(codec);
    if (!ctx) { LOGE("alloc codec ctx failed"); avformat_close_input(&fmt); return {}; }
    if (avcodec_parameters_to_context(ctx, st->codecpar) < 0) { avcodec_free_context(&ctx); avformat_close_input(&fmt); return {}; }
    if (avcodec_open2(ctx, codec, nullptr) < 0) { LOGE("avcodec_open2 failed"); avcodec_free_context(&ctx); avformat_close_input(&fmt); return {}; }

    SwrContext* swr = nullptr;
    AVChannelLayout in_ch_layout{};
    AVChannelLayout out_ch_layout{};
    if (st->codecpar->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&in_ch_layout, &st->codecpar->ch_layout);
    } else if (ctx->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&in_ch_layout, &ctx->ch_layout);
    } else {
        av_channel_layout_default(&in_ch_layout, ctx->ch_layout.nb_channels > 0 ? ctx->ch_layout.nb_channels : 2);
    }
    av_channel_layout_default(&out_ch_layout, outChannels > 0 ? outChannels : 2);

    int out_sample_rate = outSampleRate > 0 ? outSampleRate : kSampleRate;
    AVSampleFormat out_fmt = outputIsFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;
    if (swr_alloc_set_opts2(&swr,
                            &out_ch_layout, out_fmt, out_sample_rate,
                            &in_ch_layout,  ctx->sample_fmt, ctx->sample_rate,
                            0, nullptr) < 0 || !swr) {
        LOGE("swr_alloc_set_opts2 failed");
        av_channel_layout_uninit(&in_ch_layout);
        av_channel_layout_uninit(&out_ch_layout);
        avcodec_free_context(&ctx);
        avformat_close_input(&fmt);
        return {};
    }
    if (swr_init(swr) < 0) {
        LOGE("swr_init failed");
        swr_free(&swr);
        av_channel_layout_uninit(&in_ch_layout);
        av_channel_layout_uninit(&out_ch_layout);
        avcodec_free_context(&ctx);
        avformat_close_input(&fmt);
        return {};
    }

    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    if (!pkt || !frame) { LOGE("alloc pkt/frame failed"); if(pkt) av_packet_free(&pkt); if(frame) av_frame_free(&frame); swr_free(&swr); avcodec_free_context(&ctx); avformat_close_input(&fmt); return {}; }

    std::string ofn;
    if (outFileName && *outFileName) {
        ofn = std::string(outFileName);
    } else {
        ofn = outputIsFloat ? std::string("orig_f32le.pcm") : std::string("orig_s16le.pcm");
    }
    std::string outPath = joinPath(cacheDir, ofn);
    FILE* fp = fopen(outPath.c_str(), "wb");
    if (!fp) { LOGE("fopen pcm failed: %s", outPath.c_str()); av_packet_free(&pkt); av_frame_free(&frame); swr_free(&swr); avcodec_free_context(&ctx); avformat_close_input(&fmt); return {}; }

    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index != audioStreamIndex) { av_packet_unref(pkt); continue; }
        if (avcodec_send_packet(ctx, pkt) == 0) {
            int ret;
            while ((ret = avcodec_receive_frame(ctx, frame)) == 0) {
                int out_nb_samples = av_rescale_rnd(swr_get_delay(swr, ctx->sample_rate) + frame->nb_samples, out_sample_rate, ctx->sample_rate, AV_ROUND_UP);
                uint8_t* out_buf = nullptr;
                int out_linesize = 0;
                int out_channels = out_ch_layout.nb_channels;
                av_samples_alloc(&out_buf, &out_linesize, out_channels, out_nb_samples, out_fmt, 0);
                int conv = swr_convert(swr, &out_buf, out_nb_samples, (const uint8_t**)frame->extended_data, frame->nb_samples);
                if (conv > 0) {
                    int bytes_per_sample = av_get_bytes_per_sample(out_fmt);
                    int write_bytes = conv * out_channels * bytes_per_sample;
                    fwrite(out_buf, 1, write_bytes, fp);
                }
                av_freep(&out_buf);
            }
        }
        av_packet_unref(pkt);
    }

    fclose(fp);
    av_packet_free(&pkt);
    av_frame_free(&frame);
    swr_free(&swr);
    av_channel_layout_uninit(&in_ch_layout);
    av_channel_layout_uninit(&out_ch_layout);
    avcodec_free_context(&ctx);
    avformat_close_input(&fmt);
    LOGI("decoded pcm saved: %s", outPath.c_str());
    return outPath;
}

static inline const char* ff_err2str(int errnum, char* buf, size_t sz) {
    if (!buf || sz < AV_ERROR_MAX_STRING_SIZE) return "";
    return av_make_error_string(buf, sz, errnum);
}

// Flexible encode: encode S16 interleaved PCM to AAC/M4A with specified
// input sample rate and channels.
int encode_pcm_to_m4a(const char* pcmPath,
                      const char* outM4a,
                      int inSampleRate,
                      int inChannels,
                      bool inputIsFloat) {
    LOGI("encode to m4a (flex) start: in=%s out=%s sr=%d ch=%d fmt=%s",
         pcmPath ? pcmPath : "(null)", outM4a ? outM4a : "(null)", inSampleRate, inChannels,
         inputIsFloat ? "float" : "s16");
    const AVCodec* codec = avcodec_find_encoder_by_name("aac");
    if (!codec) { LOGE("aac encoder not found"); return -1; }
    AVFormatContext* fmt = nullptr;
    if (avformat_alloc_output_context2(&fmt, nullptr, "mp4", outM4a) < 0 || !fmt) { LOGE("alloc output ctx failed"); return -2; }
    AVStream* st = avformat_new_stream(fmt, nullptr);
    if (!st) { LOGE("new stream failed"); avformat_free_context(fmt); return -3; }
    AVCodecContext* c = avcodec_alloc_context3(codec);
    if (!c) { LOGE("alloc codec ctx failed"); avformat_free_context(fmt); return -4; }
    av_channel_layout_default(&c->ch_layout, inChannels > 0 ? inChannels : kChannelCount);
    c->sample_rate = inSampleRate > 0 ? inSampleRate : kSampleRate;
    c->sample_fmt = codec->sample_fmts ? codec->sample_fmts[0] : AV_SAMPLE_FMT_FLTP;
    c->bit_rate = 128000;
    c->time_base = {1, c->sample_rate};
    st->time_base = c->time_base;
    if (fmt->oformat->flags & AVFMT_GLOBALHEADER) c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if (avcodec_open2(c, codec, nullptr) < 0) { LOGE("avcodec_open2 failed"); avcodec_free_context(&c); avformat_free_context(fmt); return -5; }
    if (avcodec_parameters_from_context(st->codecpar, c) < 0) { LOGE("parameters_from_context failed"); avcodec_free_context(&c); avformat_free_context(fmt); return -6; }

    if (!(fmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&fmt->pb, outM4a, AVIO_FLAG_WRITE) < 0) { LOGE("avio_open failed: %s", outM4a); avcodec_free_context(&c); avformat_free_context(fmt); return -7; }
    }
    if (avformat_write_header(fmt, nullptr) < 0) { LOGE("write_header failed"); if(fmt->pb) avio_close(fmt->pb); avcodec_free_context(&c); avformat_free_context(fmt); return -8; }

    SwrContext* swr = nullptr;
    AVChannelLayout in_ch_layout{};  // input is interleaved (S16 or float)
    av_channel_layout_default(&in_ch_layout, inChannels > 0 ? inChannels : kChannelCount);
    enum AVSampleFormat in_sample_fmt = inputIsFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;
    if (swr_alloc_set_opts2(&swr, &c->ch_layout, c->sample_fmt, c->sample_rate,
                            &in_ch_layout, in_sample_fmt, inSampleRate > 0 ? inSampleRate : kSampleRate, 0, nullptr) < 0 || !swr) {
        LOGE("swr alloc2 failed");
        av_channel_layout_uninit(&in_ch_layout);
        av_write_trailer(fmt); if(fmt->pb) avio_close(fmt->pb); avcodec_free_context(&c); avformat_free_context(fmt); return -9;
    }
    if (swr_init(swr) < 0) { LOGE("swr init failed"); av_channel_layout_uninit(&in_ch_layout); if(swr) swr_free(&swr); av_write_trailer(fmt); if(fmt->pb) avio_close(fmt->pb); avcodec_free_context(&c); avformat_free_context(fmt); return -9; }

    FILE* fp = fopen(pcmPath, "rb");
    if (!fp) { LOGE("open input pcm failed: %s", pcmPath ? pcmPath : "(null)"); swr_free(&swr); av_write_trailer(fmt); if(fmt->pb) avio_close(fmt->pb); avcodec_free_context(&c); avformat_free_context(fmt); return -10; }

    const int frame_size = c->frame_size > 0 ? c->frame_size : 1024;
    const int bytes_per_sample = inputIsFloat ? static_cast<int>(sizeof(float)) : kBytesPerSample;
    const int bytes_per_frame = frame_size * (inChannels > 0 ? inChannels : kChannelCount) * bytes_per_sample;
    std::vector<uint8_t> inBuf(bytes_per_frame);
    AVFrame* frame = av_frame_alloc();
    AVPacket* pkt = av_packet_alloc();
    int64_t pts = 0;
    while (true) {
        size_t n = fread(inBuf.data(), 1, inBuf.size(), fp);
        if (n < (size_t)inBuf.size()) {
            if (n == 0) break;
            std::memset(inBuf.data()+n, 0, inBuf.size()-n);
        }
        AVFrame* inFrame = av_frame_alloc();
        av_channel_layout_default(&inFrame->ch_layout, inChannels > 0 ? inChannels : kChannelCount);
        inFrame->sample_rate = inSampleRate > 0 ? inSampleRate : kSampleRate;
        inFrame->format = inputIsFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;
        inFrame->nb_samples = frame_size;
        av_frame_get_buffer(inFrame, 0);
        std::memcpy(inFrame->data[0], inBuf.data(), inBuf.size());

        av_channel_layout_copy(&frame->ch_layout, &c->ch_layout);
        frame->sample_rate = c->sample_rate;
        frame->format = c->sample_fmt;
        frame->nb_samples = frame_size;
        if (av_frame_get_buffer(frame, 0) < 0) { av_frame_free(&inFrame); break; }
        int conv = swr_convert(swr, frame->data, frame_size, (const uint8_t**)inFrame->extended_data, frame_size);
        av_frame_free(&inFrame);
        if (conv <= 0) break;
        frame->pts = pts; pts += frame_size;

        int sret = avcodec_send_frame(c, frame);
        if (sret == 0) {
            while (true) {
                int rret = avcodec_receive_packet(c, pkt);
                if (rret == 0) {
                    pkt->stream_index = st->index;
                    av_packet_rescale_ts(pkt, c->time_base, st->time_base);
                    av_interleaved_write_frame(fmt, pkt);
                    av_packet_unref(pkt);
                } else if (rret == AVERROR(EAGAIN) || rret == AVERROR_EOF) {
                    break;
                } else {
                    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
                    LOGE("receive_packet error: %s", ff_err2str(rret, buf, sizeof(buf)));
                    break;
                }
            }
        } else {
            char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
            LOGE("send_frame error: %s", ff_err2str(sret, buf, sizeof(buf)));
        }
    }
    avcodec_send_frame(c, nullptr);
    while (true) {
        int rret = avcodec_receive_packet(c, pkt);
        if (rret == 0) {
            pkt->stream_index = st->index;
            av_packet_rescale_ts(pkt, c->time_base, st->time_base);
            av_interleaved_write_frame(fmt, pkt);
            av_packet_unref(pkt);
        } else if (rret == AVERROR(EAGAIN) || rret == AVERROR_EOF) {
            break;
        } else {
            char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
            LOGE("flush receive_packet error: %s", ff_err2str(rret, buf, sizeof(buf)));
            break;
        }
    }
    fclose(fp);
    av_frame_free(&frame);
    av_packet_free(&pkt);
    swr_free(&swr);
    av_channel_layout_uninit(&in_ch_layout);
    av_write_trailer(fmt);
    if (fmt->pb) avio_close(fmt->pb);
    avcodec_free_context(&c);
    avformat_free_context(fmt);
    LOGI("encode to m4a done: %s", outM4a);
    return 0;
}


