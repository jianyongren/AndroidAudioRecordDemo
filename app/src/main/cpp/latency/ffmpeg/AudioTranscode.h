#pragma once

#include <string>

std::string decode_to_pcm_48k_s16_stereo(const char* inputPath, const char* cacheDir);
int encode_pcm_s16le_stereo_48k_to_m4a(const char* pcmPath, const char* outM4a);


