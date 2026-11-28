package com.tunisianprayertimes.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SUHOOR_TOO_LATE = "فات وقت منبه السحور لهذه الليلة."
private const val SUHOOR_UNAVAILABLE = "لا يتاح منبّه للسحور في موعد الصيام القادم."
private const val FASTING_REMINDER_TIME = "تذكير بعد صلاة المغرب ليلة الصيام"
private enum class FastingPlaceAction { Reminder, Suhoor }

/** What the fasting notification asked «عباداتي» to open. */
internal object FastingRequests {
    var sheet by mutableStateOf(false)
    var suhoor by mutableStateOf(false)
}

/** Turns the reminder on, asking for notifications the first time; off at once. */
@Composable
private fun rememberFastingSwitch(access: ReminderNotificationAccess, onPlaceRequired: () -> Unit, onChanged: () -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    return toggle@{ on ->
        if (!FastingReminder.setEnabled(context, on)) {
            onPlaceRequired()
            return@toggle
        }
        if (on && access.action == ReminderNotificationAction.Request) access.perform()
        onChanged()
    }
}

/** What the card shows, read off the main thread: it reads the prayer days and the alarms. */
private data class FastingCardState(
    val enabled: Boolean,
    val tonight: FastingReminder.Eve?,
    val occasion: String?,
    val lines: List<String>,
    val suhoorAt: Instant?,
    val ownAlarm: Instant?,
    val fajr: String?,
    /** A suhoor ring still fits before Fajr tonight. */
    val canSuhoor: Boolean = false,
)

private fun fastingCardState(context: android.content.Context, now: Instant = Instant.now()): FastingCardState {
    val enabled = FastingReminder.isEnabled(context)
    val eve = FastingReminder.tonight(context, now)
    val chosenDay = eve != null && FastingReminder.isChosen(context, eve)
    val tonight = eve?.takeIf { enabled && chosenDay && !it.paused && PrefsManager.canUsePlaceForAutomation(context) }
    return when {
        tonight != null -> {
            val suhoorAt = FastingReminder.suhoorAt(context, tonight, now)
            FastingCardState(
                enabled = true, tonight = tonight,
                occasion = FastingReminder.title(context, tonight, now),
                // Away from the saved place, the eve says so too: its reminder follows that place's Maghrib.
                lines = listOfNotNull(FastingReminder.PLACE_ELSEWHERE_LINE.takeIf { FastingReminder.placeElsewhere(context, now) }),
                suhoorAt = suhoorAt,
                ownAlarm = if (suhoorAt == null) FastingReminder.ownAlarm(context, tonight) else null,
                fajr = tonight.fajr?.let { FastingReminder.clockOf(context, it) },
                canSuhoor = suhoorAt == null && FastingReminder.scheduledSuhoor(context, now) == null &&
                    tonight.fajr?.let { FastingReminder.suhoorOptions(it, now).isNotEmpty() } == true,
            )
        }
        !enabled -> FastingCardState(
            enabled = false, tonight = null,
            occasion = if (eve != null && chosenDay) FastingReminder.title(context, eve, now) else null,
            lines = listOf(FASTING_REMINDER_TIME),
            suhoorAt = null, ownAlarm = null, fajr = null,
        )
        else -> FastingCardState(
            enabled = enabled, tonight = null, occasion = null,
            lines = FastingReminder.statusLines(context, now), suhoorAt = null, ownAlarm = null, fajr = null,
        )
    }
}

/**
 * The fasting card of «عباداتي»: off, it shows the reminder time and names an upcoming fast; on, it gives
 * the next reminder or why there is none; on the eve itself, the intention's deadline and the suhoor alarm.
 */
@Composable
internal fun FastingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); revision++ }
    }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var editTonightsAlarm by rememberSaveable { mutableStateOf(false) }
    var suhoorSheet by remember { mutableStateOf<FastingReminder.Eve?>(null) }
    var placeAction by rememberSaveable { mutableStateOf<FastingPlaceAction?>(null) }
    val access = rememberReminderNotificationAccess(listOf(FastingReminder.CHANNEL_ID)) {
        if (FastingReminder.isEnabled(context)) FastingReminder.refreshLater(context)
        revision++
    }
    val switch = rememberFastingSwitch(access, onPlaceRequired = { placeAction = FastingPlaceAction.Reminder }) { revision++ }
    val state by produceState<FastingCardState?>(initialValue = null, revision) {
        value = withContext(Dispatchers.IO) { fastingCardState(context) }
    }
    val setSuhoor: (FastingReminder.Eve, Int, FajrAlarmPersistence) -> Unit = { target, minutes, level ->
        scope.launch {
            val set = withContext(Dispatchers.IO) {
                val current = FastingReminder.eve(context, target.fastDay)
                current.fajr == target.fajr && FastingReminder.isChosen(context, current) &&
                    FastingReminder.setSuhoor(context, current, minutes = minutes, level = level)
            }
            if (!set) {
                Toast.makeText(context, "تعذّر ضبط منبّه السحور. حاول مرة أخرى.", Toast.LENGTH_SHORT).show()
            }
            revision++
        }
    }
    // One tap once chosen, with alarm access and the chosen minutes still in time; the questions otherwise.
    val askSuhoor: (FastingReminder.Eve) -> Unit = ask@{ target ->
        if (!PrefsManager.hasConfirmedPlace(context)) {
            placeAction = FastingPlaceAction.Suhoor
            return@ask
        }
        val options = target.fajr?.let { FastingReminder.suhoorOptions(it) }.orEmpty()
        val minutes = FastingReminder.suhoorMinutes(context)
        val level = FastingReminder.suhoorLevel(context)
        if (FastingReminder.suhoorChosen(context) && minutes in options && suhoorWakeAccessReady(context, level)
        ) {
            setSuhoor(target, minutes, level)
        } else if (options.isNotEmpty()) {
            editTonightsAlarm = true
        } else {
            // The button is checked once a minute: a tap can come after the last ring that fits.
            Toast.makeText(context, SUHOOR_TOO_LATE, Toast.LENGTH_SHORT).show()
            revision++
        }
    }
    // Resolve the new city's coming Fajr when the editor is opened or recreated. Only Save arms it.
    LaunchedEffect(editTonightsAlarm) {
        if (!editTonightsAlarm) {
            suhoorSheet = null
            return@LaunchedEffect
        }
        val eve = withContext(Dispatchers.IO) { FastingReminder.tonight(context) }
        if (eve?.fajr != null && FastingReminder.suhoorOptions(eve.fajr).isNotEmpty()) {
            suhoorSheet = eve
        } else {
            editTonightsAlarm = false
            sheet = true
            Toast.makeText(context, "لا يتاح منبه السحور لهذه الليلة في مدينتك.", Toast.LENGTH_LONG).show()
        }
    }
    // From the notification: the sheet, or the suhoor questions.
    LaunchedEffect(FastingRequests.sheet) {
        if (FastingRequests.sheet) {
            FastingRequests.sheet = false
            placeAction = null
            editTonightsAlarm = false
            sheet = true
        }
    }
    LaunchedEffect(FastingRequests.suhoor) {
        if (FastingRequests.suhoor) {
            sheet = false
            if (!PrefsManager.hasConfirmedPlace(context)) {
                FastingRequests.suhoor = false
                placeAction = FastingPlaceAction.Suhoor
                return@LaunchedEffect
            }
            // Cleared only after the read: clearing the key first would restart this effect and cancel it.
            val eve = withContext(Dispatchers.IO) { FastingReminder.tonight(context) }
            FastingRequests.suhoor = false
            eve?.let(askSuhoor)
        }
    }

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, CardBorder), shape)
            .clickable(role = Role.Button, onClickLabel = "إعدادات التذكير") { sheet = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_adhkar_moon), null, Modifier.size(22.dp), tint = GreenPrimary)
            Text(stringResource(R.string.access_fasting_reminder), Modifier.weight(1f).semantics { heading() }, color = PrayerNameColor,
                fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(18.dp), tint = TextMuted)
            ReminderSwitchDivider()
            Switch(
                checked = state?.enabled ?: FastingReminder.isEnabled(context),
                onCheckedChange = switch,
                modifier = Modifier.semantics { contentDescription = "تذكير صيام التطوع" },
            )
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val current = state
            current?.occasion?.let { Text(it, color = PrayerNameColor, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold) }
            when {
                current == null -> Unit
                current.tonight != null -> {
                    Text(listOfNotNull("نية الصيام قبل الفجر", current.fajr).joinToString(" · "),
                        color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    when {
                        current.suhoorAt != null -> {
                            Text("منبه السحور ${FastingReminder.clockOf(context, current.suhoorAt)}",
                                color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                            val activity = LocalActivity.current as? AppCompatActivity
                            if (activity != null && current.suhoorAt.isAfter(Instant.now())) {
                                WakeAlarmAccessNotice(activity, saved = true,
                                    awakeCheckEnabled = FastingReminder.suhoorLevel(context) != FajrAlarmPersistence.GENTLE)
                            }
                        }
                        current.ownAlarm != null -> Text(
                            "موعد منبهك ${FastingReminder.clockOf(context, current.ownAlarm)}",
                            color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp,
                        )
                        current.canSuhoor -> OutlinedButton(
                            onClick = { askSuhoor(current.tonight) },
                            border = BorderStroke(1.dp, GreenPrimary.copy(alpha = .5f)),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.padding(top = 4.dp).heightIn(min = 40.dp),
                        ) {
                            Text("أيقظني للسحور", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp))
                        }
                        else -> Unit
                    }
                    current.lines.forEach { Text(it, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) }
                }
                !current.enabled || access.action == ReminderNotificationAction.None ->
                    current.lines.forEach { Text(it, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) }
            }
            if (current?.enabled == true) ReminderNotificationsWarning(access, "تذكير الصيام")
        }
    }

    if (sheet) FastingSheet(access, revision, setSuhoor, onDismiss = { sheet = false }, onChanged = { revision++ })
    suhoorSheet?.takeIf { editTonightsAlarm }?.let { target ->
        val fajr = target.fajr ?: return@let
        SuhoorAlarmSetupSheet(
            fajrAtMillis = fajr.toEpochMilli(),
            initialMinutes = FastingReminder.suhoorMinutes(context),
            initialPersistence = FastingReminder.suhoorLevel(context),
            buttonLabel = "إضافة منبه السحور",
            onSave = { minutes, level -> setSuhoor(target, minutes, level) },
            onDismiss = { editTonightsAlarm = false },
        )
    }
    placeAction?.let { action ->
        PrayerPlaceRequiredDialog(
            onPlaceConfirmed = {
                revision++
                if (action == FastingPlaceAction.Suhoor) editTonightsAlarm = true else sheet = true
            },
            onDismiss = { placeAction = null },
        )
    }
}

private data class FastingSheetContent(
    val lines: List<String> = emptyList(),
    val nextSuhoor: FastingReminder.Eve? = null,
    val scheduledSuhoor: FastingReminder.ScheduledSuhoor? = null,
)

private fun LocalDate.suhoorDayLabel(): String = "${FastingReminder.dayName(dayOfWeek)} ${dayMonth()}"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FastingSheet(
    access: ReminderNotificationAccess,
    alarmRevision: Int,
    onAddSuhoor: (FastingReminder.Eve, Int, FajrAlarmPersistence) -> Unit,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    val changed = { revision++; onChanged() }
    var placeAction by rememberSaveable { mutableStateOf<FastingPlaceAction?>(null) }
    val needReminderPlace = { placeAction = FastingPlaceAction.Reminder }
    val switch = rememberFastingSwitch(access, needReminderPlace, changed)
    val enabled = remember(revision) { FastingReminder.isEnabled(context) }
    val fasts = remember(revision) { FastingReminder.fasts(context) }
    val placeConfirmed = PrefsManager.hasConfirmedPlace(context)
    val content by produceState(FastingSheetContent(), revision, alarmRevision) {
        value = withContext(Dispatchers.IO) {
            val now = Instant.now()
            val scheduled = FastingReminder.scheduledSuhoor(context, now)
            FastingSheetContent(
                lines = FastingReminder.statusLines(context, now),
                nextSuhoor = if (enabled && placeConfirmed && scheduled == null) FastingReminder.nextSuhoorEve(context, now) else null,
                scheduledSuhoor = scheduled,
            )
        }
    }
    val paused = remember(revision, alarmRevision) { FastingReminder.isPaused(context) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var suhoorDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var suhoorTarget by remember { mutableStateOf<FastingReminder.Eve?>(null) }
    LaunchedEffect(suhoorDay) {
        val day = suhoorDay
        suhoorTarget = day?.let {
            withContext(Dispatchers.IO) {
                FastingReminder.eve(context, LocalDate.ofEpochDay(it)).takeIf { eve ->
                    eve.reminds && FastingReminder.isChosen(context, eve) &&
                        FastingReminder.suhoorOptions(eve.fajr!!).isNotEmpty()
                }
            }
        }
        if (day != null && suhoorTarget == null) {
            Toast.makeText(context, SUHOOR_UNAVAILABLE, Toast.LENGTH_SHORT).show()
            suhoorDay = null
        }
    }
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("صيام التطوع", color = PrayerNameColor, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text(FASTING_REMINDER_TIME, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
                }
                AppInfoButton(title = "صيام التطوع", onClick = { showInfo = true }, modifier = Modifier.testTag("fasting_info"))
                Switch(checked = enabled, onCheckedChange = switch,
                    modifier = Modifier.semantics { contentDescription = "تذكير صيام التطوع" })
            }
            if (enabled) {
                Text("أيام الصيام", color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FastingReminder.Fast.entries.forEach { fast ->
                        FilterChip(
                            selected = fast in fasts,
                            onClick = { FastingReminder.setFast(context, fast, fast !in fasts); changed() },
                            label = { Text(fast.label, fontSize = 14.sp) },
                            leadingIcon = if (fast in fasts) {
                                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary.copy(alpha = 0.12f)),
                        )
                    }
                }
                ReminderNotificationsWarning(access, "تذكير الصيام")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (access.action == ReminderNotificationAction.None) {
                        content.lines.forEach { Text(it, color = TextDark, fontSize = 14.sp, lineHeight = 21.sp) }
                    }
                    if (!PrefsManager.canUsePlaceForAutomation(context)) {
                        TextButton(onClick = needReminderPlace) { Text("اختيار المدينة", color = GreenPrimary) }
                    }
                    if (paused) {
                        TextButton(onClick = {
                            if (PrefsManager.hasConfirmedPlace(context)) {
                                FastingReminder.resume(context)
                                changed()
                            } else needReminderPlace()
                        }) {
                            Text("استأنف التذكير", color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { FastingReminder.pauseWeek(context) }
                                    changed()
                                    Toast.makeText(context, "أوقفنا التذكير أسبوعًا.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            border = BorderStroke(1.dp, GreenPrimary.copy(alpha = .4f)),
                        ) { Text("أوقف التذكير أسبوعًا", color = GreenPrimary) }
                    }
                }
                val scheduled = content.scheduledSuhoor
                val next = content.nextSuhoor
                if (scheduled != null || next != null || !paused && !placeConfirmed) {
                    Column(
                        Modifier.fillMaxWidth().border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (scheduled != null) {
                            Text("منبّه السحور مضبوط ليوم ${scheduled.fastDay.suhoorDayLabel()}، الساعة ${FastingReminder.clockOf(context, scheduled.ringsAt)}.",
                                color = TextDark, fontSize = 14.sp, lineHeight = 21.sp)
                            val activity = LocalActivity.current as? AppCompatActivity
                            if (activity != null) {
                                WakeAlarmAccessNotice(activity, saved = true,
                                    awakeCheckEnabled = FastingReminder.suhoorLevel(context) != FajrAlarmPersistence.GENTLE)
                            }
                        } else {
                            val question = if (next != null) "هل تريد منبّهًا للسحور يوم ${next.fastDay.suhoorDayLabel()}؟"
                                else "هل تريد منبّهًا للسحور؟"
                            Text(question, color = TextDark, fontSize = 14.sp, lineHeight = 21.sp)
                            OutlinedButton(
                                onClick = {
                                    if (!placeConfirmed) placeAction = FastingPlaceAction.Suhoor
                                    else suhoorDay = next?.fastDay?.toEpochDay()
                                },
                                border = BorderStroke(1.dp, GreenPrimary.copy(alpha = .4f)),
                            ) { Text("أضف منبّهًا", color = GreenPrimary) }
                        }
                    }
                }
            }
        }
    }
    if (showInfo) FastingInfoSheet(onDismiss = { showInfo = false })
    placeAction?.let { action ->
        PrayerPlaceRequiredDialog(
            onPlaceConfirmed = {
                changed()
                if (action == FastingPlaceAction.Suhoor) scope.launch {
                    val next = withContext(Dispatchers.IO) { FastingReminder.nextSuhoorEve(context) }
                    if (next != null) suhoorDay = next.fastDay.toEpochDay()
                    else Toast.makeText(context, SUHOOR_UNAVAILABLE, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { placeAction = null },
        )
    }
    suhoorTarget?.takeIf { it.fastDay.toEpochDay() == suhoorDay }?.let { target ->
        val fajr = target.fajr ?: return@let
        SuhoorAlarmSetupSheet(
            fajrAtMillis = fajr.toEpochMilli(),
            initialMinutes = FastingReminder.suhoorMinutes(context),
            initialPersistence = FastingReminder.suhoorLevel(context),
            buttonLabel = "إضافة منبه السحور",
            onSave = { minutes, level -> onAddSuhoor(target, minutes, level) },
            onDismiss = { suhoorDay = null },
            fastDayLabel = target.fastDay.suhoorDayLabel(),
        )
    }
}

/**
 * The line between a row that opens a reminder's settings and the switch that turns it on or off, so the two read as
 * two controls (as in the phone's own settings).
 */
@Composable
internal fun ReminderSwitchDivider() {
    Box(Modifier.padding(end = 12.dp).width(1.dp).height(32.dp).background(CardBorder))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FastingInfoSheet(onDismiss: () -> Unit) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("صيام التطوع", color = PrayerNameColor, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text("موعد التذكير", color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(
                "لا نذكّرك بصيام أيام رمضان، فصيامها فرض، ولا بصيام العيدين وأيام التشريق، فهي لا تُصام. " +
                    "وما دام موعدها لم يُعلَن، أو كنت خارج تونس، فلا نذكّرك أيضًا بصيام اليوم الذي يسبقها أو يليها، " +
                    "كيوم عرفة والثاني من شوال.",
                color = TextDark, fontSize = 14.sp, lineHeight = 22.sp,
            )
            Text("قد يتقدّم موعد تاسوعاء وعاشوراء والأيام البيض أو يتأخّر يومًا، إذ لا يصلنا إلا إعلان رمضان والعيدين.",
                color = TextDark, fontSize = 14.sp, lineHeight = 22.sp)
            HorizontalDivider(color = Gold.copy(alpha = 0.18f))
            Text("النية", color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("النية أن تعزم الليلة على صيام الغد، ولا تحتاج إلى لفظ. ولا يضرّك أن تأكل أو تنام بعدها.",
                color = TextDark, fontSize = 14.sp, lineHeight = 22.sp)
            Text("لماذا هذه الأيام؟", color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            FastingReason("تُعرض الأعمال يومي الاثنين والخميس، وكان النبي ﷺ يحبّ أن يُعرض عمله وهو صائم.", "سنن الترمذي 747")
            FastingReason("والاثنين يوم وُلد فيه النبي ﷺ، ويوم أُنزل عليه الوحي.", "صحيح مسلم 1162")
            FastingReason(
                "ورجا النبي ﷺ أن يكفّر الله بصيام يوم عرفة السنة التي قبله والتي بعده، وبصيام عاشوراء السنة التي قبله.",
                "صحيح مسلم 1162",
            )
            FastingReason("وعزم ﷺ على صيام التاسع إن بقي إلى العام القادم، فيُصام تاسوعاء مع عاشوراء.", "صحيح مسلم 1134")
            FastingReason("وشرب ﷺ اللبن يوم عرفة وهو واقف بها، فالفطر فيه أفضل للحاج.", "صحيح البخاري 1988")
            FastingReason(
                "وأمر ﷺ أبا ذرّ، إن صام من الشهر ثلاثة أيام، أن يصوم الثالث عشر والرابع عشر والخامس عشر، وهي الأيام البيض.",
                "سنن الترمذي 761",
            )
            FastingReason("وأخبر ﷺ أن من صام رمضان ثم أتبعه ستة أيام من شوال كان كمن صام الدهر.", "صحيح مسلم 1164")
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("إغلاق", color = GreenPrimary) }
        }
    }
}

@Composable
private fun FastingReason(text: String, source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, color = TextDark, fontSize = 14.sp, lineHeight = 22.sp)
        Text(source, color = TextMuted, fontSize = 12.sp)
    }
}

/** Shown only while the reminder cannot reach the reader, with the one way to allow it. */
@Composable
internal fun ReminderNotificationsWarning(access: ReminderNotificationAccess, channelName: String) {
    if (access.action == ReminderNotificationAction.None) return
    val problem = when (access.action) {
        ReminderNotificationAction.GroupSettings -> "مجموعة إشعارات «$channelName» متوقّفة في إعدادات الهاتف."
        ReminderNotificationAction.ChannelSettings -> "إشعارات «$channelName» متوقّفة في إعدادات الهاتف."
        else -> "إشعارات التطبيق متوقّفة. إعدادات تذكيرك محفوظة."
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(problem, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        TextButton(onClick = { access.perform() }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (access.action == ReminderNotificationAction.Request) "السماح بالإشعارات" else "إعدادات الإشعارات")
        }
    }
}
