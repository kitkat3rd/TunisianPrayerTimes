package com.tunisianprayertimes.mosque

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.PrayerSettingsDivider
import com.tunisianprayertimes.ui.PrayerSettingsLink

@Composable
internal fun SilenceWalkSettingsLink() {
    val context = LocalContext.current
    PrayerSettingsDivider()
    PrayerSettingsLink(
        title = stringResource(R.string.silence_walk_title),
        summary = stringResource(R.string.silence_walk_link),
        onClick = { context.startActivity(Intent(context, SilenceWalkActivity::class.java)) },
    )
}
