package com.tunisianprayertimes.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.place.PrayerPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keep the requesting control and its draft underneath the picker, including on Back and rotation. */
@Composable
internal fun PrayerPlaceRequiredDialog(onPlaceConfirmed: () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val finish by rememberUpdatedState {
        onDismiss()
        onPlaceConfirmed()
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf<FirstRunPlace?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val selected = selection
    LaunchedEffect(selected, attempt) {
        if (selected == null) return@LaunchedEffect
        failed = false
        val saved = try {
            resolveFeaturePlace(app, selected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
        if (saved) finish() else failed = true
    }
    if (pickerOpen) {
        FirstRunPicker(
            onDismiss = onDismiss,
            onLocality = {
                selection = FirstRunPlace.Local(it.id, it.manualReferenceId, it.name)
                pickerOpen = false
            },
            onCity = {
                selection = FirstRunPlace.City(it.ref.id, PlaceResolution.catalogueLabel(it))
                pickerOpen = false
            },
        )
        return
    }

    val suggested = PrefsManager.getPlace(context).takeIf { PrefsManager.isPlaceProvisional(context) }
    val label = suggested?.let { PlaceResolution.suggestedPlaceLabel(context, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_confirm_title)) },
        text = {
            Column {
                Text(when {
                    failed -> stringResource(R.string.first_run_source_error)
                    selected?.name != null -> stringResource(R.string.first_run_preparing_city, selected.name.orEmpty())
                    else -> stringResource(R.string.prayer_feature_place_required)
                })
                if (failed) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.first_run_retry)) }
                if (selected == null && suggested != null && label != null) {
                    TextButton(onClick = {
                        if (PrefsManager.confirmSuggestedPlace(context, suggested)) {
                            PlaceResolution.changes.launch { ScheduleRefreshCoordinator.syncAll(app) }
                        }
                        if (PrefsManager.hasConfirmedPlace(context)) finish() else pickerOpen = true
                    }, modifier = Modifier.testTag("feature_confirm_suggested_place")) {
                        Text(stringResource(R.string.today_confirm_place, label))
                    }
                }
            }
        },
        confirmButton = {
            if (selected == null || failed) {
                TextButton(onClick = {
                    if (failed) { selection = null; failed = false }
                    pickerOpen = true
                }, modifier = Modifier.testTag("feature_choose_place")) {
                    Text(stringResource(R.string.prayer_data_choose_location))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("feature_cancel_place")) {
                Text(stringResource(R.string.jomoaa_time_cancel))
            }
        },
    )
}

private suspend fun resolveFeaturePlace(context: Context, requested: FirstRunPlace): Boolean {
    val choice = withContext(Dispatchers.IO) { requested.resolve(context) } ?: return false
    val place = when (choice) {
        is FirstRunPlaceChoice.Local -> PrayerPlace.Inm(choice.locality.delegationId)
        is FirstRunPlaceChoice.Abroad -> choice.place
        else -> return false
    }
    withContext(Dispatchers.Default) { PrayerTimesRepository.warm(context, place) }
    currentCoroutineContext().ensureActive()
    val previous = PrefsManager.getPlace(context)
    val previousLabel = PrefsManager.getPlaceNameSnapshot(context)
    if (PrefsManager.choosePlaceForFeature(context, choice)) {
        AnalyticsTracker.applyRegionalCollection(context)
        AnalyticsTracker.markDelegationSource(context, if (choice is FirstRunPlaceChoice.Abroad) "manual_abroad" else "manual")
        PlaceResolution.changes.launch {
            if (previous == place) ScheduleRefreshCoordinator.syncAll(context)
            else PlaceResolution.onPlaceChanged(context, previous, place, previousLabel, requested.name)
        }
    }
    return PrefsManager.hasConfirmedPlace(context)
}
