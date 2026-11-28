package com.tunisianprayertimes.mosque

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tunisianprayertimes.R
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MosqueFeedbackScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun arabicPhone() = checkScreen(1f)

    @Test
    @Config(qualifiers = "ar-rTN-w320dp-h740dp-xhdpi")
    fun enlargedArabicPhone() = checkScreen(1.5f)

    private fun checkScreen(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(MosqueFeedbackActivity::class.java).setup()
        val activity = controller.get()
        try {
            fun capture(name: String) {
                compose.waitForIdle()
                val image =
                    compose.runOnIdle {
                        val view = activity.window.decorView
                        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                            view.draw(Canvas(it))
                        }
                    }
                val path =
                    File("build/outputs/mosque-feedback-preview/" + scale + "-" + name + ".png")
                requireNotNull(path.parentFile).mkdirs()
                path.outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                image.recycle()
            }
            capture("home")
            compose
                .onNodeWithText(activity.getString(R.string.mr_result))
                .performScrollTo()
                .performClick()
            val answer = activity.resources.getStringArray(R.array.mr_answers)[2]
            compose.onNodeWithText(answer).performScrollTo().performClick()
            compose
                .onNodeWithText(activity.getString(R.string.mr_review))
                .performScrollTo()
                .performClick()
            compose.onNodeWithText(answer).assertIsDisplayed()
            compose
                .onNodeWithText(activity.getString(R.string.mr_send))
                .performScrollTo()
                .assertIsDisplayed()
            capture("preview")
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
