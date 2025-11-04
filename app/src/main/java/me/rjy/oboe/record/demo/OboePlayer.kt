package me.rjy.oboe.record.demo

import android.util.Log

class OboePlayer(
    filePath: String,
    sampleRate: Int,
    isStereo: Boolean,
    isFloat: Boolean,
    audioApi: Int
) {
    companion object {
        private const val TAG = "OboePlayer"
        
        init {
            System.loadLibrary("oboe_recorder_demo")
        }

        // 音频API类型
        const val AUDIO_API_UNSPECIFIED = 0
        const val AUDIO_API_AAUDIO = 1
        const val AUDIO_API_OPENSLES = 2
    }

    // 回调接口
    interface OnPlaybackCompleteListener {
        fun onPlaybackComplete()
    }

    private var listener: OnPlaybackCompleteListener? = null
    private var nativePlayer: Long = 0 // 保存C++对象的指针

    init {
        nativePlayer = createNativePlayer(filePath, sampleRate, isStereo, isFloat, audioApi)
        if (nativePlayer == 0L) {
            throw RuntimeException("Failed to create native player")
        }
    }

    fun setOnPlaybackCompleteListener(listener: OnPlaybackCompleteListener) {
        this.listener = listener
        if (nativePlayer != 0L) {
            setCallbackObject(this)
        }
    }

    fun start(): Boolean {
        if (nativePlayer == 0L) {
            Log.e(TAG, "Native player is not initialized")
            return false
        }
        return nativeStart(nativePlayer)
    }

    fun stop() {
        if (nativePlayer != 0L) {
            nativeStop(nativePlayer)
        }
    }

    fun release() {
        if (nativePlayer != 0L) {
            nativeRelease(nativePlayer)
            nativePlayer = 0
        }
    }

    fun getPlaybackProgress(): Float {
        if (nativePlayer == 0L) {
            throw RuntimeException("Native player is not initialized")
        }
        return nativeGetPlaybackProgress(nativePlayer)
    }

    // 供C++层调用的回调方法
    private fun onPlaybackComplete() {
        Log.d(TAG, "onPlaybackComplete")
        listener?.onPlaybackComplete()
    }

    // Native方法声明
    private external fun createNativePlayer(
        filePath: String,
        sampleRate: Int,
        isStereo: Boolean,
        isFloat: Boolean,
        audioApi: Int,
        deviceId: Int = -1,
    ): Long

    private external fun nativeRelease(nativePlayer: Long)
    private external fun nativeStart(nativePlayer: Long): Boolean
    private external fun nativeStop(nativePlayer: Long)
    private external fun setCallbackObject(callbackObject: Any)
    private external fun nativeGetPlaybackProgress(nativePlayer: Long): Float

    protected fun finalize() {
        release()
    }
} 