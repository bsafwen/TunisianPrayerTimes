package com.qaloon.reciter

/**
 * Compares user's recitation transcription against reference text
 * and produces word-level diff for highlighting.
 */
object RecitationDiffer {

    enum class WordStatus {
        CORRECT,   // word matches reference
        WRONG,     // word differs from reference
        MISSING,   // word in reference was skipped
        EXTRA      // word spoken but not in reference
    }

    data class DiffWord(
        val text: String,
        val status: WordStatus
    )

    /**
     * Compare reference ayah text with transcribed text.
     * Returns a list of DiffWord for display.
     * Uses Longest Common Subsequence for alignment.
     */
    fun diff(reference: String, transcription: String): List<DiffWord> {
        val refWords = normalizeArabic(reference).split("\\s+".toRegex()).filter { it.isNotBlank() }
        val hypWords = normalizeArabic(transcription).split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (refWords.isEmpty()) {
            return hypWords.map { DiffWord(it, WordStatus.EXTRA) }
        }
        if (hypWords.isEmpty()) {
            return refWords.map { DiffWord(it, WordStatus.MISSING) }
        }

        // LCS table
        val m = refWords.size
        val n = hypWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (wordsMatch(refWords[i - 1], hypWords[j - 1])) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to build diff
        val result = mutableListOf<DiffWord>()
        var i = m
        var j = n

        val stack = mutableListOf<DiffWord>()
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && wordsMatch(refWords[i - 1], hypWords[j - 1]) -> {
                    stack.add(DiffWord(refWords[i - 1], WordStatus.CORRECT))
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    stack.add(DiffWord(hypWords[j - 1], WordStatus.EXTRA))
                    j--
                }
                else -> {
                    stack.add(DiffWord(refWords[i - 1], WordStatus.MISSING))
                    i--
                }
            }
        }

        stack.reversed().forEach { result.add(it) }
        return result
    }

    private fun normalizeArabic(text: String): String {
        return text
            // Uthmani: وٰ (waw + superscript alif) represents ا in words like الصلوٰة → الصلاة
            .replace("\u0648\u0670", "\u0627")
            // Uthmani: superscript alif over consonant = long ا (رزقنٰهم → رزقناهم)
            .replace("\u0670", "\u0627")
            // Alif wasla → regular alif (ٱلذين → الذين)
            .replace("\u0671", "\u0627")
            // Remove tashkeel (diacritics)
            .replace(Regex("[\u0610-\u061A\u064B-\u065F\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]"), "")
            .replace("\u0622", "\u0627") // آ → ا
            .replace("\u0623", "\u0627") // أ → ا
            .replace("\u0625", "\u0627") // إ → ا
            .replace("\u0629", "\u0647") // ة → ه
            // Rejoin common Arabic contractions Whisper may split
            .replace(Regex("\\bمن\\s+ما\\b"), "مما")
            .replace(Regex("\\bعن\\s+ما\\b"), "عما")
            .replace(Regex("\\bفي\\s+ما\\b"), "فيما")
            .replace(Regex("\\bان\\s+ما\\b"), "انما")
            .replace(Regex("\\bان\\s+لا\\b"), "الا")
            .replace(Regex("\\bكل\\s+ما\\b"), "كلما")
            .trim()
    }

    private fun wordsMatch(a: String, b: String): Boolean {
        return normalizeArabic(a) == normalizeArabic(b)
    }
}
