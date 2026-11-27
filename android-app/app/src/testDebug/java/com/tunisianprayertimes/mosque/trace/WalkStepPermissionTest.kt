package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalkStepPermissionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val permission = Manifest.permission.ACTIVITY_RECOGNITION

    @Test fun launcherAsksOnceAndReportsLinkRecoversADeniedPermission() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java, launcher).setup()
        val activity = controller.get()
        try {
            val android = shadowOf(activity)
            val pm = shadowOf(activity.packageManager)
            shadowOf(activity.application).denyPermissions(permission)
            pm.setSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER, true)
            PrefsManager.markFirstLaunchDone(activity)
            PrefsManager.setEnabled(activity, true)
            assertEquals(WalkStepAccess.NONE, walkStepAccess(activity, onLauncher = true))
            MosqueSilenceSettings.saveMode(activity, AutomaticSilenceMode.TIME_AND_MOSQUE)
            activity.setContent { WalkStepPermission(onLauncher = true) }
            compose.waitForIdle()
            val request = requireNotNull(android.lastRequestedPermission)
            assertArrayEquals(arrayOf(permission), request.requestedPermissions)
            assertEquals(WalkStepAccess.NONE, walkStepAccess(activity, onLauncher = true))
            compose.runOnIdle {
                activity.onRequestPermissionsResult(request.requestCode, arrayOf(permission),
                    intArrayOf(PackageManager.PERMISSION_DENIED))
            }
            assertTrue(PrefsManager.isEnabled(activity))
            assertNull(SilenceTraceStore.activeId(activity))
            pm.setShouldShowRequestPermissionRationale(permission, true)
            assertEquals(WalkStepAccess.ASK, walkStepAccess(activity, onLauncher = false))
            pm.setShouldShowRequestPermissionRationale(permission, false)
            android.nextStartedActivity // discard the Android permission request
            activity.setContent { WalkStepPermission(onLauncher = false) }
            compose.onNodeWithText(activity.getString(R.string.walk_steps_permission_settings)).performClick()
            val settings = requireNotNull(android.nextStartedActivity)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
            assertEquals(activity.packageName, settings.data?.schemeSpecificPart)
            shadowOf(activity.application).grantPermissions(permission)
            assertTrue(canReadWalkSteps(activity))
            assertEquals(WalkStepAccess.NONE, walkStepAccess(activity, onLauncher = false))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(sdk = [28]) fun olderPhonesDoNotNeedAnActivityPermissionRequest() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            assertTrue(canReadWalkSteps(controller.get()))
            assertEquals(WalkStepAccess.NONE, walkStepAccess(controller.get(), onLauncher = false))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
