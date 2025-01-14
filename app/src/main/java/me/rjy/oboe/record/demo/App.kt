package me.rjy.oboe.record.demo

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context

class App : Application() {
    init {
        context = this
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
    }
}