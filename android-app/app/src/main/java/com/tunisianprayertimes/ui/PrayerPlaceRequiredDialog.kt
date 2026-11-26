package com.tunisianprayertimes.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.R

/** Open the same Prayer location picker used when correcting a provisional timetable. */
@Composable
internal fun PrayerPlaceRequiredDialog(onChoosingPlace: () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_confirm_title)) },
        text = { Text(stringResource(R.string.prayer_feature_place_required)) },
        confirmButton = {
            TextButton(onClick = {
                onChoosingPlace()
                onDismiss()
                MainActivityPendingIntents.choosePlace(context).send()
            }) { Text(stringResource(R.string.prayer_data_choose_location)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.jomoaa_time_cancel)) }
        },
    )
}
