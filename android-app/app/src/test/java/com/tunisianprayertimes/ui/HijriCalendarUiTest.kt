package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanOverrideChecker.RamadanOverride
import com.tunisianprayertimes.TunisianHijriCalendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.TimeZone

/** Real calendar composition and gestures, with isolated announcement IO and no phone/server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "ar-rTN-w320dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HijriCalendarUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var previousStore: Any
    private val storeField = OfficialIslamicDates::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val ramadanStart = LocalDate.of(2026, 2, 19)
    private val fitr = LocalDate.of(2026, 3, 20)
    private var confirmed: Long? = null
    private var dismissals = 0
    private var fontScale = 2f

    @Before
    fun isolateOfficialDates() {
        RuntimeEnvironment.setFontScale(fontScale)
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Android grants this merged-manifest signature permission on install;
        // Robolectric's bare Application needs the same grant explicitly on API26.
        Shadows.shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        previousStore = requireNotNull(storeField.get(null))
        // The shared store is intentionally internal to its Gradle module. Reflect
        // only this dependency-injection boundary, without touching cache internals.
        val storeConstructor = Class.forName("com.tunisianprayertimes.OfficialIslamicDateStore")
            .declaredConstructors.single { it.parameterCount == 8 }
        storeField.set(null, storeConstructor.newInstance(
            { _: Int -> null as String? }, { _: Int, _: String -> }, { null as String? },
            { _: Int -> null as RamadanOverride? }, { 0L }, { 1447 },
            { _: RamadanOverride -> }, { null as RamadanOverride? },
        ))
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    @After
    fun restoreOfficialDates() {
        // Stop the real lifecycle refresh before returning the singleton to the application.
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        storeField.set(null, previousStore)
    }

    @Test
    fun narrowLargeTextRowShowsBothDatesWithoutClippingAndKeepsRtlControlsBesideTheDate() {
        publishDates(includeFitr = true)
        var previousClicks = 0
        var nextClicks = 0
        show {
            Box(Modifier.width(320.dp).testTag("calendar_surface")) {
                DateNavigationRow(
                    delegationId = -1, selectedDate = calendarDateMillis(ramadanStart), isToday = false,
                    canGoBack = true, canGoForward = true,
                    onPrevious = { previousClicks++ }, onNext = { nextClicks++ }, onDateSelected = {},
                )
            }
        }
        assertCompleteText(hijriDateLabel(TunisianHijriCalendar(OfficialIslamicDates.updates.value).date(ramadanStart)))
        assertCompleteText(gregorianDateLabel(ramadanStart))
        val label = compose.onNodeWithTag(TestTags.DATE_LABEL).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithTag(TestTags.DATE_PREVIOUS_BUTTON).assertIsDisplayed()
        val next = compose.onNodeWithTag(TestTags.DATE_NEXT_BUTTON).assertIsDisplayed()
        val previousBounds = previous.fetchSemanticsNode().boundsInRoot
        val nextBounds = next.fetchSemanticsNode().boundsInRoot
        assertTrue("RTL previous day belongs to the right", previousBounds.left >= nextBounds.right)
        assertTrue("Previous day sits right of the date, not below it", previousBounds.left >= label.right - 1f)
        assertTrue("Next day sits left of the date, not below it", nextBounds.right <= label.left + 1f)
        assertTrue("Previous day shares the date's row", previousBounds.center.y in label.top..label.bottom)
        assertTrue("Next day shares the date's row", nextBounds.center.y in label.top..label.bottom)
        assertTrue("Previous target remains at least 48dp", previousBounds.width >= 48f && previousBounds.height >= 48f)
        assertTrue("Next target remains at least 48dp", nextBounds.width >= 48f && nextBounds.height >= 48f)
        previous.performClick()
        next.performClick()
        assertEquals(1, previousClicks)
        assertEquals(1, nextClicks)
    }

    @Test
    fun largeTextDayListCanReachAndChooseTheLastDayWithBothDatesAccessible() {
        publishDates(includeFitr = true)
        showDialog(ramadanStart, ramadanStart to fitr)
        // The cell's description carries the Hijri and the Gregorian date, and the opening day is the picked one.
        scrollToDay(ramadanStart).assertIsDisplayed().assertIsSelected()
        val lastDay = fitr.minusDays(1)
        val day = scrollToDay(lastDay).assertIsDisplayed().assertIsEnabled()
        assertTrue("Large-text dates use a full-width list", day.fetchSemanticsNode().boundsInRoot.width > 200f)
        day.performClick()
        assertNull("Picking a day does not confirm prematurely", confirmed)
        compose.onNodeWithText(text(R.string.calendar_confirm)).performClick()
        assertEquals(calendarDateMillis(lastDay), confirmed)
        assertEquals(1, dismissals)
    }

    @Test
    @Config(qualifiers = "ar-rTN-w360dp-h740dp-mdpi")
    fun typicalPhoneAtSlightlyLargeTextShowsAMonthGridNotAList() {
        fontScale = 1.15f
        RuntimeEnvironment.setFontScale(fontScale)
        publishDates(includeFitr = true)
        showDialog(ramadanStart, ramadanStart to fitr)
        val first = scrollToDay(ramadanStart).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = scrollToDay(ramadanStart.plusDays(1)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Days are grid cells, not full-width rows: ${first.width}", first.width < 100f)
        assertTrue("Neighbouring days share a row", first.top == second.top)
        assertTrue("Cells remain comfortable to hit: ${first.width}x${first.height}", first.width >= 44f && first.height >= 48f)
    }

    @Test
    @Config(qualifiers = "ar-rTN-w360dp-h740dp-mdpi")
    fun wholeMonthAndTheButtonsFitWithoutScrollingEvenWhenTheMonthSpansSixWeeks() {
        fontScale = 1.15f
        RuntimeEnvironment.setFontScale(fontScale)
        publishDates(includeFitr = true)
        val calendar = TunisianHijriCalendar(OfficialIslamicDates.updates.value)
        val weekStart = WeekFields.of(calendarLocale).firstDayOfWeek
        val sixWeeks = generateSequence(calendar.monthFor(ramadanStart)) { calendar.adjacentMonth(it.year, it.month, 1) }
            .take(36)
            .first { ((it.start.dayOfWeek.value - weekStart.value + 7) % 7 + it.lengthOfMonth + 6) / 7 == 6 }
        showDialog(sixWeeks.start, calendar.supportedFirst to calendar.supportedLast)
        compose.onNodeWithText(hijriMonthLabel(sixWeeks.year, sixWeeks.month)).assertIsDisplayed()
        dayCell(calendar, sixWeeks.start).assertIsDisplayed()
        dayCell(calendar, sixWeeks.endExclusive.minusDays(1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calendar_confirm)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calendar_today)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calendar_cancel)).assertIsDisplayed()
    }

    @Test
    fun monthNavigationHonorsTheAvailableDateRangeInRtl() {
        publishDates(includeFitr = true)
        showDialog(ramadanStart, ramadanStart to fitr)
        val previous = compose.onNodeWithContentDescription(text(R.string.calendar_previous_month))
        val next = compose.onNodeWithContentDescription(text(R.string.calendar_next_month))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(text(R.string.calendar_previous_month)))
        previous.assertIsNotEnabled()
        next.assertIsEnabled()
        assertTrue(previous.fetchSemanticsNode().boundsInRoot.left > next.fetchSemanticsNode().boundsInRoot.left)
        next.performClick()
        compose.onNodeWithText(hijriMonthLabel(1447, 10)).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(text(R.string.calendar_previous_month)))
        previous.assertIsEnabled()
        next.assertIsNotEnabled()
        scrollToDay(fitr).assertIsEnabled()
        scrollToDay(fitr.plusDays(1)).assertIsNotEnabled()
    }

    @Test
    fun swipingTheCalendarMovesTheMonthLikeTheArrowsAndStopsAtTheEndOfTheRange() {
        publishDates(includeFitr = true)
        showDialog(ramadanStart, ramadanStart to fitr)
        val calendarArea = compose.onNode(hasScrollToIndexAction())
        val ramadan = hijriMonthLabel(1447, 9)
        val shawwal = hijriMonthLabel(1447, 10)
        compose.onNodeWithText(ramadan).assertIsDisplayed()
        // Right to left: the next month is on the left, so a drag to the right brings it in.
        calendarArea.performTouchInput { swipeRight() }
        compose.onNodeWithText(shawwal).assertIsDisplayed()
        calendarArea.performTouchInput { swipeRight() }
        compose.onNodeWithText(shawwal).assertIsDisplayed()
        calendarArea.performTouchInput { swipeLeft() }
        compose.onNodeWithText(ramadan).assertIsDisplayed()
        calendarArea.performTouchInput { swipeLeft() }
        compose.onNodeWithText(ramadan).assertIsDisplayed()
        // Scrolling the days up and down is not a month change.
        calendarArea.performTouchInput { swipeUp() }
        calendarArea.performScrollToNode(hasText(ramadan))
        compose.onNodeWithText(ramadan).assertIsDisplayed()
        assertNull("Swiping never confirms a date", confirmed)
    }

    @Test
    @Config(qualifiers = "ar-rTN-w360dp-h740dp-mdpi")
    fun swipingAcrossTheDayGridMovesTheMonthWithoutPickingADay() {
        fontScale = 1.15f
        RuntimeEnvironment.setFontScale(fontScale)
        publishDates(includeFitr = true)
        showDialog(ramadanStart, ramadanStart to fitr)
        val calendarArea = compose.onNode(hasScrollToIndexAction())
        val first = dayCell(TunisianHijriCalendar(OfficialIslamicDates.updates.value), ramadanStart).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("This runs against the grid, not the list: ${first.width}", first.width < 100f)
        calendarArea.performTouchInput { swipeRight() }
        compose.onNodeWithText(hijriMonthLabel(1447, 10)).assertIsDisplayed()
        calendarArea.performTouchInput { swipeLeft() }
        compose.onNodeWithText(hijriMonthLabel(1447, 9)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calendar_confirm)).performClick()
        assertEquals("The picked day is still the day the calendar opened on", calendarDateMillis(ramadanStart), confirmed)
    }

    @Test
    fun openingSelectionIsClampedAndUnavailableDaysCannotBeChosen() {
        publishDates(includeFitr = true)
        val first = ramadanStart.plusDays(5)
        val last = first.plusDays(2)
        showDialog(ramadanStart, first to last)
        scrollToDay(first).assertIsSelected()
        scrollToDay(first.minusDays(1)).assertIsNotEnabled()
        scrollToDay(last.plusDays(1)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.calendar_confirm)).performClick()
        assertEquals(calendarDateMillis(first), confirmed)
    }

    @Test
    fun incomingFitrAnnouncementUpdatesTheOpenSelectionWithoutChangingItsCivilDayOrViewedMonth() {
        publishDates(includeFitr = false)
        showDialog(fitr, ramadanStart to fitr.plusDays(30))
        val provisional = hijriDateLabel(TunisianHijriCalendar(OfficialIslamicDates.updates.value).date(fitr))
        assertTrue("Fixture exercises provisional Ramadan 30", provisional.startsWith("30 "))
        scrollToDay(fitr).assertIsSelected()
        compose.runOnIdle { publishDates(includeFitr = true) }
        val confirmedLabel = hijriDateLabel(TunisianHijriCalendar(OfficialIslamicDates.updates.value).date(fitr))
        assertTrue("Same civil day becomes Shawwal 1", confirmedLabel.startsWith("1 "))
        // Ramadan lost its 30th day, so the viewed month stays Ramadan and the picked day now lives in the next month.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(hijriMonthLabel(1447, 9)))
        compose.onNodeWithText(hijriMonthLabel(1447, 9)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.calendar_next_month)).performClick()
        scrollToDay(fitr).assertIsSelected()
        compose.onNodeWithText(text(R.string.calendar_confirm)).performClick()
        assertEquals(calendarDateMillis(fitr), confirmed)
    }

    @Test
    @Config(qualifiers = "ar-rTN-w640dp-h320dp-land-mdpi")
    fun shortLandscapeAtLargeTextKeepsConfirmationAndCancellationReachable() {
        publishDates(includeFitr = true)
        showDialog(ramadanStart, ramadanStart to fitr)
        compose.onNodeWithText(text(R.string.calendar_confirm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calendar_cancel)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, dismissals)
        assertNull(confirmed)
    }

    @Test
    fun timezoneBroadcastUpdatesTodayWhileTheCalendarRemainsOpen() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val originalDate = LocalDate.now()
            show { Text(rememberCalendarToday().toString()) }
            compose.onNodeWithText(originalDate.toString()).assertIsDisplayed()

            // These zones differ by 25 hours, guaranteeing different civil dates
            // without changing the computer clock or depending on the time of day.
            compose.runOnIdle {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
                ApplicationProvider.getApplicationContext<Application>()
                    .sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            }
            assertTrue(originalDate != LocalDate.now())
            compose.waitForIdle()
            compose.onNodeWithText(LocalDate.now().toString()).assertIsDisplayed()
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            assertEquals("Exercise the configured font setting", fontScale, LocalDensity.current.fontScale, 0f)
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalLifecycleOwner provides lifecycleOwner,
            ) { MaterialTheme(content = content) }
        }
    }

    private fun showDialog(selected: LocalDate, bounds: Pair<LocalDate, LocalDate>) = show {
        HijriCalendarDialog(
            selectedDate = calendarDateMillis(selected),
            dateRange = calendarDateMillis(bounds.first) to calendarDateMillis(bounds.second),
            onDateSelected = { confirmed = it }, onDismiss = { dismissals++ },
        )
    }

    private fun publishDates(includeFitr: Boolean) {
        OfficialIslamicDates.importJson(
            """{"hijriYear":1447,"ramadanStart":"2026-02-19","eidFitrDate":${if (includeFitr) "\"2026-03-20\"" else "null"},"lastUpdated":"2026-03-19T20:00:00Z"}""",
        )
    }

    private fun scrollToDay(date: LocalDate): SemanticsNodeInteraction {
        val calendar = TunisianHijriCalendar(OfficialIslamicDates.updates.value)
        val description = text(R.string.calendar_day_description, hijriDateLabel(calendar.date(date)), gregorianDateLabel(date))
        val matcher = hasContentDescription(description, substring = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    /** The day's cell where it already is, without scrolling to it. */
    private fun dayCell(calendar: TunisianHijriCalendar, date: LocalDate): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription(
            text(R.string.calendar_day_description, hijriDateLabel(calendar.date(date)), gregorianDateLabel(date)),
            substring = true,
        ))

    private fun assertCompleteText(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Text must have a measured layout: $label", results.isNotEmpty())
        results.forEach { layout ->
            val visibleBounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Ancestor must not clip the date horizontally: $label", visibleBounds.width >= layout.size.width - 1f)
            assertTrue("Ancestor must not clip the date vertically: $label", visibleBounds.height >= layout.size.height - 1f)
            assertFalse("Text height must grow to fit: $label", layout.didOverflowHeight)
            assertEquals("Every character is laid out: $label", label.length, layout.getLineEnd(layout.lineCount - 1))
            // Compose 1.8's simple Text semantics re-layout at the parent's max
            // width while retaining the original tight Text size. Translate its
            // line positions back to that size using the actual text alignment.
            val extraWidth = (layout.multiParagraph.width - layout.size.width).coerceAtLeast(0f)
            for (line in 0 until layout.lineCount) {
                assertFalse("No date line may be ellipsized: $label", layout.isLineEllipsized(line))
                val rtl = layout.getParagraphDirection(layout.getLineStart(line)) == ResolvedTextDirection.Rtl
                val horizontalOffset = when (layout.layoutInput.style.textAlign) {
                    TextAlign.Center -> extraWidth / 2f
                    TextAlign.Left -> 0f
                    TextAlign.Right -> extraWidth
                    TextAlign.End -> if (rtl) 0f else extraWidth
                    else -> if (rtl) extraWidth else 0f
                }
                val left = layout.getLineLeft(line) - horizontalOffset
                val right = layout.getLineRight(line) - horizontalOffset
                val diagnostic = "$label: $left..$right in ${layout.size.width}px, align=${layout.layoutInput.style.textAlign}, rtl=$rtl, extra=$extraWidth"
                assertTrue("Date glyphs must stay inside the left edge: $diagnostic", left >= -1f)
                assertTrue("Date glyphs must stay inside the right edge: $diagnostic", right <= layout.size.width + 1f)
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *args)
}
