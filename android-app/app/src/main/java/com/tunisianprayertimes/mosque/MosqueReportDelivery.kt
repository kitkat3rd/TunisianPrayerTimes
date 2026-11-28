package com.tunisianprayertimes.mosque

import android.content.Context
import androidx.work.*
import com.tunisianprayertimes.BuildConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal object MosqueReportDelivery {
    private const val METRICS_TAG = "mosque-reliability-metric"
    private const val WALK_TAG = "mosque-shared-walk"
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    fun request(
        path: String,
        method: String,
        body: JSONObject?,
        secret: String? = null,
    ): Pair<Int, JSONObject> {
        val request =
            Request.Builder()
                .url(BuildConfig.MOSQUE_REPORT_ORIGIN + path)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36",
                )
        if (secret != null) request.header("Authorization", "Bearer " + secret)
        if (path == "/v1/metrics") request.tag(String::class.java, METRICS_TAG)
        if (path == "/v1/walks") request.tag(String::class.java, WALK_TAG)
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        client.newCall(request.method(method, payload).build()).execute().use { response ->
            val result = response.body?.string().orEmpty()
            return response.code to (runCatching { JSONObject(result) }.getOrNull() ?: JSONObject())
        }
    }

    fun scheduleNew(c: Context, store: MosqueReportStore) {
        while (true) {
            val (id, kind) = store.newlyQueued.poll() ?: break
            schedule(c, id, kind)
        }
    }

    fun recover(c: Context, store: MosqueReportStore) {
        val expired = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
        store.db.execSQL(
            "UPDATE receipts SET status='expired' WHERE status IN ('queued','withdrawing') AND created<?",
            arrayOf(expired),
        )
        store.db.execSQL("DELETE FROM outbox WHERE created<?", arrayOf(expired))
        store.db.rawQuery("SELECT id,kind FROM outbox", null).use { rows ->
            while (rows.moveToNext()) schedule(c, rows.getString(0), rows.getString(1))
        }
    }

    private fun schedule(c: Context, id: String, kind: String) {
        val job =
            OneTimeWorkRequestBuilder<MosqueReportWorker>()
                .setInputData(workDataOf("id" to id, "kind" to kind))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (android.os.Build.VERSION.SDK_INT >= 31)
            job.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (kind == "metrics") job.addTag(METRICS_TAG)
        if (kind == "walk") job.addTag(WALK_TAG)
        WorkManager.getInstance(c)
            .enqueueUniqueWork(
                "mosque-report-" + kind + "-" + id,
                ExistingWorkPolicy.KEEP,
                job.build(),
            )
    }

    fun cancelMetrics(c: Context) {
        cancel(c, METRICS_TAG)
    }

    fun cancelWalks(c: Context) = cancel(c, WALK_TAG)

    private fun cancel(c: Context, tag: String) {
        WorkManager.getInstance(c).cancelAllWorkByTag(tag)
        (client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()).forEach { call ->
            if (call.request().tag(String::class.java) == tag) call.cancel()
        }
    }

    fun submit(c: Context, kind: String, fields: JSONObject, id: String): String {
        val store = MosqueReportStore.get(c)
        store.db.atomic { queueSubmission(store, kind, fields, id) }
        scheduleNew(c, store)
        return id
    }

    internal fun queueSubmission(
        store: MosqueReportStore,
        kind: String,
        fields: JSONObject,
        id: String,
    ) {
        if (store.keys("SELECT id FROM receipts WHERE id=?", id).isEmpty()) {
            require(store.keys("SELECT id FROM outbox WHERE kind!='metrics'").size < 32)
            val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            store.db.execSQL(
                "INSERT INTO receipts VALUES(?,?,?,?,?)",
                arrayOf<Any>(id, secret, kind, "queued", now),
            )
            store.queue(
                if (kind == "walk") "walk" else "ticket",
                JSONObject().put("id", id).put("kind", kind).put("fields", fields),
                now,
            )
        }
    }

    data class Receipt(val id: String, val kind: String, val status: String, val secret: String)

    fun receipts(c: Context): List<Receipt> =
        MosqueReportStore.get(c)
            .db
            .rawQuery(
                "SELECT id,kind,status,secret FROM receipts ORDER BY created DESC LIMIT 40",
                null,
            )
            .use {
                buildList {
                    while (it.moveToNext()) add(
                        Receipt(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
                    )
                }
            }

    fun updateReceipt(c: Context, receipt: Receipt, withdraw: Boolean): JSONObject {
        val store = MosqueReportStore.get(c)
        if (withdraw) {
            store.db.atomic {
                store.db.execSQL("DELETE FROM outbox WHERE id=?", arrayOf(receipt.id))
                store.db.execSQL(
                    "UPDATE receipts SET status='withdrawing' WHERE id=?",
                    arrayOf(receipt.id),
                )
                store.queue(
                    "withdraw",
                    JSONObject().put("id", receipt.id),
                    System.currentTimeMillis(),
                )
            }
            scheduleNew(c, store)
            return JSONObject().put("status", "withdrawing")
        }
        val (code, result) = request("/v1/tickets/" + receipt.id, "GET", null, receipt.secret)
        check(code in 200..299)
        val status = result.getString("status")
        store.db.atomic {
            store.db.execSQL(
                "UPDATE receipts SET status=? WHERE id=? AND status!='withdrawing'",
                arrayOf(status, receipt.id),
            )
        }
        return result
    }
}

class MosqueReportWorker(c: Context, args: WorkerParameters) : Worker(c, args) {
    override fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val store = MosqueReportStore.get(applicationContext)
        val row =
            store.db.rawQuery("SELECT kind,created,body FROM outbox WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst())
                    Triple(it.getString(0), it.getLong(1), JSONObject(it.getString(2)))
                else null
            } ?: return Result.success()
        if (inputData.getString("kind") != row.first) return Result.success()
        val metric = row.first == "metrics"
        val walk = row.first == "walk"
        val withdraw = row.first == "withdraw"
        val expired = System.currentTimeMillis() - row.second > TimeUnit.DAYS.toMillis(14)
        if (
            expired ||
                metric && !MosqueReportStore.permitted(applicationContext) ||
                walk && MosqueWalkSharing.since(applicationContext) !in 1L..row.second
        ) {
            store.db.execSQL("DELETE FROM outbox WHERE id=? AND kind=?", arrayOf(id, row.first))
            if (!metric)
                store.db.execSQL("UPDATE receipts SET status='expired' WHERE id=?", arrayOf(id))
            return Result.success()
        }
        val data = row.third
        if (metric) {
            val age = (System.currentTimeMillis() - row.second).coerceAtLeast(0)
            data.put(
                "age",
                when {
                    age >= 300_000 -> "ge300"
                    age >= 60_000 -> "lt300"
                    else -> delayBand(age)
                },
            )
        }
        val secret =
            if (metric) null
            else
                store.keys("SELECT secret FROM receipts WHERE id=?", id).singleOrNull()
                    ?: return Result.failure()
        val (code, response) =
            try {
                if (isStopped) return Result.retry()
                if (withdraw)
                    MosqueReportDelivery.request("/v1/tickets/" + id, "DELETE", null, secret)
                else
                    MosqueReportDelivery.request(
                        if (metric) "/v1/metrics" else if (walk) "/v1/walks" else "/v1/tickets",
                        "POST",
                        data,
                        secret,
                    )
            } catch (_: java.io.IOException) {
                return Result.retry()
            }
        if (code == 408 || code == 429 || code >= 500) return Result.retry()
        if (
            code in 200..299 &&
                (metric && !response.optBoolean("accepted") || !metric && !response.has("status"))
        )
            return Result.retry()
        store.db.atomic {
            store.db.execSQL("DELETE FROM outbox WHERE id=? AND kind=?", arrayOf(id, row.first))
            if (!metric)
                store.db.execSQL(
                    "UPDATE receipts SET status=? WHERE id=? AND status=?",
                    arrayOf(
                        if (code in 200..299) response.optString("status", "received")
                        else "rejected",
                        id,
                        if (withdraw) "withdrawing" else "queued",
                    ),
                )
        }
        return if (code in 200..299) Result.success() else Result.failure()
    }
}
