package ar.com.brilla.trivia

import android.app.Application

class BrillaApp : Application() {
    lateinit var runtime: AppRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime(this)
    }
}
