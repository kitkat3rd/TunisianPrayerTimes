package com.tunisianprayertimes.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.ClockTime
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalityRepository
import com.tunisianprayertimes.withAvailablePrayerSource
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerRelativeOffset
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PlaceCatalogue
import com.tunisianprayertimes.CitySearch
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.silence.SilenceEnd
import com.tunisianprayertimes.silence.SilenceEndpoints
import com.tunisianprayertimes.silence.SilenceFit
import com.tunisianprayertimes.silence.SilenceRule
import com.tunisianprayertimes.silence.SilenceStart
import com.tunisianprayertimes.place.CountryResolver as PlaceCountry
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanDetector
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.mosque.MosqueReadiness
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.AdaptiveMosqueMonitor
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.mosque.SilenceOwner
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.WAKE_RECURRING_LOOKAHEAD_DAYS
import com.tunisianprayertimes.WAKE_SUPPORTED_PRAYERS
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.WakeRepeatMode
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.hasPendingWakeOccurrenceSkip
import com.tunisianprayertimes.isRepeatingWakeAlarm
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.formatArabicByDuration
import com.tunisianprayertimes.formatArabicMinutes
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.hasFutureWakeTriggers
import com.tunisianprayertimes.wake.prayerWakeJson
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.WakeMotivationCard
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.ui.theme.BannerBg
import com.tunisianprayertimes.ui.theme.BannerStroke
import com.tunisianprayertimes.ui.theme.BannerText
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.RamadanBg
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Each tab shows its outline icon, and its filled one in the pill while selected. */
/**
 * The bar, in this order from the start (the right edge in Arabic): home first; the prayer times and the alarms
 * that follow them side by side; the Qur'an in the centre, the most reachable slot, for the tab meant for every
 * day; the adhkar next to it, the other daily reading; the occasional hub last.
 */
private enum class MainDestination(val labelRes: Int, val iconRes: Int, val selectedIconRes: Int) {
    Today(R.string.main_tab_today, R.drawable.ic_tab_prayer, R.drawable.ic_tab_prayer_filled),
    Alarms(R.string.main_tab_alarms, R.drawable.ic_tab_alarms_outline, R.drawable.ic_tab_alarms_filled),
    Quran(R.string.main_tab_quran, R.drawable.ic_tab_quran, R.drawable.ic_tab_quran_filled),
    Adhkar(R.string.adhkar_title, R.drawable.ic_tab_adhkar, R.drawable.ic_tab_adhkar),
    /** «عباداتي»: the Qibla on top, then worship beyond the five prayers (fasting first). */
    Worship(R.string.main_tab_worship, R.drawable.ic_tab_qibla, R.drawable.ic_tab_qibla_filled),
}

private enum class ManualSilenceIssue { Dnd, Rule }

private enum class WakeQuickPreset {
    PRAYER_RELATIVE,
    FIXED_TIME,
    TIMER,
}

private val WakeDraftSaver = Saver<PrayerWakeConfig?, String>(
    save = { config -> config?.let { prayerWakeJson.encodeToString(PrayerWakeConfig.serializer(), it) } },
    restore = { prayerWakeJson.decodeFromString(PrayerWakeConfig.serializer(), it) },
)

private data class WakeSilenceConflictEditorState(
    val draftConfig: PrayerWakeConfig,
    val originalConflict: WakeSilenceConflictSummary,
    val draftSilenceConfig: PrayerSilenceConfig,
    val currentConflict: WakeSilenceConflictSummary? = originalConflict,
    val hasEditedSilenceWindow: Boolean = false,
    val editRevision: Int = 0,
)

private const val DEFAULT_PRAYER_OFFSET_MINUTES = 20
private const val DEFAULT_TIMER_MINUTES = 15
/** How long after the prayer tab shows the place picker prepares its names (unless it opens first). */
private const val PICKER_PREPARE_DELAY_MS = 3_000L
/** How long after launch the Adhkar tab's scenes are decoded. */
private const val ADHKAR_SCENES_WARM_DELAY_MS = 4_000L
private const val DEFAULT_FIXED_ALARM_HOUR = 8
private const val DEFAULT_FIXED_ALARM_MINUTE = 0
private val MainContentBottomPadding = 76.dp
private val BottomNavigationMinHeight = 64.dp
private val BottomNavigationVerticalPadding = 6.dp
private val BottomNavigationPillWidth = 56.dp
private val BottomNavigationPillHeight = 30.dp
private val BottomNavigationLabelGap = 3.dp
private val BottomNavigationItemFixedHeight = BottomNavigationPillHeight + BottomNavigationLabelGap
private val BottomNavigationLabelSize = 12.sp

/**
 * The bottom bar's labels and its row's height above the system's bar. Each label is one word, so it stays on one line,
 * whole: all labels share one size, as large as the phone's text asks while the longest still fits its tab, and never
 * smaller than without large text. The row grows with that line rather than be cut by a fixed 64dp row.
 */
private class BottomNavigationLabels(val size: androidx.compose.ui.unit.TextUnit, val rowHeight: androidx.compose.ui.unit.Dp)

@Composable
private fun bottomNavigationLabels(labels: List<String>, widthPx: Float): BottomNavigationLabels {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = LocalTextStyle.current.merge(TextStyle(fontSize = BottomNavigationLabelSize, fontWeight = FontWeight.Bold))
    return remember(labels, widthPx, density, labelStyle) {
        val itemWidthPx = (widthPx - with(density) { 16.dp.toPx() }) / labels.size
        // The item's padding and the label's own.
        val labelWidthPx = (itemWidthPx - with(density) { 12.dp.toPx() }).coerceAtLeast(1f)
        fun widest(px: Float): Int = labels.maxOf { label ->
            textMeasurer.measure(label, labelStyle.copy(fontSize = with(density) { px.toSp() }), maxLines = 1, softWrap = false).size.width
        }
        val largest = with(density) { BottomNavigationLabelSize.toPx() }
        val smallest = minOf(largest, BottomNavigationLabelSize.value * density.density)
        var px = largest
        if (widest(largest) > labelWidthPx) {
            var low = smallest
            var high = largest
            while (high - low > 0.5f) {
                val mid = (low + high) / 2f
                if (widest(mid) <= labelWidthPx) low = mid else high = mid
            }
            px = low
        }
        val size = with(density) { px.toSp() }
        val labelHeightPx = labels.maxOf { label -> textMeasurer.measure(label, labelStyle.copy(fontSize = size), maxLines = 1).size.height }
        val minContentHeightPx = with(density) { (BottomNavigationMinHeight - BottomNavigationVerticalPadding * 2).toPx() }
        val itemContentHeightPx = with(density) { BottomNavigationItemFixedHeight.toPx() } + labelHeightPx
        BottomNavigationLabels(
            size,
            with(density) { (maxOf(minContentHeightPx, itemContentHeightPx) + BottomNavigationVerticalPadding.toPx() * 2f).toDp() },
        )
    }
}
private val WakeAlarmAddButtonSize = 64.dp
private val WakeAlarmAddButtonBottomPadding = 92.dp
private val WakeAlarmAddButtonShadowElevation = 8.dp
private const val WakeAlarmAddButtonIdleRevealDelayMillis = 3_000L
private const val WakeAlarmAddButtonBottomPullRevealDelayMillis = 3_000L
private val MainHeroCardHeight = 148.dp
private val SilencedHeroEnd = Color(0xFF6D3543)

private fun MainDestination.analyticsName(): String = when (this) {
    MainDestination.Today -> "prayers"
    MainDestination.Alarms -> "alarms"
    MainDestination.Worship -> "worship"
    MainDestination.Adhkar -> "adhkar"
    MainDestination.Quran -> "quran"
}

private fun requestedMainTab(destination: String?) = when (destination) {
    MainTabNavigation.DESTINATION_PRAYERS -> MainDestination.Today
    MainTabNavigation.DESTINATION_ALARMS -> MainDestination.Alarms
    MainTabNavigation.DESTINATION_QIBLA, MainTabNavigation.DESTINATION_WORSHIP -> MainDestination.Worship
    MainTabNavigation.DESTINATION_ADHKAR -> MainDestination.Adhkar
    MainTabNavigation.DESTINATION_QURAN -> MainDestination.Quran
    else -> null
}

private fun MainDestination.testTag(): String = when (this) {
    MainDestination.Today -> TestTags.MAIN_TAB_TODAY
    MainDestination.Alarms -> TestTags.MAIN_TAB_ALARMS
    MainDestination.Worship -> TestTags.MAIN_TAB_QIBLA
    MainDestination.Adhkar -> TestTags.MAIN_TAB_ADHKAR
    MainDestination.Quran -> TestTags.MAIN_TAB_QURAN
}

private fun WakeQuickPreset.testTag(): String = when (this) {
    WakeQuickPreset.PRAYER_RELATIVE -> TestTags.WAKE_QUICK_PRESET_PRAYER_RELATIVE
    WakeQuickPreset.FIXED_TIME -> TestTags.WAKE_QUICK_PRESET_FIXED_TIME
    WakeQuickPreset.TIMER -> TestTags.WAKE_QUICK_PRESET_TIMER
}

/** Its scheduled instant can fall on a different civil date from the prayer day that owns it. */
internal data class NextPrayerCountdownInfo(
    val prayer: Prayer,
    val at: java.time.Instant,
    val prayerDay: LocalDate,
    val placeZone: java.time.ZoneId,
) {
    val triggerAtMillis: Long get() = at.toEpochMilli()
    fun prayerOn(date: LocalDate): Prayer? = prayer.takeIf { prayerDay == date }
}

@Composable
fun MainScreen(
    activity: androidx.appcompat.app.AppCompatActivity,
    requestedDestination: String? = null,
    requestedDestinationSequence: Int = 0,
    requestedDhikrReminderId: String? = null,
    requestedDhikrOccurrenceId: String? = null,
    requestedQuranAction: String? = null,
    requestedPrayersAction: String? = null,
    requestedWorshipAction: String? = null,
) {
    val context = LocalContext.current
    val mainDestinations = remember { MainDestination.entries.toList() }
    var selectedDestinationIndex by rememberSaveable {
        mutableIntStateOf((requestedMainTab(requestedDestination) ?: MainDestination.Today).ordinal)
    }
    LaunchedEffect(requestedDestinationSequence, requestedDestination) {
        requestedMainTab(requestedDestination)?.let { selectedDestinationIndex = it.ordinal }
    }
    val currentDestinationIndex = selectedDestinationIndex.takeIf { it in mainDestinations.indices } ?: 0
    val selectedDestination = mainDestinations[currentDestinationIndex]
    var needsPlaceDecision by remember { mutableStateOf(PrefsManager.isFirstLaunch(context)) }
    var needsSilenceDecision by remember { mutableStateOf(PrefsManager.isFirstRunSilencePending(context)) }
    fun refreshFirstRun() {
        needsPlaceDecision = PrefsManager.isFirstLaunch(context)
        needsSilenceDecision = PrefsManager.isFirstRunSilencePending(context)
    }
    if (needsPlaceDecision || needsSilenceDecision) {
        DisposableEffect(context) {
            val unsubscribe = PrefsManager.observeFirstRunDecisions(context, ::refreshFirstRun)
            refreshFirstRun()
            onDispose { unsubscribe() }
        }
    }
    // First-run setup belongs to ordinary Prayer entry. A notification can still open its own tab or city picker.
    var handledPickerSequence by rememberSaveable { mutableIntStateOf(-1) }
    var deferSilenceForPickerVisit by rememberSaveable { mutableStateOf(false) }
    val directedPicker = requestedDestination == MainTabNavigation.DESTINATION_PRAYERS &&
        requestedPrayersAction == MainTabNavigation.PRAYERS_ACTION_CHOOSE_PLACE &&
        handledPickerSequence != requestedDestinationSequence
    LaunchedEffect(selectedDestination) {
        if (selectedDestination != MainDestination.Today && !directedPicker) deferSilenceForPickerVisit = false
    }
    if (needsPlaceDecision && selectedDestination == MainDestination.Today) {
        OnboardingScreen(pickerRequestId = requestedDestinationSequence.takeIf { directedPicker }) {
            if (directedPicker) {
                handledPickerSequence = requestedDestinationSequence
                deferSilenceForPickerVisit = true
            }
            refreshFirstRun()
        }
        return
    }
    if (needsSilenceDecision && selectedDestination == MainDestination.Today &&
        !directedPicker && !deferSilenceForPickerVisit
    ) {
        FirstRunSilenceScreen {
            PrefsManager.finishFirstRunSilenceOffer(context)
            refreshFirstRun()
        }
        return
    }
    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val officialDateRecords by OfficialIslamicDates.updates.collectAsStateWithLifecycle()
    val schedulerScope = rememberCoroutineScope()

    // Reactive state that gets refreshed on resume
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // State derived from system/prefs — re-evaluated on refreshTick
    val hasDnd = remember(refreshTick) { notificationManager.isNotificationPolicyAccessGranted }
    val hasAlarm = remember(refreshTick) { hasExactAlarmPermission(context) }
    val hasBattery = remember(refreshTick) { isIgnoringBatteryOptimizations(context) }
    val hasPhoneState = remember(refreshTick) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AnalyticsTracker.permissionStepResult(
            context = context,
            permissionType = "phone_state",
            result = if (granted) "granted" else "denied",
            entryPoint = "missed_call_feedback",
        )
        refreshTick++
    }

    fun ensureCallTrackingPermission() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "phone_state",
                result = "request_opened",
                entryPoint = "missed_call_feedback",
            )
            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    var autoSilenceEnabled by rememberSaveable { mutableStateOf(PrefsManager.isEnabled(context)) }
    var autoSilenceRequested by remember { mutableStateOf(PrefsManager.isAutomaticSilenceRequested(context)) }
    var callEndVibrationEnabled by rememberSaveable {
        mutableStateOf(PrefsManager.isCallEndVibrationEnabled(context))
    }
    var autoLocationEnabled by rememberSaveable {
        mutableStateOf(PrefsManager.isAutoLocationUpdateEnabled(context))
    }
    var delegationId by rememberSaveable { mutableIntStateOf(screenTimesId(context)) }
    var locationPickerRequested by remember { mutableStateOf(PrefsManager.consumePlacePickerOnStart(context)) }
    // 6.8: a skipped first run's place, shown with «تأكيد الموقع» until the user confirms it or a fix arrives.
    var placeProvisional by remember(delegationId) { mutableStateOf(PrefsManager.isPlaceProvisional(context)) }
    // Country-specific method questions remain next to the place until the user opens or answers them.
    var methodQuestion by remember(delegationId) { mutableStateOf(runCatching { pendingMethodQuestion(context) }.getOrNull()) }
    var methodChooserOpen by remember { mutableStateOf(false) }
    // Keep the existing travel prompt for established installations. A new first timetable uses the method row.
    var legacyMethodSeen by rememberSaveable { mutableStateOf(setOf<String>()) }
    LaunchedEffect(selectedDestination, methodQuestion, placeProvisional) {
        val question = methodQuestion
        if (selectedDestination == MainDestination.Today && !PrefsManager.usesPlaceOnboarding(context) &&
            !placeProvisional && question != null && question.hasChooser && question.key !in legacyMethodSeen
        ) {
            legacyMethodSeen = legacyMethodSeen + question.key
            methodChooserOpen = true
        }
    }
    var prayerTabSettingsPage by rememberSaveable { mutableStateOf<PrayerTabSettingsPage?>(null) }
    var featureNeedsPlace by rememberSaveable(requestedDestinationSequence, selectedDestination) { mutableStateOf(false) }
    var backgroundLocationDisclosureOpen by rememberSaveable { mutableStateOf(false) }
    val hasTravelLocation = remember(refreshTick) { DelegationLocator.hasLocationPermission(context) }
    val travelPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshTick++
    }
    DisposableEffect(context) {
        // Background location updates must refresh the timetable alongside the label.
        val unsubscribe = PrefsManager.observeLocationSelection(context) {
            delegationId = screenTimesId(context)
            placeProvisional = PrefsManager.isPlaceProvisional(context)
        }
        delegationId = screenTimesId(context)
        onDispose { unsubscribe() }
    }
    var manualSilenceMode by rememberSaveable { mutableStateOf(PrefsManager.getManualSilenceMode(context)) }
    var manualOptionsOpen by rememberSaveable { mutableStateOf(false) }
    var manualTargetPrayer by rememberSaveable {
        mutableStateOf(
            if (PrefsManager.getManualSilenceMode(context) == ManualSilenceMode.UNTIL_PRAYER) {
                PrefsManager.getManualSilenceTargetPrayer(context)
            } else {
                resolveUpcomingManualSilencePrayer(context, delegationId)
                    ?: PrefsManager.getManualSilenceTargetPrayer(context)
            }
        )
    }
    var manualDurationHours by rememberSaveable {
        mutableStateOf((PrefsManager.getManualSilenceDurationMinutes(context) / 60).toString())
    }
    var manualDurationMinutes by rememberSaveable {
        mutableStateOf((PrefsManager.getManualSilenceDurationMinutes(context) % 60).toString())
    }
    val selectedManualMinutes = (manualDurationHours.toIntOrNull() ?: 0) * 60 + (manualDurationMinutes.toIntOrNull() ?: 0)
    var autoSilenceActive by remember { mutableStateOf(PrefsManager.isAutoSilenceActive(context)) }
    var manualSilenceActive by remember { mutableStateOf(PrefsManager.isManualSilenceActive(context)) }
    // The hero and manual card share a pending action, so their writes cannot overlap.
    var silenceActionPending by remember { mutableStateOf(false) }
    var manualSilenceIssue by rememberSaveable { mutableStateOf<ManualSilenceIssue?>(null) }
    var phoneSilenced by remember { mutableStateOf(SilenceStatus.isPhoneSilenced(context)) }
    var phoneDndActive by remember { mutableStateOf(SilenceStatus.isDoNotDisturbActive(context)) }
    var appControlledSilenceActive by remember { mutableStateOf(SilenceStatus.isAppControlledSilenceActive(context)) }
    var wakeSilenceUntilAlarmActive by remember { mutableStateOf(WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) }
    var manualSilenceEndsAtMillis by remember {
        mutableLongStateOf(PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    fun refreshSilenceState() {
        autoSilenceEnabled = PrefsManager.isEnabled(context)
        autoSilenceRequested = PrefsManager.isAutomaticSilenceRequested(context)
        autoSilenceActive = PrefsManager.isAutoSilenceActive(context)
        manualSilenceActive = PrefsManager.isManualSilenceActive(context)
        if (!manualOptionsOpen || manualSilenceActive) {
            manualSilenceMode = PrefsManager.getManualSilenceMode(context)
            manualTargetPrayer = PrefsManager.getManualSilenceTargetPrayer(context)
            val minutes = PrefsManager.getManualSilenceDurationMinutes(context)
            manualDurationHours = (minutes / 60).toString()
            manualDurationMinutes = (minutes % 60).toString()
        }
        phoneSilenced = SilenceStatus.isPhoneSilenced(context)
        phoneDndActive = SilenceStatus.isDoNotDisturbActive(context)
        appControlledSilenceActive = SilenceStatus.isAppControlledSilenceActive(context)
        wakeSilenceUntilAlarmActive = WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
        manualSilenceEndsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context)
    }

    fun toggleManualSilence() {
        val mode = manualSilenceMode
        if (!manualSilenceActive && !autoSilenceActive && mode == ManualSilenceMode.UNTIL_PRAYER &&
            !PrefsManager.hasConfirmedPlace(context)
        ) {
            featureNeedsPlace = true
            return
        }
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            manualSilenceIssue = ManualSilenceIssue.Dnd
            return
        }
        if (silenceActionPending) return
        if (manualSilenceActive || autoSilenceActive) {
            silenceActionPending = true
            // Restoring the ringer waits 150 ms for the system: off the main thread, in turn, and
            // run even if the screen goes meanwhile. The saved state decides there: a silence its
            // timer ended meanwhile is not ended twice.
            schedulerScope.launch {
                try {
                    val restored = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                        val ended = when {
                            PrefsManager.isManualSilenceActive(context) ->
                                if (com.tunisianprayertimes.mosque.MosqueSilenceSettings.usesOwnedRules(context))
                                    SilenceModeController.setManualNormal(context)
                                else SilenceModeController.endManualSilenceIfActive(context)
                            PrefsManager.isAutoSilenceActive(context) -> {
                                // In turn with a re-plan, which reads the dismissals.
                                synchronized(SilenceScheduler) {
                                    val prayer = SilenceScheduler.currentSilenceWindowPrayer(context)
                                    SilenceModeController.disableAutoSilence(context).also { released ->
                                        if (released) SilenceScheduler.dismissCurrentWindows(context, prayer)
                                    }
                                }
                            }
                            else -> false
                        }
                        if (ended) SilenceModeController.notifyIfMissedCallDuringSilence(context)
                        ended
                    }
                    refreshSilenceState()
                    if (restored) {
                        Toast.makeText(context, context.getString(if (com.tunisianprayertimes.mosque.MosqueSilenceSettings.usesOwnedRules(context)) R.string.mosque_request_released else R.string.toast_normal_restored), Toast.LENGTH_SHORT).show()
                    } else {
                        manualSilenceIssue = if (notificationManager.isNotificationPolicyAccessGranted) ManualSilenceIssue.Rule else ManualSilenceIssue.Dnd
                    }
                } finally {
                    silenceActionPending = false
                }
            }
        } else {
            val totalMinutes = selectedManualMinutes
            if (mode == ManualSilenceMode.DURATION && totalMinutes <= 0) {
                Toast.makeText(context, context.getString(R.string.error_manual_duration_required), Toast.LENGTH_SHORT).show()
                return
            }
            val prayerOccurrence = if (mode == ManualSilenceMode.UNTIL_PRAYER) {
                nextManualSilenceOccurrence(context, manualTargetPrayer)
            } else {
                null
            }
            if (mode == ManualSilenceMode.UNTIL_PRAYER && prayerOccurrence == null) {
                Toast.makeText(context, context.getString(R.string.error_manual_prayer_time_unavailable), Toast.LENGTH_SHORT).show()
                return
            }

            silenceActionPending = true
            schedulerScope.launch {
                try {
                    // In turn with the screen's other silence work, off the main thread, and run even if
                    // the screen goes meanwhile.
                    val endsAt = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                        ManualSilenceScheduler.start(context, mode, totalMinutes, prayerOccurrence)
                    }
                    if (endsAt == null) {
                        refreshSilenceState()
                        manualSilenceIssue = if (notificationManager.isNotificationPolicyAccessGranted) ManualSilenceIssue.Rule else ManualSilenceIssue.Dnd
                        return@launch
                    }
                    manualSilenceMode = mode
                    val inexact = endsAt > 0L && !ManualSilenceScheduler.canScheduleExact(context)
                    when (mode) {
                        ManualSilenceMode.DURATION -> {
                            manualSilenceEndsAtMillis = endsAt
                            AnalyticsTracker.manualSilenceStarted(context, mode, totalMinutes)
                            Toast.makeText(
                                context,
                                if (inexact) context.getString(R.string.manual_inexact_started)
                                else context.getString(R.string.toast_silent_enabled_timed, formatDurationText(totalMinutes)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        ManualSilenceMode.UNTIL_PRAYER -> {
                            manualSilenceEndsAtMillis = endsAt
                            AnalyticsTracker.manualSilenceStarted(context, mode, null)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (inexact) R.string.manual_approximate_prayer else R.string.toast_silent_enabled_until_prayer,
                                    prayerName(context, requireNotNull(prayerOccurrence).prayer),
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        ManualSilenceMode.UNTIL_STOPPED -> {
                            manualSilenceEndsAtMillis = endsAt
                            AnalyticsTracker.manualSilenceStarted(context, mode, null)
                            Toast.makeText(context, context.getString(R.string.toast_silent_enabled), Toast.LENGTH_SHORT).show()
                        }
                    }
                    refreshSilenceState()
                } finally {
                    silenceActionPending = false
                }
            }
        }
    }

    manualSilenceIssue?.let { issue ->
        val needsDnd = issue == ManualSilenceIssue.Dnd
        AlertDialog(
            onDismissRequest = { manualSilenceIssue = null },
            title = { Text(stringResource(if (needsDnd) R.string.manual_dnd_access_title else R.string.manual_rule_failed_title)) },
            text = { Text(stringResource(if (needsDnd) R.string.manual_dnd_access_body else R.string.manual_rule_failed_body)) },
            confirmButton = {
                TextButton(onClick = {
                    manualSilenceIssue = null
                    if (needsDnd) SilenceAccessSettings.openDnd(context) else SilenceAccessSettings.openRules(context, SilenceOwner.MANUAL)
                }) {
                    Text(stringResource(if (needsDnd) R.string.manual_dnd_open_settings else R.string.manual_rule_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { manualSilenceIssue = null }) { Text(stringResource(R.string.jomoaa_time_cancel)) }
            },
        )
    }

    var checkingSilenceOptIn by remember { mutableStateOf(false) }
    fun changeAutoSilence(enabled: Boolean) {
        if (checkingSilenceOptIn || PrefsManager.isAutomaticSilenceRequested(context) == enabled) return
        if (enabled && !PrefsManager.hasConfirmedPlace(context)) {
            featureNeedsPlace = true
            return
        }
        schedulerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (enabled) {
                val mode = MosqueSilenceSettings.mode(context)
                if (mode == null) {
                    context.startActivity(Intent(context, MosqueSettingsActivity::class.java))
                    return@launch
                }
                checkingSilenceOptIn = true
                val ready = try {
                    withContext(Dispatchers.IO) { MosqueReadiness.forMode(context, mode).isEmpty() }
                } finally {
                    checkingSilenceOptIn = false
                }
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (!ready) {
                    context.startActivity(MosqueSettingsActivity.enableIntent(context, mode))
                    return@launch
                }
                if (!PrefsManager.activateConfiguredSilence(context, mode, firstRun = false)) return@launch
            } else {
                PrefsManager.setEnabled(context, false)
            }
            autoSilenceEnabled = enabled
            autoSilenceRequested = enabled
            AnalyticsTracker.autoSilenceStateChanged(context, enabled)
            Toast.makeText(context, if (enabled) R.string.toast_auto_enabled else R.string.toast_auto_disabled, Toast.LENGTH_SHORT).show()
            withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                ScheduleRefreshCoordinator.syncSilence(context)
            }
            refreshSilenceState()
        }
    }

    suspend fun syncWakeScheduling() {
        ScheduleRefreshCoordinator.syncWake(context)
    }

    // Single resume-sync effect — merges all refreshTick observers into one
    // so execution order is deterministic and state is read only once.
    LaunchedEffect(refreshTick) {
        // The silence and alarm work off the main thread, in turn with the screen's other silence work.
        withContext(ScheduleRefreshCoordinator.scheduling) { ManualSilenceScheduler.restoreTimer(context) }

        // Auto-update location from last known GPS fix
        if (PrefsManager.isAutoLocationUpdateEnabled(context) &&
            DelegationLocator.hasLocationPermission(context)
        ) {
            if (DelegationLocator.updateDelegationFromLastLocation(context)) {
                delegationId = screenTimesId(context)
            }
        }

        withContext(ScheduleRefreshCoordinator.scheduling) {
            ScheduleRefreshCoordinator.syncSilence(
                context = context,
                skipWhileManualSilence = true,
            )
            syncWakeScheduling()
        }

        withContext(Dispatchers.IO) {
            DhikrReminderScheduler.refresh(context, rearm = true)
        }

        refreshSilenceState()
    }

    // Start Ramadan override polling on first composition
    LaunchedEffect(Unit) {
        AnalyticsTracker.installRamadanOverrideReporter(context)
        RamadanOverrideChecker.startPollingIfNeeded()
    }

    // The Adhkar tab's two scenes, decoded once the launch has settled, so its first visit shows them at once.
    LaunchedEffect(Unit) {
        delay(ADHKAR_SCENES_WARM_DELAY_MS)
        withContext(Dispatchers.IO) { runCatching { AdhkarScenes.warm(context, AdhkarScenes.cardWidthPx(context)) } }
    }

    // Scheduling follows announcements for the process lifetime in Application.
    // Refresh the visible controls here without scheduling the same update twice.
    val currentOfficialDates = officialDateRecords[algorithmicHijriYear(LocalDate.now())]
    LaunchedEffect(currentOfficialDates) {
        if (currentOfficialDates != null) {
            refreshSilenceState()
        }
    }

    LaunchedEffect(hasDnd, hasAlarm, hasBattery) {
        AnalyticsTracker.activationCompletedIfNeeded(
            context = context,
            dndGranted = hasDnd,
            exactAlarmGranted = hasAlarm,
            batteryExempt = hasBattery,
        )
    }

    LaunchedEffect(manualSilenceEndsAtMillis) {
        if (manualSilenceEndsAtMillis <= 0L) return@LaunchedEffect

        val waitMillis = manualSilenceEndsAtMillis - System.currentTimeMillis()
        if (waitMillis <= 0L) {
            refreshTick++
            return@LaunchedEffect
        }

        delay(waitMillis)
        refreshTick++
    }

    // While the screen shows: in the background nobody sees it, and a return refreshes it at once (ON_RESUME).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1_000L)
                refreshSilenceState()
            }
        }
    }

    // Reschedule helper
    fun rescheduleIfEnabled(editedWake: PrayerWakeConfig? = null) {
        schedulerScope.launch {
            // Runs even if the screen goes before its turn comes: the change it follows is already saved.
            val silenceApplied = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                ScheduleRefreshCoordinator.syncSilence(context)
                syncWakeScheduling()
                editedWake == null || WakeAlarmScheduler.applyEditedTimerSilence(context, editedWake)
            }
            if (!silenceApplied) Toast.makeText(context, R.string.wake_timer_silence_unapplied, Toast.LENGTH_LONG).show()
            // Sync UI after rescheduling — scheduleAll may have silenced the phone
            // if the current time now falls inside a silence window.
            refreshSilenceState()
            refreshTick++
        }
    }

    var editingWakeAlarm by rememberSaveable(requestedDestinationSequence, stateSaver = WakeDraftSaver) {
        mutableStateOf<PrayerWakeConfig?>(null)
    }
    var quickAddVisible by rememberSaveable { mutableStateOf(false) }
    var wakeSilenceConflictEditor by remember { mutableStateOf<WakeSilenceConflictEditorState?>(null) }
    var pendingReturnToPrayersAfterEdit by rememberSaveable(requestedDestinationSequence) { mutableStateOf(false) }

    fun createWakeAlarm(preset: WakeQuickPreset) {
        quickAddVisible = false
        wakeSilenceConflictEditor = null
        editingWakeAlarm = newWakeAlarmConfig(preset)
    }

    LaunchedEffect(requestedDestination, requestedDestinationSequence) {
        // The travel notification opens the city picker (6.8).
        if (directedPicker) {
            locationPickerRequested = true
            deferSilenceForPickerVisit = true
            handledPickerSequence = requestedDestinationSequence
        }
        // The fasting notification opens its card's sheet, or the suhoor alarm's questions.
        if (requestedDestination == MainTabNavigation.DESTINATION_WORSHIP) when (requestedWorshipAction) {
            MainTabNavigation.WORSHIP_ACTION_FASTING -> FastingRequests.sheet = true
            MainTabNavigation.WORSHIP_ACTION_SUHOOR -> FastingRequests.suhoor = true
            MainTabNavigation.WORSHIP_ACTION_REMEMBRANCE -> RemembranceRequests.sheet = true
        }
    }
    val todayScrollState = rememberScrollState()
    val alarmsScrollState = rememberScrollState()
    val qiblaScrollState = rememberScrollState()
    val adhkarScrollState = rememberScrollState()
    val prayerDayZone = PrayerTimesRepository.place(context).zone
    val prayerToday = rememberCalendarToday(prayerDayZone)
    // A chosen day survives tab changes and recreation. "Today" follows midnight in the selected place.
    var pickedPrayerEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(prayerToday) {
        if (pickedPrayerEpochDay == prayerToday.toEpochDay()) pickedPrayerEpochDay = null
    }
    val prayerTableSelectedDate = calendarDateMillis(pickedPrayerEpochDay?.let(LocalDate::ofEpochDay) ?: prayerToday)
    val selectedDestinationScrollState = when (selectedDestination) {
        MainDestination.Today -> todayScrollState
        MainDestination.Alarms -> alarmsScrollState
        MainDestination.Worship -> qiblaScrollState
        MainDestination.Adhkar, MainDestination.Quran -> adhkarScrollState
    }

    LaunchedEffect(selectedDestination) {
        AnalyticsTracker.tabViewed(context, selectedDestination.analyticsName())
    }

    // When an alarm edit was launched from the prayer-tab timeline, return there once the editor closes.
    LaunchedEffect(editingWakeAlarm) {
        if (editingWakeAlarm == null && pendingReturnToPrayersAfterEdit) {
            selectedDestinationIndex = mainDestinations.indexOf(MainDestination.Today)
            pendingReturnToPrayersAfterEdit = false
        }
    }

    val wakeRepository = remember(context) { com.tunisianprayertimes.wake.PrayerWakeRepository(context) }
    val storedWakeAlarms: List<PrayerWakeConfig>? by wakeRepository.wakeAlarms.collectAsState(initial = null)
    val mainScope = rememberCoroutineScope()
    val awakeCheckRunning by AwakeCheckService.isRunning.collectAsState()

    // The Fajr suggestion saves straight away, as the editor's save would.
    fun addSuggestedFajrAlarm(config: PrayerWakeConfig) {
        if (!PrefsManager.hasConfirmedPlace(context)) {
            featureNeedsPlace = true
            return
        }
        if (hasUnresolvedWakeSilenceConflict(context, delegationId, config)) {
            // Only the editor can settle a silence-window conflict, so the alarm opens there.
            quickAddVisible = false
            wakeSilenceConflictEditor = null
            editingWakeAlarm = config
            return
        }
        mainScope.launch {
            wakeRepository.saveWakeConfig(config)
            AnalyticsTracker.wakeAlarmSaved(context, config)
            rescheduleIfEnabled()
        }
    }

    if (quickAddVisible) {
        WakeQuickAddSheet(
            onDismiss = { quickAddVisible = false },
            onPresetSelected = ::createWakeAlarm,
        )
    }
    // Re-read the selected mode's prerequisites on opt-in and return from Settings.
    // Reading private areas and checking platform rules stay off the main thread.
    val configuredSilenceMode = remember(refreshTick) { MosqueSilenceSettings.mode(context) }
    val silenceIssues by key(autoSilenceEnabled, configuredSilenceMode, refreshTick, selectedDestination) {
        produceState<List<Int>?>(initialValue = null) {
            if (autoSilenceEnabled && selectedDestination == MainDestination.Today) {
                value = withContext(Dispatchers.IO) {
                    if (configuredSilenceMode == null) listOf(R.string.mosque_invalid_mode)
                    else MosqueReadiness.forMode(context, configuredSilenceMode)
                }
            }
        }
    }
    val missingSilenceIssue = firstPrayerSilenceIssue(silenceIssues)
    val missingCallAccess = callEndVibrationEnabled && !hasPhoneState
    val usesPlaceOnboarding = remember { PrefsManager.usesPlaceOnboarding(context) }
    val methodConfirmation: @Composable () -> Unit = {
        methodQuestion?.takeUnless { placeProvisional }?.let { question ->
            MethodConfirmBanner(
                question = question,
                compact = usesPlaceOnboarding,
                onChoose = {
                    if (question.hasChooser) methodChooserOpen = true
                    else prayerTabSettingsPage = PrayerTabSettingsPage.Calculation
                },
                onConfirm = {
                    answerMethodQuestion(context, question, methodId = null, asrShadow = null)
                    methodQuestion = null
                },
            )
        }
    }
    val showWakeAlarmAddButton = selectedDestination == MainDestination.Alarms &&
        storedWakeAlarms?.isNotEmpty() == true &&
        editingWakeAlarm == null
    var wakeAlarmAddButtonVisible by remember { mutableStateOf(true) }
    var wakeAlarmBottomPullHideActive by remember { mutableStateOf(false) }

    LaunchedEffect(showWakeAlarmAddButton, alarmsScrollState) {
        if (!showWakeAlarmAddButton) {
            wakeAlarmAddButtonVisible = true
            return@LaunchedEffect
        }

        var previousScrollValue = alarmsScrollState.value
        snapshotFlow {
            Pair(
                alarmsScrollState.value,
                alarmsScrollState.isScrollInProgress,
            )
        }.collectLatest { (scrollValue, scrollInProgress) ->
            val scrollingDown = scrollInProgress && scrollValue > previousScrollValue
            val scrollingUp = scrollInProgress && scrollValue < previousScrollValue
            val atBottom = scrollValue >= alarmsScrollState.maxValue
            previousScrollValue = scrollValue

            when {
                scrollingDown -> {
                    wakeAlarmBottomPullHideActive = false
                    wakeAlarmAddButtonVisible = false
                }
                scrollInProgress && atBottom && !scrollingUp -> {
                    wakeAlarmBottomPullHideActive = true
                    wakeAlarmAddButtonVisible = false
                }
                scrollingUp -> {
                    wakeAlarmBottomPullHideActive = false
                    wakeAlarmAddButtonVisible = true
                }
                !scrollInProgress -> {
                    val revealDelayMillis = if (wakeAlarmBottomPullHideActive) {
                        WakeAlarmAddButtonBottomPullRevealDelayMillis
                    } else {
                        WakeAlarmAddButtonIdleRevealDelayMillis
                    }
                    delay(revealDelayMillis)
                    wakeAlarmAddButtonVisible = true
                    wakeAlarmBottomPullHideActive = false
                }
            }
        }
    }

    val density = LocalDensity.current
    val navigationBarInsetPx = WindowInsets.navigationBars.getBottom(density)
    // Height the scrolling content keeps clear of the bottom navigation. The surface
    // consumes the system navigation inset once; the measured surface height already
    // includes it, so the scrolling content must not add it again.
    var bottomNavigationOccupiedPx by remember {
        mutableIntStateOf(
            with(density) { MainContentBottomPadding.roundToPx() } + navigationBarInsetPx,
        )
    }
    // The Quran reader's dock sits right on the bar, and the bar slides away for its full player and focus mode.
    var bottomNavigationHeightPx by remember {
        mutableIntStateOf(with(density) { BottomNavigationMinHeight.roundToPx() } + navigationBarInsetPx)
    }
    var quranHidesNavigation by remember { mutableStateOf(false) }
    val navigationHidden = quranHidesNavigation && selectedDestination == MainDestination.Quran
    val quranBottomPadding by animateDpAsState(
        targetValue = with(density) { (if (navigationHidden) navigationBarInsetPx else bottomNavigationHeightPx).toDp() },
        label = "quranBottomPadding",
    )
    val quranPlayback by com.tunisianprayertimes.quran.audio.QuranAudioController.state.collectAsState()
    // The bar's height above the system's own, worked out as the bar works it out: known before the bar is first shown,
    // for the Quran page to rest clear of it from the start.
    val quranBarRoom = bottomNavigationLabels(
        mainDestinations.map { stringResource(it.labelRes) },
        with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() },
    ).rowHeight

    val adhkarStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val quranStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val isAdhkar = selectedDestination == MainDestination.Adhkar
    Box(modifier = Modifier.fillMaxSize().background(BgCream)) {
        if (isAdhkar) {
            adhkarStateHolder.SaveableStateProvider("adhkar") {
                AdhkarScreen(
                    activity = activity,
                    requestedReminderId = requestedDhikrReminderId,
                    requestedReminderSequence = requestedDestinationSequence,
                    requestedOccurrenceId = requestedDhikrOccurrenceId,
                    modifier = Modifier.fillMaxSize().padding(bottom = with(density) { bottomNavigationOccupiedPx.toDp() }),
                )
            }
        } else if (selectedDestination == MainDestination.Quran) {
            quranStateHolder.SaveableStateProvider("quran") {
                // The bar comes over the reader, which keeps its height: the page never moves for the bar. The reader
                // only lifts what it shows at its foot, the dock and its cards, above the bar.
                val gestureBar = with(density) { navigationBarInsetPx.toDp() }
                QuranScreen(
                    modifier = Modifier.fillMaxSize().padding(bottom = gestureBar),
                    // Read where the reader lays out its foot: the bar sliding does not recompose either screen.
                    bottomBar = { (quranBottomPadding - gestureBar).coerceAtLeast(0.dp) },
                    bottomBarRoom = { quranBarRoom },
                    onHideNavigation = { quranHidesNavigation = it },
                    hifzRequest = requestedQuranAction?.let { it to requestedDestinationSequence },
                )
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgCream)
                .padding(bottom = with(density) { bottomNavigationOccupiedPx.toDp() })
                .statusBarsPadding()
                .verticalScroll(selectedDestinationScrollState)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                if (selectedDestination == MainDestination.Today) {
                    // The location is the page header: the bottom tab already names the page,
                    // and the place is what decides every time shown below it.
                    // The two round buttons sit on the title's first line, whatever the name's lines and the notes
                    // under it (both rows are aligned at the top).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LocationPickerCard(
                            openRequested = locationPickerRequested,
                            onOpenRequestHandled = { locationPickerRequested = false },
                            onDelegationSelected = { delegation ->
                                delegationId = delegation.id
                                PrefsManager.setDelegationId(context, delegation.id)
                                rescheduleIfEnabled()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PrayerTabSettingsButton(onClick = { prayerTabSettingsPage = PrayerTabSettingsPage.Overview })
                    }
                    // The place's and the method's questions, under the header they are about.
                    if (!usesPlaceOnboarding) methodConfirmation()
                    if (placeProvisional) {
                        ProvisionalPlaceBanner(
                            onConfirm = {
                                PrefsManager.setPlaceProvisional(context, false)
                                placeProvisional = false
                                rescheduleIfEnabled()
                            },
                            onChange = { locationPickerRequested = true },
                        )
                    }
                } else {
                    // No page title: the selected bottom tab already names the page.
                    Spacer(Modifier.height(12.dp))
                }

                when (selectedDestination) {
                    MainDestination.Today -> {
                        var middayRevision by remember { mutableIntStateOf(0) }
                        val nextMidday = remember(manualSilenceMode, manualSilenceActive, delegationId, prayerToday, refreshTick, middayRevision) {
                            if (manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER && !manualSilenceActive)
                                nextManualSilenceOccurrence(context, Prayer.DHUHR)
                            else null
                        }
                        LaunchedEffect(nextMidday) {
                            nextMidday?.let {
                                delay((it.at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1L))
                                middayRevision++
                            }
                        }
                        val middayName = nextMidday?.prayer ?: Prayer.DHUHR
                        val namedManualPrayer = when {
                            manualSilenceActive && manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER ->
                                PrefsManager.activeManualSilencePrayer(context)
                            manualTargetPrayer == Prayer.DHUHR -> middayName
                            else -> manualTargetPrayer
                        }
                        TodayNextPrayerCard(
                            delegationId = delegationId,
                            isPhoneSilenced = phoneSilenced || phoneDndActive,
                            doNotDisturbActive = phoneDndActive,
                            hasDnd = hasDnd,
                            autoSilenceActive = autoSilenceActive,
                            manualSilenceActive = manualSilenceActive,
                            manualSilenceMode = manualSilenceMode,
                            manualTargetPrayer = namedManualPrayer,
                            manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
                            wakeSilenceUntilAlarmActive = wakeSilenceUntilAlarmActive,
                            autoSilenceEnabled = autoSilenceRequested,
                            configuredMode = configuredSilenceMode,
                            setupIssues = if (autoSilenceRequested && !autoSilenceEnabled)
                                listOf(R.string.mosque_waiting_for_place) else silenceIssues,
                            onRepairSilence = {
                                if (!autoSilenceEnabled) featureNeedsPlace = true
                                else context.startActivity(MosqueSettingsActivity.setupIntent(context))
                            },
                            silenceActionPending = silenceActionPending,
                            manualActionLabel = manualSilenceActionLabel(
                                active = (manualSilenceActive || autoSilenceActive) && hasDnd,
                                mode = manualSilenceMode, targetPrayer = namedManualPrayer,
                                minutes = selectedManualMinutes,
                                exact = hasAlarm,
                            ),
                            onManualSilenceClick = ::toggleManualSilence,
                            onChooseManualDuration = { manualOptionsOpen = true },
                        )

                        PrayerSettingsCard(
                            delegationId = delegationId,
                            activity = activity,
                            placeConfirmed = !placeProvisional && PrefsManager.hasConfirmedPlace(context),
                            timetableUncovered = !methodChooserOpen && prayerTabSettingsPage == null && !locationPickerRequested,
                            autoSilenceEnabled = autoSilenceEnabled,
                            onChooseLocation = { locationPickerRequested = true },
                            pendingMethodRow = if (usesPlaceOnboarding && methodQuestion != null && !placeProvisional) methodConfirmation else null,
                            selectedDate = prayerTableSelectedDate,
                            onSelectedDateChange = { selectedDate ->
                                pickedPrayerEpochDay = calendarLocalDate(selectedDate).takeUnless { it == prayerToday }?.toEpochDay()
                            },
                            onConfigChanged = {
                                rescheduleIfEnabled()
                            },
                            silenceSetupIssues = if (autoSilenceRequested && !autoSilenceEnabled)
                                listOf(R.string.mosque_waiting_for_place) else silenceIssues,
                            onOpenSilenceSetup = {
                                if (!PrefsManager.hasConfirmedPlace(context)) featureNeedsPlace = true
                                else if (PrefsManager.isAutomaticSilenceRequested(context)) {
                                    context.startActivity(MosqueSettingsActivity.setupIntent(context))
                                } else changeAutoSilence(true)
                            },
                        )

                        if (LocalDensity.current.fontScale > 1.3f) {
                            NextPrayerHeroVerse(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = GreenPrimaryDark)
                        }
                        if (missingCallAccess) {
                            PermissionBanner(
                                message = stringResource(R.string.banner_phone_state_missing),
                                onClick = { ensureCallTrackingPermission() },
                            )
                        }

                        if (manualOptionsOpen) ManualSilenceOptionsSheet(
                            hasDnd = hasDnd,
                            hasExactAlarm = hasAlarm,
                            resolvedTotalMinutes = selectedManualMinutes,
                            middayPrayer = middayName,
                            namedTargetPrayer = namedManualPrayer,
                            manualSilenceMode = manualSilenceMode,
                            manualTargetPrayer = manualTargetPrayer,
                            manualDurationHours = manualDurationHours,
                            manualDurationMinutes = manualDurationMinutes,
                            manualSilenceActive = manualSilenceActive,
                            autoSilenceActive = autoSilenceActive,
                            manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
                            onModeChange = { mode ->
                                manualSilenceMode = mode
                                PrefsManager.setManualSilenceMode(context, mode)
                                if (mode == ManualSilenceMode.UNTIL_PRAYER) {
                                    val upcomingPrayer = resolveUpcomingManualSilencePrayer(context, delegationId) ?: manualTargetPrayer
                                    manualTargetPrayer = upcomingPrayer
                                    PrefsManager.setManualSilenceTargetPrayer(context, upcomingPrayer)
                                }
                            },
                            onTargetPrayerChange = { prayer ->
                                manualTargetPrayer = prayer
                                PrefsManager.setManualSilenceTargetPrayer(context, prayer)
                            },
                            onDurationHoursChange = { value ->
                                manualDurationHours = value
                                val totalMinutes = (value.toIntOrNull() ?: 0) * 60 + (manualDurationMinutes.toIntOrNull() ?: 0)
                                if (totalMinutes > 0) {
                                    PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                                }
                            },
                            onDurationMinutesChange = { value ->
                                manualDurationMinutes = value
                                val totalMinutes = (manualDurationHours.toIntOrNull() ?: 0) * 60 + (value.toIntOrNull() ?: 0)
                                if (totalMinutes > 0) {
                                    PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                                }
                            },
                            onDurationPresetSelected = { totalMinutes ->
                                manualDurationHours = (totalMinutes / 60).toString()
                                manualDurationMinutes = (totalMinutes % 60).toString()
                                PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                            },
                            onDismiss = {
                                val saved = PrefsManager.getManualSilenceDurationMinutes(context)
                                manualDurationHours = (saved / 60).toString()
                                manualDurationMinutes = (saved % 60).toString()
                                manualOptionsOpen = false
                            },
                            onClick = {
                                manualOptionsOpen = false
                                toggleManualSilence()
                            },
                        )

                        SunriseAndNightTimesCard(
                            delegationId = delegationId,
                            selectedDate = prayerTableSelectedDate,
                            wakeAlarms = storedWakeAlarms,
                            onEditWakeAlarm = { alarmId ->
                                storedWakeAlarms
                                    ?.firstOrNull { alarm -> alarm.id == alarmId }
                                    ?.let { alarm ->
                                        pendingReturnToPrayersAfterEdit = true
                                        selectedDestinationIndex = mainDestinations.indexOf(MainDestination.Alarms)
                                        editingWakeAlarm = alarm
                                    }
                            },
                        )

                        if (RamadanDetector.isRamadan()) {
                            RamadanBadge()
                        }

                        prayerTabSettingsPage?.let { page ->
                            PrayerTabSettingsSheet(
                                page = page,
                                onPageChange = { prayerTabSettingsPage = it },
                                place = PrayerTimesRepository.place(context),
                                selectedDate = prayerTableSelectedDate,
                                onTimesChanged = {
                                    delegationId = screenTimesId(context)
                                    schedulerScope.launch {
                                        ScheduleRefreshCoordinator.syncAll(context)
                                        withContext(Dispatchers.Default) {
                                            DhikrReminderScheduler.recheckSavedReminders(context)
                                        }
                                    }
                                },
                                autoSilenceEnabled = autoSilenceRequested,
                                onAutoSilenceChange = ::changeAutoSilence,
                                callEndVibrationEnabled = callEndVibrationEnabled,
                                onCallEndVibrationChange = { enabled ->
                                    callEndVibrationEnabled = enabled
                                    PrefsManager.setCallEndVibrationEnabled(context, enabled)
                                    if (enabled) ensureCallTrackingPermission()
                                },
                                autoLocationEnabled = autoLocationEnabled,
                                onAutoLocationChange = { enabled ->
                                    if (enabled && !PrefsManager.hasConfirmedPlace(context)) {
                                        featureNeedsPlace = true
                                    } else {
                                        autoLocationEnabled = enabled
                                        PrefsManager.setAutoLocationUpdateEnabled(context, enabled)
                                        if (!enabled) PrefsManager.setBackgroundTravelEnabled(context, false)
                                        if (enabled && !hasTravelLocation) {
                                            travelPermissionLauncher.launch(DelegationLocator.requestedPermissions)
                                        }
                                        refreshTick++
                                    }
                                },
                                access = PrayerSettingsAccess(
                                    silenceSetupIssue = missingSilenceIssue,
                                    silenceStatus = when {
                                        !autoSilenceRequested -> null
                                        !autoSilenceEnabled -> R.string.mosque_waiting_for_place
                                        silenceIssues == null -> R.string.mosque_setup_checking
                                        missingSilenceIssue != null && configuredSilenceMode == AutomaticSilenceMode.TIME_AND_MOSQUE &&
                                            MosqueReadiness.locationIssue(missingSilenceIssue) -> R.string.prayer_silence_location_fallback
                                        missingSilenceIssue != null -> R.string.prayer_silence_waiting_for_setup
                                        else -> null
                                    },
                                    hasPhoneState = hasPhoneState,
                                    hasForegroundLocation = hasTravelLocation,
                                    backgroundTravelActive = PrefsManager.isBackgroundTravelEnabled(context) &&
                                        DelegationLocator.hasBackgroundLocationPermission(context),
                                    offerBatteryHelp = !hasBattery && (autoSilenceEnabled || storedWakeAlarms.orEmpty().any { it.enabled } ||
                                        com.tunisianprayertimes.adhan.AdhanSettings.enabledPrayers(context).isNotEmpty()),
                                ),
                                onBackgroundTravelChange = { enabled ->
                                    if (enabled && !PrefsManager.hasConfirmedPlace(context)) {
                                        featureNeedsPlace = true
                                    } else if (enabled) {
                                        backgroundLocationDisclosureOpen = true
                                    } else {
                                        PrefsManager.setBackgroundTravelEnabled(context, false)
                                        refreshTick++
                                    }
                                },
                                onDismiss = { prayerTabSettingsPage = null },
                            )
                        }
                        if (backgroundLocationDisclosureOpen) {
                            BackgroundLocationDisclosure { granted ->
                                backgroundLocationDisclosureOpen = false
                                if (granted && PrefsManager.isAutoLocationUpdateEnabled(context) && PrefsManager.hasConfirmedPlace(context)) {
                                    PrefsManager.setBackgroundTravelEnabled(context, true)
                                }
                                refreshTick++
                            }
                        }
                    }

                    MainDestination.Alarms -> {
                        WakeAlarmCard(
                            wakeAlarms = storedWakeAlarms,
                            exactAlarmsAllowed = hasAlarm,
                            onPlaceRequired = { featureNeedsPlace = true },
                            delegationId = delegationId,
                            activity = activity,
                            awakeCheckRunning = awakeCheckRunning,
                            onConfirmAwake = {
                                AwakeCheckService.confirmAwake(context)
                            },
                            onConfigChanged = ::rescheduleIfEnabled,
                            onPresetSelected = ::createWakeAlarm,
                            onEditAlarm = { config -> editingWakeAlarm = config },
                            onAddFajrAlarm = ::addSuggestedFajrAlarm,
                        )
                    }

                    MainDestination.Worship -> {
                        WorshipPage(placeRevision = delegationId)
                    }
                    MainDestination.Adhkar, MainDestination.Quran -> Unit
                }

            }
        }

        }
        AnimatedVisibility(
            visible = !navigationHidden,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    bottomNavigationHeightPx = size.height
                    bottomNavigationOccupiedPx = size.height + with(density) {
                        (MainContentBottomPadding - BottomNavigationMinHeight).roundToPx()
                    }
                }
                // A hairline over the bar's top edge, drawn so it adds nothing to the measured height.
                .drawWithContent {
                    drawContent()
                    drawLine(CardBorder, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                },
            color = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Content-driven height: large labels grow the bar instead of
                    // being clipped by a fixed 64dp row.
                    val navigationLabels = bottomNavigationLabels(
                        mainDestinations.map { stringResource(it.labelRes) }, constraints.maxWidth.toFloat(),
                    )
                    val rowHeight = navigationLabels.rowHeight
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .selectableGroup()
                            .padding(horizontal = 8.dp, vertical = BottomNavigationVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        mainDestinations.forEachIndexed { index, destination ->
                            val selected = currentDestinationIndex == index
                            val label = stringResource(destination.labelRes)
                            val tabPillColor by animateColorAsState(
                                targetValue = if (selected) NextPrayerBg else Color.Transparent,
                                label = "bottomTabPill"
                            )
                            val tabContentColor by animateColorAsState(
                                targetValue = if (selected) GreenPrimaryDark else TextMuted,
                                label = "bottomTabContent"
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp)
                                    .testTag(destination.testTag())
                                    .clip(RoundedCornerShape(12.dp))
                                    .selectable(
                                        selected = selected,
                                        role = Role.Tab,
                                        onClick = { selectedDestinationIndex = index },
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(BottomNavigationPillWidth, BottomNavigationPillHeight)
                                        .clip(CircleShape)
                                        .background(tabPillColor),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box {
                                        Icon(
                                            painter = painterResource(
                                                if (selected) destination.selectedIconRes else destination.iconRes
                                            ),
                                            contentDescription = null,
                                            tint = tabContentColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        // A recitation plays on while another tab is open.
                                        if (destination == MainDestination.Quran && !selected && quranPlayback.playing) {
                                            Box(
                                                Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 3.dp, y = (-2).dp)
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(GreenPrimary)
                                                    .testTag("quran_tab_playing")
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(BottomNavigationLabelGap))

                                Text(
                                    text = label,
                                    fontSize = navigationLabels.size,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = tabContentColor,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (showWakeAlarmAddButton && wakeAlarmAddButtonVisible) {
            WakeAlarmFloatingAddButton(
                onClick = { quickAddVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 24.dp,
                        bottom = with(density) { bottomNavigationOccupiedPx.toDp() } +
                            (WakeAlarmAddButtonBottomPadding - MainContentBottomPadding),
                    ),
            )
        }

        methodQuestion?.takeIf { methodChooserOpen }?.let { question ->
            MethodChooserSheet(
                question = question,
                onAnswered = {
                    methodChooserOpen = false
                    methodQuestion = null
                    delegationId = screenTimesId(context)
                    mainScope.launch { ScheduleRefreshCoordinator.syncAll(context) }
                },
                onDismiss = { methodChooserOpen = false },
            )
        }

        // Full-screen wake alarm editor overlay
        editingWakeAlarm?.let { wakeAlarm ->
            val wakeAlarms by wakeRepository.wakeAlarms.collectAsState(initial = emptyList())
            val isPersistedAlarm = wakeAlarms.any { existing -> existing.id == wakeAlarm.id }
            WakeEditorSheet(
                activity = activity,
                onPlaceRequired = { draft ->
                    editingWakeAlarm = draft
                    featureNeedsPlace = true
                },
                delegationId = delegationId,
                initialConfig = wakeAlarm,
                isNewAlarm = !isPersistedAlarm,
                silenceConfigRevision = refreshTick + (wakeSilenceConflictEditor?.editRevision ?: 0),
                silenceConflictResolverState = wakeSilenceConflictEditor?.let { editorState ->
                    WakeSilenceConflictResolverState(
                        conflict = editorState.currentConflict ?: editorState.originalConflict,
                        resolved = editorState.hasEditedSilenceWindow && editorState.currentConflict == null,
                    )
                },
                silenceConflictResolverContent = {
                    wakeSilenceConflictEditor?.let { editorState ->
                        WakeSilenceConflictPrayerEditor(
                            delegationId = delegationId,
                            editorState = editorState,
                            onConfigChanged = { updatedSilenceConfig ->
                                wakeSilenceConflictEditor = wakeSilenceConflictEditor?.let { state ->
                                    refreshWakeSilenceConflictEditor(
                                        context = context,
                                        delegationId = delegationId,
                                        editorState = state.copy(draftSilenceConfig = updatedSilenceConfig),
                                    )
                                }
                            },
                        )
                    }
                },
                onDismissRequest = {
                    editingWakeAlarm = null
                    wakeSilenceConflictEditor = null
                },
                onSave = { edited ->
                    // Made to repeat, the one-night suhoor alarm becomes one of the reader's own, under a new id and
                    // without its name, so the fasting card never replaces or deletes it.
                    val suhoorMadeOwn = edited.id == FastingReminder.SUHOOR_ALARM_ID && edited.repeatMode != WakeRepeatMode.ONCE
                    val config = if (suhoorMadeOwn) edited.copy(id = UUID.randomUUID().toString(), title = "") else edited
                    val stagedSilenceEditor = wakeSilenceConflictEditor
                    if (stagedSilenceEditor?.hasEditedSilenceWindow == true && !config.ringDuringSilenceWindow) {
                        val conflict = stagedSilenceEditor.originalConflict
                        persistPrayerSilenceConfig(
                            context = context,
                            prayer = conflict.silencePrayer,
                            config = stagedSilenceEditor.draftSilenceConfig,
                            anchor = conflict.silencePrayerDay?.let { timelineAnchorFor(context, conflict.silencePrayer, it) },
                        )
                    }
                    mainScope.launch {
                        if (suhoorMadeOwn) wakeRepository.deleteWakeAlarm(edited.id)
                        wakeRepository.saveWakeConfig(config)
                        AnalyticsTracker.wakeAlarmSaved(context, config)
                        editingWakeAlarm = null
                        wakeSilenceConflictEditor = null
                        rescheduleIfEnabled(config)
                    }
                },
                onEditSilenceWindowRequest = { draftConfig, conflict ->
                    quickAddVisible = false
                    wakeSilenceConflictEditor = WakeSilenceConflictEditorState(
                        draftConfig = draftConfig,
                        originalConflict = conflict,
                        draftSilenceConfig = PrefsManager.getEffectiveConfig(context, conflict.silencePrayer),
                    )
                },
                onCancelSilenceWindowEdit = {
                    wakeSilenceConflictEditor = null
                },
                onDelete = if (isPersistedAlarm) {
                    {
                        WakeAlarmScheduler.removeSilenceUntilAlarm(context, wakeAlarm.id)
                        mainScope.launch {
                            wakeRepository.deleteWakeAlarm(wakeAlarm.id)
                            editingWakeAlarm = null
                            wakeSilenceConflictEditor = null
                            rescheduleIfEnabled()
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
    if (featureNeedsPlace) {
        PrayerPlaceRequiredDialog(onPlaceConfirmed = { refreshTick++ }) { featureNeedsPlace = false }
    }
}

@Composable
private fun AwakeCheckBanner(onConfirm: () -> Unit) {
    val motivation by AwakeCheckService.motivation.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tab_alarms),
                contentDescription = null,
                tint = Color(0xFFE65100),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.awake_check_confirm_prompt),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
            ) {
                Text(
                    text = stringResource(R.string.awake_check_confirm),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            motivation?.let { message ->
                Spacer(Modifier.height(12.dp))
                key(message.reviewId) {
                    WakeMotivationCard(message, Modifier.fillMaxWidth().heightIn(max = 240.dp), compact = true)
                }
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    message: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.PERMISSION_BANNER)
            .padding(top = 12.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = BannerBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, BannerStroke)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = BannerText,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(22.dp),
            )
            Text(
                text = message,
                fontSize = 13.sp,
                color = BannerText,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationPickerCard(
    onDelegationSelected: (Delegation) -> Unit,
    openRequested: Boolean = false,
    onOpenRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val gouvernorats = remember { GouvernoratRepository.loadAll(context) }
    // Show the lightweight name list immediately while current-period timetable
    // availability is checked in the background. Selection always rechecks it.
    var allDelegations by remember { mutableStateOf(gouvernorats.flatMap { it.delegations }) }
    var sourcesChecked by remember { mutableStateOf(false) }
    val availableIds = remember(allDelegations) { allDelegations.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()
    var sourceRefreshJob by remember { mutableStateOf<Job?>(null) }
    var selectionJob by remember { mutableStateOf<Job?>(null) }
    fun refreshSources() {
        sourceRefreshJob?.cancel()
        sourceRefreshJob = scope.launch {
            allDelegations = withContext(Dispatchers.IO) {
                GouvernoratRepository.loadAllDelegations(context)
            }
            sourcesChecked = true
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshSources()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refreshSources()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshSources()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    // Prepare names while the main screen is visible; opening the sheet never waits for polygons. Not with the launch's
    // first frames, which it slowed (seconds of CPU at every start): a moment later, or at once when the sheet opens.
    var pickerWanted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PICKER_PREPARE_DELAY_MS)
        pickerWanted = true
    }
    val pickerCatalog by produceState(LocalityPickerCatalog.empty, allDelegations, pickerWanted) {
        if (!pickerWanted) return@produceState
        value = withContext(Dispatchers.Default) { LocalityRepository.preparePicker(context, allDelegations) }
    }
    // Every country's cities (owner, 2026-10-19: all regions open), their names folded once, so the picker's search
    // never folds 26,000 names per key.
    val openRegionCities by produceState(CitySearch.Index.EMPTY, pickerWanted) {
        if (!pickerWanted) return@produceState
        value = withContext(Dispatchers.IO) { PlaceCatalogue.get(context).pickerIndex }
    }

    var selectedLocation by remember { mutableStateOf(PrefsManager.getLocationSelection(context)) }
    DisposableEffect(context) {
        val unsubscribe = PrefsManager.observeLocationSelection(context) {
            selectedLocation = PrefsManager.getLocationSelection(context)
        }
        selectedLocation = PrefsManager.getLocationSelection(context)
        onDispose { unsubscribe() }
    }
    val savedPlace = selectedLocation.place
    val delegationId = (savedPlace as? PrayerPlace.Inm)?.delegationId
    val savedDelegation = remember(delegationId, allDelegations) { allDelegations.find { it.id == delegationId } }
    // Only the most recent pick, lookup or locality repair is allowed to publish its result.
    var placeChoice by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(sourcesChecked, allDelegations, selectedLocation) {
        val saved = selectedLocation
        // The source repair is for a Tunisian locality only (6.8).
        if (!sourcesChecked || saved.fromGps || saved.place !is PrayerPlace.Inm) return@LaunchedEffect
        val choice = placeChoice
        val id = saved.localityId ?: return@LaunchedEffect
        val sources = allDelegations
        val repaired = withContext(Dispatchers.Default) {
            // Manual groups and raw localities both have retained representatives.
            // Recompute after source changes even when the old source is usable;
            // GPS selections keep the result of their actual fix and are skipped.
            runCatching { LocalityRepository.manualSelection(context, id, saved.manualReferenceId) }.getOrNull()
                ?.takeIf { it.lat != null && it.lng != null }
                ?.let { withAvailablePrayerSource(it, sources) }
        } ?: return@LaunchedEffect
        if (repaired.delegationId == saved.delegationId) return@LaunchedEffect
        val source = sources.find { it.id == repaired.delegationId } ?: return@LaunchedEffect
        if (choice != placeChoice || PrefsManager.getLocationSelection(context) != saved) return@LaunchedEffect
        PrefsManager.setLocality(context, repaired)
        onDelegationSelected(source)
        selectedLocation = PrefsManager.getLocationSelection(context)
    }
    val savedSourceUnavailable = sourcesChecked && savedPlace is PrayerPlace.Inm &&
        selectedLocation.delegationId !in availableIds
    var showSheet by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    // A city abroad picked by hand, its first days being computed before it is stored.
    var preparingPlace by remember { mutableStateOf(false) }

    fun openPicker() {
        placeChoice++
        preparingPlace = false
        refreshSources()
        pickerWanted = true
        showSheet = true
    }

    LaunchedEffect(openRequested) {
        if (openRequested) {
            openPicker()
            onOpenRequestHandled()
        }
    }

    fun startLocationLookup() {
        if (locating || preparingPlace) {
            return
        }

        locating = true
        val choice = ++placeChoice
        val initialSelection = PrefsManager.getLocationSelection(context)
        scope.launch {
            val detected = DelegationLocator.detectNearestDelegation(context)
            if (choice != placeChoice || PrefsManager.getLocationSelection(context) != initialSelection) {
                // A place picked by hand during the lookup stays.
                locating = false
                return@launch
            }
            val result = if (DelegationLocator.hasLocationPermission(context)) detected else DelegationLocationResult.PermissionDenied
            if (result is DelegationLocationResult.Abroad) {
                // Stored from the process's scope: leaving the tab while the new place's first days are computed (off the
                // main thread: abroad they may scan a year of times, 6.5) must not lose the fix.
                PlaceResolution.changes.launch {
                    try {
                        withContext(Dispatchers.Default) { runCatching { PrayerTimesRepository.warm(context, result.place) } }
                    } finally {
                        locating = false
                    }
                    if (choice != placeChoice || PrefsManager.getLocationSelection(context) != initialSelection ||
                        !DelegationLocator.hasLocationPermission(context)
                    ) return@launch
                    AnalyticsTracker.markDelegationSource(context, "gps_abroad")
                    val saved = PrefsManager.getPlace(context)
                    val savedLabel = PrefsManager.getPlaceNameSnapshot(context)
                    PrefsManager.setPlace(context, result.place, result.label, fromGps = true)
                    selectedLocation = PrefsManager.getLocationSelection(context)
                    PlaceResolution.onPlaceChanged(context, saved, result.place, savedLabel, result.label)
                }
                return@launch
            }
            locating = false

            when (result) {
                is DelegationLocationResult.Success -> {
                    AnalyticsTracker.markDelegationSource(context, "gps_success")
                    onDelegationSelected(result.delegation)
                    PrefsManager.setGpsLocation(context, result)
                    selectedLocation = PrefsManager.getLocationSelection(context)
                }
                DelegationLocationResult.PermissionDenied -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "denied",
                        entryPoint = "delegation_picker",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_permission_denied),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.LocationUnavailable -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "location_unavailable",
                        entryPoint = "delegation_picker",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_lookup_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.NoDelegationFound -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "no_delegation_found",
                        entryPoint = "delegation_picker",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_no_match),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is DelegationLocationResult.Abroad -> Unit // stored above
            }
        }
    }

    val abroad = savedPlace as? PrayerPlace.Coordinates
    val locationTitle = if (abroad != null) {
        selectedLocation.placeName ?: stringResource(R.string.location_current_place)
    } else selectedLocation.name ?: savedDelegation?.nomAr ?: stringResource(when {
        savedPlace == PrayerPlace.Inm(PlaceResolution.TUNIS_DELEGATION_ID) -> R.string.location_default_tunis
        selectedLocation.fromGps -> R.string.location_current_position
        else -> R.string.hint_search_delegation
    })
    val hasLocation = abroad != null || savedDelegation != null || selectedLocation.name != null || selectedLocation.fromGps
    val governorateName = remember(gouvernorats, delegationId) {
        gouvernorats.firstOrNull { gov -> gov.delegations.any { it.id == delegationId } }?.nomAr
    }
    // Where the timetable comes from: the delegation (unless it is already the title)
    // and its governorate, so repeated neighbourhood names stay unambiguous.
    val placeContext = listOfNotNull(
        savedDelegation?.nomAr?.takeIf { it != locationTitle },
        governorateName?.takeIf { it != locationTitle && it != savedDelegation?.nomAr },
    ).joinToString("، ")
    // A place abroad found by GPS is named by its catalogue city («قرب X»), a delegation by its neighbourhood.
    val gpsSource = stringResource(R.string.location_header_from_gps)
        .takeIf { selectedLocation.fromGps && (selectedLocation.name != null || abroad != null) }
    val sourceContext = if (selectedLocation.name != null && savedDelegation != null &&
        savedDelegation.nomAr != locationTitle && placeContext.isNotBlank()
    ) stringResource(R.string.prayer_entry_time_reference, placeContext) else placeContext
    val locationSubtitle = if (abroad != null) {
        val area = if (selectedLocation.fromGps) null else abroad.catalogueId?.let(openRegionCities::areaOf)
        listOfNotNull(
            gpsSource, area?.let { cityAreaLabel(it) },
            abroad.countryCode?.let { CitySearch.countryName(PlaceCountry.normalize(it)) },
        ).joinToString(" · ")
    } else listOfNotNull(gpsSource, sourceContext.takeIf { it.isNotEmpty() })
        .joinToString(" · ")

    Column(modifier = modifier.testTag(TestTags.LOCATION_PICKER)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Full name, never truncated: a long name wraps instead of being cut off.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.location_change_action),
                        onClick = ::openPicker,
                    )
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = locationTitle,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasLocation) GreenPrimaryDark else TextMuted,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        tint = PrayerSilencePalette.SecondaryText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (locationSubtitle.isNotEmpty()) {
                    Text(
                        text = locationSubtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = PrayerSilencePalette.SecondaryText,
                    )
                }
            }
            PrayerHeaderGpsAction(
                placeChoice = placeChoice,
                locating = locating,
                preparingPlace = preparingPlace,
                onLocate = ::startLocationLookup,
                onChooseCity = ::openPicker,
            )
        }
        if (savedSourceUnavailable) {
            Text(
                text = stringResource(if (allDelegations.isEmpty()) {
                    R.string.location_no_current_prayer_data
                } else R.string.location_saved_source_unavailable),
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (abroad == null && selectedLocation.fromGps && selectedLocation.name == null) {
            // Tunisia only: a delegation found by GPS whose neighbourhood polygons have no name there. Abroad there are
            // no neighbourhoods, and the catalogue city is the name.
            Text(
                text = stringResource(R.string.location_neighborhood_unavailable),
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (showSheet) {
        LocalityPickerSheet(
            catalog = pickerCatalog,
            gouvernorats = gouvernorats,
            selectedId = selectedLocation.localityId.orEmpty(),
            onDismiss = {
                selectionJob?.cancel()
                showSheet = false
            },
            onSelect = { locality ->
                // The latest choice of place wins: a city still being prepared or a lookup still running is dropped.
                placeChoice++
                preparingPlace = false
                // A sheet can stay open across a month boundary; selection must
                // use sources with prayer data for the current period.
                selectionJob?.cancel()
                selectionJob = scope.launch {
                    val currentDelegations = withContext(Dispatchers.IO) {
                        GouvernoratRepository.loadAllDelegations(context)
                    }
                    if (!showSheet) return@launch
                    allDelegations = currentDelegations
                    sourcesChecked = true
                    val mapped = withAvailablePrayerSource(locality, currentDelegations)
                    val delegation = mapped?.let { selection -> currentDelegations.firstOrNull { it.id == selection.delegationId } }
                    if (delegation != null) {
                        showSheet = false
                        AnalyticsTracker.markDelegationSource(context, "manual")
                        onDelegationSelected(delegation)
                        PrefsManager.setLocality(context, mapped)
                        selectedLocation = PrefsManager.getLocationSelection(context)
                    } else {
                        Toast.makeText(context, R.string.location_no_match, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            cities = openRegionCities,
            loading = pickerCatalog === LocalityPickerCatalog.empty,
            citiesLoading = openRegionCities === CitySearch.Index.EMPTY,
            selectedCityId = abroad?.catalogueId,
            onSelectCity = { row ->
                showSheet = false
                val choice = ++placeChoice
                val initialSelection = PrefsManager.getLocationSelection(context)
                preparingPlace = true
                PlaceResolution.changes.launch {
                    val place = PlaceResolution.placeFromCatalogue(context, row)
                    val label = PlaceResolution.catalogueLabel(row)
                    // The new place's first days off the main thread: abroad they may scan a year of times (6.5).
                    try {
                        withContext(Dispatchers.Default) { runCatching { PrayerTimesRepository.warm(context, place) } }
                    } finally {
                        if (choice == placeChoice) preparingPlace = false
                    }
                    // A later choice of place wins.
                    if (choice != placeChoice || PrefsManager.getLocationSelection(context) != initialSelection) return@launch
                    val old = PrefsManager.getPlace(context)
                    val oldLabel = PrefsManager.getPlaceNameSnapshot(context)
                    AnalyticsTracker.markDelegationSource(context, "manual_abroad")
                    PrefsManager.setPlace(context, place, label, fromGps = false)
                    selectedLocation = PrefsManager.getLocationSelection(context)
                    PlaceResolution.onPlaceChanged(context, old, place, oldLabel, label)
                }
            },
        )
    }
}

/**
 * Scores a delegation against search terms for ranking.
 * 4 = exact name match, 3 = name starts with query, 2 = name contains a term, 0 = no name match.
 */
internal fun delegationSearchScore(delegation: Delegation, terms: List<String>): Int {
    if (terms.isEmpty()) return 0
    val query = terms.joinToString(" ")
    val names = listOf(delegation.nomFr.lowercase(), delegation.nomAr, delegation.nomEn.lowercase())
    return when {
        names.any { it == query } -> 4
        names.any { it.startsWith(query) } -> 3
        names.any { n -> terms.any { n.contains(it) } } -> 2
        else -> 0
    }
}

@Composable
private fun TodayNextPrayerCard(
    delegationId: Int,
    isPhoneSilenced: Boolean,
    doNotDisturbActive: Boolean,
    hasDnd: Boolean,
    autoSilenceActive: Boolean,
    manualSilenceActive: Boolean,
    manualSilenceMode: ManualSilenceMode,
    manualTargetPrayer: Prayer,
    manualSilenceEndsAtMillis: Long,
    wakeSilenceUntilAlarmActive: Boolean,
    autoSilenceEnabled: Boolean,
    configuredMode: AutomaticSilenceMode?,
    setupIssues: List<Int>?,
    onRepairSilence: () -> Unit,
    manualActionLabel: String,
    onChooseManualDuration: () -> Unit,
    silenceActionPending: Boolean,
    onManualSilenceClick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timesKey = remember(delegationId) { PrayerTimesRepository.timesKey(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTimeMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val countdown = remember(timesKey, currentTimeMillis) {
        runCatching { resolveNextPrayerCountdown(context, currentTimeMillis) }.getOrNull()
    }
    val combined = configuredMode == AutomaticSilenceMode.TIME_AND_MOSQUE
    val showAutomaticReason = isPhoneSilenced && autoSilenceActive && !manualSilenceActive
    val mosqueContinuationEnd = if (showAutomaticReason && combined) {
        produceState<Long?>(null, context, currentTimeMillis) {
            value = withContext(ScheduleRefreshCoordinator.scheduling) {
                AdaptiveMosqueMonitor.periods(context).continuationEnd
            }
        }.value
    } else null
    val silenceReason = remember(
        isPhoneSilenced,
        doNotDisturbActive,
        autoSilenceActive,
        manualSilenceActive,
        manualSilenceMode,
        manualTargetPrayer,
        manualSilenceEndsAtMillis,
        wakeSilenceUntilAlarmActive,
        currentTimeMillis,
        configuredMode,
        mosqueContinuationEnd,
    ) {
        resolvePhoneSilenceReason(
            isPhoneSilenced = isPhoneSilenced,
            autoSilenceActive = autoSilenceActive,
            activeAutoSilencePrayer = if (showAutomaticReason && !combined) {
                SilenceScheduler.currentSilenceWindowPrayer(context)
            } else null,
            manualSilenceActive = manualSilenceActive,
            manualSilenceMode = manualSilenceMode,
            manualTargetPrayer = manualTargetPrayer,
            manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
            wakeSilenceUntilAlarmActive = wakeSilenceUntilAlarmActive,
            currentTimeMillis = currentTimeMillis,
            combinedMode = combined,
            mosqueContinuationEnd = mosqueContinuationEnd,
            doNotDisturbActive = doNotDisturbActive,
        )
    }

    LaunchedEffect(countdown?.triggerAtMillis, lifecycleOwner) {
        // While the screen shows; back in front, the countdown is brought up to date before the first wait.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            currentTimeMillis = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                delay(nextCountdownRefreshDelayMillis(countdown?.triggerAtMillis, now))
                currentTimeMillis = System.currentTimeMillis()
            }
        }
    }

    NextPrayerHeroCard(
        countdown = countdown,
        prayerName = countdown?.let { prayerName(context, it.prayer) } ?: stringResource(R.string.prayer_data_unavailable_title),
        currentTimeMillis = currentTimeMillis,
        isPhoneSilenced = isPhoneSilenced,
        hasDnd = hasDnd,
        silenceReason = silenceReason,
        autoSilenceEnabled = autoSilenceEnabled,
        configuredMode = configuredMode,
        setupIssues = setupIssues,
        onRepairSilence = onRepairSilence,
        silenceActionPending = silenceActionPending,
        silenceActive = (manualSilenceActive || autoSilenceActive) && hasDnd,
        manualActionLabel = manualActionLabel,
        onManualSilenceClick = onManualSilenceClick,
        onChooseManualDuration = onChooseManualDuration,
    )
}

@Composable
private fun PrayerSettingsCard(
    delegationId: Int,
    activity: androidx.appcompat.app.AppCompatActivity,
    placeConfirmed: Boolean,
    timetableUncovered: Boolean,
    autoSilenceEnabled: Boolean,
    onChooseLocation: () -> Unit,
    pendingMethodRow: (@Composable () -> Unit)?,
    selectedDate: Long,
    onSelectedDateChange: (Long) -> Unit,
    onConfigChanged: () -> Unit,
    silenceSetupIssues: List<Int>?,
    onOpenSilenceSetup: () -> Unit,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var sharedSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sharedUndoBefore by remember { mutableStateOf<List<Pair<Prayer, PrayerSilenceConfig>>?>(null) }
    var sharedUndoAfter by remember { mutableStateOf<List<Pair<Prayer, PrayerSilenceConfig>>?>(null) }
    val correctedCalendar = rememberTunisianHijriCalendar()
    val selectedHijriYear = correctedCalendar.date(calendarLocalDate(selectedDate)).year
    val lifecycleOwner = LocalLifecycleOwner.current
    val displayedPlace = remember(delegationId) { PrayerTimesRepository.place(context) }
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentCalendar = remember(currentTimeMillis) {
        Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
    }
    val currentDayMillis = calendarDateMillis(java.time.Instant.ofEpochMilli(currentTimeMillis).atZone(displayedPlace.zone).toLocalDate())

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTimeMillis = System.currentTimeMillis()
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lifecycleOwner) {
        // While the screen shows; back in front, the minute is brought up to date before the first wait.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            currentTimeMillis = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                val nextMinuteMillis = ((now / 60_000L) + 1L) * 60_000L
                delay((nextMinuteMillis - now).coerceAtLeast(1L))
                currentTimeMillis = System.currentTimeMillis()
            }
        }
    }

    val selectedCal = remember(selectedDate) {
        Calendar.getInstance().apply { timeInMillis = selectedDate }
    }
    val isToday = remember(selectedDate, currentDayMillis) {
        isSameCalendarDay(selectedDate, currentDayMillis)
    }

    val selectedLocalDate = remember(selectedDate) {
        LocalDate.of(selectedCal.get(Calendar.YEAR), selectedCal.get(Calendar.MONTH) + 1, selectedCal.get(Calendar.DAY_OF_MONTH))
    }
    // The anchors show the phone's clock (D8): the zone is read again at each recomposition (every minute and on
    // resume), so a zone change re-anchors the rows as it does the hero and the night card.
    val phoneZone = java.time.ZoneId.systemDefault()
    // The five adhans of the prayer day shown, as instants (6.6).
    val dailyAnchors = remember(delegationId, selectedLocalDate, refreshTick, phoneZone) {
        runCatching { prayerTimelineAnchors(context, selectedLocalDate, displayedPlace) }.getOrDefault(emptyMap())
    }
    // Dhuhr in the phone's clock, for the Eid rows' cut-off (Tunisia only, A10).
    val dhuhrClock = dailyAnchors[Prayer.DHUHR]?.let { Math.floorMod(it.minutes, 1440) } ?: (13 * 60)

    val canGoBack = remember(delegationId, selectedDate) {
        val prev = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, -1)
        }
        LocalDate.of(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH) + 1, prev.get(Calendar.DAY_OF_MONTH)) in
            PrayerTimesRepository.getDateRange(PrayerTimesRepository.place(context))
    }
    val canGoForward = remember(delegationId, selectedDate) {
        val next = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, 1)
        }
        LocalDate.of(next.get(Calendar.YEAR), next.get(Calendar.MONTH) + 1, next.get(Calendar.DAY_OF_MONTH)) in
            PrayerTimesRepository.getDateRange(PrayerTimesRepository.place(context))
    }

    val prayerNames = mapOf(
        Prayer.FAJR to stringResource(R.string.prayer_fajr),
        Prayer.DHUHR to stringResource(R.string.prayer_dhuhr),
        Prayer.ASR to stringResource(R.string.prayer_asr),
        Prayer.MAGHRIB to stringResource(R.string.prayer_maghrib),
        Prayer.ISHA to stringResource(R.string.prayer_isha),
        Prayer.JOMOAA to stringResource(R.string.prayer_jomoaa),
        Prayer.AID_FITR to stringResource(R.string.prayer_aid_fitr),
        Prayer.AID_ADHA to stringResource(R.string.prayer_aid_adha)
    )

    val isAidFitr = remember(selectedDate, dailyAnchors, currentTimeMillis, correctedCalendar) {
        // Eid stays Tunisian (A10): a place abroad shows no Eid row.
        if (PrayerTimesRepository.place(context) !is com.tunisianprayertimes.place.PrayerPlace.Inm) return@remember false
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val gregDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = gregDate,
            nowHour = currentCalendar.get(Calendar.HOUR_OF_DAY),
            nowMinute = currentCalendar.get(Calendar.MINUTE),
            dhuhrHour = dhuhrClock / 60,
            dhuhrMinute = dhuhrClock % 60,
            isToday = isToday,
        )
    }
    val isAidAdha = remember(selectedDate, dailyAnchors, currentTimeMillis, correctedCalendar) {
        // Eid stays Tunisian (A10): a place abroad shows no Eid row.
        if (PrayerTimesRepository.place(context) !is com.tunisianprayertimes.place.PrayerPlace.Inm) return@remember false
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val gregDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        RamadanOverrideChecker.shouldShowEidAdhaPrayer(
            date = gregDate,
            nowHour = currentCalendar.get(Calendar.HOUR_OF_DAY),
            nowMinute = currentCalendar.get(Calendar.MINUTE),
            dhuhrHour = dhuhrClock / 60,
            dhuhrMinute = dhuhrClock % 60,
            isToday = isToday,
        )
    }

    // Jumu'a and Eid times are edited from their rows: shown in the phone's clock, stored in the place's (6.6).
    var silenceTimesRevision by remember { mutableIntStateOf(0) }
    // Jumu'a's chooser (6.9, A18): the default, an hour before Asr (about one Tunisian user in ten), or a fixed time.
    var jumuaChooserOpen by rememberSaveable { mutableStateOf(false) }
    var editorPrayer by rememberSaveable(delegationId, selectedDate) { mutableStateOf<Prayer?>(null) }
    var moreTimetableSettings by rememberSaveable { mutableStateOf(false) }

    // Across midnight the hero can still be counting down to yesterday's Isha, or tomorrow's Fajr.
    val nextPrayer = remember(delegationId, refreshTick, currentTimeMillis, dailyAnchors.isNotEmpty()) {
        if (dailyAnchors.isEmpty()) null else resolveNextPrayerCountdown(context, currentTimeMillis)
    }?.prayerOn(selectedLocalDate)

    val dailyPrayers = remember { listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA) }
    // The editors' anchors (6.9, D8): each adhan as an instant; Jumu'a on the Friday on or after the date shown.
    val sectionAnchors: Map<Prayer, PrayerTimelineAnchor> = remember(
        delegationId, selectedLocalDate, refreshTick, silenceTimesRevision, isAidFitr, isAidAdha, selectedHijriYear, correctedCalendar, phoneZone,
    ) {
        if (dailyAnchors.isEmpty()) return@remember emptyMap()
        val place = PrayerTimesRepository.place(context)
        buildMap {
            putAll(dailyAnchors)
            jumuaTimelineAnchor(context, selectedLocalDate, place)?.let { put(Prayer.JOMOAA, it) }
            if (isAidFitr) {
                eidTimelineAnchor(context, Prayer.AID_FITR, RamadanOverrideChecker.getEidFitrDate(selectedHijriYear), place)?.let { put(Prayer.AID_FITR, it) }
            }
            if (isAidAdha) {
                eidTimelineAnchor(context, Prayer.AID_ADHA, RamadanOverrideChecker.getEidAdhaDate(selectedHijriYear), place)?.let { put(Prayer.AID_ADHA, it) }
            }
        }
    }
    val sunrise = remember(displayedPlace, selectedLocalDate, dailyAnchors, refreshTick) {
        if (dailyAnchors.isEmpty()) null else PrayerTimesRepository.prayerDay(context, selectedLocalDate, displayedPlace)
            ?.get(com.tunisianprayertimes.engine.TimeEvent.SUNRISE)
    }
    val timetable = remember(selectedLocalDate, sectionAnchors, sunrise, isAidFitr, isAidAdha) {
        prayerTimetableOrder(selectedLocalDate, sectionAnchors, sunrise?.at, isAidFitr, isAidAdha)
    }
    LaunchedEffect(sharedUndoAfter) {
        if (sharedUndoAfter != null) {
            delay(6_000L)
            sharedUndoBefore = null
            sharedUndoAfter = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PRAYER_SETTINGS)
                .clip(RoundedCornerShape(PrayerSilenceDimens.ContainerCorner))
                .background(PrayerSilencePalette.Surface)
                .border(
                    width = 1.dp,
                    color = PrayerSilencePalette.SoftBorder,
                    shape = RoundedCornerShape(PrayerSilenceDimens.ContainerCorner),
                ),
        ) {
            Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                DateNavigationRow(
                    delegationId = delegationId,
                    selectedDate = selectedDate,
                    isToday = isToday,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    onPrevious = {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            add(Calendar.DAY_OF_MONTH, -1)
                        }
                        onSelectedDateChange(cal.timeInMillis)
                    },
                    onNext = {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            add(Calendar.DAY_OF_MONTH, 1)
                        }
                        onSelectedDateChange(cal.timeInMillis)
                    },
                    onDateSelected = { millis -> onSelectedDateChange(millis) }
                )
            }
            // 6.11: the official zone the place maps to, and the place's UTC offset when the phone keeps another zone.
            val listNotes = remember(delegationId, selectedLocalDate, phoneZone, refreshTick) {
                runCatching { timesListNotes(context, selectedLocalDate, phoneZone) }.getOrNull()
            }
            val methodName = remember(delegationId, dailyAnchors.isNotEmpty(), refreshTick) {
                if (dailyAnchors.isEmpty()) return@remember null
                if (displayedPlace is PrayerPlace.Inm) context.getString(R.string.today_inm_source)
                else PrayerTimesRepository.choice(context, displayedPlace)?.let { com.tunisianprayertimes.MethodPresentation.name(it.methodId) }
            }
            pendingMethodRow?.invoke()
            listNotes?.placeOffset?.let { offset ->
                Text(
                    text = stringResource(R.string.today_phone_clock, offset),
                    fontSize = 12.sp,
                    color = PrayerSilencePalette.SecondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("times_place_offset"),
                )
            }

            if (dailyAnchors.isNotEmpty()) {
                val mosqueMode = MosqueSilenceSettings.combined(context)
                timetable.forEachIndexed { index, prayer ->
                    if (index > 0) HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                    if (prayer == null) {
                        CompactSunriseRow(sunrise, selectedLocalDate, phoneZone)
                    } else key(prayer) {
                        val anchor = sectionAnchors[prayer]
                        val summary = if (autoSilenceEnabled && anchor != null) {
                            val config = remember(anchor, refreshTick) { PrefsManager.getEffectiveConfig(context, prayer) }
                            val enabled = remember(prayer, refreshTick) { PrefsManager.isPrayerSilenceEnabled(context, prayer) }
                            timetableSilenceSummary(anchor, config, enabled, silenceSetupIssues?.isEmpty() == true, mosqueMode)
                        } else null
                        CompactPrayerTimeRow(
                            prayer = prayer,
                            name = prayerNames.getValue(prayer),
                            anchor = anchor,
                            isNext = nextPrayer == prayer,
                            onOpenEditor = anchor?.let { { editorPrayer = prayer } },
                            silenceSummary = summary,
                            modifier = if (isToday && timetableUncovered && editorPrayer == null && anchor != null) {
                                firstTimetableRowModifier(activity, displayedPlace, placeConfirmed)
                            } else Modifier,
                        )
                    }
                }
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                Box {
                    PrayerSettingsLink(
                        title = stringResource(R.string.timetable_more_settings),
                        onClick = { moreTimetableSettings = true },
                        modifier = Modifier.testTag("timetable_more_settings"),
                    )
                    DropdownMenu(expanded = moreTimetableSettings, onDismissRequest = { moreTimetableSettings = false }) {
                        // Weekly and upcoming holiday rules remain editable outside their dated timetable.
                        val otherPrayers = listOf(Prayer.JOMOAA, Prayer.DHUHR, Prayer.AID_FITR, Prayer.AID_ADHA)
                        otherPrayers.filter { it !in timetable && sectionAnchors[it] != null }.forEach { prayer ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timetable_edit_prayer, prayerNames.getValue(prayer))) },
                                onClick = { moreTimetableSettings = false; editorPrayer = prayer },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.prayer_silence_shared_title)) },
                            onClick = { moreTimetableSettings = false; sharedSheetOpen = true },
                        )
                    }
                }
                if (sharedUndoBefore != null && sharedUndoAfter != null) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GreenPrimary.copy(alpha = 0.06f))
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.prayer_silence_shared_applied),
                            modifier = Modifier.align(Alignment.CenterVertically),
                            fontSize = 12.sp,
                            color = TextMuted,
                        )
                        TextButton(
                            onClick = {
                                if (sharedUndoAfter?.all { (prayer, applied) -> PrefsManager.getEffectiveConfig(context, prayer) == applied } == true) {
                                    sharedUndoBefore?.forEach { (prayer, config) ->
                                        persistPrayerSilenceConfig(context, prayer, config, sectionAnchors[prayer])
                                    }
                                }
                                sharedUndoBefore = null
                                sharedUndoAfter = null
                                refreshTick++
                                onConfigChanged()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.prayer_silence_undo), color = GreenPrimaryDark)
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.padding(vertical = 12.dp)) {
                    PrayerDataUnavailable(
                        isToday = isToday,
                        onReturnToToday = { onSelectedDateChange(calendarDateMillis(LocalDate.now(displayedPlace.zone))) },
                        onChooseLocation = onChooseLocation,
                        onRetry = { refreshTick++ },
                    )
                }
            }
            if (dailyAnchors.isNotEmpty() && (methodName != null || listNotes?.zoneName != null)) {
                TimetableSourceDetails(
                    methodName = methodName,
                    officialSource = displayedPlace is PrayerPlace.Inm,
                    zoneName = listNotes?.zoneName,
                )
            }
        }
    }

    if (placeConfirmed && isToday && dailyAnchors.isNotEmpty()) {
        FirstRunFeatureSuggestion()
    }
    editorPrayer?.let { prayer ->
        val anchor = sectionAnchors[prayer]
        if (anchor != null) {
            val nextAnchor = remember(anchor) { nextTimelineAnchor(context, anchor) }
            val scale = remember(sectionAnchors, refreshTick) {
                val windows = sectionAnchors.values.map { entry ->
                    entry to resolvePrayerTimelineWindow(entry, PrefsManager.getEffectiveConfig(context, entry.prayer))
                }.filter { (_, window) -> window.endMinutes >= window.startMinutes }
                sharedPrayerTimelineScale(windows)
            }
            TimetablePrayerEditorSheet(
                onDismiss = { editorPrayer = null },
                otherDate = anchor.prayerDay.takeIf { it != selectedLocalDate },
            ) {
                val setupIssue = firstPrayerSilenceIssue(silenceSetupIssues)
                if (setupIssue != null || !autoSilenceEnabled) {
                    PrayerSettingsLink(
                        title = stringResource(if (setupIssue != null) R.string.hero_finish_silence_setup else R.string.mosque_enable_auto),
                        summary = stringResource(setupIssue ?: R.string.timetable_rules_saved_while_off),
                        onClick = onOpenSilenceSetup,
                    )
                }
                PrayerRow(
                    prayer = prayer,
                    prayerName = prayerNames.getValue(prayer),
                    anchor = anchor,
                    nextAnchor = nextAnchor,
                    timelineScale = scale,
                    isNextPrayer = nextPrayer == prayer && anchor.prayerDay == selectedLocalDate,
                    onConfigChanged = { refreshTick++; onConfigChanged() },
                    onPrayerTimeClick = when (prayer) {
                        Prayer.JOMOAA -> { { jumuaChooserOpen = true } }
                        Prayer.AID_FITR, Prayer.AID_ADHA -> { {
                            val shown = Math.floorMod(anchor.minutes, 1440)
                            val fitr = prayer == Prayer.AID_FITR
                            val picker = MaterialTimePicker.Builder()
                                .setTimeFormat(TimeFormat.CLOCK_24H)
                                .setHour(shown / 60).setMinute(shown % 60)
                                .setTitleText(context.getString(if (fitr) R.string.pick_aid_fitr_time else R.string.pick_aid_adha_time))
                                .build()
                            picker.addOnPositiveButtonClickListener {
                                val clock = pickedPlaceClock(anchor, picker.hour, picker.minute)
                                if (fitr) PrefsManager.setAidFitrTime(context, clock.hour, clock.minute)
                                else PrefsManager.setAidAdhaTime(context, clock.hour, clock.minute)
                                silenceTimesRevision++
                                refreshTick++
                                onConfigChanged()
                            }
                            picker.show(activity.supportFragmentManager, "aid_time_picker")
                        } }
                        else -> null
                    },
                )
            }
        }
    }
    if (jumuaChooserOpen) {
        // Per country abroad (6.9, A18), today's keys in Tunisia; times shown in the phone's clock, as the rows.
        val place = PrayerTimesRepository.place(context)
        val jumua = sectionAnchors[Prayer.JOMOAA]
        val friday = jumua?.prayerDay ?: selectedLocalDate
        val fixed = PrefsManager.getJumuaTime(context, place)
        fun shown(at: java.time.Instant?) = at?.let { formatTimeOfDay(it.toEpochMilli()) }
        fun saved() {
            silenceTimesRevision++
            refreshTick++
            onConfigChanged()
        }
        JumuaTimeDialog(
            current = when {
                fixed != null -> JumuaMode.FIXED
                PrefsManager.getJumuaMinutesBeforeAsr(context, place) != null -> JumuaMode.BEFORE_ASR
                else -> JumuaMode.DEFAULT
            },
            defaultAt = shown(PrayerTimesRepository.defaultJumuaTime(context, place, friday)),
            defaultHint = if (place is PrayerPlace.Inm) stringResource(R.string.jomoaa_time_default_tunisia_hint) else null,
            beforeAsrAt = shown(PrayerTimesRepository.jumuaBeforeAsr(context, friday, place, PrayerTimesRepository.JUMUA_MINUTES_BEFORE_ASR)),
            fixedAt = if (fixed != null) shown(jumua?.adhan) else null,
            onPick = { mode ->
                jumuaChooserOpen = false
                when (mode) {
                    JumuaMode.DEFAULT -> {
                        PrefsManager.setJumuaTime(context, place, null)
                        saved()
                    }
                    JumuaMode.BEFORE_ASR -> {
                        PrefsManager.setJumuaMinutesBeforeAsr(context, place, PrayerTimesRepository.JUMUA_MINUTES_BEFORE_ASR)
                        saved()
                    }
                    JumuaMode.FIXED -> {
                        val start = jumua?.let { Math.floorMod(it.minutes, 1440) } ?: (13 * 60)
                        val picker = MaterialTimePicker.Builder()
                            .setTimeFormat(TimeFormat.CLOCK_24H)
                            .setHour(start / 60)
                            .setMinute(start % 60)
                            .setTitleText(context.getString(R.string.pick_jomoaa_time))
                            .build()
                        picker.addOnPositiveButtonClickListener {
                            PrefsManager.setJumuaTime(context, place, pickedPlaceClock(jumua, picker.hour, picker.minute))
                            saved()
                        }
                        picker.show(activity.supportFragmentManager, "jomoaa_time_picker")
                    }
                }
            },
            onDismiss = { jumuaChooserOpen = false },
        )
    }
    if (sharedSheetOpen) {
        val sharedAnchors = dailyPrayers.mapNotNull { prayer -> sectionAnchors[prayer]?.let { prayer to it } }.toMap()
        val sharedInitial = remember(sharedSheetOpen, refreshTick, selectedDate, delegationId) {
            val entries = sharedAnchors.map { (prayer, anchor) ->
                anchor to PrefsManager.getEffectiveConfig(context, prayer)
            }
            fun uniformStart(): Pair<EndpointRuleMode, Int>? {
                if (entries.isEmpty()) return null
                if (entries.all { (_, config) -> config.startRuleMode() == EndpointRuleMode.ADHAN }) {
                    val offsets = entries.map { (anchor, config) ->
                        anchor.offsetAt(resolvePrayerTimelineWindow(anchor, config).startMinutes)
                    }
                    return offsets.distinct().singleOrNull()?.let { EndpointRuleMode.ADHAN to it }
                }
                if (entries.all { (_, config) -> config.startRuleMode() == EndpointRuleMode.FIXED_TIME }) {
                    // Clock times as the phone shows them (D8).
                    val clocks = entries.map { (anchor, config) -> Math.floorMod(resolvePrayerTimelineWindow(anchor, config).startMinutes, 1440) }
                    return clocks.distinct().singleOrNull()?.let { EndpointRuleMode.FIXED_TIME to it }
                }
                return null
            }
            fun uniformEnd(): Pair<EndpointRuleMode, Int>? {
                if (entries.isEmpty()) return null
                if (entries.all { (_, config) -> config.endRuleMode() == EndpointRuleMode.ADHAN }) {
                    val offsets = entries.map { (anchor, config) ->
                        anchor.offsetAt(resolvePrayerTimelineWindow(anchor, config).endMinutes)
                    }
                    return offsets.distinct().singleOrNull()?.let { EndpointRuleMode.ADHAN to it }
                }
                if (entries.all { (_, config) -> config.endRuleMode() == EndpointRuleMode.FIXED_TIME }) {
                    val clocks = entries.map { (anchor, config) -> Math.floorMod(resolvePrayerTimelineWindow(anchor, config).endMinutes, 1440) }
                    return clocks.distinct().singleOrNull()?.let { EndpointRuleMode.FIXED_TIME to it }
                }
                return null
            }
            val start = uniformStart() ?: (EndpointRuleMode.ADHAN to 0)
            val end = uniformEnd() ?: (EndpointRuleMode.ADHAN to 30)
            val fallbackStartClock = entries.firstOrNull()?.let { (anchor, config) ->
                Math.floorMod(resolvePrayerTimelineWindow(anchor, config).startMinutes, 1440)
            } ?: 0
            val fallbackEndClock = entries.firstOrNull()?.let { (anchor, config) ->
                Math.floorMod(resolvePrayerTimelineWindow(anchor, config).endMinutes, 1440)
            } ?: 0
            SharedSilenceRuleDraft(
                startMode = start.first,
                startOffsetMinutes = if (start.first == EndpointRuleMode.ADHAN) start.second else 0,
                startClockMinutes = if (start.first == EndpointRuleMode.FIXED_TIME) start.second else fallbackStartClock,
                endMode = end.first,
                endOffsetMinutes = if (end.first == EndpointRuleMode.ADHAN) end.second else 30,
                endClockMinutes = if (end.first == EndpointRuleMode.FIXED_TIME) end.second else fallbackEndClock,
            )
        }
        SharedSilenceSettingsSheet(
            prayerNames = dailyPrayers.map { it to (prayerNames[it] ?: it.name) },
            anchors = sharedAnchors,
            initialDraft = sharedInitial,
            onDismiss = { sharedSheetOpen = false },
            onApply = { prayers, draft ->
                val previous = prayers.map { it to PrefsManager.getEffectiveConfig(context, it) }
                val intended = prayers.mapNotNull { prayer ->
                    val anchor = sharedAnchors[prayer] ?: return@mapNotNull null
                    prayer to draft.applyTo(PrefsManager.getEffectiveConfig(context, prayer), anchor)
                }.toMap()
                intended.forEach { (prayer, config) ->
                    persistPrayerSilenceConfig(context, prayer, config, sharedAnchors[prayer])
                }
                val persisted = intended.size == prayers.size && intended.all { (prayer, config) ->
                    val anchor = sharedAnchors.getValue(prayer)
                    resolvePrayerTimelineWindow(anchor, PrefsManager.getEffectiveConfig(context, prayer)) == resolvePrayerTimelineWindow(anchor, config)
                }
                if (!persisted) {
                    previous.forEach { (prayer, config) ->
                        persistPrayerSilenceConfig(context, prayer, config, sharedAnchors[prayer])
                    }
                    sharedUndoBefore = null
                    sharedUndoAfter = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.prayer_silence_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    sharedUndoBefore = previous
                    sharedUndoAfter = intended.keys.map { prayer -> prayer to PrefsManager.getEffectiveConfig(context, prayer) }
                }
                sharedSheetOpen = false
                refreshTick++
                onConfigChanged()
            },
        )
    }
}

@Composable
private fun SunriseAndNightTimesCard(
    delegationId: Int,
    selectedDate: Long,
    wakeAlarms: List<PrayerWakeConfig>? = null,
    onEditWakeAlarm: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val placeZone = remember(delegationId) { PrayerTimesRepository.place(context).zone }
    val currentDayMillis = calendarDateMillis(java.time.Instant.ofEpochMilli(currentTimeMillis).atZone(placeZone).toLocalDate())
    val selectedDayMillis = remember(selectedDate) { startOfDayMillis(selectedDate) }
    val selectedCalendar = remember(selectedDayMillis) {
        Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
    }
    val previousCalendar = remember(selectedDayMillis) {
        (selectedCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    }
    val nextCalendar = remember(selectedDayMillis) {
        (selectedCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    }

    // The prayer days around the date shown, as instants (6.6), shown in the phone's clock (D8).
    val selectedLocal = remember(selectedDayMillis) {
        LocalDate.of(selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH) + 1, selectedCalendar.get(Calendar.DAY_OF_MONTH))
    }
    val selectedTimes = remember(delegationId, selectedDayMillis) {
        runCatching { PrayerTimesRepository.prayerDay(context, selectedLocal) }.getOrNull()
    }
    val previousTimes = remember(delegationId, selectedDayMillis) {
        runCatching { PrayerTimesRepository.prayerDay(context, selectedLocal.minusDays(1)) }.getOrNull()
    }
    val nextTimes = remember(delegationId, selectedDayMillis) {
        runCatching { PrayerTimesRepository.prayerDay(context, selectedLocal.plusDays(1)) }.getOrNull()
    }
    // The sun's altitude at the saved place: a delegation's INM point, or a place abroad's own point.
    val solarLocation = remember(context, delegationId) {
        when (val place = PrayerTimesRepository.place(context)) {
            is com.tunisianprayertimes.place.PrayerPlace.Inm -> PrayerDataLoader.prayerTimes(context).location(delegationId)
            is com.tunisianprayertimes.place.PrayerPlace.Coordinates ->
                com.tunisianprayertimes.InmLocation(place.point.latitude, place.point.longitude, (place.elevationM ?: 0).toDouble())
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                currentTimeMillis = now
                // A tenth of a degree can change within a minute. Stop refreshing in the background.
                delay(5_000L - now % 5_000L)
            }
        }
    }

    val selectedTimesForDay = selectedTimes ?: return
    fun atMillis(day: com.tunisianprayertimes.engine.PrayerDay?, event: com.tunisianprayertimes.engine.TimeEvent): Long? =
        day?.get(event)?.at?.toEpochMilli()
    val selectedFajrMillis = atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.FAJR) ?: return
    val isToday = isSameCalendarDay(selectedDayMillis, currentDayMillis)
    val usePreviousNight = isToday &&
        currentTimeMillis < selectedFajrMillis && previousTimes != null
    val nightStartTimes = if (usePreviousNight) requireNotNull(previousTimes) else selectedTimesForDay
    val nightEndTimes = if (usePreviousNight) selectedTimesForDay else (nextTimes ?: return)

    val nightStartMillis = atMillis(nightStartTimes, com.tunisianprayertimes.engine.TimeEvent.MAGHRIB) ?: return
    val nightEndMillis = atMillis(nightEndTimes, com.tunisianprayertimes.engine.TimeEvent.FAJR) ?: return
    if (nightEndMillis <= nightStartMillis) return

    val thirdDurationMillis = (nightEndMillis - nightStartMillis) / 3L
    val firstEndMillis = nightStartMillis + thirdDurationMillis
    val secondEndMillis = nightStartMillis + thirdDurationMillis * 2L
    // A time's minute counted from the selected day's midnight in the phone's clock: an Isha after midnight
    // (Paris in June) sorts after Maghrib in the details, at the same place on the dial.
    fun minuteOfSelectedDay(millis: Long): Int {
        val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return minuteOfDay(millis) + 1440 * java.time.temporal.ChronoUnit.DAYS.between(selectedLocal, date).toInt()
    }
    // The rings shown are those of the 24 hours the dial draws: the day shown and the night after it (its Fajr to
    // the next), or, before today's Fajr, the night now running and the day after it. So the night arc holds
    // tonight's rings, not this morning's, and a ring that already rang today keeps its place.
    val dayMillis = 24L * 60L * 60L * 1000L
    val windowStartMillis = if (usePreviousNight) nightStartMillis else nightEndMillis - dayMillis
    val windowEndMillis = windowStartMillis + dayMillis
    val timelineAlarms = remember(wakeAlarms, delegationId, windowStartMillis) {
        val days = (-1..1).map { offset -> (selectedCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) } }
        wakeAlarms.orEmpty()
            .flatMap { wakeAlarm ->
                days.flatMap { day -> scheduledWakeAlarmTriggersForDay(context, delegationId, wakeAlarm, day) }
                    .filter { trigger -> trigger.triggerAtMillis >= windowStartMillis && trigger.triggerAtMillis < windowEndMillis }
                    .distinctBy { trigger -> listOf(trigger.alarmId, trigger.subAlarmId, trigger.triggerAtMillis) }
                    .map { trigger ->
                        NightTimelineAlarm(
                            id = listOfNotNull(trigger.alarmId, trigger.subAlarmId, trigger.triggerAtMillis.toString()).joinToString(":"),
                            wakeAlarmId = trigger.alarmId,
                            title = wakeAlarm.title.ifBlank {
                                when (wakeAlarm.mainAlarm.mode) {
                                    WakeMainAlarmMode.FROM_NOW -> context.getString(R.string.wake_editor_mode_from_now)
                                    WakeMainAlarmMode.FIXED_TIME -> context.getString(R.string.wake_editor_mode_fixed)
                                    WakeMainAlarmMode.PRAYER_RELATIVE -> prayerName(context, trigger.effectivePrayer)
                                }
                            },
                            time = formatTimeOfDay(trigger.triggerAtMillis),
                            minuteOfDay = minuteOfSelectedDay(trigger.triggerAtMillis),
                            isSubAlarm = trigger.isSubAlarm,
                        )
                    }
            }
            .sortedBy { alarm -> alarm.minuteOfDay }
    }
    fun timelinePrayer(prayer: Prayer, millis: Long): NightTimelinePrayer = NightTimelinePrayer(
        prayer = prayer,
        title = prayerName(context, prayer),
        time = formatTimeOfDay(millis),
        minuteOfDay = minuteOfSelectedDay(millis),
    )
    val isFriday = selectedCalendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
    val dhuhrMillis = atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.DHUHR) ?: return
    // Jumu'a at its resolved time on a Friday (A18).
    val jumuaMillis = if (isFriday) PrayerTimesRepository.jumuaTime(context, selectedLocal)?.toEpochMilli() else null
    // The day overview shows the selected day's own prayers.
    val timelinePrayers = listOfNotNull(
        timelinePrayer(Prayer.FAJR, selectedFajrMillis),
        timelinePrayer(if (jumuaMillis != null) Prayer.JOMOAA else Prayer.DHUHR, jumuaMillis ?: dhuhrMillis),
        atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.ASR)?.let { timelinePrayer(Prayer.ASR, it) },
        atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.MAGHRIB)?.let { timelinePrayer(Prayer.MAGHRIB, it) },
        atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.ISHA)?.let { timelinePrayer(Prayer.ISHA, it) },
    )
    val sunriseMillis = atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.SUNRISE) ?: return
    fun third(startMillis: Long, endMillis: Long) = NightThirdSegment(
        startTime = formatTimeOfDay(startMillis),
        endTime = formatTimeOfDay(endMillis),
        startMinuteOfDay = minuteOfSelectedDay(startMillis),
        endMinuteOfDay = minuteOfSelectedDay(endMillis),
    )
    val data = NightTimesCardData(
        selectedDateLabel = formatWakeAlarmDate(selectedDayMillis),
        sunAltitudeDeg = if (isToday) solarLocation?.let { solarAltitudeDegAt(it, currentTimeMillis) } else null,
        sunriseTime = formatTimeOfDay(sunriseMillis),
        sunriseMinuteOfDay = minuteOfSelectedDay(sunriseMillis),
        currentMinuteOfDay = minuteOfDay(currentTimeMillis),
        showCurrentTime = isToday,
        thirds = listOf(
            third(nightStartMillis, firstEndMillis),
            third(firstEndMillis, secondEndMillis),
            third(secondEndMillis, nightEndMillis),
        ),
        timelinePrayers = timelinePrayers,
        timelineAlarms = timelineAlarms,
    )
    PrayerDayOverviewCard(data = data, onEditWakeAlarm = onEditWakeAlarm)
}

@Composable
private fun WakeAlarmCard(
    wakeAlarms: List<PrayerWakeConfig>?,
    exactAlarmsAllowed: Boolean,
    onPlaceRequired: () -> Unit,
    delegationId: Int,
    activity: androidx.appcompat.app.AppCompatActivity,
    awakeCheckRunning: Boolean,
    onConfirmAwake: () -> Unit,
    onConfigChanged: (PrayerWakeConfig?) -> Unit,
    onPresetSelected: (WakeQuickPreset) -> Unit,
    onEditAlarm: (PrayerWakeConfig) -> Unit,
    onAddFajrAlarm: (PrayerWakeConfig) -> Unit,
) {
    val context = LocalContext.current
    val wakeRepository = remember(context) { PrayerWakeRepository(context) }
    var fajrSuggestionDismissed by remember {
        mutableStateOf(PrefsManager.isFajrAlarmSuggestionDismissed(context))
    }
    var fajrSetupOpen by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // Moves on the minute, when rings are due, and whenever the state below changes, so countdowns count from
    // the moment shown.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var alarmPendingDeletion by remember { mutableStateOf<PrayerWakeConfig?>(null) }
    // Every stored alarm, a finished one-off included: its occurrence can still be in progress.
    val storedWakeAlarms = wakeAlarms.orEmpty()
    val canUsePrayerTimes = PrefsManager.canUsePlaceForAutomation(context)
    // An occurrence is in progress from its earliest ring to its latest, so the windows are worked out once a day.
    val todayStartMillis = startOfDayMillis(nowMillis)
    val windowsByAlarm = remember(storedWakeAlarms, delegationId, canUsePrayerTimes, todayStartMillis) {
        storedWakeAlarms.associate { alarm ->
            alarm.id to WakeAlarmScheduler.occurrenceWindows(context, alarm)
        }
    }
    var nextTriggers by remember(storedWakeAlarms, delegationId, canUsePrayerTimes) {
        mutableStateOf(nextWakeAlarmTriggersById(context, delegationId, storedWakeAlarms))
    }
    var runningOccurrences by remember(storedWakeAlarms, windowsByAlarm) {
        mutableStateOf(runningWakeOccurrences(context, storedWakeAlarms, windowsByAlarm))
    }
    // Checked every second: a ring time passing, an alert stopped, or an awake check scheduled, started or
    // confirmed moves the hero and the rows on together. The awake check's start and end re-check at once.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(storedWakeAlarms, delegationId, windowsByAlarm, awakeCheckRunning, lifecycleOwner) {
        // While the screen shows: back in front, the first pass runs at once.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                val running = runningWakeOccurrences(context, storedWakeAlarms, windowsByAlarm, now)
                val ringPassed = nextTriggers.values.any { trigger -> trigger != null && trigger.triggerAtMillis <= now }
                if (ringPassed) {
                    nextTriggers = nextWakeAlarmTriggersById(context, delegationId, storedWakeAlarms)
                }
                if (ringPassed || running != runningOccurrences || now / 60_000L != nowMillis / 60_000L) {
                    nowMillis = now
                }
                runningOccurrences = running
                delay(1_000L)
            }
        }
    }
    val displayState = wakeAlarmDisplayState(wakeAlarms, nowMillis, inProgressAlarmIds = runningOccurrences.keys)
    val loadedWakeAlarms = displayState.visibleWakeAlarms
    // The hero and every row read the same state, so they cannot tell different stories.
    val nowByAlarm = loadedWakeAlarms.associate { alarm ->
        val placeReady = canUsePrayerTimes || alarm.mainAlarm.mode != WakeMainAlarmMode.PRAYER_RELATIVE
        val next = nextTriggers[alarm.id].takeIf { placeReady && (exactAlarmsAllowed || !alarm.enabled) }
        alarm.id to wakeAlarmNow(next, runningOccurrences[alarm.id])
    }
    // The hero shows what happens next across the alarms: a ring going or a check asking first, then whatever is
    // late or nearest, whether it belongs to an occurrence in progress or to the next one.
    val heroAlarm = loadedWakeAlarms
        .filter { alarm -> alarm.enabled }
        .mapNotNull { alarm -> nowByAlarm[alarm.id]?.let { alarmNow -> alarm to alarmNow } }
        .minByOrNull { (_, alarmNow) -> alarmNow.focusAtMillis(nowMillis) }
    if (fajrSetupOpen) {
        FajrAlarmSetupSheet(
            delegationId = delegationId,
            nowMillis = nowMillis,
            onAdd = onAddFajrAlarm,
            onDismiss = { fajrSetupOpen = false },
        )
    }

    alarmPendingDeletion?.let { alarm ->
        WakeAlarmDeleteDialog(
            alarmName = wakeAlarmDisplayName(alarm),
            onDismiss = { alarmPendingDeletion = null },
            onConfirm = {
                val alarmId = alarm.id
                alarmPendingDeletion = null
                WakeAlarmScheduler.removeSilenceUntilAlarm(context, alarmId)
                coroutineScope.launch {
                    wakeRepository.deleteWakeAlarm(alarmId)
                    onConfigChanged(null)
                }
            },
        )
    }

    // Each item above the list carries its own 12dp gap below it, so an item that animates away
    // takes its gap with it instead of leaving a spacedBy gap that snaps shut after the exit.
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        val pendingAlarms = loadedWakeAlarms.filter { it.hasFutureWakeTriggers(nowMillis) }
        if (!canUsePrayerTimes && pendingAlarms.any { it.mainAlarm.mode == WakeMainAlarmMode.PRAYER_RELATIVE }) {
            OutlinedButton(onClick = onPlaceRequired, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(stringResource(R.string.wake_access_confirm_place))
            }
        }
        val eligibleAlarms = pendingAlarms.filter { canUsePrayerTimes || it.mainAlarm.mode != WakeMainAlarmMode.PRAYER_RELATIVE }
        if (eligibleAlarms.isNotEmpty()) {
            WakeAlarmAccessNotice(activity, saved = true, modifier = Modifier.padding(bottom = 12.dp),
                awakeCheckEnabled = eligibleAlarms.any { it.playback.awakeCheckEnabled })
        }
        if (heroAlarm != null) {
            val (alarm, alarmNow) = heroAlarm
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                WakeNextAlarmPanel(
                    wakeConfig = alarm,
                    alarmNow = alarmNow,
                    nowMillis = nowMillis,
                )
            }
        } else if (loadedWakeAlarms.isNotEmpty() && loadedWakeAlarms.none { alarm -> alarm.enabled }) {
            // The hero stays when every alarm is off, and says so rather than showing an alarm that won't ring.
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                WakeNoEnabledAlarmPanel()
            }
        }

        AnimatedVisibility(
            visible = awakeCheckRunning,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                AwakeCheckBanner(onConfirm = onConfirmAwake)
            }
        }

        // Waits for the stored alarms, so it never flashes before a Fajr alarm loads.
        val offerFajrAlarm = !fajrSuggestionDismissed && wakeAlarms?.hasFajrWakeAlarm() == false
        // A new user meets the offer inside the start card; anyone with alarms gets the small row.
        AnimatedVisibility(
            visible = offerFajrAlarm && !displayState.showEmptyState,
            enter = EnterTransition.None,
            // Fade first, then close the space in a fixed short time from the bottom, so the card is
            // gone before its rounded edge is clipped and there is no long spring tail.
            exit = fadeOut(tween(durationMillis = 120)) + shrinkVertically(
                animationSpec = tween(durationMillis = 220, delayMillis = 60, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            FajrAlarmSuggestionRow(
                modifier = Modifier.padding(bottom = 12.dp),
                onOpen = { fajrSetupOpen = true },
                onClose = {
                    PrefsManager.setFajrAlarmSuggestionDismissed(context)
                    fajrSuggestionDismissed = true
                },
            )
        }

        if (displayState.showEmptyState) {
            WakeAlarmEmptyState(
                onPresetSelected = onPresetSelected,
                onFajrSetup = if (offerFajrAlarm) {
                    { fajrSetupOpen = true }
                } else {
                    null
                },
            )
        }

        if (loadedWakeAlarms.isNotEmpty()) {
            WakeAlarmListPanel(
                wakeAlarms = loadedWakeAlarms,
                nowByAlarm = nowByAlarm,
                nowMillis = nowMillis,
                onEditAlarm = onEditAlarm,
                onEnabledChange = { wakeAlarm, enabled ->
                    val updatedAlarm = wakeAlarm.copy(enabled = enabled)
                    if (enabled && wakeAlarm.mainAlarm.mode == WakeMainAlarmMode.PRAYER_RELATIVE && !PrefsManager.hasConfirmedPlace(context)) {
                        onPlaceRequired()
                    } else {
                        coroutineScope.launch {
                            wakeRepository.saveWakeConfig(updatedAlarm)
                            AnalyticsTracker.wakeAlarmSaved(context, updatedAlarm)
                            onConfigChanged(updatedAlarm)
                        }
                    }
                },
                onDeleteRequest = { alarm -> alarmPendingDeletion = alarm },
                onSkipNext = { alarm ->
                    coroutineScope.launch {
                        WakeAlarmScheduler.toggleSkipNextWakeOccurrence(context, alarm.id)
                        // A fasting reminder on screen may count on this ring to wake the reader.
                        FastingReminder.refreshLater(context)
                    }
                },
                onStop = { alarm ->
                    coroutineScope.launch {
                        WakeAlarmScheduler.stopWakeAlarm(context, alarm.id)
                        onConfigChanged(null)
                    }
                },
            )
            // Room for the floating + at the end of the list, so the last alarm's buttons can scroll clear of it.
            Spacer(
                modifier = Modifier.height(
                    WakeAlarmAddButtonBottomPadding - MainContentBottomPadding + WakeAlarmAddButtonSize,
                ),
            )
        }
    }
}

@Composable
private fun WakeAlarmFloatingAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)

    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                shadowElevation = WakeAlarmAddButtonShadowElevation.toPx()
                this.shape = shape
                clip = false
            }
            .testTag(TestTags.WAKE_ALARM_ADD_BUTTON)
            .size(WakeAlarmAddButtonSize),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = GreenPrimary,
            disabledContainerColor = GreenPrimary,
            disabledContentColor = Color.White,
        ),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.wake_quick_add_title),
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeQuickAddSheet(
    onDismiss: () -> Unit,
    onPresetSelected: (WakeQuickPreset) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag(TestTags.WAKE_QUICK_ADD_SHEET)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_quick_add_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
            WakeQuickPreset.values().forEach { preset ->
                WakeQuickPresetRow(
                    preset = preset,
                    onClick = { onPresetSelected(preset) },
                    modifier = Modifier.testTag(preset.testTag()),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WakeQuickPresetRow(
    preset: WakeQuickPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recommended = preset == WakeQuickPreset.PRAYER_RELATIVE
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(if (recommended) GreenPrimary.copy(alpha = 0.10f) else GoldLight.copy(alpha = 0.10f))
            .border(
                BorderStroke(1.dp, if (recommended) GreenPrimary.copy(alpha = 0.40f) else Gold.copy(alpha = 0.22f)),
                shape,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = wakeQuickPresetTitle(preset),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (recommended) GreenPrimaryDark else TextDark,
            )
            Text(
                text = wakeQuickPresetSubtitle(preset),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (recommended) GreenPrimaryDark.copy(alpha = 0.78f) else TextMuted,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = if (recommended) GreenPrimary else TextMuted,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(20.dp),
        )
    }
}

@Composable
private fun wakeQuickPresetTitle(preset: WakeQuickPreset): String = when (preset) {
    WakeQuickPreset.PRAYER_RELATIVE -> stringResource(R.string.wake_quick_add_prayer_relative_title)
    WakeQuickPreset.FIXED_TIME -> stringResource(R.string.wake_quick_add_fixed_title)
    WakeQuickPreset.TIMER -> stringResource(R.string.wake_quick_add_timer_title)
}

@Composable
private fun wakeQuickPresetSubtitle(preset: WakeQuickPreset): String = when (preset) {
    WakeQuickPreset.PRAYER_RELATIVE -> stringResource(R.string.wake_quick_add_prayer_relative_subtitle)
    WakeQuickPreset.FIXED_TIME -> stringResource(R.string.wake_quick_add_fixed_subtitle)
    WakeQuickPreset.TIMER -> stringResource(R.string.wake_quick_add_timer_subtitle)
}

private fun newWakeAlarmConfig(preset: WakeQuickPreset): PrayerWakeConfig {
    val nowMillis = System.currentTimeMillis()
    val fixedTime = roundedClockTimeAfter(minutesFromNow = 30)
    val mainAlarm = when (preset) {
        WakeQuickPreset.PRAYER_RELATIVE -> WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.PRAYER_RELATIVE,
            prayerOffset = PrayerRelativeOffset(-DEFAULT_PRAYER_OFFSET_MINUTES),
        )
        WakeQuickPreset.FIXED_TIME -> WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FIXED_TIME,
            fixedTime = ClockTime(DEFAULT_FIXED_ALARM_HOUR, DEFAULT_FIXED_ALARM_MINUTE),
        )
        WakeQuickPreset.TIMER -> WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FROM_NOW,
            fixedTime = fixedTime,
            oneOffOffsetMinutes = DEFAULT_TIMER_MINUTES,
            oneOffTriggerAtMillis = nowMillis + DEFAULT_TIMER_MINUTES * 60_000L,
        )
    }

    return PrayerWakeConfig(
        id = UUID.randomUUID().toString(),
        enabled = true,
        prayer = Prayer.FAJR,
        mainAlarm = mainAlarm,
        playback = WakePlaybackOptions(
            ringtone = RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS,
        ),
    )
}

private fun roundedClockTimeAfter(minutesFromNow: Int): ClockTime {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.MINUTE, minutesFromNow)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val roundedMinute = ((calendar.get(Calendar.MINUTE) + 4) / 5) * 5
    if (roundedMinute >= 60) {
        calendar.add(Calendar.HOUR_OF_DAY, 1)
        calendar.set(Calendar.MINUTE, 0)
    } else {
        calendar.set(Calendar.MINUTE, roundedMinute)
    }
    return ClockTime(
        hour = calendar.get(Calendar.HOUR_OF_DAY),
        minute = calendar.get(Calendar.MINUTE),
    )
}

// A second tap on the skip/stop button, meant for the action it just had, must not trigger the one it turns into.
private const val WAKE_ACTION_DEBOUNCE_MILLIS = 1_200L

/** The ring each alarm would sound next by the clock, an alarm that is off included so its row can still show it. */
private fun nextWakeAlarmTriggersById(
    context: android.content.Context,
    delegationId: Int,
    wakeAlarms: List<PrayerWakeConfig>,
): Map<String, WakeAlarmComputer.ScheduledWakeTrigger?> =
    wakeAlarms.associate { alarm ->
        alarm.id to nextWakeAlarmTrigger(context, delegationId, alarm.copy(enabled = true))
    }

private fun runningWakeOccurrences(
    context: android.content.Context,
    wakeAlarms: List<PrayerWakeConfig>,
    windowsByAlarm: Map<String, List<WakeAlarmScheduler.OccurrenceWindow>>,
    nowMillis: Long = System.currentTimeMillis(),
): Map<String, WakeAlarmScheduler.RunningOccurrence> =
    wakeAlarms
        .filter { alarm -> alarm.enabled }
        .mapNotNull { alarm ->
            WakeAlarmScheduler.runningOccurrence(context, alarm, windowsByAlarm[alarm.id].orEmpty(), nowMillis)
                ?.let { running -> alarm.id to running }
        }
        .toMap()

@Composable
private fun WakeAlarmListPanel(
    wakeAlarms: List<PrayerWakeConfig>,
    nowByAlarm: Map<String, WakeAlarmNow?>,
    nowMillis: Long,
    onEditAlarm: (PrayerWakeConfig) -> Unit,
    onEnabledChange: (PrayerWakeConfig, Boolean) -> Unit,
    onDeleteRequest: (PrayerWakeConfig) -> Unit,
    onSkipNext: (PrayerWakeConfig) -> Unit,
    onStop: (PrayerWakeConfig) -> Unit,
) {
    val context = LocalContext.current
    var lastActionAtMillis by remember { mutableLongStateOf(0L) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.WAKE_ALARM_LIST),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // By what comes next, like the hero, so the list reads in time order and an alarm in progress stays first;
        // alarms that are off go last.
        val sortedAlarms = wakeAlarms.sortedWith(
            compareBy<PrayerWakeConfig>(
                { alarm -> !alarm.enabled },
                { alarm -> nowByAlarm[alarm.id]?.focusAtMillis(nowMillis) ?: Long.MAX_VALUE },
            ),
        )
        // A named alarm («منبه السحور») keeps its name; the user's own are numbered among themselves.
        val unnamedNumbers = sortedAlarms.filter { it.title.isBlank() }.withIndex().associate { it.value.id to it.index + 1 }
        sortedAlarms.forEach { wakeAlarm ->
            val alarmNow = nowByAlarm[wakeAlarm.id]
            val isRunning = alarmNow?.inProgress == true
            WakeAlarmRow(
                alarmName = wakeAlarm.title.ifBlank {
                    context.getString(R.string.wake_alarm_row_title, unnamedNumbers[wakeAlarm.id] ?: 1)
                },
                wakeConfig = wakeAlarm,
                alarmNow = alarmNow,
                nowMillis = nowMillis,
                onClick = { onEditAlarm(wakeAlarm) },
                onEnabledChange = { enabled -> onEnabledChange(wakeAlarm, enabled) },
                onDeleteRequest = { onDeleteRequest(wakeAlarm) },
                onSkipNext = {
                    val tappedAtMillis = SystemClock.elapsedRealtime()
                    if (tappedAtMillis - lastActionAtMillis >= WAKE_ACTION_DEBOUNCE_MILLIS) {
                        lastActionAtMillis = tappedAtMillis
                        // The label decides: a pending skip is undone, a running alarm is stopped, else the next one is skipped.
                        if (isRunning && !wakeAlarm.hasPendingWakeOccurrenceSkip(nowMillis)) {
                            onStop(wakeAlarm)
                        } else {
                            onSkipNext(wakeAlarm)
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun WakeNextAlarmPanel(
    wakeConfig: PrayerWakeConfig,
    alarmNow: WakeAlarmNow,
    nowMillis: Long,
) {
    val prayerName = wakeAlarmPrayerName(wakeConfig.prayer)
    // The rings of the occurrence on show, as it really had them if the alarm was edited meanwhile.
    val ringOffsets = alarmNow.occurrenceRingOffsets(
        wakeAlarmRingOffsets(wakeConfig.subAlarms.map { extra -> extra.signedOffsetMinutes }),
        nowMillis,
    )
    // The hero leads with what comes next: the ring going now, a ring still to come (late or ahead), or the awake
    // check once the last ring is stopped.
    val focus = alarmNow.focus
    val shownRing = alarmNow.highlightedRing
    // The check is set only once the last ring is stopped; while a ring is still to come or going, an alarm with the
    // check on shows it planned after its rings.
    val awakeCheck = alarmNow.awakeCheckAtMillis?.let { atMillis ->
        WakeTimelineAwakeCheck(
            atMillis = atMillis,
            fromMillis = alarmNow.awakeCheckFromMillis,
            running = alarmNow.awakeCheckRunning,
            isNext = focus == WakeAlarmFocus.AWAKE_CHECK,
        )
    } ?: wakeConfig.playback
        .takeIf { playback -> playback.awakeCheckEnabled && shownRing != null }
        ?.let { playback ->
            plannedWakeTimelineAwakeCheck(
                offsets = ringOffsets,
                occurrenceAtMillis = alarmNow.occurrenceAtMillis,
                delayMinutes = playback.awakeCheckDelayMinutes,
                nowMillis = nowMillis,
            )
        }
    // An alarm with extras or the awake check shows every step of the occurrence on show; its rule stays in the row
    // below. Only a lone ring keeps the rule here.
    val showsTimeline = ringOffsets.size > 1 || awakeCheck != null
    val summaryText = if (showsTimeline) null else wakeSummaryText(prayerName, wakeConfig)
    val shownAtMillis = when (focus) {
        WakeAlarmFocus.AWAKE_CHECK -> alarmNow.awakeCheckAtMillis ?: alarmNow.occurrenceAtMillis
        else -> shownRing?.atMillis ?: alarmNow.occurrenceAtMillis
    }
    val shownDate = calendarLocalDate(shownAtMillis)
    val calendar = rememberTunisianHijriCalendar()
    val hijriDate = remember(shownDate, calendar) { hijriDateLabel(calendar.date(shownDate)) }
    LoadOfficialCalendarYear(algorithmicHijriYear(shownDate))
    val shownIsExtra = focus != WakeAlarmFocus.AWAKE_CHECK && shownRing?.isExtra == true
    val titleRes = when (focus) {
        WakeAlarmFocus.RING ->
            if (shownIsExtra) R.string.wake_editor_hero_extra_title else R.string.wake_alarm_next_title
        WakeAlarmFocus.AWAKE_CHECK -> R.string.wake_alarm_hero_awake_check_title
        WakeAlarmFocus.RINGING ->
            if (alarmNow.ringWaiting) R.string.wake_alarm_hero_waiting_title else R.string.wake_alarm_hero_ringing_title
    }
    // The bell tells an extra alert from the alarm itself, the sun the awake check.
    val iconRes = when {
        focus == WakeAlarmFocus.AWAKE_CHECK -> R.drawable.ic_adhkar_sun
        shownIsExtra -> R.drawable.ic_adhkar_bell
        else -> R.drawable.ic_tab_alarms
    }
    // Counts to what comes next, in the minutes the clock shows; the title already says a ring is going. Android
    // can deliver a ring or start the check a little after its time.
    val countdown = formatWakeCountdownDuration(wakeClockCountdownMillis(shownAtMillis, nowMillis))
    val countdownText = when {
        focus == WakeAlarmFocus.RINGING || shownRing == null && focus == WakeAlarmFocus.RING -> null
        alarmNow.awakeCheckRunning -> stringResource(R.string.wake_alarm_row_in_progress)
        focus == WakeAlarmFocus.AWAKE_CHECK && shownAtMillis <= nowMillis ->
            stringResource(R.string.wake_alarm_hero_awake_check_due)
        focus == WakeAlarmFocus.AWAKE_CHECK -> stringResource(R.string.wake_alarm_hero_awake_check_countdown, countdown)
        shownAtMillis <= nowMillis -> stringResource(R.string.wake_alarm_hero_ring_due)
        else -> stringResource(R.string.wake_editor_hero_countdown, countdown)
    }
    HeroCardSurface(modifier = Modifier.heightIn(min = MainHeroCardHeight)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp)
                .heightIn(min = 122.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WakeNextAlarmDetails(
                titleRes = titleRes,
                iconRes = iconRes,
                timeText = formatTimeOfDay(shownAtMillis),
                gregorianDateText = formatWakeAlarmDate(shownAtMillis),
                hijriDateText = hijriDate,
                summaryText = summaryText,
            )
            if (countdownText != null) {
                WakeNextAlarmCountdown(text = countdownText, color = Gold)
            }
            if (showsTimeline) {
                WakeNextAlarmTimeline(
                    stops = wakeTimelineStops(
                        offsets = ringOffsets,
                        occurrenceAtMillis = alarmNow.occurrenceAtMillis,
                        highlightedAtMillis = shownRing?.atMillis,
                        highlightedOngoing = focus == WakeAlarmFocus.RINGING && !alarmNow.ringWaiting,
                        awakeCheck = awakeCheck,
                    ),
                    nowMillis = nowMillis,
                    formatTime = ::formatTimeOfDay,
                )
            }
        }
    }
}

/** The hero when alarms exist but all are off: it says so, in the same scene as the next-alarm hero. */
@Composable
private fun WakeNoEnabledAlarmPanel() {
    HeroCardSurface(modifier = Modifier.heightIn(min = MainHeroCardHeight)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp)
                .heightIn(min = 122.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroIconRing(iconRes = R.drawable.ic_tab_alarms)
            Text(
                text = stringResource(R.string.wake_alarm_hero_none_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * How long until the next ring, as one gold line under the date. Like the time row above, the icon sits
 * in the bell's column and the text lines up with the title and the date.
 */
@Composable
private fun WakeNextAlarmCountdown(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_adhkar_clock),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
internal fun WakeNextAlarmDetails(
    titleRes: Int,
    iconRes: Int,
    timeText: String,
    gregorianDateText: String,
    hijriDateText: String,
    summaryText: String?,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val alarmIconSize = 34.dp
    val alarmTextInset = alarmIconSize + 8.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.padding(start = alarmTextInset),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroIconRing(iconRes = iconRes)
            Text(
                text = timeText,
                fontSize = 29.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                autoSize = if (largeText) null else TextAutoSize.StepBased(maxFontSize = 29.sp),
                style = TextStyle(fontFamily = AppFontFamily, textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
            )
        }
        Column(modifier = Modifier.padding(start = alarmTextInset)) {
            Text(
                text = hijriDateText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f),
            )
            Text(
                text = gregorianDateText,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
        if (summaryText != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.91f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = summaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GreenPrimaryDark,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_adhkar_bell),
                    contentDescription = null,
                    tint = Gold,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** How the place's country sets Jumu'a (6.9, A18). */
private enum class JumuaMode { DEFAULT, BEFORE_ASR, FIXED }

/**
 * Jumu'a's time: the default (Dhuhr in Tunisia, A18 abroad), an hour before each Friday's Asr (about one Tunisian user
 * in ten, whose mosque prays then), or a fixed time. Each choice shows this Friday's time.
 */
@Composable
private fun JumuaTimeDialog(
    current: JumuaMode,
    defaultAt: String?,
    defaultHint: String?,
    beforeAsrAt: String?,
    fixedAt: String?,
    onPick: (JumuaMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = stringResource(R.string.jomoaa_time_choice_title),
                color = PrayerNameColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                JumuaChoice(stringResource(R.string.jomoaa_time_default), defaultHint, defaultAt, current == JumuaMode.DEFAULT) { onPick(JumuaMode.DEFAULT) }
                JumuaChoice(stringResource(R.string.jomoaa_time_before_asr), stringResource(R.string.jomoaa_time_before_asr_hint), beforeAsrAt, current == JumuaMode.BEFORE_ASR) {
                    onPick(JumuaMode.BEFORE_ASR)
                }
                JumuaChoice(stringResource(R.string.jomoaa_time_fixed), stringResource(R.string.jomoaa_time_fixed_hint), fixedAt, current == JumuaMode.FIXED) { onPick(JumuaMode.FIXED) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.jomoaa_time_cancel))
            }
        },
    )
}

@Composable
private fun JumuaChoice(title: String, hint: String?, time: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextDark, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            hint?.let { Text(text = it, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        // A clock in Arabic text, isolated left to right.
        time?.let { Text(text = "⁦$it⁩", color = PrayerNameColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
    }
}

@Composable
private fun WakeAlarmDeleteDialog(
    alarmName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = stringResource(R.string.wake_alarm_delete_title),
                color = PrayerNameColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.wake_alarm_delete_message, alarmName),
                color = TextDark,
                lineHeight = 20.sp,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.wake_alarm_delete_keep))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = SilenceRed),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(text = stringResource(R.string.wake_alarm_delete_confirm))
            }
        },
    )
}

@Composable
private fun wakeAlarmPrayerName(prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> stringResource(R.string.prayer_fajr)
    Prayer.DHUHR -> stringResource(R.string.prayer_dhuhr)
    Prayer.ASR -> stringResource(R.string.prayer_asr)
    Prayer.MAGHRIB -> stringResource(R.string.prayer_maghrib)
    Prayer.ISHA -> stringResource(R.string.prayer_isha)
    Prayer.JOMOAA -> stringResource(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> stringResource(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> stringResource(R.string.prayer_aid_adha)
}

@Composable
private fun WakeAlarmEmptyState(
    onPresetSelected: (WakeQuickPreset) -> Unit,
    onFajrSetup: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_alarm_empty_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
            Text(
                text = stringResource(R.string.wake_alarm_empty_body_modern),
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 18.sp,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onFajrSetup?.let { onClick -> FajrAlarmStartOption(onClick = onClick) }
                WakeQuickPreset.values().forEach { preset ->
                    WakeQuickPresetRow(
                        preset = preset,
                        onClick = { onPresetSelected(preset) },
                        modifier = Modifier.testTag(preset.testTag()),
                    )
                }
            }
        }
    }
}

@Composable
private fun WakeAlarmFeatureChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GoldLight.copy(alpha = 0.26f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            text = text,
        )
    }
}

@Composable
private fun PrayerRow(
    prayer: Prayer,
    prayerName: String,
    modifier: Modifier = Modifier,
    anchor: PrayerTimelineAnchor?,
    nextAnchor: PrayerTimelineAnchor?,
    isNextPrayer: Boolean,
    highlighted: Boolean = false,
    draftSilenceConfig: PrayerSilenceConfig? = null,
    onDraftSilenceConfigChange: ((PrayerSilenceConfig) -> Unit)? = null,
    onConfigChanged: () -> Unit,
    onPrayerTimeClick: (() -> Unit)? = null,
    timelineScale: PrayerTimelineScale? = null,
    showEnableSwitch: Boolean = true,
) {
    val context = LocalContext.current
    val usesDraft = draftSilenceConfig != null && onDraftSilenceConfigChange != null
    // The effective config takes the place's kind (D12's Tunisian defaults): a move between Tunisia and abroad reloads it.
    var savedConfig by remember(context, prayer, anchor?.inm) { mutableStateOf(PrefsManager.getEffectiveConfig(context, prayer)) }
    val config = if (usesDraft) requireNotNull(draftSilenceConfig) else savedConfig
    var prayerEnabled by remember(context, prayer) {
        mutableStateOf(PrefsManager.isPrayerSilenceEnabled(context, prayer))
    }
    var editorEndpoint by rememberSaveable(prayer) { mutableStateOf<SilenceEndpoint?>(null) }
    var placeRequired by rememberSaveable(prayer) { mutableStateOf(false) }
    if (placeRequired) PrayerPlaceRequiredDialog { placeRequired = false }
    var previewWindow by remember(prayer) { mutableStateOf<PrayerTimelineWindow?>(null) }
    var undoBefore by remember(prayer) { mutableStateOf<PrayerSilenceConfig?>(null) }
    var undoAfter by remember(prayer) { mutableStateOf<PrayerSilenceConfig?>(null) }
    val currentConfig by rememberUpdatedState(config)
    // The overlap warning below uses the next prayer's saved window, which can change from that prayer's row.
    val nextPrayer = nextAnchor?.prayer
    var nextPrayerConfig by remember(context, nextPrayer, nextAnchor?.inm) {
        mutableStateOf(nextPrayer?.let { PrefsManager.getEffectiveConfig(context, it) })
    }

    DisposableEffect(context, prayer, usesDraft) {
        val stopObservingConfig = if (usesDraft) {
            ({})
        } else {
            PrefsManager.observeConfigChanges(context, prayer) {
                savedConfig = PrefsManager.getEffectiveConfig(context, prayer)
            }
        }
        val stopObservingEnabled = if (usesDraft) {
            ({})
        } else {
            PrefsManager.observePrayerEnabledChanges(context, prayer) {
                prayerEnabled = PrefsManager.isPrayerSilenceEnabled(context, prayer)
            }
        }
        onDispose {
            stopObservingConfig()
            stopObservingEnabled()
        }
    }
    DisposableEffect(context, nextPrayer) {
        val stopObservingNextConfig = if (nextPrayer == null) {
            ({})
        } else {
            PrefsManager.observeConfigChanges(context, nextPrayer) {
                nextPrayerConfig = PrefsManager.getEffectiveConfig(context, nextPrayer)
            }
        }
        onDispose { stopObservingNextConfig() }
    }
    LaunchedEffect(config) {
        previewWindow = null
        if (undoAfter != null && config != undoAfter) {
            undoBefore = null
            undoAfter = null
        }
    }
    LaunchedEffect(undoAfter) {
        if (undoAfter != null) {
            delay(6_000L)
            if (undoAfter == currentConfig) {
                undoBefore = null
                undoAfter = null
            }
        }
    }

    fun applyConfig(updated: PrayerSilenceConfig): Boolean {
        if (usesDraft) {
            onDraftSilenceConfigChange?.invoke(updated)
        } else {
            savedConfig = updated
            persistPrayerSilenceConfig(context, prayer, updated, anchor)
            val persisted = PrefsManager.getEffectiveConfig(context, prayer)
            // Compared by the window armed: an endpoint left at its default is stored as unset (6.9).
            val matches = anchor?.let { resolvePrayerTimelineWindow(it, persisted) == resolvePrayerTimelineWindow(it, updated) }
                ?: (persisted == updated)
            if (!matches) {
                savedConfig = persisted
                undoBefore = null
                undoAfter = null
                Toast.makeText(
                    context,
                    context.getString(R.string.prayer_silence_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
                onConfigChanged()
                return false
            }
        }
        onConfigChanged()
        return true
    }

    fun commitConfig(updated: PrayerSilenceConfig): Boolean {
        val previous = if (usesDraft) config else PrefsManager.getEffectiveConfig(context, prayer)
        if (updated == previous) return true
        undoBefore = previous
        undoAfter = updated
        val applied = applyConfig(updated)
        if (!applied) {
            undoBefore = null
            undoAfter = null
        }
        return applied
    }

    // D16's year run (6.9): a window with a clock endpoint is checked over the coming year before it is saved.
    var pendingYearFit by remember(prayer) { mutableStateOf<Pair<PrayerSilenceConfig, SilenceFit>?>(null) }
    val rowScope = rememberCoroutineScope()
    fun commitChecked(updated: PrayerSilenceConfig) {
        val checkedAnchor = anchor
        val hasClock = updated.startRuleMode() == EndpointRuleMode.FIXED_TIME || updated.endRuleMode() == EndpointRuleMode.FIXED_TIME
        if (usesDraft || checkedAnchor == null || !hasClock) {
            commitConfig(updated)
            return
        }
        rowScope.launch {
            val fit = withContext(Dispatchers.Default) { silenceYearFit(context, checkedAnchor, updated) }
            if (fit.fitsAllYear) commitConfig(updated) else pendingYearFit = updated to fit
        }
    }

    val savedEnds = remember(anchor, config) { anchor?.let { silenceEndsAt(it, config) } }
    val savedWindow = if (anchor != null && savedEnds != null)
        PrayerTimelineWindow(anchor.axisOf(savedEnds.start), anchor.axisOf(savedEnds.end)) else null
    val window = previewWindow ?: savedWindow
    val scale = timelineScale ?: remember(anchor, savedWindow) {
        if (anchor != null && savedWindow != null) {
            sharedPrayerTimelineScale(listOf(anchor to savedWindow))
        } else {
            PrayerTimelineScale(
                DEFAULT_TIMELINE_START_OFFSET_MINUTES,
                DEFAULT_TIMELINE_END_OFFSET_MINUTES,
            )
        }
    }
    val warningRes = if (anchor != null && savedEnds != null) {
        val start = previewWindow?.let { anchor.instantAt(it.startMinutes) } ?: savedEnds.start
        val end = previewWindow?.let { anchor.instantAt(it.endMinutes) } ?: savedEnds.end
        if (end <= start) R.string.prayer_timeline_invalid_window else if (nextAnchor != null) {
            // The next prayer's silence can start before its adhan, so compare with whichever comes first.
            val nextSilenceStart = nextPrayerConfig?.let { silenceEndsAt(nextAnchor, it).start }
            val nextStart = minOf(nextAnchor.adhan, nextSilenceStart ?: nextAnchor.adhan)
            R.string.warning_overlaps_next_prayer.takeIf { end > nextStart }
        } else null
    } else null
    Column(
        modifier = modifier.fillMaxWidth()
            .background(if (highlighted) GoldLight.copy(alpha = 0.30f) else Color.Transparent),
    ) {
        if (anchor != null && window != null) {
            PrayerSilenceSection(
                prayer = prayer,
                prayerName = prayerName,
                anchor = anchor,
                config = config,
                window = window,
                scale = scale,
                enabled = if (usesDraft) true else prayerEnabled,
                showSwitch = showEnableSwitch && !usesDraft,
                isNextPrayer = isNextPrayer,
                onEnabledChange = changePrayer@{ newValue ->
                    if (newValue && !PrefsManager.hasConfirmedPlace(context)) {
                        placeRequired = true
                        return@changePrayer
                    }
                    prayerEnabled = newValue
                    PrefsManager.setPrayerSilenceEnabled(context, prayer, newValue)
                    onConfigChanged()
                },
                onPreviewWindow = { previewWindow = it },
                onCommitWindow = { candidate ->
                    previewWindow = null
                    prayerTimelineConfigForWindow(anchor, config, candidate)?.let { updated ->
                        commitChecked(updated)
                    }
                },
                onEditEndpoint = { endpoint -> editorEndpoint = endpoint },
                onPrayerTimeClick = onPrayerTimeClick,
            )
        } else {
            Text(
                text = stringResource(R.string.no_prayer_data),
                fontSize = 13.sp,
                color = TextMuted,
                modifier = Modifier.padding(
                    horizontal = PrayerSilenceDimens.SectionHorizontalPadding,
                    vertical = PrayerSilenceDimens.SectionVerticalPadding,
                ),
            )
        }
        warningRes?.let {
            Text(
                text = stringResource(it),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.OVERLAP_WARNING)
                    .clip(RoundedCornerShape(8.dp)).background(SilenceRed.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = SilenceRed,
                lineHeight = 18.sp,
            )
        }
        if (undoBefore != null && undoAfter == config) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(GreenPrimary.copy(alpha = 0.06f)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.prayer_silence_saved),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    fontSize = 12.sp,
                    color = TextMuted,
                )
                TextButton(
                    onClick = {
                        val previous = undoBefore
                        val current = if (usesDraft) config else PrefsManager.getEffectiveConfig(context, prayer)
                        if (previous != null && current == undoAfter) applyConfig(previous)
                        undoBefore = null
                        undoAfter = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("prayer_timeline_undo_${prayer.name}"),
                ) {
                    Text(stringResource(R.string.prayer_silence_undo), color = GreenPrimaryDark)
                }
            }
        }
    }
    val currentEditorEndpoint = editorEndpoint
    if (currentEditorEndpoint != null && anchor != null) {
        PrayerSilenceEditorSheet(
            prayer = prayer,
            prayerName = prayerName,
            anchor = anchor,
            config = config,
            endpoint = currentEditorEndpoint,
            onDismiss = { editorEndpoint = null },
            onSave = { updated ->
                editorEndpoint = null
                commitChecked(updated)
                true
            },
        )
    }
    val yearFit = pendingYearFit
    if (yearFit != null && anchor != null) {
        val (draft, fit) = yearFit
        SilenceYearFitDialog(
            fit = fit,
            anchor = anchor,
            onAdhanRelative = fit.adhanRelativeEnd?.let { offset ->
                {
                    pendingYearFit = null
                    commitConfig(draft.copy(mode = SilenceMode.DURATION, endOffsetMinutes = offset).withLegacyDurationSynced(anchor))
                }
            },
            onLaterFixedEnd = fit.laterFixedEnd?.let { time ->
                {
                    pendingYearFit = null
                    commitConfig(draft.copy(mode = SilenceMode.FIXED_TIME, fixedHour = time.hour, fixedMinute = time.minute, endOffsetMinutes = null))
                }
            },
            onKeep = {
                pendingYearFit = null
                commitConfig(draft)
            },
            onDismiss = { pendingYearFit = null },
        )
    }
}

@Composable
private fun WakeSilenceConflictPrayerEditor(
    delegationId: Int,
    editorState: WakeSilenceConflictEditorState,
    onConfigChanged: (PrayerSilenceConfig) -> Unit,
) {
    val context = LocalContext.current
    val targetPrayer = editorState.originalConflict.silencePrayer
    val conflict = editorState.currentConflict ?: editorState.originalConflict
    // The conflicting window's own prayer day (6.9), else the day its silence starts on at the place.
    val prayerDay = conflict.silencePrayerDay ?: java.time.Instant.ofEpochMilli(conflict.silenceStartAtMillis)
        .atZone(PrayerTimesRepository.place(context).zone).toLocalDate()
    val anchors = remember(delegationId, prayerDay, targetPrayer) {
        val anchor = timelineAnchorFor(context, targetPrayer, prayerDay)
        anchor to anchor?.let { nextTimelineAnchor(context, it) }
    }
    val (anchor, nextAnchor) = anchors
    if (anchor == null) {
        Text(
            text = stringResource(R.string.no_prayer_data),
            fontSize = 12.sp,
            color = TextMuted,
            lineHeight = 17.sp,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PrayerRow(
            prayer = targetPrayer,
            prayerName = prayerName(context, targetPrayer),
            anchor = anchor,
            nextAnchor = nextAnchor,
            isNextPrayer = false,
            highlighted = false,
            draftSilenceConfig = editorState.draftSilenceConfig,
            onDraftSilenceConfigChange = onConfigChanged,
            onConfigChanged = {},
            showEnableSwitch = false,
        )
    }
}

@Composable
internal fun WakeAlarmRow(
    alarmName: String,
    wakeConfig: PrayerWakeConfig,
    // The occurrence on show, the same as the hero's: the one in progress, else the next one.
    alarmNow: WakeAlarmNow?,
    nowMillis: Long,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val enabled = wakeConfig.enabled
    val isRunning = alarmNow?.inProgress == true
    // The ring still to come (late or ahead); null once every ring of an occurrence in progress has gone.
    val nextAlarmMillis = alarmNow?.nextRing?.atMillis
    // The ring lit in gold, as in the hero: the one going now, else the one coming.
    val highlightedRingAtMillis = alarmNow?.highlightedRing?.atMillis
    val mainAlarmMillis = alarmNow?.occurrenceAtMillis
    val isRepeatingAlarm = wakeConfig.isRepeatingWakeAlarm()
    val isSkipPending = wakeConfig.hasPendingWakeOccurrenceSkip(nowMillis)
    val showSkipNextAction = isRunning ||
        (isRepeatingAlarm && (isSkipPending || (enabled && nextAlarmMillis != null)))
    // A pending skip is undone first, so a stopped alarm offers undo for the rest of its occurrence.
    val skipActionDescription = stringResource(
        when {
            isSkipPending -> R.string.wake_alarm_undo_skip_next
            isRunning -> R.string.wake_alarm_stop
            else -> R.string.wake_alarm_skip_next
        },
    )
    val deleteActionDescription = stringResource(R.string.wake_alarm_delete_action)
    val context = LocalContext.current
    // The card names the alarm by its own time; the ring coming next, maybe an extra, is a detail below it.
    val alarmTimeMillis = mainAlarmMillis
    // What the alarm follows: its days for a fixed time, its rule for a prayer or a timer.
    val isTimer = wakeConfig.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW
    val scheduleText = if (isTimer) {
        stringResource(R.string.wake_editor_mode_from_now)
    } else if (wakeConfig.mainAlarm.mode == WakeMainAlarmMode.FIXED_TIME) {
        if (wakeConfig.repeatMode == WakeRepeatMode.ONCE) {
            stringResource(R.string.wake_schedule_once)
        } else {
            formatWakeScheduleDaysSummary(context, wakeConfig.scheduledDays)
        }
    } else {
        wakeSummaryText(wakeAlarmDisplayName(wakeConfig), wakeConfig)
    }
    @Composable
    fun relativeDay(millis: Long): String = when (wakeHeroRelativeDay(millis, nowMillis)) {
        WakeHeroRelativeDay.TODAY -> stringResource(R.string.date_today)
        WakeHeroRelativeDay.TOMORROW -> stringResource(R.string.next_prayer_countdown_tomorrow)
        WakeHeroRelativeDay.LATER -> formatWakeAlarmDate(millis)
    }
    // A state the user must notice replaces the plain "next" note.
    val skippedOccurrenceAtMillis = wakeConfig.skipNextOccurrenceAtMillis.takeIf { isSkipPending }
    // Once the last ring is stopped, what is left of the occurrence is its awake check.
    val awakeCheckAtMillis = alarmNow?.takeIf { now -> now.focus == WakeAlarmFocus.AWAKE_CHECK }?.awakeCheckAtMillis
    val statusText = when {
        !enabled -> stringResource(R.string.wake_alarm_row_off)
        alarmNow?.awakeCheckRunning == true -> stringResource(R.string.wake_alarm_row_awake_check_running)
        awakeCheckAtMillis != null ->
            stringResource(R.string.wake_alarm_row_awake_check_at, formatTimeOfDay(awakeCheckAtMillis))
        isRunning -> stringResource(R.string.wake_alarm_row_in_progress)
        skippedOccurrenceAtMillis != null ->
            stringResource(R.string.wake_alarm_row_skipped, relativeDay(skippedOccurrenceAtMillis))
        else -> null
    }
    val nextDayText = nextAlarmMillis?.takeIf { statusText == null }?.let { millis ->
        if (isTimer) {
            stringResource(
                R.string.wake_editor_hero_countdown,
                formatWakeCountdownDuration(wakeClockCountdownMillis(millis, nowMillis)),
            )
        } else {
            stringResource(R.string.wake_alarm_row_next_at, relativeDay(millis))
        }
    }
    // Every ring of the occurrence on show, in time order, the way the hero's timeline lists them.
    val alarmRingOffsets = wakeAlarmRingOffsets(wakeConfig.subAlarms.map { extra -> extra.signedOffsetMinutes })
    val ringTimes = alarmNow?.let { now ->
        now.occurrenceRingOffsets(alarmRingOffsets, nowMillis)
            .takeIf { offsets -> offsets.size > 1 }
            ?.map { offset -> offset to now.occurrenceAtMillis + offset * 60_000L }
    }
    val featureChips = buildList {
        val playback = wakeConfig.playback
        if (playback.wakeUpCheckEnabled) {
            add(
                when (playback.hardestChallengeDifficulty()) {
                    MathDifficulty.HARD -> context.getString(R.string.wake_alarm_feature_challenge_hard)
                    MathDifficulty.INTERMEDIATE -> context.getString(R.string.wake_alarm_feature_challenge_medium)
                    MathDifficulty.EASY -> context.getString(R.string.wake_alarm_feature_challenge_easy)
                    null -> context.getString(R.string.wake_alarm_feature_wake_check)
                },
            )
        }
        if (playback.awakeCheckEnabled) add(context.getString(R.string.wake_alarm_feature_awake_check))
        if (playback.vibrationOnly) add(context.getString(R.string.wake_alarm_feature_vibration))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.wakeAlarmRow(wakeConfig.id))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.dp,
            color = GreenPrimary.copy(alpha = 0.14f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // An alarm that is off keeps its details, greyed out.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (enabled) 1f else 0.5f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (alarmTimeMillis != null) {
                        Text(
                            text = formatTimeOfDay(alarmTimeMillis),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = GreenPrimaryDark,
                            maxLines = 1,
                            style = TextStyle(fontFamily = AppFontFamily, textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                        )
                    }
                    Text(
                        text = listOfNotNull(scheduleText, nextDayText).joinToString(separator = " • "),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimaryDark,
                        lineHeight = 18.sp,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning && enabled) SilenceRed else PrayerNameColor,
                            lineHeight = 18.sp,
                        )
                    }
                    if (ringTimes != null) {
                        WakeAlarmRingStrip(
                            rings = ringTimes,
                            nextRingAtMillis = highlightedRingAtMillis.takeIf { enabled && !isSkipPending },
                            nowMillis = nowMillis,
                        )
                    }
                    if (featureChips.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            featureChips.forEach { chip -> WakeAlarmFeatureChip(text = chip) }
                        }
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(TestTags.wakeAlarmEnabledSwitch(wakeConfig.id))
                        .semantics { contentDescription = alarmName },
                )
            }
            if (enabled && isTimer && wakeConfig.silenceUntilAlarm && wakeConfig.hasFutureWakeTriggers(nowMillis)) {
                WakeTimerSilenceNotice(savedTimer = wakeConfig)
            }
            HorizontalDivider(color = GreenPrimary.copy(alpha = 0.1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (showSkipNextAction) {
                        TextButton(
                            onClick = onSkipNext,
                            colors = ButtonDefaults.textButtonColors(contentColor = GreenPrimaryDark),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(TestTags.wakeAlarmSkipNextButton(wakeConfig.id))
                                .semantics { contentDescription = skipActionDescription },
                        ) {
                            Text(
                                text = stringResource(
                                    when {
                                        isSkipPending -> R.string.wake_alarm_undo_skip_next_short
                                        isRunning -> R.string.wake_alarm_stop_short
                                        else -> R.string.wake_alarm_skip_next_short
                                    },
                                ),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDeleteRequest,
                    colors = ButtonDefaults.textButtonColors(contentColor = SilenceRed),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(TestTags.wakeAlarmDeleteButton(wakeConfig.id))
                        .semantics { contentDescription = deleteActionDescription },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = deleteActionDescription, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * The rings of one occurrence as times, e.g. 04:50 • 04:55 • 05:00: the main alarm in bold, the ring
 * coming next in gold, the other extras muted.
 */
@Composable
private fun WakeAlarmRingStrip(rings: List<Pair<Int, Long>>, nextRingAtMillis: Long?, nowMillis: Long) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rings.forEachIndexed { index, (offset, atMillis) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(TextMuted.copy(alpha = 0.45f)),
                )
            }
            val isMain = offset == 0
            val isNext = atMillis == nextRingAtMillis
            val isPast = atMillis < nowMillis && !isNext
            Text(
                modifier = Modifier.alpha(if (isPast) 0.4f else 1f),
                text = formatTimeOfDay(atMillis),
                fontSize = 14.sp,
                fontWeight = if (isMain || isNext) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isNext -> Gold
                    isMain -> GreenPrimaryDark
                    else -> TextMuted
                },
                style = TextStyle(fontFamily = AppFontFamily, textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun wakeSummaryText(
    prayerName: String,
    wakeConfig: PrayerWakeConfig,
    // The alarm row shows the fixed time big already, so it names only the mode.
    includeFixedTime: Boolean = true,
): String {
    val mainSummary = when (wakeConfig.mainAlarm.mode) {
        WakeMainAlarmMode.FIXED_TIME -> if (!includeFixedTime) stringResource(R.string.wake_editor_mode_fixed) else stringResource(
            R.string.wake_summary_fixed,
            formatClockTime(
                wakeConfig.mainAlarm.fixedTime.hour,
                wakeConfig.mainAlarm.fixedTime.minute,
            ),
        )

        WakeMainAlarmMode.FROM_NOW -> stringResource(
            R.string.wake_summary_from_now,
            formatArabicMinutes(wakeConfig.mainAlarm.oneOffOffsetMinutes),
        )

        WakeMainAlarmMode.PRAYER_RELATIVE -> {
            val offset = wakeConfig.mainAlarm.prayerOffset
            when {
                offset.minutes == 0 -> stringResource(R.string.wake_editor_hero_rule_prayer_at, prayerName)
                offset.minutes < 0 -> stringResource(
                    R.string.wake_summary_relative_before,
                    prayerName,
                    formatArabicByDuration(offset.absoluteMinutes),
                )
                else -> stringResource(
                    R.string.wake_summary_relative_after,
                    prayerName,
                    formatArabicByDuration(offset.absoluteMinutes),
                )
            }
        }
    }

    val context = LocalContext.current
    val recurringSummary = if (
        wakeConfig.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
        wakeConfig.repeatMode == WakeRepeatMode.ONCE
    ) {
        stringResource(
            R.string.wake_summary_with_days,
            mainSummary,
            stringResource(R.string.wake_schedule_once),
        )
    } else if (
        wakeConfig.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
        !isEveryWakeScheduleDay(wakeConfig.scheduledDays)
    ) {
        stringResource(
            R.string.wake_summary_with_days,
            mainSummary,
            formatWakeScheduleDaysSummary(context, wakeConfig.scheduledDays),
        )
    } else {
        mainSummary
    }

    return recurringSummary
}

@Composable
private fun wakeAlarmDisplayName(wakeConfig: PrayerWakeConfig): String = when (wakeConfig.mainAlarm.mode) {
    WakeMainAlarmMode.FROM_NOW -> stringResource(R.string.wake_editor_mode_from_now)
    WakeMainAlarmMode.FIXED_TIME -> stringResource(R.string.wake_editor_mode_fixed)
    WakeMainAlarmMode.PRAYER_RELATIVE -> wakeAlarmPrayerName(wakeConfig.prayer)
}

@Composable
private fun NumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Number,
    enabled: Boolean = true,
    inputDescription: String? = null,
) {
    val focusManager = LocalFocusManager.current
    // Intermediate edits belong to the field, never to saved scheduling settings.
    // Keying by the committed value also reflects changes from another editor.
    var editingValue by remember(value) { mutableStateOf(value) }
    var lastValidValue by remember(value) { mutableStateOf(value) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = editingValue,
            enabled = enabled,
            onValueChange = { new ->
                // Normalize Eastern Arabic (٠-٩) and Extended Arabic-Indic (۰-۹) to 0-9
                val normalized = normalizeDigits(new)
                val filtered = if (allowNegative) {
                    // Allow optional leading '-' followed by up to 3 digits
                    val negative = normalized.startsWith("-")
                    val digits = normalized.filter { it in '0'..'9' }.take(3)
                    if (negative) "-$digits" else digits
                } else {
                    // Only allow digits, max 3 chars
                    normalized.filter { it in '0'..'9' }.take(3)
                }
                editingValue = filtered
                if (filtered.toIntOrNull() != null) {
                    lastValidValue = filtered
                    if (filtered != value) onValueChange(filtered)
                }
            },
            modifier = modifier
                .heightIn(min = 48.dp)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) editingValue = lastValidValue
                }
                .semantics { inputDescription?.let { contentDescription = it } }
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .then(
                    Modifier.background(
                        color = Color.White,
                        shape = RoundedCornerShape(6.dp)
                    )
                )
                .padding(4.dp),
            textStyle = TextStyle(
                fontFamily = AppFontFamily,
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                textDirection = TextDirection.Ltr
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (allowNegative) KeyboardType.Phone else keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                editingValue = lastValidValue
                focusManager.clearFocus()
            }),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(
                            color = Color.White,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .then(
                            Modifier
                                .background(
                                    color = GoldLight.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                        )
                ) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun RamadanBadge() {
    Text(
        text = stringResource(R.string.ramadan_active),
        fontSize = 14.sp,
        color = Gold,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(RamadanBg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    )
}

/** Durations offered as one-tap choices before the custom hours/minutes fields. */
private val ManualSilenceDurationPresets = listOf(15, 30, 60, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSilenceOptionsSheet(
    hasDnd: Boolean,
    hasExactAlarm: Boolean,
    resolvedTotalMinutes: Int,
    middayPrayer: Prayer,
    namedTargetPrayer: Prayer,
    manualSilenceMode: ManualSilenceMode,
    manualTargetPrayer: Prayer,
    manualDurationHours: String,
    manualDurationMinutes: String,
    manualSilenceActive: Boolean,
    autoSilenceActive: Boolean,
    manualSilenceEndsAtMillis: Long,
    onModeChange: (ManualSilenceMode) -> Unit,
    onTargetPrayerChange: (Prayer) -> Unit,
    onDurationHoursChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onDurationPresetSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val silenceActive = (manualSilenceActive || autoSilenceActive) && hasDnd
    val targetPrayerName = manualSilencePrayerName(namedTargetPrayer)
    var customDurationOpen by rememberSaveable { mutableStateOf(false) }
    val showCustomDuration = customDurationOpen || resolvedTotalMinutes !in ManualSilenceDurationPresets
    val accent by animateColorAsState(
        targetValue = if (silenceActive) SilencedHeroEnd else PrayerSilencePalette.InteractiveTeal,
        label = "manualSilenceAccent",
    )
    val subtitle = when {
        !silenceActive -> stringResource(R.string.manual_silence_subtitle_idle)
        !manualSilenceActive -> stringResource(R.string.manual_silence_active_auto)
        manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER ->
            stringResource(if (hasExactAlarm) R.string.manual_silence_active_until_prayer else R.string.manual_approximate_prayer, targetPrayerName)
        manualSilenceMode == ManualSilenceMode.DURATION && manualSilenceEndsAtMillis > 0L ->
            stringResource(if (hasExactAlarm) R.string.manual_silence_active_until_time else R.string.manual_approximate_time, formatTimeOfDay(manualSilenceEndsAtMillis))
        else -> stringResource(R.string.manual_silence_active_until_stopped)
    }
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PrayerSilencePalette.Surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bell_off),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (silenceActive) R.string.manual_silence_active_title else R.string.manual_silence_title,
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrayerSilencePalette.PrimaryText,
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = PrayerSilencePalette.SecondaryText,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.prayer_control_close))
                }
            }

            if (!silenceActive) {
                val modes = listOf(
                    ManualSilenceMode.UNTIL_STOPPED,
                    ManualSilenceMode.DURATION,
                    ManualSilenceMode.UNTIL_PRAYER,
                )
                EndpointModeChoices(
                    choices = listOf(
                        stringResource(R.string.manual_silence_mode_until_short),
                        stringResource(R.string.manual_silence_mode_duration),
                        stringResource(R.string.manual_silence_mode_prayer_short),
                    ),
                    selected = modes.indexOf(manualSilenceMode),
                    enabled = true,
                    onSelected = { index -> onModeChange(modes[index]) },
                    optionTestTags = listOf(
                        TestTags.MANUAL_SILENCE_MODE_UNTIL,
                        TestTags.MANUAL_SILENCE_MODE_DURATION,
                        TestTags.MANUAL_SILENCE_MODE_PRAYER,
                    ),
                )
                when (manualSilenceMode) {
                    ManualSilenceMode.DURATION -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ManualSilenceDurationPresets.forEach { minutes ->
                                ManualSilenceOptionChip(
                                    text = formatDurationText(minutes),
                                    selected = !showCustomDuration && resolvedTotalMinutes == minutes,
                                    onClick = {
                                        customDurationOpen = false
                                        onDurationPresetSelected(minutes)
                                    },
                                )
                            }
                            ManualSilenceOptionChip(
                                text = stringResource(R.string.manual_silence_duration_custom),
                                selected = showCustomDuration,
                                onClick = { customDurationOpen = true },
                            )
                        }
                        if (showCustomDuration) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ManualSilenceDurationField(
                                    label = stringResource(R.string.manual_silence_hours_label),
                                    value = manualDurationHours,
                                    onValueChange = onDurationHoursChange,
                                    inputTestTag = TestTags.MANUAL_SILENCE_DURATION_INPUT,
                                    modifier = Modifier.weight(1f),
                                )
                                ManualSilenceDurationField(
                                    label = stringResource(R.string.manual_silence_minutes_label),
                                    value = manualDurationMinutes,
                                    onValueChange = onDurationMinutesChange,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    ManualSilenceMode.UNTIL_PRAYER -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WAKE_SUPPORTED_PRAYERS.forEach { prayer ->
                                ManualSilenceOptionChip(
                                    text = manualSilencePrayerName(if (prayer == Prayer.DHUHR) middayPrayer else prayer),
                                    selected = prayer == manualTargetPrayer,
                                    onClick = { onTargetPrayerChange(prayer) },
                                    modifier = Modifier.testTag(TestTags.manualSilenceTargetPrayer(prayer.name)),
                                )
                            }
                        }
                    }
                    ManualSilenceMode.UNTIL_STOPPED -> Unit
                }
            }

            if (!hasExactAlarm && manualSilenceMode != ManualSilenceMode.UNTIL_STOPPED && (!silenceActive || manualSilenceActive)) {
                Text(stringResource(R.string.manual_exact_delay_detail), color = PrayerSilencePalette.SecondaryText)
                TextButton(onClick = { SilenceAccessSettings.openExactAlarms(context) }) {
                    Text(stringResource(R.string.manual_exact_open_settings))
                }
            }

            Button(
                onClick = onClick,
                enabled = silenceActive || manualSilenceMode != ManualSilenceMode.DURATION || resolvedTotalMinutes > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag(TestTags.MANUAL_SILENCE_BUTTON),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) {
                Icon(
                    painter = painterResource(if (silenceActive) R.drawable.ic_adhkar_bell else R.drawable.ic_bell_off),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (!silenceActive && manualSilenceMode == ManualSilenceMode.DURATION && resolvedTotalMinutes <= 0)
                        stringResource(R.string.error_manual_duration_required)
                    else manualSilenceActionLabel(silenceActive, manualSilenceMode, namedTargetPrayer, resolvedTotalMinutes, hasExactAlarm),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One choice in the duration or prayer row; same visual language as [EndpointModeChoices]. */
@Composable
private fun ManualSilenceOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) PrayerSilencePalette.Surface else PrayerSilencePalette.TintedStrip)
            .border(1.dp, if (selected) PrayerSilencePalette.InteractiveTeal else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) PrayerSilencePalette.PrimaryText else PrayerSilencePalette.SecondaryText,
        )
    }
}

/** A custom-duration number field with its unit, e.g. "[ 1 ] ساعة". */
@Composable
private fun ManualSilenceDurationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputTestTag: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PrayerSilencePalette.TintedStrip)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberInput(
            value = value,
            onValueChange = onValueChange,
            inputDescription = label,
            modifier = Modifier
                .weight(1f)
                .then(if (inputTestTag != null) Modifier.testTag(inputTestTag) else Modifier),
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = PrayerSilencePalette.SecondaryText,
        )
    }
}

@Composable
private fun manualSilenceActionLabel(
    active: Boolean,
    mode: ManualSilenceMode,
    targetPrayer: Prayer,
    minutes: Int,
    exact: Boolean,
): String = when {
    active -> stringResource(R.string.manual_silence_action_stop)
    mode == ManualSilenceMode.DURATION -> stringResource(
        if (exact) R.string.manual_silence_action_duration else R.string.manual_silence_approx_duration,
        formatDurationText(minutes),
    )
    mode == ManualSilenceMode.UNTIL_PRAYER -> stringResource(
        if (exact) R.string.manual_silence_action_prayer else R.string.manual_silence_approx_prayer,
        manualSilencePrayerName(targetPrayer),
    )
    else -> stringResource(R.string.manual_silence_action_indefinite)
}

@Composable
private fun manualSilencePrayerName(prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> stringResource(R.string.prayer_fajr)
    Prayer.DHUHR -> stringResource(R.string.prayer_dhuhr)
    Prayer.ASR -> stringResource(R.string.prayer_asr)
    Prayer.MAGHRIB -> stringResource(R.string.prayer_maghrib)
    Prayer.ISHA -> stringResource(R.string.prayer_isha)
    Prayer.JOMOAA -> stringResource(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> stringResource(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> stringResource(R.string.prayer_aid_adha)
}

private fun prayerName(context: Context, prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> context.getString(R.string.prayer_fajr)
    Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
    Prayer.ASR -> context.getString(R.string.prayer_asr)
    Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
    Prayer.ISHA -> context.getString(R.string.prayer_isha)
    Prayer.JOMOAA -> context.getString(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> context.getString(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> context.getString(R.string.prayer_aid_adha)
}

/**
 * The next prayer by instant over prayer days D − 1..D + 1 (6.6, 6.9): at 00:30 yesterday's 01:07 Isha is
 * still next. On a Friday, Jumu'a at its resolved time stands in Dhuhr's place.
 */
private fun resolveNextPrayerCountdown(
    context: Context,
    nowMillis: Long,
): NextPrayerCountdownInfo? {
    val place = PrayerTimesRepository.place(context)
    val now = java.time.Instant.ofEpochMilli(nowMillis)
    val next = PrayerTimesRepository.upcomingPrayers(context, now, place).firstOrNull() ?: return null
    return NextPrayerCountdownInfo(
        prayer = next.prayer,
        at = next.at,
        prayerDay = next.prayerDay,
        placeZone = place.zone,
    )
}

/**
 * The id the screen keys its remembered times on: the delegation in Tunisia, else a negative hash of the
 * times key (6.6), so a new place or method abroad recomputes every remembered time and matches no delegation.
 */
internal fun screenTimesId(context: Context): Int = when (val place = PrayerTimesRepository.place(context)) {
    is PrayerPlace.Inm -> place.delegationId
    is PrayerPlace.Coordinates -> -(PrayerTimesRepository.timesKey(context, place).hashCode() and Int.MAX_VALUE) - 1
}

/** The prayer the manual silence card targets next: on a Friday the card's Dhuhr stands for Jumu'a. */
private fun resolveUpcomingManualSilencePrayer(
    context: Context,
    @Suppress("UNUSED_PARAMETER") delegationId: Int,
    nowMillis: Long = System.currentTimeMillis()
): Prayer? = PrayerTimesRepository.upcomingPrayers(context, java.time.Instant.ofEpochMilli(nowMillis))
    .firstOrNull()?.prayer?.let { if (it == Prayer.JOMOAA) Prayer.DHUHR else it }

/** The next adhan of [prayer] after now (6.9), Jumu'a standing for Dhuhr on a Friday. */
internal fun nextManualSilenceOccurrence(
    context: Context,
    prayer: Prayer,
    nowMillis: Long = System.currentTimeMillis()
): PrayerTimesRepository.PrayerOccurrence? {
    if (prayer !in WAKE_SUPPORTED_PRAYERS) return null
    return PrayerTimesRepository.upcomingPrayers(context, java.time.Instant.ofEpochMilli(nowMillis))
        .firstOrNull { (if (it.prayer == Prayer.JOMOAA) Prayer.DHUHR else it.prayer) == prayer }
}

internal fun formatCountdownRemaining(targetAtMillis: Long, currentTimeMillis: Long): String {
    val remainingMillis = (targetAtMillis - currentTimeMillis).coerceAtLeast(0L)
    if (remainingMillis < 60_000L) {
        val remainingSeconds = ((remainingMillis + 999L) / 1_000L)
            .coerceAtLeast(0L)
            .toInt()
        return "${remainingSeconds} ث"
    }

    val remainingMinutes = ((remainingMillis + 59_999L) / 60_000L)
        .coerceAtLeast(1L)
        .toInt()
    return formatDurationText(remainingMinutes)
}

private fun nextCountdownRefreshDelayMillis(targetAtMillis: Long?, currentTimeMillis: Long): Long {
    val remainingMillis = targetAtMillis?.let { target -> target - currentTimeMillis }
    val nextTickMillis = if (remainingMillis != null && remainingMillis in 1L..60_000L) {
        ((currentTimeMillis / 1_000L) + 1L) * 1_000L
    } else {
        ((currentTimeMillis / 60_000L) + 1L) * 60_000L
    }
    return (nextTickMillis - currentTimeMillis).coerceAtLeast(1L)
}

// Helper functions

/** Normalizes Eastern Arabic (٠-٩) and Extended Arabic-Indic (۰-۹) digits to Western 0-9. */
private fun normalizeDigits(input: String): String = buildString(input.length) {
    for (c in input) {
        when (c) {
            in '\u0660'..'\u0669' -> append('0' + (c - '\u0660')) // ٠١٢٣٤٥٦٧٨٩
            in '\u06F0'..'\u06F9' -> append('0' + (c - '\u06F0')) // ۰۱۲۳۴۵۶۷۸۹
            else -> append(c)
        }
    }
}

private fun hasExactAlarmPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }
    return true
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun startOfDayMillis(sourceTimeInMillis: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = sourceTimeInMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

private fun isSameCalendarDay(firstTimeInMillis: Long, secondTimeInMillis: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = firstTimeInMillis }
    val second = Calendar.getInstance().apply { timeInMillis = secondTimeInMillis }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

private fun resolveManualDurationMinutes(value: String, fallback: Int): Int {
    return value.toIntOrNull()?.coerceAtLeast(1) ?: fallback.coerceAtLeast(1)
}

private fun formatDurationText(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h} س و ${m} د"
        h > 0 -> "${h} س"
        else -> "${m} د"
    }
}

private fun nextWakeAlarmMillis(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
): Long? {
    return nextWakeAlarmTrigger(context, delegationId, config)?.triggerAtMillis
}

internal fun nextWakeAlarmTrigger(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
): WakeAlarmComputer.ScheduledWakeTrigger? {
    return nextWakeAlarmTriggers(context, delegationId, config).firstOrNull()
}

private fun nextWakeAlarmTriggers(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
): List<WakeAlarmComputer.ScheduledWakeTrigger> {
    if (!config.enabled) return emptyList()
    val now = Calendar.getInstance()
    val prayerDays = WakeAlarmScheduler.loadPrayerDayContexts(context, now)
    return WakeAlarmComputer.compute(now, config, prayerDays)
        .allTriggers
}

internal fun scheduledWakeAlarmTriggersForDay(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
    day: Calendar,
): List<WakeAlarmComputer.ScheduledWakeTrigger> {
    if (!config.enabled) return emptyList()
    val isOneOff = config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW || config.repeatMode == WakeRepeatMode.ONCE
    if (isOneOff && config.mainAlarm.oneOffTriggerAtMillis <= 0L) return emptyList()
    val dayStart = startOfDayMillis(day.timeInMillis)
    val dayEnd = (day.clone() as Calendar).apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    // Work backwards from each ring's offset to find its possible prayer dates. A ring just after
    // midnight can belong to yesterday's occurrence; a pre-alarm can belong to tomorrow's. The prayer itself
    // can fall on another calendar day than its prayer day (an Isha after midnight in Paris in June), so each
    // range takes one more day on both sides; rings outside [day] are filtered out below.
    val prayerOffset = if (config.mainAlarm.mode == WakeMainAlarmMode.PRAYER_RELATIVE) {
        config.mainAlarm.prayerOffset.minutes.toLong()
    } else {
        0L
    }
    val candidateDays = linkedSetOf<Long>()
    (listOf(0L) + config.subAlarms.map { it.signedOffsetMinutes.toLong() }).forEach { ringOffset ->
        val totalOffsetMillis = (prayerOffset + ringOffset) * 60_000L
        val firstDay = startOfDayMillis(dayStart - totalOffsetMillis)
        val lastDay = (day.clone() as Calendar).apply {
            timeInMillis = startOfDayMillis(dayEnd - 1L - totalOffsetMillis)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val candidate = (day.clone() as Calendar).apply {
            timeInMillis = firstDay
            add(Calendar.DAY_OF_YEAR, -1)
        }
        while (candidate.timeInMillis <= lastDay) {
            candidateDays.add(candidate.timeInMillis)
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    if (isOneOff) {
        candidateDays.add(startOfDayMillis(config.mainAlarm.oneOffTriggerAtMillis))
    }
    // Each candidate date as its prayer day of instants at the saved place (6.6), by its day offset from [day].
    val zone = java.time.ZoneId.systemDefault()
    val firstDate = java.time.Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate()
    val dayCalendar = (day.clone() as Calendar).apply { timeInMillis = dayStart }
    val prayerDays = candidateDays.sorted().flatMap { dateMillis ->
        val date = java.time.Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val offset = java.time.temporal.ChronoUnit.DAYS.between(firstDate, date).toInt()
        WakeAlarmScheduler.loadPrayerDayContexts(context, dayCalendar, offset..offset)
    }
    return WakeAlarmComputer.scheduledTriggers(config, prayerDays)
        .filter { trigger -> trigger.triggerAtMillis >= dayStart && trigger.triggerAtMillis < dayEnd }
}

/** «تأكيد الموقع» (6.8): the place a skipped first run chose, until the user confirms it or picks another. */
@Composable
private fun ProvisionalPlaceBanner(onConfirm: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val place = PrefsManager.getPlace(context)
    val label = PlaceResolution.suggestedPlaceLabel(context, place)
    // Inside the page's own 16 dp margins, under the place header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BannerBg)
            .border(1.dp, BannerStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("provisional_place_banner"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (label == null) stringResource(R.string.today_unnamed_suggestion) else stringResource(R.string.today_suggested_place, label),
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BannerText,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = if (label == null) stringResource(R.string.location_confirm_title) else stringResource(R.string.today_confirm_place, label),
                    color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.today_change_place), color = GreenPrimaryDark)
            }
        }
    }
}

/** Two ends are the same setting; a fixed end by its clock alone, its saved offset being the day's (D16). */
private fun sameSilenceEnd(a: SilenceEnd, b: SilenceEnd): Boolean =
    if (a is SilenceEnd.Clock && b is SilenceEnd.Clock) a.time == b.time else a == b

/** A clock picked in the phone's clock for [anchor]'s day, as the place's clock it is stored in (6.6). */
private fun pickedPlaceClock(anchor: PrayerTimelineAnchor?, hour: Int, minute: Int): LocalTime =
    anchor?.placeClockAt(Math.floorDiv(anchor.minutes, 1440) * 1440 + hour * 60 + minute) ?: LocalTime.of(hour, minute)

private fun refreshWakeSilenceConflictEditor(
    context: Context,
    delegationId: Int,
    editorState: WakeSilenceConflictEditorState,
): WakeSilenceConflictEditorState = editorState.copy(
    currentConflict = findWakeSilenceConflictForGuide(
        context = context,
        delegationId = delegationId,
        config = editorState.draftConfig,
        targetPrayer = editorState.originalConflict.silencePrayer,
        stagedSilenceConfig = editorState.draftSilenceConfig,
    ),
    hasEditedSilenceWindow = true,
    editRevision = editorState.editRevision + 1,
)

/**
 * Stores a prayer's window as the user left it (plan 6.9): only the endpoint that moved, so the other keeps following
 * its default (D12, A18); a Dhuhr or Jumu'a endpoint moved back to its default follows it again; and a fixed end's
 * offset from the adhan on the day shown ([anchor], else today), which D16 falls back on.
 */
private fun persistPrayerSilenceConfig(
    context: Context,
    prayer: Prayer,
    config: PrayerSilenceConfig,
    anchor: PrayerTimelineAnchor? = null,
) {
    val place = PrayerTimesRepository.place(context)
    val inm = place is PrayerPlace.Inm
    val previous = SilenceEndpoints.withDefaults(PrefsManager.getSilenceRule(context, prayer), prayer, inm)
    val defaults = SilenceEndpoints.withDefaults(SilenceRule(SilenceStart.Unset, SilenceEnd.Unset), prayer, inm)
    val next = SilenceRule.of(config)
    val followsDefaults = prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA
    fun save(moved: Boolean, isDefault: Boolean) = when {
        !moved -> PrefsManager.EndpointSave.KEEP
        followsDefaults && isDefault -> PrefsManager.EndpointSave.CLEAR
        else -> PrefsManager.EndpointSave.WRITE
    }
    val shownOn = anchor ?: timelineAnchorFor(context, prayer, LocalDate.now(place.zone), place)
    val savedOffset = if (config.endRuleMode() == EndpointRuleMode.FIXED_TIME) {
        shownOn?.let { it.offsetAt(resolvePrayerTimelineWindow(it, config).endMinutes) }
    } else null
    PrefsManager.setConfig(
        context, prayer, config,
        start = save(next.start != previous.start, next.start == defaults.start),
        end = save(!sameSilenceEnd(next.end, previous.end), sameSilenceEnd(next.end, defaults.end)),
        savedEndOffsetMinutes = savedOffset,
    )
    AnalyticsTracker.silenceConfigChanged(
        context = context,
        prayer = prayer,
        mode = config.mode,
        durationMinutes = config.afterMinutes,
        delayMode = config.delayMode,
    )
}

private fun findWakeSilenceConflictForGuide(
    context: Context,
    delegationId: Int,
    config: PrayerWakeConfig,
    targetPrayer: Prayer,
    stagedSilenceConfig: PrayerSilenceConfig? = null,
): WakeSilenceConflictSummary? {
    if (!config.enabled || !PrefsManager.isEnabled(context)) return null

    val now = Calendar.getInstance()
    val prayerDays = WakeAlarmScheduler.loadPrayerDayContexts(context, now)
    // The windows the scheduler arms (6.9), with the editor's draft in place of the target prayer's.
    val planner = SilenceScheduler.plannerWithDraft(context, targetPrayer, stagedSilenceConfig)

    return WakeAlarmComputer.compute(now, config, prayerDays)
        .allTriggers
        .asSequence()
        .mapNotNull { trigger ->
            planner.containing(java.time.Instant.ofEpochMilli(trigger.triggerAtMillis))
                .firstOrNull { window -> window.prayer == targetPrayer }
                ?.let { window ->
                    WakeSilenceConflictSummary(
                        detail = context.getString(
                            R.string.wake_editor_warning_silence_overlap,
                            wakeGuideTriggerLabel(context, trigger),
                            formatWakeAlarmDateTime(trigger.triggerAtMillis),
                            prayerName(context, window.prayer),
                            formatWakeAlarmDateTime(window.start.toEpochMilli()),
                            formatWakeAlarmDateTime(window.end.toEpochMilli()),
                        ),
                        silencePrayer = window.prayer,
                        silenceStartAtMillis = window.start.toEpochMilli(),
                        silenceEndAtMillis = window.end.toEpochMilli(),
                        triggerAtMillis = trigger.triggerAtMillis,
                    )
                }
        }
        .firstOrNull()
}

private fun wakeGuideTriggerLabel(
    context: Context,
    trigger: WakeAlarmComputer.ScheduledWakeTrigger,
): String = if (trigger.isSubAlarm) {
    context.getString(
        R.string.wake_editor_warning_trigger_subalarm,
        context.getString(
            if (trigger.signedOffsetMinutes < 0) {
                R.string.wake_alarm_offset_before
            } else {
                R.string.wake_alarm_offset_after
            },
            formatArabicMinutes(abs(trigger.signedOffsetMinutes)),
        ),
    )
} else {
    context.getString(R.string.wake_editor_warning_trigger_main)
}

private fun formatWakeAlarmDateTime(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("EEE d MMM - HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun formatWakeAlarmDate(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun formatClockTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

private fun minuteOfDay(targetTimeInMillis: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE)
    )
}
