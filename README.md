# Android Audio Recording Demo

[中文](README_zh.md) | English

An audio recording and processing demonstration project using Oboe and Android native audio APIs, providing rich audio recording, playback, and testing features.

## Features

### 🎙️ Audio Recording

#### Recording Method Selection

- **Oboe Recording**: High-quality recording with low latency using Google Oboe library
- **AudioRecord Recording**: Using Android native AudioRecord API

#### Rich Recording Parameter Configuration

- **Channel Selection**: Mono / Stereo
- **Sample Rate**: 8kHz / 16kHz / 44.1kHz / 48kHz
- **Data Format**: PCM_16BIT / PCM_FLOAT
- **Audio Source**: Default, Microphone, Voice Call, Voice Recognition, Camera, Unprocessed, Performance, etc.
- **Recording Device**: Support for built-in microphone, USB devices, Bluetooth devices, wired headphones, etc.
- **Audio API** (Oboe mode only): Unspecified / AAudio / OpenSL ES

#### Real-time Waveform Display

- Real-time audio waveform display during recording
- Single waveform display in mono mode
- Separate left and right channel waveforms in stereo mode
- Playback waveform and progress bar during playback

#### PCM File Management

- Automatic filename generation with parameter information (recording method, channels, sample rate, format, etc.)
- File list management with support for viewing and selecting recorded PCM files
- Support for deleting recording files (long press to enter edit mode)
- Display recording file path with one-click path copying

### 🎵 Audio Playback

#### Playback Method Selection

- **Oboe Playback**: Low-latency playback using Oboe library
- **AudioTrack Playback**: Using Android native AudioTrack API

#### PCM File Playback

- Automatic parameter parsing from filename (channels, sample rate, format)
- Support for playing recorded PCM files
- Waveform and playback progress display during playback
- Automatic resource cleanup after playback completion

### 📊 Recording Latency Test

- Automatic recording latency detection
- Display average latency time
- Display latency window information with highest correlation (Top 3)
- Generate latency test recording files (M4A format)
- Support for playing and sharing test result files
- Automatic cleanup of old test files (keep latest 20 files)

### 🎬 Local Player

- Support for local audio/video file playback

### ⚙️ Other Features

- **Parameter Persistence**: Automatic saving and restoration of recording parameter settings
- **Device Dynamic Monitoring**: Automatic detection of audio device plug/unplug and device list refresh
- **Error Handling**: User-friendly error prompts and handling mechanisms
- **Modern UI**: Built with Jetpack Compose, supporting landscape and portrait orientation adaptation
- **File Sharing**: Support for sharing recording files through system share functionality
- **Multi-language Support**: Supports English, Chinese, and Japanese

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Audio Libraries**: Oboe, Android AudioRecord/AudioTrack
- **Native Code**: C++ (JNI)
- **Architecture Pattern**: MVVM (ViewModel + LiveData)

## Permissions

The application requires the following permissions:

- `RECORD_AUDIO`: For recording audio
- `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`: For reading media files (Android 13+ / older versions)

## Screenshots

<p align="center">
  <img src="./docs/audio_recorder_zh.png" alt="App Screenshot" width="45%" />
  &nbsp;&nbsp;
  <img src="./docs/audio_recorder_latency_test_zh.png" alt="Latency Test" width="45%" />
</p>
