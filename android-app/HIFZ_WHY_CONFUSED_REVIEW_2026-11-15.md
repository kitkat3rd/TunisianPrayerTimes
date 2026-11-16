# Review of «لماذا يلتبس؟» — 15 November 2026

This review covers the section «لماذا يلتبس؟» in the hifz screens: what makes each line, whether each line is true, how it reads, and where it shows. Nothing in the code was changed.

**How I checked.** I read the code. Then I wrote a script that repeats each rule of `hifzFix` and `HifzLookAlikes` on the bundled Qaloun text (`assets/quran/index.json`, 6214 verses). For each verse, the script takes the first look-alike the record sheet offers, as if the reader picked it. The script and its output are in `build/why-confused-review-20261115/`.

The script is a copy of the rules in JavaScript, not the app code itself, so small differences are possible. I checked it in two places against the app: the test values in `HifzTest.kt` («تشترك الآيتان 68 و69 في أول 7 كلمات») and the screenshot from the OnePlus (Az-Zumar 31, two other verses).

## Summary

Most lines are true for the letters, but they do not help the reader.

1. The app compares words without vowels, hamza seat or dagger alef. So some words it calls "shared" are different words when recited. These are often the exact words the reader confuses.
2. Almost no line names the words or the verses it talks about.
3. The heading asks "why", but several lines only give a location or repeat a setting. They do not say why the verse is confused.

## Where the section shows

| Screen | When | Source |
|---|---|---|
| Fix sheet of a point | Always, after the correction, before «ما يذكّرك بها» | [HifzSheets.kt:1409](app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt) |
| Review, full screen | After «أظهر»; not in the drill | [HifzReview.kt:663](app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt) |
| Review on the page (the screenshot) | After «أظهر», under the three answers; not in the drill | [HifzReview.kt:802](app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt) |
| «مراجعة الدفتر» | Not in the drill | [HifzPractice.kt:609](app/src/main/java/com/tunisianprayertimes/ui/HifzPractice.kt) |

The section shows only when there is at least one line. Over all 6214 verses, as measured by the script:

- **«شبيهة» and «خاتمة»:** always at least one line, because the location line is always added.
- **«أول الآية»:** 2844 verses get the count line (46 %).
- **«تخطٍّ» and «آيات متتالية»:** 1211 verses get a line (19 %).
- **«كلمة»:** never a line.

## The lines, one by one

All lines are made in [HifzText.kt:433-493](app/src/main/java/com/tunisianprayertimes/quran/hifz/HifzText.kt).

| Kind | Line | True? | Says why? |
|---|---|---|---|
| look-alike | «الآيتان متطابقتان؛ الفرق فيما يأتي بعد كل منهما» | Yes (260 of the checked look-alikes; 4 of them differ only by a mark from the word before) | Yes |
| «شبيهة» | «تتفقان في أول N كلمات، وتفترقان بعد كلمة «X»» | Mostly; see finding 1 | Yes, and it is the only line that quotes a word |
| «خاتمة» | «تتفقان في N كلمات قبل الخاتمة» | Mostly; see finding 1 | Partly: it does not say which words or which ending |
| look-alike | «تشتركان في N كلمات متتالية» | Mostly; see finding 1 | Partly: it does not say which words |
| look-alike | «الأخرى في سورة X» | Yes | No: it says where the other verse is |
| look-alike | «متتاليتان في السورة نفسها» / «في السورة نفسها، بينهما N آيات» | Yes | Yes: nearness is a real cause |
| look-alike, both ways | «قد تقرأ كلًّا منهما مكان الأخرى، فنسألك عن الآيتين كلتيهما» | Can become false (finding 6) | No: it repeats the reader's setting |
| «تخطٍّ», «آيات متتالية» | «تشترك الآيتان 68 و69 في أول 7 كلمات» | Yes | Yes |
| «تخطٍّ», «آيات متتالية» | «الآية X في أول الصفحة N» / «في آخر الصفحة N» | Yes; page = image + 1, as «انتقل إلى صفحة» does | Not said: the reader must guess "page turn" |
| «أول الآية» | «N آيات أخرى في المصحف تبدأ بالكلمتين نفسيهما» | Yes for the letters | Weakly; see finding 4 |
| «أول الآية» | «أول سورة X» | Yes | No: it repeats the question «ما أول آية في سورة X؟» |
| «كلمة» | (nothing) | | |

## Findings

### 1. Some "shared" words are different words when recited — high

The comparison key ([normalizeQuranSearch](app/src/main/java/com/tunisianprayertimes/quran/QuranRepository.kt), used by `hifzWords`) removes all vowel marks, shadda and the dagger alef, and writes أ and إ as ا. This key was made for search. The look-alike facts use the same key.

**Your rule (15 November):** a different case ending makes a different word («أَنفُسَهُمْ / أَنفُسِهِمْ» are different). The script now uses this rule.

I also assumed that marks which come from the words around a word do not make a different word. These are:

- the shadda of idgham on the first letter («مِّن / مِن»)
- the vowel on a joining alef, and Qaloun's small marks
- the stop signs
- open or closed tanween, and the iqlab meem («عَلِيمُۢ» = «عَلِيمٌ»)
- a sukun printed or left out («كُنتُمْ / كُنتُم»)
- «إِلَى / إِلَىٰ»
- a final sukun said with a vowel before a joining alef

Please correct me if one of these should count.

With these rules, the script checked the shared-words line of 8072 look-alikes, the first one the record sheet offers for each verse. **766 of these lines (about 1 in 10) count at least one word as shared that is recited differently:**

- **308 lines have a difference inside a word:**
  - hamza seat (133 words): «إِنَّ / أَنَّ», «إِن / أَن»
  - dagger alef (26 words): «لَسَٰحِرٌ / لَسِحْرٞ» (Al-A'raf 108 and Yunus 2), «قَٰتِلُواْ / قُتِلُواْ» (Al Imran 167 and 169), «يُّقْتَلُ / يُّقَٰتِلْ» (Al-Baqara 153 and An-Nisa 73), «وَبَطَلَ / وَبَٰطِلٞ»
  - other vowels or shadda (162 words): «يُغْفَرْ / يَغْفِرْ», «أُنزِلَ / أَنزَلَ», «وَجَٰهِدُواْ / وَجَٰهَدُواْ»
- **458 lines differ only at the end of a word:** a case ending such as «اَ۬للَّهَ / اَ۬للَّهُ», «كُلَّ / كُلِّ», «عَذَابٌ / عَذَابُ», «صِرَٰطٖ / صِرَٰطِ», or a final shadda such as «إِن / إِنَّ», «وَلَٰكِنْ / وَلَٰكِنَّ».

The 260 pairs of verses that are the same in letters are also the same when recited, so the line «الآيتان متطابقتان» is right.

Example: Al-A'raf 108 ends «إِنَّ هَٰذَا لَسَٰحِرٌ عَلِيمٞ», and Yunus 2 ends «إِنَّ هَٰذَا لَسِحْرٞ مُّبِينٌۖ». The app says «تتفقان في 3 كلمات قبل الخاتمة». Recited, they share 2 words. The third word, «لساحر / لسحر», is a known trap in Qaloun's reading.

The same key also chooses the words marked as different in the comparison (`hifzDifferences`). So «لسحر» is not marked either.

### 2. The lines do not name the words or the verses — high

- «تتفقان في 3 كلمات قبل الخاتمة»: which 3 words, and the ending of which verse?
- «تشتركان في 4 كلمات متتالية»: which 4 words?
- «آيتان أخريان في المصحف تبدآن بالكلمتين نفسيهما»: which two words, and which two verses?

Only the shared-opening line of «شبيهة» quotes a word: «…وتفترقان بعد كلمة «X»». The reader cannot check the other lines and cannot learn from them.

### 3. The heading promises "why"; several lines are not reasons — high

The code comment says these lines are «facts a script can prove». The heading says «لماذا يلتبس؟». The gap between the two is why the section feels confusing.

- **«الأخرى في سورة X» is the most frequent line:** in 6079 of the 8072 look-alikes the script checked. It only says where the other verse is, and the look-alike's own heading already names it («الشبيهة · الأعراف · الآية 35»).
- **«قد تقرأ كلًّا منهما مكان الأخرى، فنسألك عن الآيتين كلتيهما»** repeats the reader's own choice on the record sheet and says what the app does.
- **«أول سورة X»** repeats the question above it.
- **«في أول الصفحة N»** does not say that the page turn is the cause. Also, it is not true for every page in a printed book:
  - An open book shows two pages side by side. Going from the right-hand page to the left-hand page, nothing moves: the end of the verse before stays in view. Only going from the left-hand page to the next right-hand page turns a sheet. So only every second page start comes after a turn.
  - On the phone, every page is its own screen, so every page start is a swipe.
  - **In your book, odd pages are on the right** (your answer, 15 November). So a sheet is turned only before an odd page (3, 5, 7…). Only the last verse of an even page comes right before a turn.
  - The page in these lines is the printed page (the app's image + 1), so its number tells which case applies.
  - Of the 545 verses that get «في أول الصفحة N», 274 are at the top of an odd page, after a turn. Of the 599 that get «في آخر الصفحة N», 300 are at the foot of an even page, before a turn. In the book, the other half have the verse before (or after) in view on the facing page.

### 4. «أول الآية»: the count helps little — medium

[HifzText.kt:486-491](app/src/main/java/com/tunisianprayertimes/quran/hifz/HifzText.kt) counts the verses whose first two words have the same letters.

- **It does not explain a forgotten opening.** It explains a different slip: going on with another verse's words.
- **Large counts say nothing.** 196 verses would show 31 or more; «يا أيها الذين» shows 89.
- **«و» and «ف» twins are not counted:** 312 verses have one. For Az-Zumar 31 the line counts 2 verses with «فَمَنْ أَظْلَمُ» and leaves out the 8 with «وَمَنْ أَظْلَمُ».
- **It always stops at two words.** The three «فَمَنْ أَظْلَمُ» verses share three words. Then the other two go on «اِ۪فْتَرَىٰ عَلَى اَ۬للَّهِ كَذِباً», and Az-Zumar 31 goes on «كَذَبَ عَلَى اَ۬للَّهِ». That parting is the real trap, and the line does not show it.

### 5. «كلمة» slips never get a line — medium

The code's own note lists «كلمة» first, as the most common kind. A forgotten opening is now recorded as a «كلمة» slip on the first word ([Hifz.kt:27](app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt)). So old «أول الآية» points get the opening line, and new points for the same slip get nothing.

### 6. «فنسألك عن الآيتين كلتيهما» can become false — medium

"Both ways" makes a second point on the other verse ([Hifz.kt:932](app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt)). Deleting that second point ([Hifz.kt:1041](app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt)) leaves `bothWays` on the first one. The line then still says that the app asks about both verses.

### 7. A point with several mistakes mixes its lines — medium

`fixes.flatMap { it.reasons }.distinct()` puts all the lines in one list and does not say which mistake each line belongs to. With two look-alikes the list can say «الأخرى في سورة الأعراف» and then «الأخرى في سورة يونس». The reader cannot tell which «تتفقان» line goes with which verse.

### 8. Small points — low

- **Two number styles in one list:** «الآية الثالثة في أول الصفحة» (a word) next to «تشترك الآيتان 2 و3» (digits).
- **The heading's gender:** «يلتبس» is masculine, while the card speaks of the verse as feminine («تذكّرتها»). It may be read as impersonal. Your call.
- **The opening count can appear a moment late** the first time a hifz screen opens after the app starts, because the look-alike index is built in the background ([QuranScreen.kt:917](app/src/main/java/com/tunisianprayertimes/ui/QuranScreen.kt)).
- **A known trade-off, kept as decided:** «تخطٍّ» lines leave out the verse after, so that the line does not give away another point's answer. Two verses in a row that open alike are a common cause of a skip, and the line does not show it.
- **No test** covers the «أول الآية» line or the hidden word differences.

## Arabic wording

I checked the lines with the project's «arabic-native» rules. All lines are correct Fusha. Number forms are right (كلمتين / 3 كلمات / 12 كلمة; آيتان / آية واحدة).

| Line | Comment |
|---|---|
| «تتفقان…», «تشتركان…», «متتاليتان…» | No subject. Under a heading, «تتفق الآيتان…» reads clearer. |
| «الأخرى في سورة X» | A fragment with no noun. If kept: «والشبيهة في سورة X». |
| «تتفقان في 3 كلمات قبل الخاتمة» | «الخاتمة» of which verse? Better to quote the words, then say that the two endings differ. |
| «آيتان أخريان في المصحف تبدآن بالكلمتين نفسيهما» | «نفسيهما» refers to words that were never shown. Verb first and naming the words reads more natively. |
| «أول سورة X» | A label, not a sentence. A reason would be: there is no verse before it to bring it to mind. |
| «قد تقرأ كلًّا منهما مكان الأخرى…» | Correct, but it is not a reason. It belongs with the look-alike or the record sheet. |

## What changed (15 November, after your OK)

- **Words are compared as recited.** `HifzWords.recited` and `hifzSameRecited` in [HifzText.kt](app/src/main/java/com/tunisianprayertimes/quran/hifz/HifzText.kt) apply your rule and the list in finding 1. The look-alike lines, the marked words, where two verses part, and the record sheet's suggested parting word all use it.
- **Every line names the words and the verses.**
  - Look-alike: «تتفق هذه الآية والآية الثانية من سورة يونس في «إِنَّ هَٰذَا»، ثم تختلفان: هنا «لَسَٰحِرٌ عَلِيمٞ»، وهناك «لَسِحْرٞ مُّبِينٌ».»
  - Forgotten opening, both the old «أول الآية» points and new slips on the first word: «تبدأ بـ«فَمَنْ أَظْلَمُ مِمَّن» آيتان أخريان: …» and «بعدها هنا «…»، وفي الأخريين «…».» Above 3 verses, only the count is given.
  - Verses that open alike but for «و» or «ف»: «وتبدأ 8 آيات أخرى بـ«وَمَنْ أَظْلَمُ»، بالواو لا بالفاء.»
  - An exact twin: «آية أخرى في المصحف مثل هذه الآية تمامًا: …».
- **Lines that did not say why are gone:** «الأخرى في سورة X», the "both ways" line, and «أول سورة X». The nearness lines stay.
- **Page lines say the page change.** They add «وبينهما تقلب الورقة في المصحف المطبوع» only where the book turns a sheet (the top of an odd page, the foot of an even page).
- **Checks:**
  - 61 of 61 hifz unit tests pass, two of them new.
  - A temporary test ran the new code on all 6214 verses: no crash, and the slowest box took 8 ms. Its output is `build/why-confused-review-20261115/reasons-after-change.txt`.

## What I would change, in this order

1. **Compare recited words for these facts.** Keep the hamza seat, the dagger alef and the inner vowels. Keep case endings too (your rule). Ignore only the marks that come from the words around a word (the list in finding 1) and the stop signs. Use this both for the lines and for the marked words.
2. **Name the words and the verses in each line:** quote the shared words, say where the verses part, and name the other verses. Above 3 verses, give the count only.
3. **Keep only lines that say why:**
   - Drop «الأخرى في سورة X» where the heading names the look-alike; keep the nearness lines.
   - Move the "both ways" line to the look-alike.
   - Turn «أول سورة X» into a reason, or drop it.
   - In the page lines, say that the page turn is the cause. **Open question for you:** show them only where the book turns a sheet (the top of an odd page, the foot of an even page), or at every page, because on the phone every page is a swipe?
4. **For a forgotten opening («أول الآية», and «كلمة» on the first word):**
   - Say how the verse before ends.
   - If other verses open alike, say where this one parts.
   - Count the «و/ف» twins.
   - Drop counts above about 5.
5. **Label the lines by mistake** when a point has more than one.
6. **Check that the second point still exists** before showing the "both ways" line.
