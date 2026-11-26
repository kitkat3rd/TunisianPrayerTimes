package com.tunisianprayertimes.adhan

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.tunisianprayertimes.R

/** The interval between samples in the bundled waveform files. */
internal const val ADHAN_LEVEL_INTERVAL_MILLIS = 50L

/**
 * The adhan recordings bundled with the app, each for Fajr or for the other prayers, never both. The Fajr ones say
 * «الصلاة خير من النوم», which the five others lack (the owner listened to all of them, 2026-11-08), so Fajr offers
 * only them and the other prayers never do. The five general ones are the wake alarms' adhans; their enum names are
 * the wake presets', so a voice saved before still reads. Display labels use only the muezzin's name in both groups.
 *
 * All eight are Opus, mono, 32 kbps, in .ogg (owner's choice for size, 2026-11-08): 8.2 MB instead of 27.4 MB of MP3.
 * Android 5+ plays Opus in Ogg, and the build keeps .ogg raw files uncompressed, as MediaPlayer needs.
 *
 * The Fajr recordings are captures of the Haramain's broadcasts found on archive.org, where no licence is stated:
 * - Ali Ahmed Mulla, al-Masjid al-Haram, 28 Nov 2014: archive.org/details/athan_28-11-2014
 *   (athan_28-11-2014_ali_mulla_f.mp3).
 * - Muhammad Marwan Qassas, al-Masjid an-Nabawi: the app's general Madinah adhan (his, adhan_madinah_marwan_qassas)
 *   with «الصلاة خير من النوم» twice inserted after its second «حي على الفلاح» (owner's idea and approval, 2026-11-08).
 *   The two phrases come from his Fajr adhan of 6 Safar 1446 on tilawatalharamain.com
 *   (archive.org/download/my-audio-22_20240815/My%20Audio22.mp3, 216.45-251.0 s), lowered 1.15 semitones to the
 *   general recording's key, equalised to its tone, given a matching stereo hall echo, and levelled to its phrases.
 *   Two earlier choices were dropped: a broadcast capture whose sound the owner found poor, and the owner's own
 *   recording (24567g1qh1.mp3), which turned out to lack «الصلاة خير من النوم».
 * - Abdulmajeed Al-Surayhi, al-Masjid an-Nabawi, 14 Oct 2014: archive.org/details/athan_fajr_as-surayhi
 *   (athan_fajr_14-10-2014_as-surayhi.mp3).
 */
enum class AdhanVoice(
    @param:RawRes val rawResId: Int,
    /** The recording's loudness, 20 bytes a second (0 = rest, 255 = its loudest), for the page's live waveform. */
    @param:RawRes val levelResId: Int,
    @param:StringRes val titleResId: Int,
    val forFajr: Boolean,
) {
    ADHAN_ISLAM_SOBHI(R.raw.adhan_islam_sobhi, R.raw.adhan_islam_sobhi_level, R.string.adhan_voice_islam_sobhi, forFajr = false),
    ADHAN_OMAR_HISHAM_AL_ARABI(R.raw.adhan_omar_hisham_al_arabi, R.raw.adhan_omar_hisham_al_arabi_level, R.string.adhan_voice_omar_hisham_al_arabi, forFajr = false),
    ADHAN_ABDULLAH_AL_ZAILI(R.raw.adhan_abdullah_al_zaili, R.raw.adhan_abdullah_al_zaili_level, R.string.adhan_voice_abdullah_al_zaili, forFajr = false),
    ADHAN_MADINAH_MARWAN_QASSAS(R.raw.adhan_madinah_marwan_qassas, R.raw.adhan_madinah_marwan_qassas_level, R.string.adhan_voice_marwan_qassas, forFajr = false),
    ADHAN_NASSER_AL_QATAMI(R.raw.adhan_nasser_al_qatami, R.raw.adhan_nasser_al_qatami_level, R.string.adhan_voice_nasser_al_qatami, forFajr = false),
    FAJR_ALI_MULLA(R.raw.adhan_fajr_ali_mulla, R.raw.adhan_fajr_ali_mulla_level, R.string.adhan_voice_ali_mulla, forFajr = true),
    FAJR_MARWAN_QASSAS(R.raw.adhan_fajr_marwan_qassas, R.raw.adhan_fajr_marwan_qassas_level, R.string.adhan_voice_marwan_qassas, forFajr = true),
    FAJR_ABDULMAJEED_SURAYHI(R.raw.adhan_fajr_abdulmajeed_surayhi, R.raw.adhan_fajr_abdulmajeed_surayhi_level, R.string.adhan_voice_abdulmajeed_surayhi, forFajr = true),
}
