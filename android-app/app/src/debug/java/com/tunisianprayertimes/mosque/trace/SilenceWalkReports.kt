package com.tunisianprayertimes.mosque.trace

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tunisianprayertimes.mosque.SilenceWalkExport
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** Closed SQLite sessions are the source of truth for the private, standalone report files. */
internal object SilenceWalkReports {
    private const val DIRECTORY = "silence-walk-reports"
    internal const val SESSION = "session"
    private val background = Executors.newSingleThreadExecutor { task -> Thread(task, "silence-walk-html") }
    @Volatile private var installed: Context? = null

    fun file(context: Context, id: Long) = File(File(context.noBackupFilesDir, DIRECTORY), "silence-walk-$id.html")

    fun install(context: Context) {
        val app = context.applicationContext
        installed = app
        background.execute {
            // Opening the live store also finalizes a session interrupted by process death.
            if (File(app.noBackupFilesDir, "silence-walks.db").isFile) {
                try {
                    reconcile(app)
                } catch (error: Exception) {
                    Log.w("SilenceTrace", "Will retry saving completed walk reports", error)
                    enqueueRetry(app, null)
                }
            }
        }
    }

    fun closed(context: Context, id: Long) {
        if (installed === context.applicationContext) scheduleSave(context, id)
    }

    fun scheduleSave(context: Context, id: Long) {
        val app = context.applicationContext
        background.execute {
            // The persisted job can finish if Android stops this process during the immediate write.
            enqueueRetry(app, id)
            try {
                save(app, id)
            } catch (error: Exception) {
                Log.w("SilenceTrace", "Will retry saving the completed walk report", error)
            }
        }
    }

    private fun enqueueRetry(context: Context, id: Long?) {
        try {
            val work = OneTimeWorkRequestBuilder<SilenceWalkReportWorker>()
            if (id != null) work.setInputData(workDataOf(SESSION to id))
            WorkManager.getInstance(context).enqueueUniqueWork(
                "silence-walk-html-${id ?: "recovery"}", ExistingWorkPolicy.KEEP, work.build(),
            )
        } catch (error: RuntimeException) {
            // The immediate write and the next process startup can still save this report.
            Log.w("SilenceTrace", "Could not schedule the walk report worker", error)
        }
    }

    /** A missing file represents pending work, including recordings from older builds. */
    fun reconcile(context: Context) {
        var failure: Exception? = null
        for (session in SilenceTraceStore.sessions(context)) {
            if (session.endedAt == null) continue
            try {
                save(context, session.id)
            } catch (error: Exception) {
                // A single unwriteable file should not prevent saving the other completed walks.
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }

    @Synchronized fun save(context: Context, id: Long) {
        val session = SilenceTraceStore.session(context, id) ?: return
        if (session.endedAt == null) return
        val target = file(context, id)
        if (target.isFile) return
        val directory = requireNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the walk report directory")
        val pending = pendingFile(target)
        try {
            FileOutputStream(pending).use { output ->
                SilenceWalkExport.writeHtml(context, session, SilenceTraceStore.entries(context, id), output)
                output.fd.sync()
            }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            pending.delete()
        }
    }

    /** Serialize deletion and publication so a pending worker cannot recreate a deleted report. */
    @Synchronized fun delete(context: Context, id: Long, database: TraceDatabase) {
        if (database.session(id)?.endedAt == null) return
        val target = file(context, id)
        Files.deleteIfExists(pendingFile(target).toPath())
        Files.deleteIfExists(target.toPath())
        database.delete(id)
    }

    private fun pendingFile(target: File) = File(target.parentFile, ".${target.name}.pending")

    internal fun closeForTest() {
        if (installed == null) return
        installed = null
        background.submit {}.get()
    }
}

class SilenceWalkReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val id = inputData.getLong(SilenceWalkReports.SESSION, -1L)
        if (id >= 0) SilenceWalkReports.save(applicationContext, id)
        else SilenceWalkReports.reconcile(applicationContext)
        Result.success()
    } catch (error: Exception) {
        Log.w("SilenceTrace", "Walk report is pending", error)
        Result.retry()
    }
}
