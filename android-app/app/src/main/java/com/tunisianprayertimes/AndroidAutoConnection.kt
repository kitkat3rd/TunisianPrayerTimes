package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

internal object AndroidAutoConnection {
    // AndroidX CarConnection's projection host protocol. Read it at activation as a
    // prayer alarm can launch the process before an async connection observer delivers.
    internal const val AUTHORITY = "androidx.car.app.connection"
    internal const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    internal const val STATE_COLUMN = "CarConnectionState"
    private const val PROJECTION = 2
    private val uri = Uri.parse("content://$AUTHORITY")

    fun isProjected(context: Context): Boolean = try {
        context.contentResolver.query(uri, arrayOf(STATE_COLUMN), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(STATE_COLUMN)
            column >= 0 && cursor.moveToFirst() && cursor.getInt(column) == PROJECTION
        } ?: false
    } catch (error: RuntimeException) {
        Log.w("AndroidAutoConnection", "Could not read the Android Auto connection", error)
        false
    }

    fun observe(context: Context) {
        val app = context.applicationContext
        // The projection host only signals that the state changed. Trust the provider query.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_UPDATED) SilenceModeController.reconcileAndroidAutoSilence(app)
            }
        }, IntentFilter(ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED)
        SilenceModeController.reconcileAndroidAutoSilence(app)
    }
}
