package com.tunisianprayertimes.mosque

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val TEST_MILLIS = 120_000L
private enum class TestStatus { RUNNING, CONFIRMED, TIMED_OUT, FAILED }

internal data class EditorTestReading(val distance: Double, val accuracy: Double,
    val arrival: AdaptiveVisitDetector.Arrival?, val clearlyAway: Boolean)

/** Evaluate this draft only. The editor never acquires a production silence owner. */
internal suspend fun testDraftArrival(area: SilenceArea, readings: Flow<PresenceFix>, started: Long,
    now: () -> Long, report: (EditorTestReading) -> Unit): Boolean {
    val detector = AdaptiveVisitDetector(listOf(area))
    return readings.firstOrNull { fix ->
        val delivered = now()
        if (delivered - started >= TEST_MILLIS || !detector.observe(fix, delivered, started)) return@firstOrNull false
        report(EditorTestReading(detector.distance, requireNotNull(fix.accuracyMeters), detector.arrival, detector.clearlyAway))
        detector.arrival != null
    } != null
}

@SuppressLint("MissingPermission")
private fun editorReadings(context: Context): Flow<PresenceFix> = callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
        .setMinUpdateIntervalMillis(2_000L).setMaxUpdateAgeMillis(0)
        .setMaxUpdateDelayMillis(0).setDurationMillis(TEST_MILLIS).build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) trySend(location.toMosqueFix())
        }
    }
    fun remove() { runCatching { client.removeLocationUpdates(callback) } }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        .addOnFailureListener { close(it) }.addOnSuccessListener { if (!isActive) remove() }
    awaitClose { remove() }
}

@Composable
internal fun MosqueArrivalTestDialog(polygons: List<SilencePolygon>, stricter: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(hasMapLocation(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMapLocation(context)
    }
    var attempt by remember { mutableIntStateOf(0) }
    var reading by remember(attempt) { mutableStateOf<EditorTestReading?>(null) }
    var status by remember(attempt) { mutableStateOf(TestStatus.RUNNING) }
    var started by remember(attempt) { mutableLongStateOf(0L) }
    var secondsLeft by remember(attempt) { mutableIntStateOf(120) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP, onEvent = onDismiss)
    LaunchedEffect(attempt, granted, polygons, stricter, lifecycle) {
        if (!granted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val area = SilenceArea("editor-preview", 0, polygons, requireStrongerEvidence = stricter)
            status = TestStatus.RUNNING
            started = SystemClock.elapsedRealtime()
            try {
                val found = withTimeoutOrNull(TEST_MILLIS) {
                    testDraftArrival(area, editorReadings(context), started, SystemClock::elapsedRealtime) { reading = it }
                }
                status = if (found == true) TestStatus.CONFIRMED else TestStatus.TIMED_OUT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                granted = hasMapLocation(context)
                status = TestStatus.FAILED
            }
        }
    }
    LaunchedEffect(started, status) {
        if (started == 0L || status != TestStatus.RUNNING) return@LaunchedEffect
        do {
            secondsLeft = ((TEST_MILLIS - (SystemClock.elapsedRealtime() - started) + 999) / 1_000).coerceAtLeast(0).toInt()
            delay(1_000)
        } while (secondsLeft > 0)
    }
    val last = reading
    val message = when {
        !granted -> R.string.mosque_test_permission
        status == TestStatus.FAILED -> R.string.mosque_test_failed
        status == TestStatus.TIMED_OUT && last == null -> R.string.mosque_test_no_readings
        status == TestStatus.TIMED_OUT -> R.string.mosque_test_no_arrival
        status == TestStatus.CONFIRMED -> when (last?.arrival) {
            AdaptiveVisitDetector.Arrival.INSIDE -> R.string.mosque_test_inside_confirmed
            AdaptiveVisitDetector.Arrival.EARLIER_PRECISE -> R.string.mosque_test_drift_confirmed
            else -> R.string.mosque_test_near_confirmed
        }
        last == null -> R.string.mosque_test_waiting
        last.clearlyAway -> R.string.mosque_test_far
        last.accuracy > 30 -> R.string.mosque_test_low_accuracy
        else -> R.string.mosque_test_collecting
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.mosque_test_arrival)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mosque_test_explanation))
                Text(stringResource(message), style = MaterialTheme.typography.titleSmall)
                if (last != null) {
                    val distance = abs(last.distance)
                    val length = if (distance >= 1_000) stringResource(R.string.mosque_test_km, distance / 1_000)
                        else stringResource(R.string.mosque_test_m, distance)
                    Text(stringResource(if (last.distance < 0) R.string.mosque_test_position_inside
                        else R.string.mosque_test_position_outside, length))
                    Text(stringResource(R.string.qibla_location_accuracy, last.accuracy))
                }
                if (granted && status == TestStatus.RUNNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.mosque_test_remaining, secondsLeft), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mosque_test_close)) } },
        dismissButton = {
            if (!granted) TextButton(onClick = {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(stringResource(R.string.mosque_test_allow_location)) }
            else if (status != TestStatus.RUNNING) TextButton(onClick = { attempt++ }) {
                Text(stringResource(R.string.mosque_test_retry))
            }
        })
}
