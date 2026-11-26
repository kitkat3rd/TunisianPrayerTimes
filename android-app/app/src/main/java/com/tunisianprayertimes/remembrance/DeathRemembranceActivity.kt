package com.tunisianprayertimes.remembrance

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.adhkar.DeathRemembranceTexts
import com.tunisianprayertimes.ui.DeathRemembranceNoticeCard
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.util.Locale

/**
 * The reminder's notification opens the day's «ذكر الموت» here, as a card over whatever was on screen, so reading it
 * does not take the reader into the app. Its own task, left out of recents, so closing it returns to where they were.
 */
class DeathRemembranceActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val item = readingFrom(intent) ?: DeathReminder.remembranceFor(DeathReminder.today(this))
        setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                DeathRemembranceNoticeCard(
                    item = item,
                    onSettings = {
                        startActivity(
                            // SINGLE_TOP, like every other way in: a running screen switches tab instead of being rebuilt.
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_WORSHIP)
                                .putExtra(MainTabNavigation.EXTRA_WORSHIP_ACTION, MainTabNavigation.WORSHIP_ACTION_REMEMBRANCE),
                        )
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        internal const val EXTRA_READING_ID = "com.tunisianprayertimes.remembrance.READING_ID"
        const val EXTRA_EPOCH_DAY = "com.tunisianprayertimes.remembrance.EPOCH_DAY"

        private fun readingFrom(intent: Intent): DeathRemembranceTexts.Remembrance? {
            val readings = DeathRemembranceTexts.ALL
            intent.getStringExtra(EXTRA_READING_ID)?.let { id -> return readings.find { it.id == id } }
            // Older notifications carried only the date of their reading in the original 28-day rotation.
            val day = intent.getLongExtra(EXTRA_EPOCH_DAY, Long.MIN_VALUE)
            return if (day == Long.MIN_VALUE) null else readings[day.mod(28)]
        }
    }
}
