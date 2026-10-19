# Adhkar catalog sources

The bundled Arabic catalog is a selection, not an exhaustive adhkar collection. Previously listed Arabic texts and references were checked on 2026-09-21; entries added or expanded on 2026-09-23 were checked against the sources linked below. Quranic passages and verse numbers were checked on 2026-09-28 against the [King Fahd Complex Qaloon mushaf](https://quranpedia.net/book/2019), using its [searchable text](https://quranpedia.net/surah/7/2) with the later Madani verse count. Punctuation, vowel marks, and Quranic spelling are normalized for readable Arabic text; Quran quotations retain their complete wording. The source collection and hadith number are also displayed with each entry offline. On 2026-10-06 every entry was checked again against two sources, sunnah.com and dorar.net (الموسوعة الحديثية): the cited collections and numbers, the wording, the quoted narrations, the gradings and the repetition counts. A correction was made only where both sources agreed.

## Editorial conventions

- `defaultCount` is the count for the entry's specified occasion. A value of 1 is the reading default when the narration does not specify repetitions; it does not imply an exclusive religious limit.
- Each dhikr has one master catalog entry with a stable ID. Collections reuse that entry through membership rather than copying its text; collection-specific count defaults belong to that membership context.
- User reminder targets and time windows are personal settings. In particular, 100 salawat between 08:00 and Maghrib on Friday is a customizable personal goal, not a prescribed count or time window attributed to these hadiths.
- The salawat (al-salat al-ibrahimiyya) is quoted in the wording of Ka'b ibn Ujra's narration, Bukhari 6357, which Muslim 406 also reports; the catalog and the screens cite both. It is cataloged under daily dhikr, not as an additional prescribed post-prayer formula, and it is one of the mosque TV's default ticker texts.
- The salawat has several authentic wordings, all one dhikr: the reader's «الصيغ» chip lets the user read another in its place, with that wording's source and hadith; counts and reminders are unchanged, and the mosque TV keeps the default. Besides Bukhari 6357, `DhikrVariants` offers Ka'b ibn Ujra's «على إبراهيم وعلى آل إبراهيم» (Bukhari 3370), Abu Humayd al-Sa'idi's «وأزواجه وذريته» (Bukhari 3369, quoted; Muslim 407 has «وعلى أزواجه»), Abu Mas'ud al-Ansari's «في العالمين» (Muslim 405, commas added between clauses, the closing «والسلام كما قد علمتم» left out as it is not part of the salawat) and Abu Sa'id al-Khudri's «عبدك ورسولك» (Bukhari 6358). Texts and narrations were built from sunnah.com's code points and SHA-256-checked on 2026-10-09. The same day every cited number, narrator and wording was checked a second time on dorar.net (الموسوعة الحديثية), all graded صحيح: Bukhari 6357 and Muslim 406 «جميعا بلفظه»; Bukhari 3370 (Muslim 406 «بنحوه»); Bukhari 3369 and 6360 with Muslim 407 «باختلاف يسير»; Muslim 405, narrated by Abu Mas'ud 'Uqba ibn 'Amr, «من أفراد مسلم على البخاري»; Bukhari 6358 «من أفراد البخاري على مسلم».
- The two light words (`kalimatan_khafifatan`) are cited to Bukhari 6682 and Muslim 2694, whose wording has the catalog's order, «سبحان الله وبحمده، سبحان الله العظيم»; the narration shown is Bukhari 6682's. Until 2026-10-06 the entry cited Bukhari 6406, which has the two phrases in the reverse order; Bukhari 7563 has the catalog's order of the phrases but puts «حبيبتان إلى الرحمن» first. The four narrations were checked on sunnah.com on 2026-10-06.
- The after-prayer 33/33/33 tasbih, tahmid, and takbir followed by one tahlil are one reading item whose counter advances through four phrases (Muslim 597). Bedtime tasbih, tahmid, and takbir remain a separate 33/33/34 practice (Muslim 2727).
- A standalone tahlil goal of at least 100 repetitions in one day uses Bukhari 3293 and Muslim 2691. Older saved 100-count reminder rules that pointed to the retired post-prayer tahlil ID are re-linked to the daily entry; old reading sessions containing retired entries are discarded.
- Al-Ikhlas, Al-Falaq, and An-Nas are each read three times in the morning/evening. At bedtime, Bukhari 5017 describes reading the three surahs, blowing into the cupped hands, and wiping the body, repeated three times. The source label mentions the associated bedtime action.
- A source label that covers several occasions (the three surahs, Ayat al-Kursi) may have a shorter label per collection, made of its own clauses (`collectionReferences`). The mosque TV's after-prayer screen shows only the after-prayer clause, with the surah named: «سورة الإخلاص؛ دبر كل صلاة مرة: سنن أبي داود 1523 (صحيح)». The phone's collections and the TV's ticker keep the whole label.
- The home-entry card combines saying Allah's name and greeting the household, drawing on Muslim 2018 and Qaloon 24:59. It is not represented as a single verbatim narrated formula. The longer home-entry supplication of Abu Dawud 5096 is a separate entry of the additional catalog (`home_enter_bismillah`, in the home collection). Its source label states the differing assessment: Al-Albani graded it sahih, then weak for a break in its chain. It is in no list the mosque TV shows by default, but the dashboard's library offers it like every catalog entry.
- The evening refuge card uses the Muslim narration without a fixed repetition count, hence the reading default of 1; it does not infer a threefold count from that narration.
- Since 2026-10-06 the morning and evening collections follow, item for item and in its order, the list of «31 فائدة في أذكار الصباح والمساء» (Zad team, from the lectures of Sheikh Muhammad Salih al-Munajjid), benefit 28: the kingdom supplication, «اللهم بك أصبحنا», «على فطرة الإسلام», Ayat al-Kursi, the three surahs, Sayyid al-istighfar, the ʿafiya supplication, «اللهم فاطر السماوات», «بسم الله الذي لا يضر», «أعوذ بكلمات الله التامات», «يا حي يا قيوم», «اللهم عافني في بدني» with «اللهم إني أعوذ بك من الكفر والفقر», the tahlil, and «سبحان الله وبحمده» a hundred times. The booklet lists «أعوذ بكلمات الله التامات» for both times, so it is in both lists, although its cited narration (Muslim 2709) names the evening. The morning list then adds the fourfold tasbih (`subhan_count`), which the booklet counts among the adhkar of any time (benefit 29); the owner kept it there. The explanations of these adhkar quote the booklet (benefits 28-29); each phrase they quote is shown in the catalog's own wording (Qaloon for the Qur'an).
- Removed on 2026-10-06: the morning and evening supplications that al-Albani graded weak and the booklet leaves out of its list of what is authentic: «اللهم إني أصبحت أشهدك» (Abu Dawud 5069), «اللهم ما أصبح بي من نعمة» (Abu Dawud 5073) and «أصبحنا وأصبح الملك لله رب العالمين، اللهم إني أسألك خير هذا اليوم» (Abu Dawud 5084), each in its morning and evening form.
- The ruku and sujud tasbih cards (`ruku_tasbih`, `sujud_tasbih`) quote Muslim 772, which gives no count, hence the reading default of 1 (3 until 2026-10-06). The threefold count comes from Ibn Mas'ud's hadith (Abu Dawud 886, Tirmidhi 261, Ibn Majah 890), which sunnah.com and dorar.net both show as weak (al-Albani; al-Tirmidhi notes its chain is broken).
- The optional night reminder groups the last two verses of al-Baqarah with Sayyid al-istighfar. Their sources describe recitation at night; the suggested alert 15 minutes after Maghrib is a convenient personal setting, not a prescribed post-prayer formula or required time.
- Quranic verse markers use the Qaloon mushaf's later Madani verse boundaries and the app's `0–9` digits. The basmala preceding the three short surahs is not counted among their verses. In this count, the passage commonly called Ayat al-Kursi spans al-Baqarah 253–254, with a verse break after `الْقَيُّومُ`. The last two verses of al-Baqarah are 284–285. The displayed text preserves Qaloon readings such as `كُفُؤًا`, `وَهْوَ`, and `لِذِكْرِيَ` while using the app's readable Arabic spelling.

## Source mapping

| Entries | Sources |
| --- | --- |
| Post-prayer istighfar and salam | [Sahih Muslim 591](https://sunnah.com/muslim:591) |
| Post-prayer tahlil with «لا مانع لما أعطيت» | [Sahih al-Bukhari 844](https://sunnah.com/bukhari:844), [Sahih Muslim 593](https://sunnah.com/muslim:593) |
| Post-prayer tasbih, tahmid, takbir, and tahlil | [Sahih Muslim 597a](https://sunnah.com/muslim:597a) |
| Salawat (al-salat al-ibrahimiyya), in the narration's wording | [Sahih al-Bukhari 6357](https://sunnah.com/bukhari:6357), [Sahih Muslim 406](https://sunnah.com/muslim:406), both from Ka'b ibn Ujra |
| Subhan Allah wa bihamdihi, subhan Allah al-azim (the two light words) | [Sahih al-Bukhari 6682](https://sunnah.com/bukhari:6682), [Sahih Muslim 2694](https://sunnah.com/muslim:2694) |
| The reply to the muezzin at the two calls to prayer (la hawla wa la quwwata illa billah) | [Sahih al-Bukhari 611](https://sunnah.com/bukhari:611), [613](https://sunnah.com/bukhari:613); [Sahih Muslim 385](https://sunnah.com/muslim:385) |
| The shahada on hearing the muezzin | [Sahih Muslim 386](https://sunnah.com/muslim:386) |
| The supplication after the adhan (al-wasila) | [Sahih al-Bukhari 614](https://sunnah.com/bukhari:614) |
| Morning/evening kingdom supplication | [Sahih Muslim 2723b](https://sunnah.com/muslim:2723b); morning wording follows the narration's instruction to use the corresponding morning form |
| Sayyid al-istighfar | [Sahih al-Bukhari 6306](https://sunnah.com/bukhari:6306) |
| Bismillah protection | [Sunan Abi Dawud 5088](https://sunnah.com/abudawud:5088), graded sahih by Al-Albani on the source page |
| Subhan Allah wa bihamdih, 100 morning/evening | [Sahih Muslim 2692](https://sunnah.com/muslim:2692) |
| Tahlil 100 times in one day (same standalone entry as morning/evening tahlil) | [Sahih al-Bukhari 3293](https://sunnah.com/bukhari:3293), [Sahih Muslim 2691](https://sunnah.com/muslim:2691) |
| Asking for guidance, piety, chastity, and sufficiency | [Sahih Muslim 2721](https://sunnah.com/muslim:2721a) |
| La hawla wa la quwwata illa billah | [Sahih al-Bukhari 6384](https://sunnah.com/bukhari:6384) |
| Al-Ikhlas, Al-Falaq, An-Nas | [Qaloon 112](https://quranpedia.net/surah/7/112), [Qaloon 113](https://quranpedia.net/surah/7/113), [Qaloon 114](https://quranpedia.net/surah/7/114); occasion/count: [Sunan Abi Dawud 5082](https://sunnah.com/abudawud:5082), graded hasan by Al-Albani, and [Sahih al-Bukhari 5017](https://sunnah.com/bukhari:5017); once after every prayer: [Sunan Abi Dawud 1523](https://sunnah.com/abudawud:1523), graded sahih by Al-Albani |
| Bedtime bismika and waking | [Sahih al-Bukhari 6324](https://sunnah.com/bukhari:6324) |
| Bedtime supplication (Bismika Rabbi wada'tu janbi) | [Sahih al-Bukhari 6320](https://sunnah.com/bukhari:6320) |
| Last two verses of al-Baqarah at night | [Qaloon 2:284–285](https://quranpedia.net/surah/7/2), [Sahih al-Bukhari 5009](https://sunnah.com/bukhari:5009) |
| Ayat al-Kursi | [Qaloon 2:253–254](https://quranpedia.net/surah/7/2); before sleep: [Sahih al-Bukhari 2311](https://sunnah.com/bukhari:2311); morning/evening: al-Hakim's al-Mustadrak (hadith of Ubayy ibn Ka'b); after every prayer: al-Nasa'i's al-Sunan al-Kubra (hadith of Abu Umama); Al-Albani graded both sahih |
| Bedtime submission supplication | [Sahih al-Bukhari 6311](https://sunnah.com/bukhari:6311) |
| Bedtime tasbih, tahmid, takbir | [Sahih Muslim 2727a](https://sunnah.com/muslim:2727a) |
| Mentioning Allah and greeting on home entry | [Sahih Muslim 2018a](https://sunnah.com/muslim:2018a), [Qaloon 24:59](https://quranpedia.net/surah/7/24) |
| Home-entry supplication (card «بسم الله ولجنا») | [Sunan Abi Dawud 5096](https://sunnah.com/abudawud:5096); Al-Albani graded it sahih, then weak for a break in its chain, as its source label says |
| Leaving home | [Sunan Abi Dawud 5095](https://sunnah.com/abudawud:5095), graded sahih by Al-Albani |
| Before food | [Sahih al-Bukhari 5376](https://sunnah.com/bukhari:5376) |
| Forgotten bismillah during food | [Sunan Abi Dawud 3767](https://sunnah.com/abudawud:3767), graded sahih by Al-Albani |
| Praise after food/drink | [Sahih Muslim 2734a](https://sunnah.com/muslim:2734a) |
| Toilet entry | [Sahih al-Bukhari 142](https://sunnah.com/bukhari:142) |
| Toilet exit | [Sunan Abi Dawud 30](https://sunnah.com/abudawud:30), graded sahih by Al-Albani |
| Mosque entry/exit | [Sahih Muslim 713a](https://sunnah.com/muslim:713a) |
| Mounting for travel | [Sahih Muslim 1342](https://sunnah.com/muslim:1342), includes [Qaloon 43:12–13](https://quranpedia.net/surah/7/43); the three opening takbirs are included in the text |
| After wudu | [Sahih Muslim 234b](https://sunnah.com/muslim:234b) |
| Worry and grief | [Sahih al-Bukhari 6369](https://sunnah.com/bukhari:6369) |
| Evening refuge | [Riyad as-Salihin 1452](https://sunnah.com/riyadussalihin:1452), explicitly sourced there to Muslim; [Sahih Muslim 2709b](https://sunnah.com/muslim:2709b) identifies the corresponding narration |

The prayer-card excerpt `وَأَقِمِ الصَّلَاةَ لِذِكْرِيَ` is [Qaloon 20:13](https://quranpedia.net/surah/7/20). The Qur'an excerpt in the cited Muslim narration of Ibn Abbas corresponds to [Qaloon 3:190](https://quranpedia.net/surah/7/3); narration text is quoted as transmitted.
The supplication of Dhu al-Nun in the additional catalog is part of [Qaloon 21:86](https://quranpedia.net/surah/7/21).

The mosque TV shows, by default, the after-prayer collection (`salah_istighfar`, `salah_salam`, `salah_la_mani`, `salah_hundred`, `ayat_kursi`, `surah_ikhlas`, `surah_falaq`, `surah_nas`), and the ticker's `subhanallah_bihamdih`, `kalimatan_khafifatan`, `la_hawla_quwwata` and `salawat_ibrahimiyya`: every one has its row above. Its adhan screen shows the listener's replies below (`MosqueAdhkar.ADHAN_REPLIES`), not catalog entries, then the catalog's `after_adhan_wasila` (Bukhari 614) on a screen of its own (below); `adhan_response` and `adhan_shahada` (Muslim 386) stay in the catalog for the phone app.

## Qur'an text as the mushaf

Since 2026-10-15, wherever the apps show Qur'an text, it is typed exactly as the app's own mushaf prints it and set in the mushaf's font. That mushaf is the Qaloon mushaf of the Quran tab, in Mashriqi script with the Madinah waqf signs and the later Madani count. The places:
- **Phone:** the Quran tab (search, verse excerpts, index and bookmarks, repeat sheet, the hifz heads-up); «المراجعة» (verse text, word chips, closings, finder); the adhkar's Qur'an passages (reader, closing card, lists); the prayer card's verse; the reader's size sample.
- **Mosque TV:** the header verse, the after-prayer Ayat al-Kursi and surahs, a Qur'an text a mosque puts in the ticker, the setup basmala, and the dashboard's adhkar view.
- **Other products:** the desktop app's and the website's basmala.

Image crops of the pages were tried first for the adhkar (2026-10-14) and dropped: they could not follow the reader's text size.

### The text

The words are the bundled Qaloon text (King Fahd Complex encoding, from quranpedia), never retyped, only converted by code point. That encoding has the mushaf's own dabt: the wasl alif as the alif, the vowel it joins and a dot; open tanween; the eased hamza. The conversion (`scripts/mushaf_text/mushaf_text.py`, ported to `QuranMushafText` in android-app) changes only what the mushaf prints differently:
- the encoding's waqf signs, set at Maghribi positions, go; so do its sajdah sign and stray right-to-left marks;
- the opening letters of 29 surahs, spelled as read in the encoding («أَلَٓمِّٓ»), are the bare letters with only the madd and the shadda («الٓمّٓ», «كٓهيعٓصٓ», «طه»);
- a word-final ya is dotless whatever it carries («إِنِّىَ», «إِلَىَّ», «يُحْىِ»), and so is a ya carrying the small alif («مَوْلَىٰنَا»);
- a tanween fath before a final alif or alif maqsura, the iqlab one too (a fatha and a small meem, «أَبَدَۢا»), sits on the letter before it («كُفُؤًا», «هُدًى»), on a hamza over a tatweel on the tatweel («شَيْـًٔا»);
- a hamza on an alif that the encoding marks with a madd and no vowel carries a fatha («ٱلْأَخِرَةِ», «لِأَدَمَ»);
- the single lam of الذي, الذين, الذان, التي and الليل, prefixed or not, carries a shadda and a fatha («ٱلَّذِى», «وَالَّذِينَ», «لِلَّذِينَ», «ٱلَّيْلِ», «وَالَّذَٰنِ»); so does the second lam of the name Allah after li («لِلَّهِ»);
- the pronoun «أَنَا» (and «لكنا», lakin ana) before a word that starts with neither a hamza nor an alif carries the round silent mark on its alif («أَنَاْ عَابِدٌ», «لَّٰكِنَّاْ هُوَ»);
- a final letter merged whole into the next word's first (marked there with a shadda) loses its sukun («وَقُل رَّبِّ»); a nun merged with its ghunna keeps it, drawn grey in the mushaf («مَنْ يَّقُولُ»); so does a silent alif («عَصَواْ وَّكَانُواْ»);
- the wasl alif is written on «ٱ» with its joining vowel and dot, because the shaper puts a vowel before a dot and could not otherwise tell it from an eased hamza (the dot first); the article of a surah's opening basmala, bare in the encoding, takes the basmala's wasl marks;
- the encoding's other dots: above stays (U+06EC); below is the dot below (U+065C); on the line (U+06DF) stays and is drawn on the line, except on a waw («أَؤُ۬نَبِّئُكُم»), where the mushaf puts it above;
- Unicode's code points for the open tanween (U+08F0–U+08F2), the dotless ya and the ya with hamza.

The rules were read on the page scans, not assumed:
- **Round 1:** 12 rules at 89 words spread evenly over the Quran, plus 20 random verses compared mark by mark. Each difference found was checked by a second reader told to refute it.
- **Round 2:** 9 questions the first round raised, at 50 more words.

A tool set each scan line over the same words typed in the font (`verse_sheet.py`, kept with the audit notes). `QuranMushafTextTest` pins the conversion of the whole text to the reference implementation's SHA-256. The conversion never adds, drops or moves a letter.

One word of the source differs from the scans and is corrected in `scripts/quran-data/qaloun-text-corrections.json`, which `scripts/import-quran-index.py` applies: 17:62 opens with qala, with a fatha on the lam (page 287), where quranpedia has a sukun.

### Waqf signs and verse numbers

The source has no Madinah waqf signs, so the Quran tab and «المراجعة» show verses without them. Since 2026-10-18 the mushaf's signs of every verse are in `assets/quran/waqf.json`, read on the scans (see «Shared verses» below); only a shared verse uses them so far. The passages shown outside the Quran tab carry the mushaf's own signs, read line by line on the scans:
- `scripts/mushaf_text/build_passages.py` writes them to `MushafPassages` (shared module).
- They are the adhkar passages, the closing card's verses, the 14 header verses and the basmala.
- A header verse keeps the sign that follows its last word in the mushaf (7 of them).

A verse ends with its frame and number in Western digits, bound to the verse's last word by a no-break space. `MushafPassagesTest` holds each passage's SHA-256 and checks that its words are the Quran tab's words.

### The font

`mushaf_qaloun.ttf` is built by `scripts/mushaf_font/build_font.py` from Amiri Quran 1.003 (SIL Open Font License, no reserved name). Each product carries a copy with its licence: the phone, the TV, the desktop app and the website. The changes make Amiri draw what the mushaf draws:
- **The wasl alif:** the joining vowel is a stroke on the alif. A fatha's grows out of the top of the stem like a flag, a damma's crosses its middle, a kasra's sits at its foot. A filled dot stands for the vowel it is started with: above, on its left at mid height, or under its foot. The middle dot of a damma start is included («ٱذْكُرُواْ», 33:41); Amiri has no glyph for it.
- **The eased hamza at the start of a word:** its dot sits on the alif with its vowel above it, or under the alif's foot with a kasra under the dot.
- **The dot on the line:** an eased or dropped hamza after a final madd alif («هَٰؤُلَآ•»), after a small alif standing in the line («أَٰ•ذَا»), or before an alif with its vowel («•َالِهَةً»). The tas-hil dot sits on top of a standing small alif («أَرَٰ۬يْتَ»).
- **The small alif:** it stands in the line («إِلَٰهَ», «ٱلسَّمَٰوَٰتِ»).
- **The closed dammatan:** two dammas, one above the other.
- **The waqf signs:** small, just above the letters, over the gap after the word. Amiri draws them large, far above the line.
- **The verse frame:** a ring with a cap above and below. The number inside is in bold sans digits, from Noto Sans Arabic UI SemiBold (SIL Open Font License).

### What stays readable

Some text is not set in the mushaf font:
- **Search and TalkBack:** the catalog's readable wording stays for search and for TalkBack.
- **Copied and shared verses (since 2026-10-18):** the mushaf's converted words and its waqf signs (`waqf.json`), in code points other apps' fonts draw as the mushaf means them (`QuranMushafText.plain`), then the surah, the verse number, the riwaya and the count. The source encoding is not shared: other fonts draw its wasl as a vowel to read (a kasra on the alif of al-hamdu, 1:1), its open tanween as an inverted damma, a fatha with two dots or a subscript alif, and its ya as the Urdu yeh barree, and its waqf signs are at Maghribi positions.
- **Notifications:** «المراجعة» notifications show the same form (`QuranMushafText.plain`).
- **Hadith quotations:** duas that quote the Qur'an inside a hadith's wording keep the hadith's text. These are the travel supplication (Muslim 1342, with al-Zukhruf 12–13), the supplication of Dhul-Nun (al-Anbiya 86) and the home-entry greeting. So does commentary quoting a few words.

### Shared verses

`QuranMushafText.plain` writes the wasl as a bare «ٱ», and the dot on the line (an eased or changed hamza, 38 words such as 13:5 and 2:30) as the dot above (U+06EC), the Madinah mushafs' sign of an eased hamza. Other fonts draw U+06DF as the small zero of a silent letter, and dropping it would drop the hamza. Every code point it leaves is drawn by Noto Naskh Arabic (Android's Arabic font), Segoe UI, Arial, Tahoma and Times New Roman.

The shared text was checked on 2026-10-18 against the King Fahd Complex's own Qaloon data (version 10, 2021, from [quran-data-kfgqpc](https://github.com/thetruetruth/quran-data-kfgqpc)):
- The 6214 verses are divided and numbered alike, in every surah.
- 6047 verses have the same words. In the others:
  - waqf signs differ (66), but its signs are not used: they are Maghribi;
  - the hamza of «شيئا» sits on a tatweel (77), and the opening letters order their madd differently (20);
  - 7 verses space «أو», «ما» or «لو» against the next word differently (7:97, 15:7, 15:32, 27:20, 36:21, 37:17, 56:51). The letters are the same, and the scans set these words tight.
  - In 6 words, version 10 differs from the scans or the rasm. 17:62: it has the sukun on the lam of qala as well; the scan has a fatha, as corrected. 34:23: it drops the shadda on the shin of al-shafa'a. 58:10: it drops the fatha on the waw of wa-'ala. 33:37: it gives a wasl mark to the alif after wa. 2:71: it puts the hamza of fa-ddara'tum above the small alif; the bundled text has it on the line, and both read the same.
- The count is the scan's own: the edition's afterword (scan 604) says it follows the later Madani count (6214 verses).
- The medallions match the index's verse ends on all 603 pages (`highlights-audit.json`). Printed numbers were read at 1:1–7, 2:1–4, 2:70–71, 2:252–253, 7:96–97, 13:4–5, 15:6–7, 15:32, 17:61–62, 27:19–20, 36:20–21, 37:16–17 and 56:50–51.

The waqf signs (`assets/quran/waqf.json`, 4305 signs in 2630 verses) were read on the scans on 2026-10-18 by `scripts/mushaf_waqf` (its `build.py` says how):
- **The signs:** the mushaf prints four: ج (2037), صلى (1678), قلى (586) and the paired ∴ (4, at 2:1 and 2:194). It prints no lazim mim and no «لا», not even where both Madinah Hafs texts have them (2:25, 2:117, 5:53, 10:65, 29:25, 36:75, 54:6 were read).
- **The scan decides** each sign's kind and how many a verse has. Every glyph was found by matching one template of each sign, cut from the scans, over all 603 pages; true signs score 0.65 or more and everything else 0.62 or less, which was read on contact sheets of every hit in between.
- **The word** each sign follows comes from its place in the verse's width on the page, choosing among the words after which the Madinah Hafs texts (KFGQPC data v18 and Tanzil's 1405H text) put a sign. Where neither has one, it is the nearest word. Most signs (3988) are where both Madinah texts have the same sign; 66 differ from both in kind, and Qaloun's own readings move some (2:283 has no sign after the name of Allah, before the jussive «fa-yaghfir»).
- **Read on the scans:** 145 signs the matching flagged, 107 near a place where the Qaloun words differ from the Hafs ones, and the 17 whose kind neither Madinah text has. Five verses were corrected (`overrides.json`: 7:58, 9:102, 27:84, 57:18, 78:37). The 26 signs read by hand for `MushafPassages` all agree, and a test checks it.

## Closing card

When a reading is complete, the phone's reader shows a card over it (`DhikrClosingCard`, android-app). That covers the morning, evening and night lists, the bedtime and after-prayer adhkar, and a reading toward a reminder's goal. The card thanks the reader, prays for them in the app's own words, and quotes one text on the reward of remembering Allah from `DhikrClosingTexts` (shared module), one a day in turn. A verse is shown as the mushaf prints it (see «Qur'an passages as the mushaf» above); its text here is what TalkBack reads. The app's own lines are duas, never promises made on Allah's behalf. On 2026-10-14 they were drafted in three voices and judged for adab and for Arabic, and the following were left out:
- a bare «غفر الله لك», which can sound like a reproach; the card says «غفر الله لنا ولك» instead;
- a set «تقبّل الله» after every prayer, which some scholars discourage; the after-prayer card says «بارك الله فيك»;
- «سترك الله» and «غمرك برحمته», whose usual contexts do not fit;
- any wording that echoes a verse or hadith.

The morning and evening cards credit completion to Allah («بفضل الله»). A morning or evening list finished outside its time gets a card that thanks for the dhikr without calling it that time's adhkar.

| Text | Source | SHA-256 (first 16) |
| --- | --- | --- |
| `al_baqara_151` `فَاذْكُرُونِي أَذْكُرْكُمْ وَاشْكُرُوا لِي وَلَا تَكْفُرُونِ` | [Qaloon 2:151](https://quranpedia.net/surah/7/2) (Hafs 2:152) | `74913ce48be40da4` |
| `ar_rad_29` `الَّذِينَ آمَنُوا وَتَطْمَئِنُّ قُلُوبُهُمْ بِذِكْرِ اللَّهِ ۗ أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ` | [Qaloon 13:29](https://quranpedia.net/surah/7/13) (Hafs 13:28) | `3151ded0245138f1` |
| `al_ahzab_35` `إِنَّ الْمُسْلِمِينَ وَالْمُسْلِمَاتِ وَالْمُؤْمِنِينَ وَالْمُؤْمِنَاتِ وَالْقَانِتِينَ وَالْقَانِتَاتِ وَالصَّادِقِينَ وَالصَّادِقَاتِ وَالصَّابِرِينَ وَالصَّابِرَاتِ وَالْخَاشِعِينَ وَالْخَاشِعَاتِ وَالْمُتَصَدِّقِينَ وَالْمُتَصَدِّقَاتِ وَالصَّائِمِينَ وَالصَّائِمَاتِ وَالْحَافِظِينَ فُرُوجَهُمْ وَالْحَافِظَاتِ وَالذَّاكِرِينَ اللَّهَ كَثِيرًا وَالذَّاكِرَاتِ أَعَدَّ اللَّهُ لَهُمْ مَغْفِرَةً وَأَجْرًا عَظِيمًا` | [Qaloon 33:35](https://quranpedia.net/surah/7/33) | `1cddaf00192042ee` |
| `al_ahzab_41_43` `يَا أَيُّهَا الَّذِينَ آمَنُوا اذْكُرُوا اللَّهَ ذِكْرًا كَثِيرًا ۝41 وَسَبِّحُوهُ بُكْرَةً وَأَصِيلًا ۝42 هُوَ الَّذِي يُصَلِّي عَلَيْكُمْ وَمَلَائِكَتُهُ لِيُخْرِجَكُمْ مِنَ الظُّلُمَاتِ إِلَى النُّورِ ۚ وَكَانَ بِالْمُؤْمِنِينَ رَحِيمًا` | [Qaloon 33:41–43](https://quranpedia.net/surah/7/33) | `7ee5a259f6dee1ff` |
| `taha_128` `فَاصْبِرْ عَلَىٰ مَا يَقُولُونَ وَسَبِّحْ بِحَمْدِ رَبِّكَ قَبْلَ طُلُوعِ الشَّمْسِ وَقَبْلَ غُرُوبِهَا ۖ وَمِنْ آنَاءِ اللَّيْلِ فَسَبِّحْ وَأَطْرَافَ النَّهَارِ لَعَلَّكَ تَرْضَىٰ` | [Qaloon 20:128](https://quranpedia.net/surah/7/20) (Hafs 20:130) | `baffdb58235452b7` |
| `bukhari_7405` `عَنْ أَبِي هُرَيْرَةَ رضي الله عنه قَالَ قَالَ النَّبِيُّ ﷺ …` | [Sahih al-Bukhari 7405](https://sunnah.com/bukhari:7405); dorar.net: صحيح, «أخرجه مسلم (2675) باختلاف يسير» | `bd7de0a5bfc61d54` |
| `bukhari_6465` `عَنْ عَائِشَةَ رضي الله عنها أَنَّهَا قَالَتْ سُئِلَ …` | [Sahih al-Bukhari 6465](https://sunnah.com/bukhari:6465); dorar.net: صحيح, «أخرجه مسلم (783) … بلفظه دون قوله: اكلفوا من العمل» | `43a81868b4b04cf4` |

The verses were built from Tanzil's simple text (Hafs), downloaded from tanzil.net on 2026-10-14. They went through a conversion that reproduces every Qur'an passage of the catalog byte for byte:
- no tatweel;
- no assimilation shadda on a word's first letter;
- a sukun on a bare nun or mim;
- the vowel stored before the shadda.

The only differences that remain are Qaloon's own readings (kufu'an, wa-hwa) and its verse breaks. Each verse was then compared word by word with the bundled Qaloon text (`quran/index.json`, from quranpedia). The five passages read the same in both riwayas, and their numbers are Qaloon's. The verse markers between verses follow the mosque screen texts: there is none after the last verse. In Ta-Ha 20:128 (Hafs 20:130) the Qaloon text writes «ءَانَآءِےْ اِ۬ليْلِ», which is spelling, not a different reading. It writes al-layl with one unmarked lam everywhere (2:163, 3:113, 35:13 …). The silent ya after the hamza is a seat the Uthmani rasm writes in a few words of this shape: Hafs has it in six, tilqa'i (10:15), wa-ita'i (16:90), ana'i (20:130), bi-liqa'i (30:8), wa-liqa'i (30:16) and wara'i (42:51). The Qaloon text keeps it in four of them and in no other word: tilqa'i (10:15), wa-ita'i (16:90), ana'i (20:128) and wara'i (42:48). The verse therefore reads «آنَاءِ اللَّيْلِ», as in Hafs, and every other word of the verse matches the Qaloon text letter for letter.

The hadith were built from sunnah.com's code points with the `DhikrNarrations` conventions. The same conversion reproduces Bukhari 5009 and Muslim 2692 of the catalog byte for byte. Two more steps follow the catalog:
- a vowelled alif maqsura is written as ya («إِلَيَّ», «أَيُّ»);
- the reference is kept apart from the text, and the card shows it under the text.

Bukhari 6465, rather than Muslim 783 or Bukhari 6464, gives «أدومها وإن قل» as a whole answer. The card labels it «من فضل المداومة», and the other texts «من فضل الذكر».

## Mosque screen texts

The TV app shows a verse in the header of its main screen, a greeting on Eid morning, and the basmala over its first setup. They live in `DisplayTexts` (shared module), outside the adhkar library, and a mosque can't change them. Wording and verse numbers were checked on 2026-09-29 against the Qaloon searchable text (later Madani count), letter by letter against each sura page.

On ordinary days the header shows twelve verses about the prayer in turn, moving on at each adhan; Fridays show the Jumu'a verse and Ramadan its own verse. Two keep Qaloon's reading where Hafs differs: `لِذِكْرِيَ` (Hafs `لِذِكْرِي`) and `يَا بُنَيِّ` (Hafs `يَا بُنَيَّ`).

| Text | When | Source |
| --- | --- | --- |
| `إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَوْقُوتًا` | ordinary days, in turn | [Qaloon 4:102](https://quranpedia.net/surah/7/4) (Hafs 4:103) |
| `حَافِظُوا عَلَى الصَّلَوَاتِ وَالصَّلَاةِ الْوُسْطَىٰ` | ordinary days, in turn | [Qaloon 2:236](https://quranpedia.net/surah/7/2) (Hafs 2:238) |
| `قَدْ أَفْلَحَ الْمُؤْمِنُونَ ۝1 الَّذِينَ هُمْ فِي صَلَاتِهِمْ خَاشِعُونَ` | ordinary days, in turn | [Qaloon 23:1–2](https://quranpedia.net/surah/7/23) |
| `إِنَّ الصَّلَاةَ تَنْهَىٰ عَنِ الْفَحْشَاءِ وَالْمُنْكَرِ` | ordinary days, in turn | [Qaloon 29:45](https://quranpedia.net/surah/7/29) |
| `وَأْمُرْ أَهْلَكَ بِالصَّلَاةِ وَاصْطَبِرْ عَلَيْهَا` | ordinary days, in turn | [Qaloon 20:131](https://quranpedia.net/surah/7/20) (Hafs 20:132) |
| `أَقِمِ الصَّلَاةَ لِدُلُوكِ الشَّمْسِ إِلَىٰ غَسَقِ اللَّيْلِ وَقُرْآنَ الْفَجْرِ` | ordinary days, in turn | [Qaloon 17:78](https://quranpedia.net/surah/7/17) |
| `وَأَقِيمُوا الصَّلَاةَ وَآتُوا الزَّكَاةَ وَارْكَعُوا مَعَ الرَّاكِعِينَ` | ordinary days, in turn | [Qaloon 2:42](https://quranpedia.net/surah/7/2) (Hafs 2:43) |
| `وَأَقِمِ الصَّلَاةَ لِذِكْرِيَ` | ordinary days, in turn | [Qaloon 20:13](https://quranpedia.net/surah/7/20) (Hafs 20:14) |
| `وَاسْتَعِينُوا بِالصَّبْرِ وَالصَّلَاةِ` | ordinary days, in turn | [Qaloon 2:44](https://quranpedia.net/surah/7/2) (Hafs 2:45) |
| `يَا بُنَيِّ أَقِمِ الصَّلَاةَ` | ordinary days, in turn | [Qaloon 31:16](https://quranpedia.net/surah/7/31) (Hafs 31:17) |
| `وَأَقِمِ الصَّلَاةَ طَرَفَيِ النَّهَارِ وَزُلَفًا مِنَ اللَّيْلِ` | ordinary days, in turn | [Qaloon 11:114](https://quranpedia.net/surah/7/11) |
| `رَبِّ اجْعَلْنِي مُقِيمَ الصَّلَاةِ وَمِنْ ذُرِّيَّتِي` | ordinary days, in turn | [Qaloon 14:42](https://quranpedia.net/surah/7/14) (Hafs 14:40) |
| `فَاسْعَوْا إِلَىٰ ذِكْرِ اللَّهِ وَذَرُوا الْبَيْعَ` | Fridays | [Qaloon 62:9](https://quranpedia.net/surah/7/62) |
| `شَهْرُ رَمَضَانَ الَّذِي أُنْزِلَ فِيهِ الْقُرْآنُ` | Ramadan | [Qaloon 2:184](https://quranpedia.net/surah/7/2) (Hafs 2:185) |
| `تَقَبَّلَ اللَّهُ مِنَّا وَمِنْكُمْ` | Eid morning | Jubayr ibn Nufayr's report that the companions greeted each other with `تقبل الله منا ومنك` on Eid, in al-Mahamiliyyat; Ibn Hajar graded its chain hasan in Fath al-Bari (Kitab al-Idayn). The screen uses the plural to greet everyone; the plural is not the narrated wording, and was approved editorially on 2026-09-29. |
| `بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ` | setup wizard's welcome (first start only) | The basmala that opens every sura but at-Tawba; same wording as the catalog's basmala before al-Ikhlas, al-Falaq and an-Nas. Cited as «البسملة», with no verse number: in the Qaloon reading with the Madani count it is not a verse of al-Fatiha (Hafs numbers it 1:1). |

The verses are excerpts: each is the complete clause shown, not the whole verse. The Eid greeting's source is shown in small type under it on the Eid screen.

## Mosque screen adhan replies

Decided by the owner on 2026-09-30: the wall cannot follow the muezzin, so for the whole adhan screen the TV shows, all at once and in order, what the listener says while the muezzin calls: only the replies (the muezzin does not follow the screen, so no line names the phrase it answers), each as many times as the muezzin says its phrase (twice for each shahada and each `حيّ على`). They live in `MosqueAdhkar.ADHAN_REPLIES` (shared module); a mosque can't change them. `MosqueAdhkarTest` pins the wording, the order and the Fajr-only lines. On the screen, a phrase said twice in a row shares one row. The orthography is the catalog's modern form (`لَا … إِلَّا`), not the narration's `لاَ … إِلاَّ`.

| # | Line | Answers the muezzin's | Adhans | Source |
| --- | --- | --- | --- | --- |
| 1 | `اللَّهُ أَكْبَرُ اللَّهُ أَكْبَرُ` | takbir | all | [Sahih Muslim 385](https://sunnah.com/muslim:385) |
| 2 | `أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ` | first shahada | all | Sahih Muslim 385 |
| 3 | `أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ` | first shahada, said again | all | Sahih Muslim 385; twice as the muezzin says it (the owner's choice, 2026-09-30) |
| 4 | `أَشْهَدُ أَنَّ مُحَمَّدًا رَسُولُ اللَّهِ` | second shahada | all | Sahih Muslim 385 |
| 5 | `أَشْهَدُ أَنَّ مُحَمَّدًا رَسُولُ اللَّهِ` | second shahada, said again | all | Sahih Muslim 385; twice as the muezzin says it (the owner's choice, 2026-09-30) |
| 6 | `لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ` | `حَيَّ عَلَى الصَّلَاةِ` | all | Sahih Muslim 385 |
| 7 | `لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ` | `حَيَّ عَلَى الصَّلَاةِ`, said again | all | Sahih Muslim 385; twice as the muezzin says it (the owner's choice, 2026-09-30) |
| 8 | `لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ` | `حَيَّ عَلَى الْفَلَاحِ` | all | Sahih Muslim 385 |
| 9 | `لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ` | `حَيَّ عَلَى الْفَلَاحِ`, said again | all | Sahih Muslim 385; twice as the muezzin says it (the owner's choice, 2026-09-30) |
| 10 | `لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ` | `الصَّلَاةُ خَيْرٌ مِنَ النَّوْمِ` | Fajr only | The owner's editorial choice, 2026-09-30 (not Muslim 385) |
| 11 | `لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ` | `الصَّلَاةُ خَيْرٌ مِنَ النَّوْمِ`, said again | Fajr only | The owner's editorial choice, 2026-09-30 (not Muslim 385) |
| 12 | `اللَّهُ أَكْبَرُ اللَّهُ أَكْبَرُ` | takbir | all | Sahih Muslim 385 |
| 13 | `لَا إِلَهَ إِلَّا اللَّهُ` | tahlil | all | Sahih Muslim 385 |

The "Answers" column documents the order; the screen shows only the lines. Lines 1-9, 12 and 13 are the hadith of ʿUmar ibn al-Khaṭṭāb (quoted whole in the catalog's narrations under `adhan_response`): the listener repeats each phrase of the muezzin, except at the two `حيّ على`, where he says `لا حول ولا قوة إلا بالله`. The hadith names each phrase once; the muezzin says each shahada and each `حيّ على` twice, and the screen shows each reply twice, as the listener repeats it (the owner's choice, 2026-09-30, after the general command of Bukhari 611 to say what the muezzin says). The screen shows no source line (the owner's choice, 2026-09-30): no single hadith covers every line, so this table is the record of their sources.

Lines 10 and 11 are not from Muslim 385 or any other narration: no narration gives a reply to `الصلاة خير من النوم`. Answering it with `لا حول ولا قوة إلا بالله`, twice as the muezzin says it, by analogy with the two `حيّ على`, is the owner's editorial choice of 2026-09-30.

### The dua after the adhan

Decided by the owner on 2026-09-30: once the adhan screen ends (it covers the adhan), the TV shows the dua after the adhan alone, for one minute (`FlowTiming.ADHAN_DUA_MINUTES`, not a mosque setting), then the usual wait for the iqamah. It is the catalog's reviewed entry `after_adhan_wasila`, «الدعاء بعد الأذان», [Sahih al-Bukhari 614](https://sunnah.com/bukhari:614), read through `MosqueAdhkar.adhanDua()`: `اللَّهُمَّ رَبَّ هَذِهِ الدَّعْوَةِ التَّامَّةِ، وَالصَّلَاةِ الْقَائِمَةِ، آتِ مُحَمَّدًا الْوَسِيلَةَ وَالْفَضِيلَةَ، وَابْعَثْهُ مَقَامًا مَحْمُودًا الَّذِي وَعَدْتَهُ.` The screen shows the entry's text exactly as reviewed, its final full stop included (the replies above have no punctuation of their own; this is the catalog's sentence), with a small muted source line under it: «صحيح البخاري 614», the entry's reference. It follows every adhan, Fajr's included, and Jumu'a's unless the mosque turned it off (below; on Friday the khutba screen starts after it); the Eid prayer has no adhan and so no dua. The iqamah never comes before the dua ends: one set closer to the adhan than the adhan screen and this minute waits for their end, as it waited for the adhan screen before. `MosqueAdhkarTest` pins the wording and the reference, `PrayerFlowTest` the timing.

On Friday the dua is a mosque setting (the owner's decision of 2026-10-01): shown by default, because the imam says it too, and the mosque may turn it off (`PrayerSettings.adhanDua`, the settings file's `"dua": false` on Jumu'a, the switch «الدعاء بعد الأذان قبل الخطبة» on the TV and on the phone page). Off, the khutba screen (or the countdown before it) follows the adhan screen directly and the Jumu'a iqamah waits for the adhan screen only, as before the dua existed. The other prayers always show it; the setting is refused for them and in Ramadan's changes.

## Mosque screen ticker

The TV's ticker runs under the prayer times all day, so its bundled texts are adhkar of the day, the morning and the evening only: `subhanallah_bihamdih`, `kalimatan_khafifatan`, `la_hawla_quwwata` and the salawat. `اللهم أنت السلام` (Muslim 591) is said right after the taslim; it stays in the after-prayer sequence and is not in the ticker. A mosque may still add any catalog entry to its own ticker list.

The ticker shows each text on one line (line breaks become spaces, the wording is unchanged). Where a long source does not fit beside the text, the ticker cites only its first clause, up to the first `؛`: the Qur'an reference or the first collection and number, never a later narration or grading on its own. The full source stays on the after-prayer screen and in the library.
