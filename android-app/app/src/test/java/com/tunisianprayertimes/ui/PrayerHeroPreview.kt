package com.tunisianprayertimes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.io.File
import org.junit.Assert.assertTrue

@Composable
internal fun PrayerHeroPreviewFrame(width: Dp, fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        TunisianPrayerTimesTheme {
            Box(Modifier.width(width).background(BgCream).padding(16.dp)) { content() }
        }
    }
}

internal fun ComposeTestRule.savePrayerHeroPreview(name: String) {
    waitForIdle()
    val bounds = onNodeWithTag(TestTags.STATUS_CARD).fetchSemanticsNode().boundsInWindow
    val bitmap = runOnIdle {
        val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        Bitmap.createBitmap(bounds.width.toInt() + 16, bounds.height.toInt() + 16, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(android.graphics.Color.rgb(255, 248, 240))
            canvas.translate(8f - bounds.left, 8f - bounds.top)
            view.draw(canvas)
        }
    }
    val output = File("build/outputs/prayer-hero-preview/$name.png")
    requireNotNull(output.parentFile).mkdirs()
    output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    bitmap.recycle()
}
