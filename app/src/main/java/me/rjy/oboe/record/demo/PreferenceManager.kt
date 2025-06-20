package me.rjy.oboe.record.demo

import android.content.Context
import android.media.MediaRecorder

data class RecorderSettings(
    val useOboe: Boolean,
    val useOboePlayback: Boolean,
    val isStereo: Boolean,
    val sampleRate: Int,
    val isFloat: Boolean,
    val echoCanceler: Boolean,
    val audioSource: Int,
    val audioApi: Int
)

object PreferenceManager {
    private const val PREF_NAME = "recorder_settings"
    private const val KEY_USE_OBOE = "use_oboe"
    private const val KEY_USE_OBOE_PLAYBACK = "use_oboe_playback"
    private const val KEY_IS_STEREO = "is_stereo"
    private const val KEY_SAMPLE_RATE = "sample_rate"
    private const val KEY_IS_FLOAT = "is_float"
    private const val KEY_ECHO_CANCELER = "echo_canceler"
    private const val KEY_AUDIO_SOURCE = "audio_source"
    private const val KEY_AUDIO_API = "audio_api"

    fun saveSettings(context: Context, settings: RecorderSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_USE_OBOE, settings.useOboe)
            putBoolean(KEY_USE_OBOE_PLAYBACK, settings.useOboePlayback)
            putBoolean(KEY_IS_STEREO, settings.isStereo)
            putInt(KEY_SAMPLE_RATE, settings.sampleRate)
            putBoolean(KEY_IS_FLOAT, settings.isFloat)
            putBoolean(KEY_ECHO_CANCELER, settings.echoCanceler)
            putInt(KEY_AUDIO_SOURCE, settings.audioSource)
            putInt(KEY_AUDIO_API, settings.audioApi)
            apply()
        }
    }

    fun loadSettings(context: Context): RecorderSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return RecorderSettings(
            useOboe = prefs.getBoolean(KEY_USE_OBOE, true),
            useOboePlayback = prefs.getBoolean(KEY_USE_OBOE_PLAYBACK, true),
            isStereo = prefs.getBoolean(KEY_IS_STEREO, false),
            sampleRate = prefs.getInt(KEY_SAMPLE_RATE, 48000),
            isFloat = prefs.getBoolean(KEY_IS_FLOAT, false),
            echoCanceler = prefs.getBoolean(KEY_ECHO_CANCELER, false),
            audioSource = prefs.getInt(KEY_AUDIO_SOURCE, MediaRecorder.AudioSource.DEFAULT),
            audioApi = prefs.getInt(KEY_AUDIO_API, 0)
        )
    }
} 