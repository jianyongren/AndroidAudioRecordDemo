package me.rjy.oboe.record.demo

object LatencyEvents {
    @Volatile
    var listener: ((String, Int, Double, Double, Double, Double, Double, Double, Double) -> Unit)? = null

    @Volatile
    var detectingListener: (() -> Unit)? = null

    @Volatile
    var errorListener: ((String, Int) -> Unit)? = null

    @JvmStatic
    fun notifyDetecting() {
        detectingListener?.invoke()
    }

    @JvmStatic
    fun notifyError(errorMessage: String, errorCode: Int) {
        errorListener?.invoke(errorMessage, errorCode)
    }

    @JvmStatic
    fun notifyCompleted(
        outputPath: String,
        resultCode: Int,
        avgDelayMs: Double,
        delay1: Double, corr1: Double,
        delay2: Double, corr2: Double,
        delay3: Double, corr3: Double
    ) {
        listener?.invoke(outputPath, resultCode, avgDelayMs, delay1, corr1, delay2, corr2, delay3, corr3)
    }
}


