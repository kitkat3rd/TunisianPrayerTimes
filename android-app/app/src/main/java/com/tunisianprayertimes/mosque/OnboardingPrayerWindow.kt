package com.tunisianprayertimes.mosque

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.adhan.AdhanSettings
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An example from the same enabled prayers and endpoint rules that the scheduler will use. */
@Composable
internal fun OnboardingPrayerWindow() {
    val context = LocalContext.current
    if (!PrefsManager.hasConfirmedPlace(context)) return
    val place = PrefsManager.getPlace(context)
    val sample by produceState<SilenceWindow?>(null, place) {
        value = try {
            withContext(Dispatchers.Default) {
                val now = Instant.now()
                SilenceScheduler.planner(context, place).windows(now).firstOrNull { it.end > now }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val window = sample ?: return
    val start = window.start.atZone(place.zone)
    val end = window.end.atZone(place.zone)
    val endLabel = if (start.toLocalDate() == end.toLocalDate()) clock.format(end)
        else dayAndClock.format(end)
    Text(
        stringResource(R.string.first_run_window_example, AdhanSettings.prayerName(context, window.prayer),
            dayAndClock.format(start), endLabel),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("onboarding_silence_window"),
    )
}

private val dayAndClock = DateTimeFormatter.ofPattern("EEEE d MMMM، HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
