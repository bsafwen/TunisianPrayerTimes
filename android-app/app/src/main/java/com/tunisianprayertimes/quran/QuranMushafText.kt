package com.tunisianprayertimes.quran

/**
 * The bundled Qaloon text (the King Fahd Complex encoding, from quranpedia) as the app's Qaloon mushaf prints it, for
 * the mushaf font ([com.tunisianprayertimes.ui.QuranMushafFont], res/font/mushaf_qaloun.ttf). The encoding's own dabt
 * is the mushaf's (the wasl alif's joining vowel and dot, open tanween, the eased hamza) and the font draws it as the
 * mushaf does; these rules change only what the mushaf prints otherwise. Each was read on the page scans at samples
 * spread over the whole Quran (2026-10-15, docs/adhkar-sources.md). The reference implementation is
 * scripts/mushaf_text/mushaf_text.py; the test pins this one's conversion of the whole text to it.
 *
 * - The encoding's waqf signs (at Maghribi positions), its sajdah sign and stray right-to-left marks go: the mushaf's
 *   Madinah signs are not in the source.
 * - The opening letters of 29 surahs, spelled as read in the encoding, are the bare letters the mushaf prints, with
 *   only the madd and the shadda.
 * - A word-final ya is dotless whatever it carries, and so is a ya carrying a small alif.
 * - A tanween fath before a final alif or alif maqsura (also the iqlab one, a fatha and a small meem) sits on the
 *   letter before it, on a hamza over a tatweel on the tatweel.
 * - A hamza on an alif that the encoding marks with a madd and no vowel (al-akhira) carries a fatha.
 * - The single lam of alladhi, alladhina, alladhani, allati and al-layl, prefixed or not, carries a shadda and a
 *   fatha, and so does the second lam of the name Allah after li.
 * - The wasl alif (alif + the vowel it joins + the dot of the vowel it starts with) is written on U+0671, which the
 *   font alone draws as the mushaf's wasl: an eased hamza (its dot first) keeps its alif. The article of a surah's
 *   opening basmala, bare in the encoding, takes the basmala's wasl marks.
 * - The pronoun ana (and lakinna, lakin ana) before a word that starts with neither a hamza nor an alif: its alif
 *   carries the round silent mark.
 * - A final letter merged whole into the next word's first (marked there with a shadda) loses its sukun; a nun merged
 *   with its ghunna keeps it, grey in the mushaf, and so does a silent alif.
 * - The encoding's other dots: above stays; below is the mushaf's dot below (U+065C); on the line (U+06DF) stays, the
 *   font draws it on the line, except on a waw, where the mushaf puts it above and drops the sukun.
 * - Unicode's code points for the open tanween (U+08F0-U+08F2), the dotless ya (U+0649), the ya with hamza.
 *
 * Words are converted one for one (a token that was only a waqf sign comes out empty; ana and a merged final letter
 * look at the next token), so a verse keeps its words and no letter is added or dropped.
 */
object QuranMushafText {
    private val drop = Regex("[\u200F\u06D6-\u06DB\u06E9]")
    private val wasl = Regex("^\u0627([\u064E\u064F\u0650][\u06EC\u06DF\u06EA])")
    private val waslHead = Regex("^\u0671[\u064E\u064F\u0650][\u06EC\u06DF\u06EA]")
    /** A tanween fath: closed, open, or the iqlab form (a fatha and a small meem) before a ba. */
    private const val TANWEEN_FATH = "(?:[\u064B\u08F0]|\u064E\u06E2)"
    private val tanweenOnAlif = Regex("([\u0621-\u064A]\u0651?)([\u0627\u0649])(" + TANWEEN_FATH + ")")
    private val hamzaOnTatweel = Regex("\u0640\u0654\u0627(" + TANWEEN_FATH + ")")
    private val doubledLam = Regex(
        "^([\u0648\u0641\u0628\u0643\u0644]{0,2}\u0627|[\u0648\u0641]?\u0644)\u0644" +
            "(?:\u0630\u0649|\u0630\u064A\u0646|\u062A\u0649|\u064A\u0644|\u0630\u0646)$",
    )
    private val allahAfterLi = Regex("^([\u0648\u0641]?\u0644)\u0644\u0647$")
    private val basmalaArticle = Regex("^\u0627(?=\u0644)")
    private val hamzaOrAlif = Regex("^[\u0621\u0622\u0623\u0624\u0625\u0626\u0627\u0671]")
    private val nextShadda = Regex("^[\u0621-\u064A][\u064B-\u0650\u0652-\u065F\u0670]*\u0651")
    private val ana = setOf(
        "\u0623\u064E\u0646\u064E\u0627",
        "\u0648\u064E\u0623\u064E\u0646\u064E\u0627",
        "\u0641\u064E\u0623\u064E\u0646\u064E\u0627",
        // lakinna (lakin ana, 18:37), whose alif is read the same way
        "\u0644\u0651\u064E\u0670\u0643\u0650\u0646\u0651\u064E\u0627",
    )

    /** The opening letters as the encoding spells them, and as the mushaf prints them: without the spoken vowels. */
    private val openingLetters: Map<String, String> = listOf(
        "\u0623\u064E\u0644\u064E\u0653\u0645\u0651\u0650\u0653",
        "\u0623\u064E\u0644\u064E\u0653\u0645\u0651\u0650\u0653\u0635\u064E\u0653",
        "\u0623\u064E\u0644\u064E\u0653\u0631\u064E",
        "\u0623\u064E\u0644\u064E\u0653\u0645\u0651\u0650\u0653\u0631\u064E",
        "\u0643\u064E\u0653\u0647\u064E\u064A\u064E\u0639\u064E\u0653\u0635\u064E\u0653",
        "\u0637\u064E\u0647\u064E",
        "\u0637\u064E\u0633\u0650\u0653\u0645\u0651\u0650\u0653",
        "\u0637\u064E\u0633\u0650\u0653",
        "\u064A\u064E\u0633\u0650\u0653",
        "\u0635\u064E\u0653",
        "\u062D\u064E\u0645\u0650\u0653",
        "\u0639\u064E\u0653\u0633\u0650\u0653\u0642\u064E\u0653",
        "\u0642\u064E\u0653",
        "\u0646\u064F\u0653",
    ).associateWith { it.replace(Regex("[\u064E\u064F\u0650]"), "").replace('\u0623', '\u0627') }

    private fun isLetter(c: Char) = c in '\u0621'..'\u064A' || c == '\u0671' || c == '\u06D2' || c == '\u06D3'

    private fun letters(t: String) = t.indices.filter { isLetter(t[it]) }

    /** One word in the mushaf's spelling; [next] is the source token after it, null at the end of the run. */
    fun word(token: String, next: String? = null): String {
        var t = token.replace(drop, "")
        openingLetters[t]?.let { return it }
        val following = next?.replace(drop, "").orEmpty()
        t = t.replace("\u06D2\u0654", "\u0626").replace("\u06D3", "\u0626").replace("\u06D2", "\u0649")
            .replace("\u064A\u0654", "\u0626")
            .replace("\u0657", "\u08F0").replace("\u065E", "\u08F1").replace("\u0656", "\u08F2")
        letters(t).lastOrNull()?.let { last -> if (t[last] == '\u064A') t = t.replaceRange(last, last + 1, "\u0649") }
        t = t.replace("\u064A\u0670", "\u0649\u0670")
            .replace(tanweenOnAlif) { it.groupValues[1] + it.groupValues[3] + it.groupValues[2] }
            .replace(hamzaOnTatweel) { "\u0640" + it.groupValues[1] + "\u0654\u0627" }
            .replace("\u0623\u0653", "\u0623\u064E")
        val positions = letters(t)
        val skeleton = positions.joinToString("") { t[it].toString() }
        (doubledLam.find(skeleton) ?: allahAfterLi.find(skeleton))?.let { m ->
            val lam = positions[m.groupValues[1].length]
            if (t.getOrNull(lam + 1) != '\u0651') t = t.substring(0, lam + 1) + "\u0651\u064E" + t.substring(lam + 1)
        }
        t = t.replace(basmalaArticle, "\u0671\u0650\u06EC")
            .replace(wasl) { "\u0671" + it.groupValues[1] }
        if (t in ana && following.isNotEmpty() && !hamzaOrAlif.containsMatchIn(following)) t += "\u0652"
        // A letter merged whole loses its sukun; a nun merged with its ghunna keeps it (grey in the mushaf), and so
        // does a silent alif.
        if (t.endsWith('\u0652') && t.length > 1 && isLetter(t[t.length - 2]) && t[t.length - 2] != '\u0646' &&
            t[t.length - 2] != '\u0627' && nextShadda.containsMatchIn(following)
        ) {
            t = t.dropLast(1)
        }
        val keep = waslHead.find(t)?.range?.last?.plus(1) ?: 0
        val body = t.substring(keep).replace("\u0648\u0652\u06DF", "\u0648\u06EC").replace('\u06EA', '\u065C')
        return t.substring(0, keep) + body
    }

    /** A run of tokens, one word for each (empty where a token was only a waqf sign). */
    fun words(tokens: List<String>): List<String> = tokens.mapIndexed { k, token ->
        word(token, tokens.subList(k + 1, tokens.size).firstOrNull { it.replace(drop, "").isNotEmpty() })
    }

    /** A verse, or a run of words: the words that are left, joined by spaces. */
    fun text(text: String): String = words(text.split(' ')).filter { it.isNotEmpty() }.joinToString(" ")

    private val plainWasl = Regex("\u0671[\u064E\u064F\u0650][\u06EC\u06DF\u06EA]")

    /**
     * Converted words for a font that is not the mushaf's (a notification, a line of the interface): the wasl as a
     * bare U+0671 and no dot on the line, which other fonts draw as a small zero above.
     */
    fun plain(text: String): String = text.replace(plainWasl, "\u0671").replace("\u06DF", "")
}
