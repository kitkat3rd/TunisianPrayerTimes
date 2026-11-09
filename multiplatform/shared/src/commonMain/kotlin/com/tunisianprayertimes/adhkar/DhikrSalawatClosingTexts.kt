package com.tunisianprayertimes.adhkar

import com.tunisianprayertimes.adhkar.DhikrClosingTexts.ClosingText
import com.tunisianprayertimes.adhkar.DhikrClosingTexts.ClosingTheme

/** The eight approved Salawat benefits, separate from the generic dhikr rewards. See docs/adhkar-sources.md. */
object DhikrSalawatClosingTexts {
    val ALL: List<ClosingText> = listOf(
        ClosingText("salawat_muslim_408", ClosingTheme.SALAWAT, quran = false,
            heading = "عشر صلوات من الله",
            text = "قال رسول الله ﷺ: «مَنْ صَلَّى عَلَىَّ وَاحِدَةً صَلَّى اللَّهُ عَلَيْهِ عَشْرًا».",
            reference = "صحيح مسلم 408"),
        ClosingText("al_ahzab_56", ClosingTheme.SALAWAT, quran = true,
            heading = "طاعة لأمر الله",
            text = "إِنَّ اللَّهَ وَمَلَائِكَتَهُ يُصَلُّونَ عَلَى النَّبِيءِ ۚ يَا أَيُّهَا الَّذِينَ آمَنُوا صَلُّوا عَلَيْهِ وَسَلِّمُوا تَسْلِيمًا",
            reference = "الأحزاب 56"),
        ClosingText("salawat_abudawud_1531", ClosingTheme.SALAWAT, quran = false,
            heading = "صلاتك معروضة عليه يوم الجمعة",
            text = "قال رسول الله ﷺ: «إِنَّ مِنْ أَفْضَلِ أَيَّامِكُمْ يَوْمَ الْجُمُعَةِ فَأَكْثِرُوا عَلَىَّ مِنَ الصَّلاَةِ فِيهِ فَإِنَّ صَلاَتَكُمْ مَعْرُوضَةٌ عَلَىَّ».",
            reference = "سنن أبي داود 1531", fridayOnly = true),
        ClosingText("salawat_nasai_1297", ClosingTheme.SALAWAT, quran = false,
            heading = "محو الخطايا ورفعة الدرجات",
            text = "قال رسول الله ﷺ: «مَنْ صَلَّى عَلَىَّ صَلاَةً وَاحِدَةً صَلَّى اللَّهُ عَلَيْهِ عَشْرَ صَلَوَاتٍ وَحُطَّتْ عَنْهُ عَشْرُ خَطِيئَاتٍ وَرُفِعَتْ لَهُ عَشْرُ دَرَجَاتٍ».",
            reference = "سنن النسائي 1297"),
        ClosingText("salawat_abudawud_2042", ClosingTheme.SALAWAT, quran = false,
            heading = "صلاتك تبلغه أينما كنت",
            text = "قال رسول الله ﷺ: «لاَ تَجْعَلُوا بُيُوتَكُمْ قُبُورًا وَلاَ تَجْعَلُوا قَبْرِي عِيدًا وَصَلُّوا عَلَىَّ فَإِنَّ صَلاَتَكُمْ تَبْلُغُنِي حَيْثُ كُنْتُمْ».",
            reference = "سنن أبي داود 2042"),
        ClosingText("salawat_abudawud_1481", ClosingTheme.SALAWAT, quran = false,
            heading = "من أسباب إجابة الدعاء",
            text = "قال رسول الله ﷺ: «إِذَا صَلَّى أَحَدُكُمْ فَلْيَبْدَأْ بِتَحْمِيدِ رَبِّهِ جَلَّ وَعَزَّ وَالثَّنَاءِ عَلَيْهِ ثُمَّ يُصَلِّي عَلَى النَّبِيِّ ثُمَّ يَدْعُو بَعْدُ بِمَا شَاءَ».",
            reference = "سنن أبي داود 1481"),
        // A scholarly explanation, explicitly attributed; never presented as a Prophetic hadith.
        ClosingText("salawat_love", ClosingTheme.SALAWAT, quran = false,
            heading = "زيادة محبة النبي ﷺ",
            text = "قال ابن القيم في فوائد الصلاة على النبي ﷺ: «أنها سبب لدوام محبَّته للرسول ﷺ وزيادتها وتضاعفها».",
            reference = "ابن القيم، جلاء الأفهام، ص 525"),
        ClosingText("salawat_tirmidhi_484", ClosingTheme.SALAWAT, quran = false,
            heading = "القرب منه يوم القيامة",
            text = "قال رسول الله ﷺ: «أَوْلَى النَّاسِ بِي يَوْمَ الْقِيَامَةِ أَكْثَرُهُمْ عَلَىَّ صَلاَةً».",
            reference = "سنن الترمذي 484"),
    )

    /** Friday's report must not describe a reading completed on another day. */
    fun eligible(isFriday: Boolean): List<ClosingText> = ALL.filter { isFriday || !it.fridayOnly }
}
