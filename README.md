# Android 音频录制演示项目

这是一个使用Android原生音频API实现的录音演示项目，使用Jetpack Compose构建UI界面。

## 功能特性

- 音频录制与播放
- 支持可配置的录音参数：
  - 声道选择（单声道/立体声）
  - 采样率选择（8kHz/16kHz/44.1kHz/48kHz）
  - 数据格式选择（PCM_16BIT/PCM_FLOAT）
  - 回声消除开关
- 实时录音状态显示
- PCM格式音频文件的播放功能

## 系统要求

- Android SDK 版本：最低 API 21 (Android 5.0)
- Kotlin 版本：1.8.0 或更高
- Gradle 版本：7.0.0 或更高

## 权限要求

应用需要以下权限：
- `RECORD_AUDIO`: 用于录制音频
