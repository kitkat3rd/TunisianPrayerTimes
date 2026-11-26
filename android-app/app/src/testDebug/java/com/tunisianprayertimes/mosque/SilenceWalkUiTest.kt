package com.tunisianprayertimes.mosque

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.GooglePlayServicesUtilLight
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SilenceWalkUiTest {
    @After fun releaseTraceDatabase() = SilenceTraceStore.closeForTest()

    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun settingsOpenAStoppedWalkWithSelectableEventsExportAndDeletion() {
        val store = SilenceTraceStore
        val id = store.start(context)
        assertTrue(store.append(context, "route", latitude = 36.8, longitude = 10.2, accuracyMeters = 4.0, sessionId = id))
        val arrival = context.getString(R.string.silence_walk_arrived_event)
        assertTrue(store.append(context, "test_arrived", arrival, sessionId = id))
        store.stop(context, id)

        PrefsManager.markFirstLaunchDone(context)
        // Opening silencing settings checks Play Services readiness in this non-APK test host.
        val flag = GooglePlayServicesUtilLight::class.java.getDeclaredField("zzc").apply { isAccessible = true }
        val apkIndependent = flag.get(null) as AtomicBoolean
        val previous = apkIndependent.get()
        GooglePlayServicesUtilLight.enableUsingApkIndependentContext()
        val intent = try {
            val settings = Robolectric.buildActivity(MosqueSettingsActivity::class.java).setup()
            try {
                compose.onNodeWithText(context.getString(R.string.silence_walk_title)).performScrollTo().performClick()
                requireNotNull(shadowOf(settings.get()).nextStartedActivity)
            } finally {
                settings.pause().stop().destroy()
            }
        } finally {
            apkIndependent.set(previous)
        }
        assertEquals(SilenceWalkActivity::class.java.name, intent.component?.className)

        val walk = Robolectric.buildActivity(SilenceWalkActivity::class.java, intent).setup()
        try {
            val save = context.getString(R.string.silence_walk_save)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(save).fetchSemanticsNodes().isNotEmpty() }
            assertNull(store.activeId(context))
            assertEquals(listOf(id), store.sessions(context).map { it.id })
            compose.onNodeWithText("خريطة المسار").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("الأحداث فقط").performScrollTo().performClick()
            compose.onNodeWithContentDescription("انتقال عبر الخط الزمني").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.onNodeWithText(arrival).performScrollTo().assertIsDisplayed()

            compose.onNodeWithText(save).performScrollTo().performClick()
            val document = requireNotNull(shadowOf(walk.get()).nextStartedActivity)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, document.action)
            assertEquals("text/html", document.type)
            val delete = context.getString(R.string.silence_walk_delete)
            compose.onNodeWithText(delete).performScrollTo().performClick()
            compose.onNode(hasAnyAncestor(isDialog()) and hasClickAction() and hasText(delete)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(save).fetchSemanticsNodes().isEmpty() }
            assertTrue(store.sessions(context).isEmpty())
            compose.onNodeWithText(context.getString(R.string.silence_walk_start)).performScrollTo().assertIsDisplayed()
            assertNull(store.activeId(context))
        } finally {
            walk.pause().stop().destroy()
        }
    }
}
