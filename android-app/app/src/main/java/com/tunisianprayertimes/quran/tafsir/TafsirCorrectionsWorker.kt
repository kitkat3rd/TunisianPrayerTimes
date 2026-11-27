package com.tunisianprayertimes.quran.tafsir

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class TafsirCorrectionsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            val catalog = context.assets.open("quran/tafsir/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
            val config = TafsirCorrections.config(catalog) ?: return@withContext Result.success()
            val installed = TafsirCorrections.beginCheck(context) ?: return@withContext Result.success()
            val index = TafsirCorrections.verifiedIndex(download(config.index, TafsirCorrections.INDEX_LIMIT), config.key)
            for (entry in index) {
                currentCoroutineContext().ensureActive()
                if (entry.base !in installed[entry.book].orEmpty() || TafsirCorrections.version(context, entry) >= entry.version) continue
                try {
                    val bytes = download(URL(config.index, entry.path), entry.bytes)
                    currentCoroutineContext().ensureActive()
                    TafsirCorrections.install(context, entry, bytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("TafsirCorrections", "Could not apply corrections for " + entry.book, error)
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("TafsirCorrections", "Could not check corrections", error)
            Result.failure()
        }
    }

    private fun download(url: URL, limit: Int): ByteArray {
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
            check(connection.responseCode == 200)
            require(connection.contentLengthLong <= limit)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
