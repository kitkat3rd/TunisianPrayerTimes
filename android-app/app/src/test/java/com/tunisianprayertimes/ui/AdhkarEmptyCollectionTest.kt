package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrRepository
import com.tunisianprayertimes.adhkar.collectionEntries
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhkarEmptyCollectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun emptyMorningCollectionOpensPickerAndSavesMembership() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        controller.setup()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(activity.applicationContext)
            val repo = DhikrRepository(activity)
            val morning = DhikrCategory.MORNING
            val entries = repo.state.value.collectionEntries(morning)
            entries.forEach { repo.setCollectionMembership(morning, it.id, false) }
            activity.setContent { AdhkarScreen(activity) }

            compose.onNodeWithTag("adhkar_hero_morning").assertIsDisplayed().performClick()
            val entry = entries.first()
            compose.onNodeWithTag("adhkar_add_to_list_search").assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(entry.title)) }
            compose.onNode(hasText(entry.title) and hasAnyAncestor(hasTestTag("adhkar_add_to_list_results")))
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil { repo.state.value.collectionEntries(morning).map { it.id } == listOf(entry.id) }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
