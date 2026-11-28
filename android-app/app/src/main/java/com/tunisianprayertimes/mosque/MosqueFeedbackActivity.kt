package com.tunisianprayertimes.mosque

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.PrayerSettingsGroup
import com.tunisianprayertimes.ui.PrayerSettingsLink
import com.tunisianprayertimes.ui.PrayerSettingsSwitch
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MosqueFeedbackActivity : ComponentActivity() {
    companion object {
        internal fun mapIntent(c: Context, id: String?, polygons: List<SilencePolygon>) =
            Intent(c, MosqueFeedbackActivity::class.java)
                .putExtra("page", "boundary")
                .putExtra(
                    "public_id",
                    id?.takeIf { it.startsWith("catalogue:") }?.removePrefix("catalogue:"),
                )
                .putExtra(
                    "geometry",
                    if (
                        polygons.sumOf { p ->
                            (listOf(p.exterior) + p.holes).sumOf { it.size + 1 }
                        } <= 1500
                    )
                        reportGeometry(polygons).toString()
                    else null,
                )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                Surface(Modifier.fillMaxSize()) { FeedbackScreen(intent, ::finish) }
            }
        }
    }
}

@Composable
internal fun MosqueParticipationSettings() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(MosqueReportStore.enabled(c)) }
    var sharingWalks by remember { mutableStateOf(MosqueWalkSharing.since(c) > 0L) }
    PrayerSettingsGroup {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrayerSettingsSwitch(
                title = stringResource(R.string.mr_participation),
                checked = on,
                modifier = Modifier.weight(1f),
                summary =
                    stringResource(
                        if (AnalyticsTracker.mosqueMetricsAllowed(c)) R.string.mr_effect
                        else R.string.mr_questions_only
                    ),
                onCheckedChange = {
                    MosqueMetrics.setEnabled(c, it)
                    on = it
                },
            )
            com.tunisianprayertimes.ui.AppInfoButton(
                stringResource(R.string.mr_data_info),
                stringResource(R.string.mr_data_detail),
            )
        }
        PrayerSettingsSwitch(
            title = stringResource(R.string.mr_share_walks),
            summary = stringResource(R.string.mr_walk_consequence),
            checked = sharingWalks,
            onCheckedChange = {
                MosqueWalkSharing.setEnabled(c, it)
                sharingWalks = MosqueWalkSharing.since(c) > 0L
            },
        )
        PrayerSettingsLink(
            title = stringResource(R.string.mr_feedback),
            onClick = { c.startActivity(Intent(c, MosqueFeedbackActivity::class.java)) },
        )
    }
}

internal val prayerLabels =
    mapOf(
        "FAJR" to "الفجر",
        "DHUHR" to "الظهر",
        "ASR" to "العصر",
        "MAGHRIB" to "المغرب",
        "ISHA" to "العشاء",
        "JOMOAA" to "الجمعة",
        "AID_FITR" to "عيد الفطر",
        "AID_ADHA" to "عيد الأضحى",
    )

@Composable
internal fun MosqueFeedbackInvitation() {
    val c = LocalContext.current
    val question by
        produceState<String?>(null, c) {
            value =
                withContext(Dispatchers.IO) {
                    if (!MosqueReportStore.enabled(c) || MosqueSilenceMonitor.canObserve(c))
                        return@withContext null
                    val store = MosqueReportStore.get(c)
                    val local =
                        store.db.atomic {
                            val pending =
                                store.value("question")?.takeIf { it.isNotEmpty() }
                                    ?: return@atomic null
                            val now = System.currentTimeMillis()
                            if (
                                now - JSONObject(pending).optLong("end") !in 0..86_400_000L ||
                                    now - (store.value("lastQuestion")?.toLongOrNull() ?: 0) <
                                        14 * 86_400_000L
                            )
                                return@atomic null
                            store.put("question", "")
                            store.put("lastQuestion", now.toString())
                            MosqueMetrics.surveyCount(c, "survey_shown")
                            pending
                        } ?: return@withContext null
                    val campaign =
                        runCatching {
                                MosqueReportDelivery.request("/v1/question", "GET", null)
                                    .second
                                    .optJSONObject("question")
                            }
                            .getOrNull()
                    if (!MosqueReportStore.enabled(c)) null
                    else JSONObject(local).put("campaign", campaign).toString()
                }
        }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val raw = question ?: return
    if (dismissed || !MosqueReportStore.enabled(c)) return
    val prayer = JSONObject(raw).optString("prayer")
    val campaign = JSONObject(raw).optJSONObject("campaign")
    val skip = {
        dismissed = true
        MosqueMetrics.surveyCount(c, "survey_skipped")
    }
    AlertDialog(
        onDismissRequest = skip,
        title = {
            Text(
                campaign?.optString("title")
                    ?: stringResource(R.string.mr_question, prayerLabels[prayer].orEmpty())
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dismissed = true
                    c.startActivity(
                        Intent(c, MosqueFeedbackActivity::class.java)
                            .putExtra("page", if (campaign == null) "survey" else "custom")
                            .putExtra("prayer", prayer)
                            .putExtra("campaign", campaign?.toString())
                    )
                }
            ) {
                Text(stringResource(R.string.mr_answer))
            }
        },
        dismissButton = { TextButton(onClick = skip) { Text(stringResource(R.string.mr_skip)) } },
    )
}

@Composable
private fun FeedbackScreen(intent: Intent, close: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(intent.getStringExtra("page") ?: "home") }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var id by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var error by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val back = {
        if (!busy) {
            if (preview != null) preview = null
            else if (page == "home" || page == intent.getStringExtra("page")) close()
            else page = "home"
        }
    }
    BackHandler { back() }
    Column(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = back) { Text(stringResource(R.string.mosque_back)) }
        val title =
            when {
                preview != null -> R.string.mr_review
                else ->
                    when (page) {
                        "missing" -> R.string.mr_missing
                        "boundary" -> R.string.mr_boundary
                        "receipts" -> R.string.mr_receipts
                        "field" -> R.string.mr_field
                        "feedback",
                        "survey",
                        "custom" -> R.string.mr_result
                        else -> R.string.mr_feedback
                    }
            }
        Text(
            stringResource(title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        val draft = preview
        val formState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        if (draft != null) {
            val data = remember(draft) { JSONObject(draft) }
            if (data.getJSONObject("fields").optString("question") == "custom_v1") {
                val campaign = JSONObject(intent.getStringExtra("campaign")!!)
                Text(campaign.getString("title"))
                Text(
                    campaign
                        .getJSONArray("options")
                        .getString(data.getJSONObject("fields").getInt("choice"))
                )
            } else ReportPreview(data)
            if (error)
                Text(stringResource(R.string.mr_error), color = MaterialTheme.colorScheme.error)
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = false
                    scope.launch {
                        val ok =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                        MosqueReportDelivery.submit(
                                            c,
                                            data.getString("kind"),
                                            data.getJSONObject("fields"),
                                            id,
                                        )
                                    }
                                    .isSuccess
                            }
                        busy = false
                        if (ok) {
                            if (page == "survey" || page == "custom")
                                MosqueMetrics.surveyCount(c, "survey_answered")
                            if (page == "field") MosqueReportStore.get(c).put("volunteer_trial", "")
                            formState.removeState(page)
                            preview = null
                            page = "receipts"
                            id = UUID.randomUUID().toString()
                        } else error = true
                    }
                },
            ) {
                Text(stringResource(R.string.mr_send))
            }
            TextButton(enabled = !busy, onClick = { preview = null }) {
                Text(stringResource(R.string.mr_edit))
            }
        } else
            formState.SaveableStateProvider(page) {
                fun review(kind: String, fields: JSONObject) {
                    preview = JSONObject().put("kind", kind).put("fields", fields).toString()
                }
                when (page) {
                    "custom" -> {
                        val campaign = remember { JSONObject(intent.getStringExtra("campaign")!!) }
                        Text(campaign.getString("title"))
                        var choice by rememberSaveable { mutableIntStateOf(-1) }
                        val options = campaign.getJSONArray("options")
                        val labels = List(options.length(), options::getString)
                        PickReportOption(
                            labels.indices.map(Int::toString),
                            labels,
                            choice.toString(),
                        ) {
                            choice = it.toInt()
                        }
                        Button(
                            enabled = choice >= 0,
                            onClick = {
                                review(
                                    "feedback",
                                    JSONObject()
                                        .put("question", "custom_v1")
                                        .put("campaign", campaign.getString("id"))
                                        .put("choice", choice),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.mr_review))
                        }
                    }
                    "feedback",
                    "survey" ->
                        OutcomeForm(page == "survey", intent.getStringExtra("prayer")) {
                            review("feedback", it)
                        }
                    "missing",
                    "boundary" ->
                        MapProposalForm(
                            page,
                            intent.getStringExtra("public_id"),
                            intent.getStringExtra("geometry"),
                            ::review,
                        )
                    "receipts" -> ReceiptList()
                    "field" -> FieldReportForm { review("feedback", it) }
                    else ->
                        listOf(
                                "feedback" to R.string.mr_result,
                                "missing" to R.string.mr_missing,
                                "boundary" to R.string.mr_boundary,
                                "field" to R.string.mr_field,
                                "receipts" to R.string.mr_receipts,
                            )
                            .forEach { (route, label) ->
                                OutlinedButton(
                                    onClick = { page = route },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(label))
                                }
                            }
                }
            }
    }
}

internal val outcomes =
    listOf(
        "worked",
        "audible_inside",
        "late",
        "silent_outside",
        "early_release",
        "late_release",
        "unsure",
    )
internal val places =
    listOf(
        "hall",
        "courtyard",
        "entrance",
        "passing",
        "waiting",
        "neighbour",
        "inside",
        "outside",
        "unsure",
    )
internal val durations = listOf("lt10", "lt30", "lt60", "ge60", "unsure")
internal val surveyAnswers = listOf("inside", "passed", "elsewhere", "unsure")
internal val reasons = listOf("roof", "pavement", "neighbour", "wrong_building", "other")
internal val receiptStates =
    listOf(
        "queued",
        "received",
        "held",
        "accepted",
        "duplicate",
        "declined",
        "published",
        "closed",
        "withdrawn",
        "expired",
        "rejected",
        "withdrawing",
    )

@Composable
internal fun PickReportOption(
    codes: List<String>,
    labels: List<String>,
    selected: String,
    action: (String) -> Unit,
) {
    codes.forEachIndexed { i, code ->
        Row(
            Modifier.fillMaxWidth()
                .selectable(
                    selected == code,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    onClick = { action(code) },
                )
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected == code, null)
            Spacer(Modifier.width(8.dp))
            Text(labels[i])
        }
    }
}

@Composable
private fun OutcomeForm(survey: Boolean, prayer: String?, review: (JSONObject) -> Unit) {
    val c = LocalContext.current
    val codes = if (survey) surveyAnswers else outcomes
    val names = stringArrayResource(if (survey) R.array.mr_survey_answers else R.array.mr_answers)
    var answer by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(false) }
    val latest = remember {
        MosqueReportStore.get(c).value("latest")?.let(::JSONObject)?.takeIf {
            System.currentTimeMillis() - it.optLong("end") in 0..86_400_000L
        }
    }
    if (survey) Text(stringResource(R.string.mr_question, prayerLabels[prayer].orEmpty()))
    PickReportOption(codes, names.toList(), answer) {
        answer = it
        detail = ""
    }
    val options =
        when (answer) {
            "audible_inside",
            "late" -> places.take(3)
            "silent_outside" -> places.subList(3, 6)
            "early_release" -> listOf("inside", "outside", "unsure")
            "late_release" -> durations
            "inside",
            "passed" -> listOf("yes", "no", "unsure")
            else -> emptyList()
        }
    if (options.isNotEmpty()) {
        val labels =
            when {
                options == durations -> stringArrayResource(R.array.mr_durations).toList()
                options.first() == "yes" -> listOf("نعم", "لا", "غير متأكّد")
                else -> {
                    val all = stringArrayResource(R.array.mr_places)
                    options.map { all[places.indexOf(it)] }
                }
            }
        Text(
            if (options.first() == "yes") "هل عمل الكتم كما توقّعت؟"
            else stringResource(if (options == durations) R.string.mr_delay else R.string.mr_where)
        )
        PickReportOption(options, labels, detail) { detail = it }
    }
    if (latest != null && !survey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(attach, { attach = it })
            Text(
                stringResource(R.string.mr_attach) +
                    " · " +
                    prayerLabels[latest.optString("prayer")].orEmpty()
            )
        }
    }
    Button(
        enabled = answer.isNotEmpty(),
        onClick = {
            review(
                JSONObject()
                    .put("question", if (survey) "window_v1" else "outcome_v1")
                    .put("answer", answer)
                    .apply {
                        if (detail.isNotBlank()) put("detail", detail)
                        if (survey && prayer in prayerLabels) put("prayer", prayer)
                        if (attach && latest != null) {
                            put("technical", latest.getJSONObject("counts"))
                            put("prayer", latest.getString("prayer"))
                        }
                    }
            )
        },
    ) {
        Text(stringResource(R.string.mr_review))
    }
}
