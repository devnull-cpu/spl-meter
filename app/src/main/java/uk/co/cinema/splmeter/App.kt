package uk.co.cinema.splmeter

import android.app.Application
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.data.SessionStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        SessionStore.init(this)
    }
}
