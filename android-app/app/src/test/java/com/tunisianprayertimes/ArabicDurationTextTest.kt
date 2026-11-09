package com.tunisianprayertimes

import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicDurationTextTest {
    @Test
    fun duration_underAnHour_readsInMinutes() {
        assertEquals("دقيقة واحدة", formatArabicDuration(1))
        assertEquals("دقيقتين", formatArabicDuration(2))
        assertEquals("5 دقائق", formatArabicDuration(5))
        assertEquals("20 دقيقة", formatArabicDuration(20))
    }

    @Test
    fun duration_wholeHours_dropTheMinutes() {
        assertEquals("ساعة", formatArabicDuration(60))
        assertEquals("ساعتين", formatArabicDuration(120))
        assertEquals("3 ساعات", formatArabicDuration(180))
        assertEquals("12 ساعة", formatArabicDuration(720))
    }

    @Test
    fun duration_hoursAndMinutes_joinWithWa() {
        assertEquals("ساعة و30 دقيقة", formatArabicDuration(90))
        assertEquals("ساعة ودقيقة واحدة", formatArabicDuration(61))
        assertEquals("ساعتين و15 دقيقة", formatArabicDuration(135))
        assertEquals("3 ساعات و5 دقائق", formatArabicDuration(185))
        assertEquals("16 ساعة و39 دقيقة", formatArabicDuration(999))
    }

    @Test
    fun byDuration_joinsTheBaToAWord_andSeparatesItFromADigit() {
        assertEquals("بـ 20 دقيقة", formatArabicByDuration(20))
        assertEquals("بـ 3 ساعات", formatArabicByDuration(180))
        assertEquals("بدقيقة واحدة", formatArabicByDuration(1))
        assertEquals("بساعة", formatArabicByDuration(60))
        assertEquals("بساعة و30 دقيقة", formatArabicByDuration(90))
        assertEquals("بساعتين و15 دقيقة", formatArabicByDuration(135))
    }
}
