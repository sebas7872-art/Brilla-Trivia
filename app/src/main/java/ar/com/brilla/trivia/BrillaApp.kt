package ar.com.brilla.trivia

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BrillaApp: Application() {
    lateinit var runtime: AppRuntime; private set
    override fun onCreate() { super.onCreate(); runtime=AppRuntime(this) }
}
class StopReceiver: BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { (context.applicationContext as BrillaApp).runtime.stopNow() } }
