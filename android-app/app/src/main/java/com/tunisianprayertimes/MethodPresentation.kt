package com.tunisianprayertimes

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.place.MappedZone
import com.tunisianprayertimes.place.PrayerPlace

/**
 * One chip per method (A6, plan 6.11), owner 2026-10-20: «either it is official or it is falakia». «رسمية» where the
 * times follow an official source for the place, «فلكية» where the app computes them from the method.
 */
enum class MethodChip(val labelAr: String) {
    OFFICIAL("رسمية"),
    ASTRONOMICAL("فلكية"),
}

/**
 * How a method is named and labelled on the phone (plan 6.11): Tunisian Arabic names (D19), the authority's name
 * first for a profile, the Fajr and Isha parameters, the chip of A6 and the wording of the zone line.
 */
object MethodPresentation {
    private const val LRI = "⁦"
    private const val PDI = "⁩"
    private const val RLI = "⁧"
    private const val MINUS = "−"

    private val NAMES = mapOf(
        "tunisia_18_18" to "طريقة المعهد الوطني للرصد الجوي (تونس)",
        "mwl" to "رابطة العالم الإسلامي",
        "isna" to "الجمعية الإسلامية لأمريكا الشمالية",
        "karachi" to "جامعة العلوم الإسلامية بكراتشي",
        "umm_al_qura" to "تقويم أم القرى",
        "egyptian" to "الهيئة المصرية العامة للمساحة (طريقة عامة)",
        "mwl_angle" to "المركز الثقافي الإسلامي في أيرلندا",
        "mdf_12" to "مسلمو فرنسا",
        "rhone_ctir" to "مجلس مساجد الرون",
        "be_sunrise_90" to "الفجر قبل الشروق بـ90 دقيقة (بلجيكا)",
        "fajr15_isha90" to "الفجر 15° والعشاء بعد المغرب بـ90 دقيقة",
        "fajr15_isha15" to "الفجر 15° والعشاء 15°",
        "gmp" to "مسجد باريس الكبير",
        "apbif" to "جمعية المشاريع الخيرية الإسلامية في فرنسا (APBIF)",
        "diyanet_eu" to "رئاسة الشؤون الدينية التركية (أوروبا)",
        "diyanet_tr" to "رئاسة الشؤون الدينية التركية",
        "irn" to "المجلس الإسلامي في النرويج",
        "ru_dum_rt" to "الإدارة الدينية لمسلمي تتارستان",
        "bosnia_iz" to "المشيخة الإسلامية في البوسنة والهرسك",
        "hr_miz_zagreb" to "المشيخة الإسلامية في زغرب",
        "si_islamska_skupnost" to "المشيخة الإسلامية في سلوفينيا",
        "luxembourg_shoura" to "مجلس الشورى في لوكسمبورغ",
        "ie_ifi" to "المؤسسة الإسلامية في أيرلندا",
        "mcw" to "لجنة رؤية الهلال العالمية",
        "london_unified" to "جدول لندن الموحد",
        "uk_18_nearest_day" to "18° مع أقرب يوم (بريطانيا)",
        "cil_lisbon" to "الجماعة الإسلامية في لشبونة",
        "dum_rf" to "الإدارة الدينية لمسلمي روسيا",
        "ecfr_90" to "المجلس الأوروبي للإفتاء والبحوث",
        "al_kmsh" to "الجماعة الإسلامية في ألبانيا",
        "xk_bik" to "الجماعة الإسلامية في كوسوفو",
        "mk_bfi" to "الجماعة الدينية الإسلامية في مقدونيا الشمالية",
        "bg_grand_mufti" to "دار الإفتاء في بلغاريا",
        "re_cir" to "المجلس الإسلامي في لا ريونيون",
        "yt_traditional" to "الجدول التقليدي في مايوت",
        "gf_a2cmgf" to "جمعية مسلمي غويانا الفرنسية",
        "mq_ccim" to "المركز الثقافي الإسلامي في مارتينيك",
        "sa_umm_al_qura" to "تقويم أم القرى (السعودية)",
        "eg_egsa" to "الهيئة المصرية العامة للمساحة",
        "ma_habous" to "وزارة الأوقاف والشؤون الإسلامية (المغرب)",
        "dz_marw" to "وزارة الشؤون الدينية والأوقاف (الجزائر)",
        "ly_awqaf" to "الهيئة العامة للأوقاف والشؤون الإسلامية (ليبيا)",
        "mr_islamic_affairs" to "وزارة الشؤون الإسلامية والتعليم الأصلي (موريتانيا)",
        "jo_awqaf" to "وزارة الأوقاف والشؤون والمقدسات الإسلامية (الأردن)",
        "ps_dar_al_ifta" to "دار الإفتاء الفلسطينية",
        "ps_gaza_awqaf" to "وزارة الأوقاف والشؤون الدينية (غزة)",
        "lb_dar_al_fatwa" to "دار الفتوى (لبنان)",
        "sy_awqaf" to "وزارة الأوقاف (سوريا)",
        "iq_sunni_endowment" to "ديوان الوقف السني (العراق)",
        "kw_awqaf" to "وزارة الأوقاف والشؤون الإسلامية (الكويت)",
        "qa_awqaf" to "وزارة الأوقاف والشؤون الإسلامية (قطر)",
        "qa_qch" to "دار التقويم القطري",
        "bh_calendar" to "المجلس الأعلى للشؤون الإسلامية (البحرين)",
        "ae_awqaf" to "الهيئة العامة للشؤون الإسلامية والأوقاف (الإمارات)",
        "om_mara" to "وزارة الأوقاف والشؤون الدينية (عُمان)",
        "ye_awqaf" to "الهيئة العامة للأوقاف والإرشاد (اليمن)",
        "sd_fitted" to "طريقة مستخلصة (السودان)",
        "so_fitted" to "طريقة مستخلصة (الصومال)",
        "dj_fitted" to "طريقة مستخلصة (جيبوتي)",
        "km_fitted" to "الجدول التقليدي (جزر القمر)",
        PrayerMethod.CUSTOM_ID to "مخصّصة",
    )

    /** User-facing sources, not the shared model's English research notes or internal fit paths. */
    private val SOURCES = mapOf(
        "tunisia_18_18" to "القيم المنشورة للمعهد الوطني للرصد الجوي؛ أمّا قاعدة الصيف فمن التطبيق.",
        "mwl" to "زاويتا رابطة العالم الإسلامي، دون إضافات الدقائق المقرّرة عام ${LRI}1986${PDI}.",
        "isna" to "الزاويتان المعتمدتان لدى الجمعية الإسلامية لأمريكا الشمالية.",
        "karachi" to "الزاويتان المعتمدتان لدى جامعة العلوم الإسلامية بكراتشي.",
        "umm_al_qura" to "طريقة مستخلصة من جداول مدينة الملك عبد العزيز للعلوم والتقنية، التي لا تنشر تعريفًا مكتوبًا للطريقة.",
        "egyptian" to "زاويتا الهيئة المصرية العامة للمساحة.",
        "mwl_angle" to "شيفرة موقع المركز الثقافي الإسلامي في أيرلندا لعام ${LRI}2026${PDI}، لا جدوله المنشور.",
        "mdf_12" to "الطريقة الشائعة في مساجد فرنسا ضمن عيّنة البيانات، بزاويتي ${LRI}12°${PDI} و${LRI}12°${PDI}.",
        "rhone_ctir" to "التقويم الموحد لمجلس مساجد الرون لعام ${LRI}2020${PDI}.",
        "be_sunrise_90" to "الطريقة الشائعة في مساجد بلجيكا ضمن عيّنة البيانات.",
        "fajr15_isha90" to "طريقة شائعة في مساجد سويسرا وألمانيا ضمن عيّنة البيانات.",
        "fajr15_isha15" to "طريقة شائعة في مساجد فرنسا وإيطاليا وإسبانيا وسويسرا وهولندا وبريطانيا ضمن عيّنة البيانات.",
        "gmp" to "جدول مسجد باريس الكبير لشهر أكتوبر ${LRI}2026${PDI}؛ لا يتجاوز الفرق دقيقة واحدة في أيّ من الأوقات الستة.",
        "apbif" to "شرح طريقة الحساب وجداول عام ${LRI}2026${PDI} المنشورة لدى جمعية المشاريع الخيرية الإسلامية في فرنسا.",
        "diyanet_eu" to "معايير مؤتمر رئاسة الشؤون الدينية التركية لعام ${LRI}2021${PDI}، مع التحقق من جداولها الأوروبية لعام ${LRI}2027${PDI}.",
        "diyanet_tr" to "رئاسة الشؤون الدينية التركية.",
        "irn" to "جداول المجلس الإسلامي في النرويج لعام ${LRI}2026${PDI}.",
        "ru_dum_rt" to "جداول الإدارة الدينية لمسلمي تتارستان لعام ${LRI}2026${PDI}، وتشمل ${LRI}44${PDI} مكانًا.",
        "bosnia_iz" to "تقويم المشيخة الإسلامية في البوسنة والهرسك.",
        "hr_miz_zagreb" to "إمساكية المشيخة الإسلامية في زغرب لرمضان ${LRI}2025${PDI}.",
        "si_islamska_skupnost" to "جداول موقع ${LRI}vaktija.si${PDI} من أكتوبر إلى ديسمبر ${LRI}2026${PDI}.",
        "luxembourg_shoura" to "جدول مجلس الشورى في لوكسمبورغ لعام ${LRI}2026${PDI}.",
        "ie_ifi" to "الجدول الدائم للمؤسسة الإسلامية في أيرلندا، لمدينة دبلن.",
        "mcw" to "معاملات لجنة رؤية الهلال العالمية من مكتبة ${LRI}adhan${PDI} بترخيص ${LRI}MIT${PDI}؛ لم نتحقّق من نصّ أصلي منشور للجنة.",
        "london_unified" to "طريقة تقريبية لجدول لندن الموحد.",
        "uk_18_nearest_day" to "دراسة برادفورد لعام ${LRI}1983${PDI}؛ زاوية العشاء مفترضة.",
        "cil_lisbon" to "موقع ${LRI}Aladhan${PDI} فقط، دون مصدر مباشر من الجماعة الإسلامية في لشبونة.",
        "dum_rf" to "جداول موسكو الشهرية الصادرة عن الإدارة الدينية لمسلمي روسيا.",
        "ecfr_90" to "بند منسوب إلى المجلس الأوروبي للإفتاء والبحوث، منقول عن مصدر ثانوي؛ لم نتحقّق من نصّه الأصلي.",
        "al_kmsh" to "تقويم الجماعة الإسلامية في ألبانيا لعام ${LRI}2026${PDI}.",
        "xk_bik" to "التقويم الدائم للجماعة الإسلامية في كوسوفو، طبعة ${LRI}2026${PDI}.",
        "mk_bfi" to "مواقيت يوم واحد في سكوبيه من موقع الجماعة الدينية الإسلامية في مقدونيا الشمالية؛ الطريقة مستخلصة منها.",
        "bg_grand_mufti" to "جداول دار الإفتاء في بلغاريا لشهر أكتوبر ${LRI}2026${PDI}.",
        "re_cir" to "جداول المجلس الإسلامي في لا ريونيون للأعوام ${LRI}2023–2026${PDI}، ولوحة مواقيت برا فوزيل.",
        "yt_traditional" to "الجدول المنشور في مايوت ومواقيت مساجدها.",
        "gf_a2cmgf" to "تقاويم جمعية مسلمي غويانا الفرنسية، التي تنصّ على اتباع طريقة رابطة العالم الإسلامي.",
        "mq_ccim" to "صفحة ${LRI}IslamicFinder${PDI} التي يحيل إليها المركز الثقافي الإسلامي في مارتينيك، لشهر أكتوبر ${LRI}2026${PDI}.",
        "sa_umm_al_qura" to "طريقة مستخلصة من جداول مدينة الملك عبد العزيز للعلوم والتقنية وخدمتها لحساب المواقيت.",
        "eg_egsa" to "جداول المدن المنشورة لدى الهيئة المصرية العامة للمساحة، مع التصريح بزاوية الفجر.",
        "ma_habous" to "جداول المناطق المنشورة لدى وزارة الأوقاف والشؤون الإسلامية في المغرب.",
        "dz_marw" to "تقويما وزارة الشؤون الدينية والأوقاف في الجزائر لعامي ${LRI}1447${PDI} و${LRI}1448${PDI}.",
        "ly_awqaf" to "الجدول الدائم للهيئة العامة للأوقاف في ليبيا كما طُبع، بسنة مرجعية هي ${LRI}2006${PDI}.",
        "mr_islamic_affairs" to "جداول رمضان لوزارة الشؤون الإسلامية والتعليم الأصلي في موريتانيا؛ تُطبَّق طريقتها على السنة كلها.",
        "jo_awqaf" to "تقويم وزارة الأوقاف في الأردن، المحسوب بالتعاون مع المشروع الإسلامي لرصد الأهلة.",
        "ps_dar_al_ifta" to "الجدول الدائم للمسجد الأقصى الصادر عن دار الإفتاء الفلسطينية كما طُبع، بسنة مرجعية هي ${LRI}2016${PDI}.",
        "ps_gaza_awqaf" to "جدول وزارة الأوقاف في غزة لعام ${LRI}2026${PDI}، عند إحداثيات مدينة غزة.",
        "lb_dar_al_fatwa" to "إمساكية دار الفتوى في بيروت لرمضان ${LRI}1445${PDI}.",
        "sy_awqaf" to "الجداول السنوية المعروضة على شاشات مساجد وزارة الأوقاف في سوريا، بحسب كل منطقة.",
        "iq_sunni_endowment" to "جداول المدن الدائمة لديوان الوقف السني في العراق كما طُبعت، بسنة مرجعية هي ${LRI}2021${PDI}.",
        "kw_awqaf" to "الجدول الدائم لوزارة الأوقاف في الكويت كما طُبع، بسنة مرجعية هي ${LRI}2011${PDI}.",
        "qa_awqaf" to "جداول دار التقويم القطري ووزارة الأوقاف في قطر.",
        "qa_qch" to "جداول دار التقويم القطري.",
        "bh_calendar" to "تقويم المجلس الأعلى للشؤون الإسلامية في البحرين.",
        "ae_awqaf" to "جداول الأوقاف في الإمارات، المحسوبة ببرنامج ${LRI}Accurate Times${PDI}.",
        "om_mara" to "جداول المناطق المنشورة لدى وزارة الأوقاف والشؤون الدينية في عُمان.",
        "ye_awqaf" to "جداول الهيئة العامة للأوقاف والإرشاد في اليمن، وتشمل ${LRI}31${PDI} موقعًا في صنعاء.",
        "sd_fitted" to "مواقيت مسجدين في عيّنة البيانات، والطريقة الجاهزة للمشروع الإسلامي لرصد الأهلة.",
        "so_fitted" to "مواقيت ثلاثة مساجد في عيّنة البيانات؛ قد تعكس الإعداد الافتراضي لبرامجها.",
        "dj_fitted" to "مواقيت ستة مساجد في عيّنة البيانات؛ قد تعكس الإعداد الافتراضي لبرامجها.",
        "km_fitted" to "جدول موروني، المعتمد لدى ${LRI}16${PDI} مسجدًا من أصل ${LRI}24${PDI} في عيّنة البيانات.",
        PrayerMethod.CUSTOM_ID to "إعدادات طريقة الحساب التي اخترتها.",
    )

    fun name(methodId: String): String = NAMES[methodId] ?: methodId

    fun source(method: PrayerMethod): String = SOURCES[method.id] ?: method.evidence.source

    /** Every method the app names, for the source-scan test. */
    val namedIds: Set<String> get() = NAMES.keys

    /** Read from the right: Fajr first, then Isha. Keep each label and its value on the same line. */
    fun parameters(method: PrayerMethod): String {
        val fajr = when (val f = method.fajr) {
            is FajrRule.Angle -> degrees(f.degrees)
            is FajrRule.MinutesBeforeSunrise -> minutes(f.minutes)
        }
        val isha = when (val i = method.isha) {
            is IshaRule.Angle -> degrees(i.degrees)
            is IshaRule.MinutesAfter -> minutes(i.minutes)
        }
        return "${RLI}الفجر\u00a0$fajr · العشاء\u00a0$isha$PDI"
    }

    private fun minutes(value: Int): String = "$value\u00a0د"

    /** The row of «طريقة الحساب»: the name, then its parameters. */
    fun summary(method: PrayerMethod): String = name(method.id) + " · " + parameters(method)

    private fun degrees(value: Double): String {
        val number = value.toBigDecimal().stripTrailingZeros().toPlainString()
        return "$LRI$number°$PDI"
    }

    /** A signed minute value in an LTR isolate with U+2212 (6.11): «⁦−3⁩», «⁦+5⁩». */
    fun signedMinutes(value: Int): String = LRI + (if (value < 0) MINUS + (-value) else "+$value") + PDI

    /** The country of each method with official zones, where a zone names no other. A place outside it never shows «رسمية». */
    private val PROFILE_COUNTRY = mapOf(
        "sa_umm_al_qura" to "SA", "eg_egsa" to "EG", "ma_habous" to "MA", "jo_awqaf" to "JO", "qa_awqaf" to "QA", "qa_qch" to "QA",
        "ae_awqaf" to "AE", "om_mara" to "OM", "ye_awqaf" to "YE", "dz_marw" to "DZ", "ly_awqaf" to "LY",
        "mr_islamic_affairs" to "MR", "ps_dar_al_ifta" to "PS", "ps_gaza_awqaf" to "PS", "sy_awqaf" to "SY", "kw_awqaf" to "KW",
        "iq_sunni_endowment" to "IQ", "lb_dar_al_fatwa" to "LB", "bh_calendar" to "BH",
        "irn" to "NO", "luxembourg_shoura" to "LU", "diyanet_eu" to "AT", "bosnia_iz" to "BA", "diyanet_tr" to "TR",
        "xk_bik" to "XK", "bg_grand_mufti" to "BG", "si_islamska_skupnost" to "SI", "hr_miz_zagreb" to "HR", "ie_ifi" to "IE",
        "dum_rf" to "RU",
    )

    /**
     * The chip of A6 (6.11): «رسمية» for a national body's own definition in its own country, for `sa_umm_al_qura`
     * anywhere in Saudi Arabia (KACST computes for any point), and for an authority's table at the mapped zone in the
     * zone's own country; «فلكية» everywhere else: exact coordinates, a place no zone serves (the Kurdistan Region, 8.8),
     * a table no authority publishes, Bosnia's table stood in for Montenegro's, and a custom method.
     */
    fun chip(method: PrayerMethod, place: PrayerPlace, mapped: MappedZone?): MethodChip {
        val cc = (place as? PrayerPlace.Coordinates)?.countryCode
        val official = !method.isCustom && cc != null && when {
            (method.id == "bosnia_iz" && cc == "BA") || (method.id == "diyanet_tr" && cc == "TR") -> true
            method.id == "sa_umm_al_qura" -> cc == "SA"
            mapped == null || mapped.zone.authority in UNOFFICIAL_ZONES || standsInForMontenegro(method.id, place) -> false
            else -> (mapped.zone.country ?: PROFILE_COUNTRY[method.id]) == cc
        }
        return if (official) MethodChip.OFFICIAL else MethodChip.ASTRONOMICAL
    }

    /** Tables no authority publishes for that place drop «الرسمي» (6.11). */
    private val UNOFFICIAL_ZONES = setOf("rhone_ctir", "km_fitted", "yt_traditional", "re_cir")

    /** Bosnia's table, shifted, where the Islamic Community in Montenegro's own was not found (8.8). */
    private fun standsInForMontenegro(methodId: String, place: PrayerPlace) =
        methodId == "bosnia_iz" && (place as? PrayerPlace.Coordinates)?.countryCode == "ME"

    /** The zone line: «الجدول الرسمي المعتمد: مسقط», or «الجدول المعتمد: الرون». */
    fun zoneLine(methodId: String, mapped: MappedZone, place: PrayerPlace): String {
        val prefix = if (methodId in UNOFFICIAL_ZONES || standsInForMontenegro(methodId, place)) "الجدول المعتمد" else "الجدول الرسمي المعتمد"
        val name = mapped.baseZone?.takeIf { mapped.zone.id.startsWith("me:") }?.nameAr ?: mapped.zone.nameAr
        return "$prefix: $name"
    }
}
