package com.tunisianprayertimes.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.MethodPresentation
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanSettings
import com.tunisianprayertimes.adhan.AdhanSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextDark
import kotlinx.coroutines.launch

internal enum class PrayerTabSettingsPage { Overview, Calculation }

internal data class PrayerSettingsAccess(
    val hasPhoneState: Boolean,
    val hasForegroundLocation: Boolean,
    val backgroundTravelActive: Boolean,
    val offerBatteryHelp: Boolean,
    val silenceSetupIssue: Int? = null,
    val silenceStatus: Int? = null,
)

@Composable
internal fun PrayerTabSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(GreenPrimary.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(TestTags.PRAYER_TAB_SETTINGS_BUTTON),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.prayer_tab_settings_title),
            tint = GreenPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrayerTabSettingsSheet(
    page: PrayerTabSettingsPage,
    onPageChange: (PrayerTabSettingsPage) -> Unit,
    place: PrayerPlace,
    selectedDate: Long,
    onTimesChanged: () -> Unit,
    autoSilenceEnabled: Boolean,
    onAutoSilenceChange: (Boolean) -> Unit,
    callEndVibrationEnabled: Boolean,
    onCallEndVibrationChange: (Boolean) -> Unit,
    autoLocationEnabled: Boolean,
    onAutoLocationChange: (Boolean) -> Unit,
    access: PrayerSettingsAccess,
    onBackgroundTravelChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val abroad = place as? PrayerPlace.Coordinates
    val calculationPlace = abroad?.takeIf { page == PrayerTabSettingsPage.Calculation }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val overviewScroll = rememberScrollState()
    var showInmExplanation by rememberSaveable { mutableStateOf(false) }
    val showOverview = { onPageChange(PrayerTabSettingsPage.Overview) }
    val openAppSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    LaunchedEffect(page, abroad != null) {
        if (page == PrayerTabSettingsPage.Calculation && abroad == null) showOverview()
    }

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PrayerSilencePalette.Surface,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler {
            if (calculationPlace != null) {
                showOverview()
            } else {
                scope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) onDismiss()
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (calculationPlace != null) {
                    IconButton(onClick = showOverview) {
                        val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
                        Icon(
                            painter = painterResource(R.drawable.ic_adhkar_back),
                            contentDescription = stringResource(R.string.prayer_settings_back),
                            tint = PrayerSilencePalette.PrimaryText,
                            modifier = Modifier.graphicsLayer { scaleX = if (rightToLeft) 1f else -1f },
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (calculationPlace == null) R.string.prayer_tab_settings_title
                        else R.string.prayer_settings_calculation_title,
                    ),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerSilencePalette.PrimaryText,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val scroll = if (calculationPlace == null) overviewScroll else rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (calculationPlace == null) {
                    var adhanSummary by remember { mutableStateOf(AdhanSettings.summary(context)) }
                    var silenceMode by remember { mutableStateOf(MosqueSilenceSettings.mode(context)) }
                    LifecycleResumeEffect(Unit) {
                        adhanSummary = AdhanSettings.summary(context)
                        silenceMode = MosqueSilenceSettings.mode(context)
                        onPauseOrDispose { }
                    }

                    PrayerSettingsGroup(title = stringResource(R.string.prayer_settings_sound_group)) {
                        PrayerSettingsSwitch(
                            title = stringResource(R.string.auto_silence),
                            summary = access.silenceStatus?.let { stringResource(it) },
                            checked = autoSilenceEnabled,
                            onCheckedChange = onAutoSilenceChange,
                            modifier = Modifier.testTag(TestTags.AUTO_SILENCE_SWITCH),
                        )
                        PrayerSettingsDivider()
                        if (autoSilenceEnabled && access.silenceSetupIssue != null) {
                            PrayerSettingsLink(
                                title = stringResource(R.string.mosque_readiness_title),
                                summary = stringResource(access.silenceSetupIssue),
                                onClick = { context.startActivity(MosqueSettingsActivity.setupIntent(context)) },
                            )
                            PrayerSettingsDivider()
                        }
                        PrayerSettingsLink(
                            title = stringResource(R.string.mosque_settings_title),
                            summary = when (silenceMode) {
                                AutomaticSilenceMode.TIME_ONLY -> stringResource(R.string.mosque_mode_time)
                                AutomaticSilenceMode.TIME_AND_MOSQUE -> stringResource(R.string.mosque_mode_combined)
                                null -> null
                            },
                            onClick = { context.startActivity(Intent(context, MosqueSettingsActivity::class.java)) },
                        )
                        PrayerSettingsDivider()
                        PrayerSettingsSwitch(
                            title = stringResource(R.string.call_end_vibration_title),
                            summary = stringResource(R.string.call_feedback_uses_phone_state),
                            checked = callEndVibrationEnabled,
                            onCheckedChange = onCallEndVibrationChange,
                            modifier = Modifier.testTag(TestTags.CALL_END_VIBRATION_SWITCH),
                        )
                        if (callEndVibrationEnabled && !access.hasPhoneState) {
                            PrayerSettingsDivider()
                            PrayerSettingsLink(
                                title = stringResource(R.string.call_feedback_allow_phone_state),
                                summary = stringResource(R.string.call_feedback_waiting),
                                onClick = openAppSettings,
                            )
                        }
                        PrayerSettingsDivider()
                        PrayerSettingsLink(
                            title = stringResource(R.string.adhan_settings_title),
                            summary = adhanSummary,
                            onClick = { context.startActivity(Intent(context, AdhanSettingsActivity::class.java)) },
                        )
                    }
                    PrayerSettingsGroup(title = stringResource(R.string.prayer_settings_location_group)) {
                        PrayerSettingsSwitch(
                            title = stringResource(R.string.auto_location_title),
                            summary = stringResource(R.string.travel_foreground_summary),
                            checked = autoLocationEnabled,
                            onCheckedChange = onAutoLocationChange,
                            modifier = Modifier.testTag(TestTags.AUTO_LOCATION_SWITCH),
                        )
                        if (autoLocationEnabled && !access.hasForegroundLocation) {
                            PrayerSettingsDivider()
                            PrayerSettingsLink(
                                title = stringResource(R.string.travel_allow_foreground),
                                summary = stringResource(R.string.travel_location_waiting),
                                onClick = openAppSettings,
                            )
                        } else if (autoLocationEnabled) {
                            PrayerSettingsDivider()
                            PrayerSettingsSwitch(
                                title = stringResource(R.string.travel_in_background),
                                summary = stringResource(if (access.backgroundTravelActive) R.string.travel_background_active else R.string.auto_location_foreground_only),
                                checked = access.backgroundTravelActive,
                                onCheckedChange = onBackgroundTravelChange,
                            )
                        }
                        if (autoLocationEnabled) TravelNotificationSettings()
                        PrayerSettingsDivider()
                        if (abroad == null) {
                            PrayerSettingsLink(
                                title = stringResource(R.string.prayer_method_entry_title),
                                modifier = Modifier.testTag(TestTags.PRAYER_TIME_METHOD_ROW),
                                onClick = { showInmExplanation = true },
                            )
                        } else {
                            PrayerSettingsLink(
                                title = stringResource(R.string.prayer_settings_method),
                                summary = PrayerTimesRepository.choice(context, abroad)?.let {
                                    MethodPresentation.name(it.methodId)
                                },
                                onClick = { onPageChange(PrayerTabSettingsPage.Calculation) },
                            )
                        }
                    }
                    if (access.offerBatteryHelp) {
                        PrayerSettingsGroup(title = stringResource(R.string.prayer_delivery_troubleshooting)) {
                            PrayerSettingsLink(
                                title = stringResource(R.string.prayer_check_battery),
                                summary = stringResource(R.string.prayer_check_battery_summary),
                                onClick = openAppSettings,
                            )
                        }
                    }
                    PrayerSettingsGroup(title = stringResource(R.string.launcher_icon_title)) {
                        LauncherIconChoice()
                    }
                } else {
                    PrayerMethodSection(
                        place = calculationPlace,
                        onTimesChanged = onTimesChanged,
                        onShowInmExample = { showInmExplanation = true },
                    )
                }
            }
        }
    }

    if (showInmExplanation) {
        PrayerTimeMethodDialog(
            // From outside Tunisia, the worked example is always for Tunis.
            delegationId = (place as? PrayerPlace.Inm)?.delegationId ?: PlaceResolution.TUNIS_DELEGATION_ID,
            selectedDate = selectedDate,
            asExample = abroad != null,
            onDismiss = { showInmExplanation = false },
        )
    }
}

@Composable
private fun TravelNotificationSettings() {
    val context = LocalContext.current
    val channel = PlaceResolution.LOCATION_CHANNEL
    var issue by remember(context) { mutableStateOf(NotificationAccess.issue(context, channel)) }
    val refresh = { issue = NotificationAccess.issue(context, channel) }
    LifecycleResumeEffect(context) {
        refresh()
        onPauseOrDispose { }
    }
    if (issue == null) return
    val repair = rememberNotificationAccessAction(onReturn = refresh)
    PrayerSettingsDivider()
    PrayerSettingsLink(
        title = stringResource(R.string.travel_notification_enable),
        summary = stringResource(R.string.travel_notification_help),
        onClick = { repair(channel) },
    )
}

@Composable
internal fun PrayerSettingsGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerSilencePalette.PrimaryText,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = PrayerSilencePalette.Surface,
            border = BorderStroke(1.dp, PrayerSilencePalette.SoftBorder),
        ) {
            Column { content() }
        }
    }
}

@Composable
internal fun PrayerSettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = PrayerSilencePalette.SoftBorder)
}

@Composable
internal fun PrayerSettingsLink(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    PrayerSettingsRow(
        title = title,
        summary = summary,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_qibla_chevron),
            contentDescription = null,
            tint = PrayerSilencePalette.SecondaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun PrayerSettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    PrayerSettingsRow(
        title = title,
        summary = summary,
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GreenPrimary),
        )
    }
}

@Composable
private fun PrayerSettingsRow(
    title: String,
    summary: String?,
    modifier: Modifier,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).then(modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            if (!summary.isNullOrBlank()) {
                Text(summary, fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
            }
        }
        control()
    }
}
