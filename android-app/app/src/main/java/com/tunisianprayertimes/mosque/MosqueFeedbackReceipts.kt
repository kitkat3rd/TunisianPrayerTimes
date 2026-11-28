package com.tunisianprayertimes.mosque

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private typealias Receipt = MosqueReportDelivery.Receipt

@Composable
internal fun ReceiptList() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Receipt?>(null) }
    var reply by rememberSaveable { mutableStateOf("") }
    val items by
        produceState<List<Receipt>>(emptyList(), revision) {
            value = withContext(Dispatchers.IO) { MosqueReportDelivery.receipts(c) }
        }
    val labels = stringArrayResource(R.array.mr_states)
    fun update(item: Receipt, withdraw: Boolean) {
        busy = true
        error = false
        info = ""
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { MosqueReportDelivery.updateReceipt(c, item, withdraw) }
                }
            busy = false
            result
                .onSuccess {
                    info =
                        listOf(it.optString("reason"), it.optString("version"))
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                    revision++
                }
                .onFailure { error = true }
        }
    }
    if (items.isEmpty()) Text(stringResource(R.string.mr_empty))
    if (error) Text(stringResource(R.string.mr_error), color = MaterialTheme.colorScheme.error)
    if (info.isNotBlank()) Text(info)
    for (item in items) {
        val label =
            when (item.kind) {
                "walk" -> R.string.mr_shared_walk
                "missing" -> R.string.mr_missing
                "boundary" -> R.string.mr_boundary
                else -> R.string.mr_feedback
            }
        Text(
            stringResource(label) +
                " · " +
                labels.getOrElse(receiptStates.indexOf(item.status)) { item.status }
        )
        Text(item.id.take(8), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !busy, onClick = { update(item, false) }) {
                Text(stringResource(R.string.mr_refresh))
            }
            if (item.status == "held")
                TextButton(
                    enabled = !busy,
                    onClick = {
                        replyTo = item
                        reply = ""
                    },
                ) {
                    Text(stringResource(R.string.mr_answer))
                }
            if (item.status in setOf("queued", "received", "held"))
                TextButton(enabled = !busy, onClick = { update(item, true) }) {
                    Text(stringResource(R.string.mr_withdraw))
                }
        }
        HorizontalDivider()
    }
    val target = replyTo
    if (target != null)
        AlertDialog(
            onDismissRequest = { replyTo = null },
            title = { Text(stringResource(R.string.mr_answer)) },
            text = {
                OutlinedTextField(
                    reply,
                    { reply = it.take(240) },
                    label = { Text("توضيح لفريق المراجعة") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && reply.isNotBlank(),
                    onClick = {
                        val answer = reply.trim()
                        scope.launch {
                            busy = true
                            val accepted =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                            MosqueReportDelivery.request(
                                                    "/v1/tickets/" + target.id,
                                                    "PATCH",
                                                    JSONObject().put("reply", answer),
                                                    target.secret,
                                                )
                                                .first in 200..299
                                        }
                                        .getOrDefault(false)
                                }
                            busy = false
                            if (accepted) {
                                replyTo = null
                                update(target, false)
                            } else error = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.mr_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { replyTo = null }) {
                    Text(stringResource(R.string.mosque_cancel))
                }
            },
        )
}

/** Explicit volunteer trial. Local marker times never enter the submission. */
internal object MosqueFieldTrial {
    private const val KEY = "volunteer_trial"

    fun read(c: Context) =
        MosqueReportStore.get(c)
            .value(KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::JSONObject)
            ?.takeIf { System.currentTimeMillis() - it.optLong("created") in 0..6 * 3_600_000L }

    fun begin(c: Context, pass: Boolean) {
        val owned = SilenceCoordinator.requested(c, SilenceOwner.MOSQUE)
        val counts = JSONObject().put(if (pass) "field_pass" else "field_entry", 1)
        if (owned) counts.put("field_already_active", 1)
        MosqueReportStore.get(c)
            .put(
                KEY,
                JSONObject()
                    .put("created", System.currentTimeMillis())
                    .put("start", SystemClock.elapsedRealtime())
                    .put("pass", pass)
                    .put("counts", counts)
                    .toString(),
            )
    }

    fun exit(c: Context) {
        val t = read(c) ?: return
        t.put("exit", SystemClock.elapsedRealtime())
        t.getJSONObject("counts").put("field_exit", 1)
        MosqueReportStore.get(c).put(KEY, t.toString())
    }

    fun changed(c: Context, on: Boolean, elapsed: Long) {
        val store = MosqueReportStore.get(c)
        store.db.atomic {
            val t = read(c) ?: return@atomic
            val n = t.getJSONObject("counts")
            if (on && t.optBoolean("pass")) n.put("field_pass_activated", 1)
            if (on && !t.has("on") && !t.has("exit")) {
                n.put("field_entry_" + delayBand(elapsed - t.getLong("start")), 1)
                t.put("on", true)
            }
            if (!on && t.has("exit") && !t.has("off")) {
                n.put("field_exit_" + delayBand(elapsed - t.getLong("exit")), 1)
                t.put("off", true)
            } else if (!on && !t.has("exit")) n.put("field_early_release", 1)
            store.put(KEY, t.toString())
        }
    }
}

@Composable
internal fun FieldReportForm(review: (JSONObject) -> Unit) {
    val c = LocalContext.current
    var marker by rememberSaveable { mutableStateOf(MosqueFieldTrial.read(c)?.toString()) }
    Text(stringResource(R.string.mr_field_notice))
    if (marker == null) {
        Button(
            onClick = {
                MosqueFieldTrial.begin(c, false)
                marker = MosqueFieldTrial.read(c)?.toString()
            }
        ) {
            Text(stringResource(R.string.mr_field_enter))
        }
        OutlinedButton(
            onClick = {
                MosqueFieldTrial.begin(c, true)
                marker = MosqueFieldTrial.read(c)?.toString()
            }
        ) {
            Text(stringResource(R.string.mr_field_pass))
        }
    } else {
        val trial = JSONObject(marker!!)
        Text(stringResource(R.string.mr_field_active))
        if (!trial.has("exit") && !trial.optBoolean("pass"))
            Button(
                onClick = {
                    MosqueFieldTrial.exit(c)
                    marker = MosqueFieldTrial.read(c)?.toString()
                }
            ) {
                Text(stringResource(R.string.mr_field_exit))
            }
        OutlinedButton(
            onClick = {
                val t = MosqueFieldTrial.read(c) ?: trial
                val n = t.getJSONObject("counts")
                if (!t.optBoolean("pass") && !t.has("on") && !n.has("field_already_active"))
                    n.put("field_entry_unknown", 1)
                if (t.has("exit") && !t.has("off")) n.put("field_exit_unknown", 1)
                review(
                    JSONObject()
                        .put("answer", "unsure")
                        .put("question", "field_v1")
                        .put("technical", n)
                )
            }
        ) {
            Text(stringResource(R.string.mr_field_finish))
        }
        TextButton(
            onClick = {
                MosqueReportStore.get(c).put("volunteer_trial", "")
                marker = null
            }
        ) {
            Text(stringResource(R.string.mosque_cancel))
        }
    }
}
