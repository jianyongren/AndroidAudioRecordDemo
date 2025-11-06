#pragma once

#include <string>

// Flexible helpers that allow specifying target sample rate and channels.
// Output PCM is interleaved; choose S16 or float via outputIsFloat.
std::string decode_to_pcm_interleaved(const char* inputPath,
                                          const char* cacheDir,
                                          int outSampleRate,
                                          int outChannels,
                                          const char* outFileName,
                                          bool outputIsFloat);
// Generic encode: encode interleaved PCM (S16 or float) to AAC/M4A.
// If inputIsFloat is true, input PCM is 32-bit float interleaved; otherwise 16-bit S16.
int encode_pcm_to_m4a(const char* pcmPath,
                      const char* outM4a,
                      int inSampleRate,
                      int inChannels,
                      bool inputIsFloat);


