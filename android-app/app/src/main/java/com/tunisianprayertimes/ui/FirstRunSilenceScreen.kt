package com.tunisianprayertimes.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.MosqueMethodChoices
import com.tunisianprayertimes.mosque.MosqueReadiness
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choosing a radio option previews the mode. The owning setup activity enables it on explicit confirmation. */
@Composable
internal fun FirstRunSilenceScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onContinue)
    val supportsMosques = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    var selected by rememberSaveable {
        mutableStateOf(MosqueSilenceSettings.mode(context)?.takeIf { supportsMosques } ?: AutomaticSilenceMode.TIME_ONLY)
    }
    var awaitingSetup by remember { mutableStateOf(false) }
    var returnedFromSetup by rememberSaveable { mutableStateOf(false) }
    var confirmPlace by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    val savedMode = remember(revision) { MosqueSilenceSettings.mode(context) }
    val requested = remember(revision) { PrefsManager.isAutomaticSilenceRequested(context) }
    var issues by remember(revision) { mutableStateOf<List<Int>?>(null) }
    var checkFailed by remember(revision) { mutableStateOf(false) }
    LaunchedEffect(revision) {
        if (!requested || savedMode == null) return@LaunchedEffect
        try {
            issues = withContext(Dispatchers.IO) { MosqueReadiness.forMode(context, savedMode) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkFailed = true
        }
    }
    val setup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingSetup = false
        returnedFromSetup = true
        revision++
        if (result.resultCode == Activity.RESULT_OK && PrefsManager.isEnabled(context) &&
            MosqueSilenceSettings.mode(context) == selected
        ) finish()
    }
    fun openSetup() {
        if (awaitingSetup) return
        if (!PrefsManager.hasConfirmedPlace(context)) {
            confirmPlace = true
            return
        }
        awaitingSetup = true
        setup.launch(MosqueSettingsActivity.onboardingIntent(context, selected))
    }

    val savedLabel = stringResource(
        if (savedMode == AutomaticSilenceMode.TIME_AND_MOSQUE) R.string.mosque_mode_combined else R.string.mosque_mode_time,
    )
    val firstIssue = issues?.firstOrNull()
    val ready = requested && savedMode != null && issues?.isEmpty() == true && PrefsManager.hasConfirmedPlace(context)
    val status = when {
        requested && (savedMode == null || checkFailed) -> stringResource(R.string.first_silence_check_failed)
        requested && !PrefsManager.hasConfirmedPlace(context) -> stringResource(R.string.mosque_waiting_for_place)
        requested && firstIssue != null -> stringResource(R.string.first_silence_pending, savedLabel, stringResource(firstIssue))
        ready -> stringResource(R.string.first_silence_ready, savedLabel)
        requested -> stringResource(R.string.first_silence_checking)
        returnedFromSetup -> stringResource(R.string.first_silence_off)
        else -> null
    }
    val currentChoiceReady = ready && savedMode == selected
    val title = stringResource(R.string.first_silence_title)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(BgCream).safeDrawingPadding()
            .testTag("onboarding_silence").semantics { paneTitle = title },
        contentAlignment = Alignment.Center,
    ) {
        // Keep the action in reach on tall phones. In a shallow window the whole page can scroll.
        val pinActions = maxHeight >= 480.dp && maxHeight.value / LocalDensity.current.fontScale >= 400f
        val sidePadding = 20.dp
        val scroll = rememberScrollState()
        Column(
            Modifier.widthIn(max = 504.dp).fillMaxSize()
                .then(if (pinActions) Modifier else Modifier.verticalScroll(scroll)),
        ) {
            Column(
                Modifier.then(if (pinActions) Modifier.weight(1f).verticalScroll(scroll) else Modifier)
                    .fillMaxWidth().padding(horizontal = sidePadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FirstRunHeader(
                    title = title,
                    description = if (requested) null else stringResource(R.string.first_silence_description),
                    art = FirstRunArt.Silence,
                    titleModifier = Modifier.testTag("onboarding_silence_title"),
                )
                if (status != null) {
                    Text(
                        text = status,
                        modifier = Modifier.fillMaxWidth()
                            .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("onboarding_silence_status")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PrayerSilencePalette.PrimaryText,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.first_silence_methods),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimaryDark,
                    )
                    MosqueMethodChoices(selected, supportsMosques) { selected = it }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = sidePadding), color = CardBorder)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val primaryLabel = when {
                    currentChoiceReady -> R.string.first_silence_continue
                    requested && savedMode == selected -> R.string.first_silence_resume
                    else -> R.string.first_silence_setup
                }
                val setupTag = if (currentChoiceReady) "onboarding_silence_view_times" else "onboarding_silence_setup"
                Button(
                    onClick = { if (currentChoiceReady) finish() else openSetup() },
                    enabled = !awaitingSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(setupTag),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                ) {
                    Text(
                        stringResource(primaryLabel), fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = {
                        PrefsManager.declineFirstRunSilence(context)
                        finish()
                    },
                    enabled = !awaitingSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_silence_continue"),
                    colors = ButtonDefaults.textButtonColors(contentColor = GreenPrimaryDark),
                ) {
                    Text(
                        stringResource(if (requested) R.string.first_silence_turn_off else R.string.first_silence_decline),
                        fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    if (confirmPlace) {
        PrayerPlaceRequiredDialog(onPlaceConfirmed = ::openSetup, onDismiss = { confirmPlace = false })
    }
}
