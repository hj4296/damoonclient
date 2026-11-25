package com.damoon.sseuregi

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Assistant.init(this)
        Speaker.init(this)
    }
}
