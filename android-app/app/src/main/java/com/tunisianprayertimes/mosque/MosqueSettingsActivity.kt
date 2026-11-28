package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.mosque.presence.*
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.AppInfoButton
import com.tunisianprayertimes.ui.PrayerPlaceRequiredDialog
import com.tunisianprayertimes.ui.PrayerSettingsDivider
import com.tunisianprayertimes.ui.PrayerSettingsGroup
import com.tunisianprayertimes.ui.PrayerSettingsLink
import com.tunisianprayertimes.ui.PrayerSilencePalette
import com.tunisianprayertimes.ui.rememberNotificationAccessAction
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Also the system Modes configuration activity. Opening it never activates an unrequested rule. */
class MosqueSettingsActivity : ComponentActivity() {
    companion object {
        private const val OPEN_SETUP = "com.tunisianprayertimes.mosque.OPEN_SETUP"
        private const val ONBOARDING_SETUP = "com.tunisianprayertimes.mosque.ONBOARDING_SETUP"
        private const val ENABLE_SETUP = "com.tunisianprayertimes.mosque.ENABLE_SETUP"
        private const val ACTIVATION_MODE = "onboarding_mode"
        private const val RULE_OWNER = "rule_owner"
        internal fun setupIntent(context: Context, owner: SilenceOwner? = null) =
            Intent(context, MosqueSettingsActivity::class.java).setAction(OPEN_SETUP)
                .apply { owner?.let { putExtra(RULE_OWNER, it.name) } }

        internal fun onboardingIntent(context: Context, mode: AutomaticSilenceMode) =
            Intent(context, MosqueSettingsActivity::class.java).setAction(ONBOARDING_SETUP)
                .putExtra(ACTIVATION_MODE, mode.name)

        internal fun enableIntent(context: Context, mode: AutomaticSilenceMode) =
            Intent(context, MosqueSettingsActivity::class.java).setAction(ENABLE_SETUP)
                .putExtra(ACTIVATION_MODE, mode.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firstRun = intent.action == ONBOARDING_SETUP
        val activationMode = if (firstRun || intent.action == ENABLE_SETUP) {
            AutomaticSilenceMode.entries.firstOrNull { it.name == intent.getStringExtra(ACTIVATION_MODE) }
        } else null
        setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                Surface(Modifier.fillMaxSize(), color = PrayerSilencePalette.Surface) {
                    val owner = remember { SilenceOwner.entries.firstOrNull { it.name == intent.getStringExtra(RULE_OWNER) } }
                    MosqueSettingsScreen(
                        openSetup = activationMode != null || intent.action == OPEN_SETUP &&
                            (owner != null || MosqueSilenceSettings.mode(this) != null),
                        repairOwner = owner.takeIf { intent.action == OPEN_SETUP },
                        activationMode = activationMode,
                        firstRun = firstRun && activationMode != null,
                        onBack = ::finish,
                        onActivated = { setResult(RESULT_OK); finish() },
                    )
                }
            }
        }
    }
}

private enum class MosqueSettingsPage(val title: Int) {
    Method(R.string.mosque_settings_title),
    Setup(R.string.mosque_readiness_title),
    Areas(R.string.mosque_areas_title),
}

private data class MosqueSettingsSnapshot(
    val mode: AutomaticSilenceMode?,
    val issues: List<Int>,
    val disabledRuleId: String?,
    val areas: SilenceAreaRead,
    val state: PresenceState?,
    val periods: MosquePeriods,
    val canUndo: Boolean,
    val releasePending: Boolean,
    val phoneSilent: Boolean,
)

/** Geometry stays in the private draft store. Another editor's newer draft belongs to that editor. */
internal fun activeMosqueDraftSaver(context: Context): Saver<AreaDraft?, String> = Saver(
    save = { it?.key },
    restore = { key -> PrivateSilenceAreaStore.readDraft(context)?.takeIf { it.key == key } },
)

@Composable
private fun MosqueSettingsScreen(
    openSetup: Boolean,
    repairOwner: SilenceOwner?,
    activationMode: AutomaticSilenceMode?,
    firstRun: Boolean,
    onBack: () -> Unit,
    onActivated: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(MosqueSilenceSettings.mode(context)) }
    var enabled by remember { mutableStateOf(PrefsManager.isEnabled(context)) }
    var masterRequested by remember { mutableStateOf(PrefsManager.isAutomaticSilenceRequested(context)) }
    var checkingActivation by remember { mutableStateOf(false) }
    var activationCommitted by rememberSaveable { mutableStateOf(false) }
    var schedulingActivation by rememberSaveable { mutableStateOf(false) }
    if (firstRun) {
        var pending by remember { mutableStateOf(PrefsManager.isFirstRunSilencePending(context)) }
        DisposableEffect(context) {
            fun reload() { pending = PrefsManager.isFirstRunSilencePending(context) }
            val unsubscribe = PrefsManager.observeFirstRunDecisions(context, ::reload)
            reload()
            onDispose { unsubscribe() }
        }
        if (!pending && !activationCommitted) {
            LaunchedEffect(Unit) { onBack() }
            return
        }
    }
    if (activationMode != null) {
        // The user chose setup. Returning from Android, even after a denial, stays on the checklist.
        var checkedInitialDnd by rememberSaveable { mutableStateOf(false) }
        LifecycleResumeEffect(activationMode) {
            if (!checkedInitialDnd) {
                checkedInitialDnd = true
                if (PrefsManager.hasConfirmedPlace(context) &&
                    !context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
                ) SilenceAccessSettings.openDnd(context)
            }
            onPauseOrDispose { }
        }
    }
    var snapshot by remember { mutableStateOf<MosqueSettingsSnapshot?>(null) }
    val areas = snapshot?.areas ?: remember { PrivateSilenceAreaStore.read(context) }
    var page by rememberSaveable { mutableStateOf(if (openSetup) MosqueSettingsPage.Setup else MosqueSettingsPage.Method) }
    var areasParent by rememberSaveable { mutableStateOf(MosqueSettingsPage.Method) }
    var enrollmentFailed by rememberSaveable { mutableStateOf(false) }
    var placeRequired by rememberSaveable { mutableStateOf(false) }
    var resumeSetup by rememberSaveable { mutableStateOf(openSetup) }
    var pendingCombined by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    var warmed by remember { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SavedSilenceArea?>(null) }
    var editor by rememberSaveable(stateSaver = remember(context) { activeMosqueDraftSaver(context) }) {
        mutableStateOf<AreaDraft?>(null)
    }
    var pendingEditor by remember { mutableStateOf<Pair<AreaDraft, AreaDraft>?>(null) }
    val activity = LocalActivity.current
    var preciseRequested by rememberSaveable { mutableStateOf(false) }
    var backgroundRequested by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberNotificationAccessAction { refresh++ }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri ->
        val json = exportPreview
        if (uri != null && json != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
            withContext(Dispatchers.Main) { Toast.makeText(context, if (ok) R.string.mosque_export_done else R.string.mosque_save_failed, Toast.LENGTH_LONG).show() }
        }
        exportPreview = null
    }
    fun settings(action: String, packageUri: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply { if (packageUri) data = Uri.parse("package:${context.packageName}") }) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    fun requestPreciseLocation() {
        if (preciseRequested && activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false) {
            settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        } else {
            preciseRequested = true
            foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    fun openEditor(next: AreaDraft) {
        val previous = PrivateSilenceAreaStore.readDraft(context)
        if (previous == null) {
            runCatching { PrivateSilenceAreaStore.saveDraft(context, next) }
                .onSuccess { editor = next }.onFailure { Toast.makeText(context, R.string.mosque_save_failed, Toast.LENGTH_LONG).show() }
        } else pendingEditor = previous to next
    }
    fun reschedule(start: Boolean = false) {
        if (activationMode != null) {
            refresh++
            return
        }
        scope.launch {
            withContext(ScheduleRefreshCoordinator.scheduling) { ScheduleRefreshCoordinator.syncSilence(context) }
            if (start && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) MosqueSilenceMonitor.reconcile(context, canStart = true)
            refresh++
        }
    }
    LifecycleResumeEffect(Unit) {
        if (activationMode == null && repairOwner == null && resumeSetup && PrefsManager.isEnabled(context))
            reschedule(start = MosqueSilenceSettings.combined(context))
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { PrayerTimesRepository.warm(context.applicationContext) }
        warmed = true
    }
    LaunchedEffect(refresh, warmed, lifecycle) {
        if (!warmed) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Only a visible activity resumes acquisition; a lost visit still needs the explicit button.
            if (activationMode == null && repairOwner == null) MosqueSilenceMonitor.reconcile(context, canStart = true)
            while (true) {
                snapshot = withContext(Dispatchers.Default) {
                    val currentMode = activationMode ?: MosqueSilenceSettings.mode(context)
                    val saved = PrivateSilenceAreaStore.read(context)
                    val issues = if (repairOwner != null) MosqueReadiness.ownedRuleIssues(context, repairOwner)
                        else MosqueReadiness.forMode(context, currentMode, saved)
                    if (currentMode == AutomaticSilenceMode.TIME_AND_MOSQUE) MosqueMetrics.readiness(context, issues.isEmpty())
                    val owner = repairOwner ?: if (currentMode == AutomaticSilenceMode.TIME_AND_MOSQUE || pendingCombined)
                        SilenceOwner.MOSQUE else SilenceOwner.PRAYER
                    MosqueSettingsSnapshot(
                        mode = currentMode,
                        issues = issues,
                        disabledRuleId = if (R.string.mosque_needs_rules in issues || enrollmentFailed)
                            SilenceCoordinator.disabledOwnedRuleId(context, owner) else null,
                        areas = saved,
                        state = MosqueSilenceMonitor.status(context),
                        periods = AdaptiveMosqueMonitor.periods(context),
                        canUndo = MosquePrayerActions.canUndo(context),
                        releasePending = SilenceCoordinator.releasePending(context),
                        phoneSilent = SilenceStatus.isPhoneSilenced(context),
                    )
                }
                mode = MosqueSilenceSettings.mode(context)
                enabled = PrefsManager.isEnabled(context)
                masterRequested = PrefsManager.isAutomaticSilenceRequested(context)
                delay(2_000)
            }
        }
    }
    val draft = editor
    if (draft != null) {
        AreaEditor(draft, areas.areas, onClose = { editor = null; refresh++ })
        return
    }
    val displayedMode = activationMode ?: mode
    val current = snapshot?.takeIf { it.mode == displayedMode }
    val issues = current?.issues
    val combined = displayedMode == AutomaticSilenceMode.TIME_AND_MOSQUE
    fun ownsCurrentChoice(chosen: AutomaticSilenceMode) =
        activationCommitted && PrefsManager.isEnabled(context) && MosqueSilenceSettings.mode(context) == chosen

    fun activate(chosen: AutomaticSilenceMode) {
        if (checkingActivation || schedulingActivation) return
        if (firstRun && !PrefsManager.isFirstRunSilencePending(context) && !ownsCurrentChoice(chosen)) {
            onBack()
            return
        }
        if (!PrefsManager.hasConfirmedPlace(context)) {
            placeRequired = true
            return
        }
        checkingActivation = true
        scope.launch {
            try {
                val ready = withContext(Dispatchers.Default) { MosqueReadiness.forMode(context, chosen).isEmpty() }
                if (activity?.isFinishing == true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (!ready || !PrefsManager.hasConfirmedPlace(context)) {
                    refresh++
                    return@launch
                }
                if (!activationCommitted) {
                    val wasRequested = PrefsManager.isAutomaticSilenceRequested(context)
                    if (!PrefsManager.activateConfiguredSilence(context, chosen, firstRun)) {
                        if (firstRun && !PrefsManager.isFirstRunSilencePending(context)) onBack()
                        else {
                            Toast.makeText(context, R.string.mosque_save_failed, Toast.LENGTH_LONG).show()
                            refresh++
                        }
                        return@launch
                    }
                    activationCommitted = true
                    if (!wasRequested) AnalyticsTracker.autoSilenceStateChanged(context, true)
                }
                if (ownsCurrentChoice(chosen)) schedulingActivation = true else onBack()
            } finally {
                checkingActivation = false
            }
        }
    }
    LaunchedEffect(schedulingActivation) {
        if (!schedulingActivation || activationMode == null) return@LaunchedEffect
        if (!ownsCurrentChoice(activationMode)) {
            onBack()
            return@LaunchedEffect
        }
        val scheduled = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
            try {
                ScheduleRefreshCoordinator.syncSilence(context.applicationContext) == ScheduleRefreshCoordinator.RESULT_SUCCESS
            } catch (error: RuntimeException) {
                Log.w("MosqueSettings", "Could not schedule the requested silence", error)
                false
            }
        }
        if (!ownsCurrentChoice(activationMode)) {
            onBack()
            return@LaunchedEffect
        }
        if (!scheduled) {
            schedulingActivation = false
            refresh++
            Toast.makeText(context, R.string.mosque_onboarding_schedule_pending, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        if (combined && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            MosqueSilenceMonitor.reconcile(context, canStart = true)
        onActivated()
    }
    fun back() {
        if (schedulingActivation) return
        when (page) {
            MosqueSettingsPage.Method -> onBack()
            MosqueSettingsPage.Areas -> page = areasParent
            MosqueSettingsPage.Setup -> {
                pendingCombined = false
                enrollmentFailed = false
                if (openSetup) onBack() else page = MosqueSettingsPage.Method
            }
        }
    }
    fun showAreas() {
        areasParent = page
        page = MosqueSettingsPage.Areas
    }
    fun selectMode(option: AutomaticSilenceMode) {
        if (option == mode) return
        if (!PrefsManager.hasConfirmedPlace(context)) {
            placeRequired = true
            return
        }
        val mosque = option == AutomaticSilenceMode.TIME_AND_MOSQUE
        if (mosque && !MosqueSilenceSettings.usesOwnedRules(context) && !SilenceCoordinator.enrollOrRepair(context)) {
            pendingCombined = true
            enrollmentFailed = true
            page = MosqueSettingsPage.Setup
            return
        }
        if (SilenceCoordinator.switchMode(context, option)) {
            mode = option
            reschedule(start = mosque)
        } else {
            Toast.makeText(context, R.string.mosque_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = page != MosqueSettingsPage.Method) { back() }
    val title = stringResource(if (page == MosqueSettingsPage.Setup && activationMode != null) {
        if (combined) R.string.mosque_mode_combined else R.string.mosque_mode_time
    } else page.title)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = ::back) {
                Icon(painterResource(R.drawable.ic_adhkar_back), stringResource(R.string.mosque_back),
                    tint = PrayerSilencePalette.PrimaryText)
            }
            Text(
                title, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = PrayerSilencePalette.PrimaryText,
            )
            if (page == MosqueSettingsPage.Areas) {
                AppInfoButton(stringResource(R.string.mosque_area_privacy_title), stringResource(R.string.mosque_privacy))
            } else if (page == MosqueSettingsPage.Setup && combined && repairOwner == null) {
                AppInfoButton(stringResource(R.string.mosque_location_accuracy_title), stringResource(R.string.mosque_provider_note))
            }
        }
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
        pages.SaveableStateProvider(page.name) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (page) {
                    MosqueSettingsPage.Method -> {
                        MosqueMethodChoices(mode, Build.VERSION.SDK_INT >= 29, ::selectMode)
                        MosqueParticipationSettings()
                        if (mode == null) Text(stringResource(R.string.mosque_invalid_mode), color = MaterialTheme.colorScheme.error)
                        if (masterRequested && !enabled) {
                            PrayerSettingsGroup {
                                PrayerSettingsLink(
                                    title = stringResource(R.string.prayer_data_choose_location),
                                    summary = stringResource(R.string.mosque_waiting_for_place),
                                    onClick = { placeRequired = true },
                                )
                                PrayerSettingsDivider()
                                PrayerSettingsLink(
                                    title = stringResource(R.string.mosque_disable_auto),
                                    onClick = {
                                        PrefsManager.setEnabled(context, false)
                                        masterRequested = false
                                        reschedule()
                                    },
                                )
                            }
                        } else if (!masterRequested && mode != null) {
                            PrayerSettingsGroup {
                                PrayerSettingsLink(
                                    title = stringResource(R.string.mosque_enable_auto),
                                    onClick = {
                                        if (PrefsManager.hasConfirmedPlace(context)) {
                                            mode?.let { context.startActivity(MosqueSettingsActivity.enableIntent(context, it)) }
                                        } else placeRequired = true
                                    },
                                )
                            }
                        }
                        PrayerSettingsGroup {
                            PrayerSettingsLink(
                                title = stringResource(R.string.mosque_readiness_title),
                                summary = stringResource(when {
                                    issues == null -> R.string.mosque_setup_checking
                                    issues.isEmpty() -> R.string.mosque_requirements_ready
                                    else -> R.string.mosque_status_setup
                                }),
                                onClick = { resumeSetup = enabled; page = MosqueSettingsPage.Setup },
                            )
                            if (combined) {
                                PrayerSettingsDivider()
                                MosqueBackgroundBatteryLink()
                            }
                            PrayerSettingsDivider()
                            PrayerSettingsLink(
                                title = stringResource(R.string.mosque_areas_title),
                                summary = when {
                                    !areas.available -> stringResource(R.string.mosque_store_unavailable)
                                    areas.areas.isEmpty() -> stringResource(R.string.mosque_saved_areas_empty)
                                    else -> stringResource(R.string.mosque_areas_count, areas.areas.count { it.geometry.enabled }, areas.areas.size)
                                },
                                onClick = ::showAreas,
                            )
                            SilenceWalkSettingsLink()
                        }
                        MosqueSilenceStatus(
                            mode = mode, enabled = enabled, issues = issues,
                            state = current?.state,
                            periods = current?.periods ?: MosquePeriods(null, null, emptyList()),
                            phoneSilent = snapshot?.phoneSilent, releasePending = snapshot?.releasePending == true,
                            canUndo = current?.canUndo == true,
                            onRecheck = { MosqueSilenceMonitor.reconcile(context, canStart = true, deliberate = true); refresh++ },
                            onSkip = { keys ->
                                MosqueSilenceMonitor.skip(context, keys)
                                reschedule(start = true)
                            },
                            onManual = { key -> MosquePrayerActions.manual(context, key); refresh++ },
                            onUndo = { MosquePrayerActions.undo(context); refresh++ },
                        )
                    }
                    MosqueSettingsPage.Setup -> {
                        if (activationMode != null) OnboardingPrayerWindow()
                        MosqueSetupSettings(
                            combined = combined && repairOwner == null, issues = issues, enrollmentFailed = enrollmentFailed,
                            repairOwner = repairOwner,
                            onManageDisabledRule = current?.disabledRuleId?.let { id -> { SilenceAccessSettings.openDisabledRule(context, id) } },
                            onPrepareRules = {
                                if (SilenceCoordinator.enrollOrRepair(context)) {
                                    enrollmentFailed = false
                                    if (activationMode != null) {
                                        refresh++
                                    } else if (pendingCombined) {
                                        pendingCombined = false
                                        page = MosqueSettingsPage.Method
                                        selectMode(AutomaticSilenceMode.TIME_AND_MOSQUE)
                                    } else {
                                        MosqueSilenceMonitor.environmentChanged(context)
                                        reschedule(start = combined)
                                    }
                                } else {
                                    enrollmentFailed = true
                                }
                            },
                            onDnd = { SilenceAccessSettings.openDnd(context) },
                            onPrecise = ::requestPreciseLocation,
                            onBackground = { disclosure = true },
                            onExact = { SilenceAccessSettings.openExactAlarms(context) },
                            onNotifications = { notifications(MosqueObservationService.CHANNEL) },
                            onLocation = { settings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
                            onAppSettings = { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                            onAreas = ::showAreas,
                            showSystemShortcuts = activationMode == null,
                        )
                        if (activationMode != null) {
                            if (activationCommitted && !schedulingActivation) {
                                Text(stringResource(R.string.mosque_onboarding_schedule_pending))
                            }
                            Button(
                                onClick = { activate(activationMode) },
                                enabled = issues?.isEmpty() == true && !checkingActivation && !schedulingActivation,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("onboarding_activate_silence"),
                            ) {
                                Text(stringResource(when {
                                    schedulingActivation -> R.string.mosque_onboarding_scheduling
                                    activationCommitted -> R.string.first_run_retry
                                    else -> R.string.mosque_enable_auto
                                }))
                            }
                            val canDecline = firstRun && !activationCommitted
                            TextButton(
                                onClick = {
                                    if (canDecline) {
                                        PrefsManager.declineFirstRunSilence(context)
                                        onBack()
                                    } else back()
                                },
                                enabled = !checkingActivation && !schedulingActivation,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_postpone_silence"),
                            ) { Text(stringResource(if (canDecline) R.string.first_silence_decline else R.string.mosque_back)) }
                        }
                    }
                    MosqueSettingsPage.Areas -> MosqueAreasSettings(
                        areas = areas, combined = combined,
                        hasDraft = remember(refresh) { PrivateSilenceAreaStore.readDraft(context) != null },
                        onAdd = { openEditor(AreaDraft(null, "", "")) },
                        onResumeDraft = { editor = PrivateSilenceAreaStore.readDraft(context) },
                        onEdit = { area ->
                            openEditor(AreaDraft(area.geometry.id, area.name, "", area.geometry.enabled)
                                .withPolygons(area.geometry.polygons))
                        },
                        onDelete = { deleting = it },
                        onEnabledChange = { area, value ->
                            runCatching { PrivateSilenceAreaStore.setEnabled(context, area.geometry.id, value) }
                                .onFailure { Toast.makeText(context, R.string.mosque_save_failed, Toast.LENGTH_LONG).show() }
                            refresh++
                        },
                        onStrictChange = { area, value ->
                            runCatching { PrivateSilenceAreaStore.setStricter(context, area.geometry.id, value) }
                                .onSuccess { reschedule(start = true) }
                                .onFailure { Toast.makeText(context, R.string.mosque_save_failed, Toast.LENGTH_LONG).show() }
                        },
                        onExport = { exportPreview = PrivateSilenceAreaStore.exportGeoJson(context) },
                    )
                }
            }
        }
    }
    pendingEditor?.let { (previous, next) ->
        AlertDialog(
            onDismissRequest = { pendingEditor = null },
            title = { Text(stringResource(R.string.mosque_existing_draft_title)) },
            text = { Text(stringResource(R.string.mosque_existing_draft_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingEditor = null
                    editor = PrivateSilenceAreaStore.readDraft(context)?.takeIf { it.key == previous.key }
                }) { Text(stringResource(R.string.mosque_restore_draft)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingEditor = null
                    if (PrivateSilenceAreaStore.clearDraft(context, previous.key)) openEditor(next)
                }) { Text(stringResource(R.string.mosque_discard_draft)) }
            },
        )
    }
    if (placeRequired) PrayerPlaceRequiredDialog(onPlaceConfirmed = { refresh++ }) { placeRequired = false }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text(stringResource(R.string.mosque_disclosure_title)) },
        text = {
            Text(stringResource(R.string.mosque_background_disclosure),
                Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = {
            disclosure = false
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine) requestPreciseLocation()
            else {
                MosqueSilenceSettings.acceptBackgroundDisclosure(context)
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT == 29 && (!backgroundRequested ||
                            activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == true)) {
                        backgroundRequested = true
                        background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                    }
                }
                refresh++
            }
        }) { Text(stringResource(R.string.mosque_continue)) } }, dismissButton = { TextButton(onClick = { disclosure = false }) { Text(stringResource(R.string.mosque_cancel)) } })
    deleting?.let { area -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.mosque_delete)) },
        text = { Text(stringResource(R.string.mosque_delete_question, area.name)) },
        confirmButton = { TextButton(onClick = { PrivateSilenceAreaStore.delete(context, area.geometry.id); deleting = null; refresh++ }) { Text(stringResource(R.string.mosque_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.mosque_cancel)) } }) }
    exportPreview?.let { json -> AlertDialog(onDismissRequest = { exportPreview = null }, title = { Text(stringResource(R.string.mosque_export)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.mosque_export_warning)); Text(json, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
        } }, confirmButton = { TextButton(onClick = { export.launch("my-mosque-areas.geojson") }) { Text(stringResource(R.string.mosque_export_save)) } },
        dismissButton = { TextButton(onClick = { exportPreview = null }) { Text(stringResource(R.string.mosque_cancel)) } }) }
}
