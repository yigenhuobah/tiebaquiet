package io.github.yigenhuobah.tiebaquiet

import android.app.Application
import io.github.yigenhuobah.tiebaquiet.settings.SettingsRepository

class TiebaQuietApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
    }
}
