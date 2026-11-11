package io.github.mo3bdlaa.tvmouse

import android.app.Application
import io.github.mo3bdlaa.tvmouse.crash.CrashReporter

class TvMouseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
