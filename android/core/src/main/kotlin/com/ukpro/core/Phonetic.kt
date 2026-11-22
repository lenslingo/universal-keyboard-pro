package com.ukpro.core

/**
 * Avro-style Bangla phonetic transliteration: "ami banglay gan gai" → "আমি বাংলায় গান গাই".
 *  • vowels: a আ/া, i ই/ি, I ঈ/ী, u উ/ু, U ঊ/ূ, e এ/ে, o অ/(inherent), O ও/ো, oi ঐ/ৈ, ou ঔ/ৌ, rri ঋ/ৃ, ee ঈ/ী, oo উ/ু
 *  • consonants: k kh g gh Ng c ch j jh NG T Th D Dh N t th d dh n p ph/f b bh/v m z r R Rh l sh S s h y Y
 *  • two consonants in a row form a conjunct (k+t → ক্ত); type "o" between them to keep them apart (koro → কর)
 *  • y/Z after a consonant = য-ফলা (্য), w after a consonant = ব-ফলা (্ব); ng ং, : ঃ, ^ ঁ, t` ৎ, . ।, digits → ০-৯
 */
object BanglaPhonetic {
    private class V(val independent: String, val kar: String)

    private val vowels: List<Pair<String, V>> = listOf(
        "rri" to V("ঋ", "ৃ"), "OI" to V("ঐ", "ৈ"), "oi" to V("ঐ", "ৈ"), "OU" to V("ঔ", "ৌ"), "ou" to V("ঔ", "ৌ"),
        "ee" to V("ঈ", "ী"), "oo" to V("উ", "ু"), "a" to V("আ", "া"), "A" to V("আ", "া"), "i" to V("ই", "ি"), "I" to V("ঈ", "ী"),
        "u" to V("উ", "ু"), "U" to V("ঊ", "ূ"), "e" to V("এ", "ে"), "E" to V("এ", "ে"), "o" to V("অ", ""), "O" to V("ও", "ো"))

    private val consonants: List<Pair<String, String>> = listOf(
        "chh" to "ছ", "kh" to "খ", "gh" to "ঘ", "Ng" to "ঙ", "ch" to "ছ", "jh" to "ঝ", "NG" to "ঞ", "Th" to "ঠ", "Dh" to "ঢ",
        "th" to "থ", "dh" to "ধ", "ph" to "ফ", "bh" to "ভ", "Sh" to "ষ", "sh" to "শ", "Rh" to "ঢ়", "ng" to "ং", "t`" to "ৎ",
        "k" to "ক", "K" to "ক", "q" to "ক", "Q" to "ক", "g" to "গ", "G" to "গ", "c" to "চ", "C" to "ছ", "j" to "জ", "J" to "জ",
        "T" to "ট", "D" to "ড", "N" to "ণ", "t" to "ত", "d" to "দ", "n" to "ন", "p" to "প", "P" to "প", "f" to "ফ", "F" to "ফ",
        "b" to "ব", "B" to "ব", "v" to "ভ", "V" to "ভ", "m" to "ম", "M" to "ম", "z" to "য", "r" to "র", "R" to "ড়", "l" to "ল",
        "L" to "ল", "S" to "শ", "s" to "স", "h" to "হ", "H" to "হ", "x" to "ক্স", "X" to "ক্স")

    private val signs = setOf("ং", "ঃ", "ঁ", "ৎ")
    private const val DIGITS = "০১২৩৪৫৬৭৮৯"

    private enum class T { NONE, VOWEL, CONSONANT, SIGN }

    fun transliterate(latin: String): String {
        val out = StringBuilder()
        var prev = T.NONE
        var i = 0
        loop@ while (i < latin.length) {
            val c = latin[i]
            // vowels (longest first)
            for ((pat, v) in vowels) if (latin.startsWith(pat, i)) {
                out.append(if (prev == T.CONSONANT) v.kar else v.independent)
                prev = T.VOWEL; i += pat.length; continue@loop
            }
            // phala forms after a consonant
            if (prev == T.CONSONANT && (c == 'y' || c == 'Z')) { out.append("্য"); i++; continue@loop }
            if (prev == T.CONSONANT && c == 'w') { out.append("্ব"); i++; continue@loop }
            if (c == 'y' || c == 'Y') { out.append("য়"); prev = T.CONSONANT; i++; continue@loop }
            if (c == 'w' || c == 'W') { out.append("ও"); prev = T.VOWEL; i++; continue@loop }
            for ((pat, b) in consonants) if (latin.startsWith(pat, i)) {
                val isSign = b in signs
                if (prev == T.CONSONANT && !isSign) out.append("্")
                out.append(b)
                prev = if (isSign) T.SIGN else T.CONSONANT
                i += pat.length; continue@loop
            }
            when {
                c == ':' -> { out.append("ঃ"); prev = T.SIGN }
                c == '^' -> { out.append("ঁ"); prev = T.SIGN }
                c == '.' -> { out.append("।"); prev = T.NONE }
                c == '$' -> { out.append("৳"); prev = T.NONE }
                c in '0'..'9' -> { out.append(DIGITS[c - '0']); prev = T.NONE }
                else -> { out.append(c); prev = T.NONE }
            }
            i++
        }
        return out.toString()
    }

    /**
     * Loose "sound skeleton" of a Bangla word so phonetic input matches dictionary spellings despite
     * ambiguous letters: ত/ট, থ/ঠ, দ/ড, ধ/ঢ, ন/ণ/ঞ, স/শ/ষ, জ/য, ি/ী, ু/ূ, র/ড়/ঢ়, ঙ/ং; hasant, ো, ঁ and ঃ ignored.
     */
    fun skeleton(word: String): String {
        val w = word.replace("য়", "য়").replace("ড়", "ড়").replace("ঢ়", "ঢ়")
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val next = if (i + 1 < w.length) w[i + 1] else '\u0000'
            if (next == '়') { // nukta forms
                sb.append(when (c) { '\u09AF' -> '\u09DF'; '\u09A1', '\u09A2' -> '\u09B0'; else -> c }); i += 2; continue
            }
            when (c) {
                '্', '‌', '‍', 'ঁ', 'ঃ', 'ো' -> {} // ো ≈ inherent vowel: "tomake" matches তোমাকে
                'ট' -> sb.append('ত'); 'ঠ' -> sb.append('থ'); 'ড' -> sb.append('দ'); 'ঢ' -> sb.append('ধ')
                'ণ', 'ঞ' -> sb.append('ন'); 'ঙ' -> sb.append('ং'); 'শ', 'ষ' -> sb.append('স'); 'য' -> sb.append('জ')
                'ী' -> sb.append('ি'); 'ঈ' -> sb.append('ই'); 'ূ' -> sb.append('ু'); 'ঊ' -> sb.append('উ'); 'ৎ' -> sb.append('ত')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

/** Frequency-ranked word list ("word\tscore" lines). Thread-safe after construction. */
class WordDictionary(lines: Sequence<String>, keyOf: (String) -> String = { it }) {
    class Entry(val word: String, val key: String, val freq: Int)

    private val byKey: List<Entry>
    private val exact = HashMap<String, Int>()
    val size: Int get() = byKey.size

    init {
        val list = ArrayList<Entry>()
        for (line in lines) {
            val t = line.indexOf('\t')
            val w = if (t < 0) line.trim() else line.substring(0, t)
            if (w.isEmpty()) continue
            val f = if (t < 0) 0 else line.substring(t + 1).trim().toIntOrNull() ?: 0
            if (!exact.containsKey(w)) { exact[w] = f; list.add(Entry(w, keyOf(w), f)) }
        }
        list.sortWith(Comparator { a, b -> a.key.compareTo(b.key) })
        byKey = list
    }

    fun contains(word: String) = exact.containsKey(word)
    fun freq(word: String) = exact[word] ?: -1

    /** Entries whose key starts with [prefix], highest frequency first (scans at most [scan] candidates). */
    fun withKeyPrefix(prefix: String, limit: Int, scan: Int = 3000): List<Entry> {
        var lo = 0; var hi = byKey.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (byKey[mid].key < prefix) lo = mid + 1 else hi = mid }
        val found = ArrayList<Entry>()
        var i = lo
        while (i < byKey.size && byKey[i].key.startsWith(prefix) && found.size < scan) { found.add(byKey[i]); i++ }
        found.sortWith(Comparator { a, b -> b.freq - a.freq })
        return if (found.size > limit) found.subList(0, limit) else found
    }
}

/** Suggestions + auto-correction for Bangla phonetic input and English words. */
class Suggester(private val bangla: WordDictionary?, private val english: WordDictionary?) {

    // ---------------------------------------------------------------- Bangla (phonetic)

    /** Up to 3 suggestions for a latin buffer: dictionary words that sound the same, the literal transliteration, completions. */
    fun bangla(latin: String): List<String> {
        val literal = BanglaPhonetic.transliterate(latin)
        val dict = bangla ?: return listOf(literal)
        val sk = BanglaPhonetic.skeleton(literal)
        val cands = dict.withKeyPrefix(sk, 30)
        val exact = cands.filter { it.key == sk }.map { it.word }
        val prefix = cands.filter { it.key != sk }.map { it.word }
        val out = ArrayList<String>()
        if (exact.isNotEmpty()) { out.add(exact[0]); out.add(literal); out.addAll(exact.drop(1)); out.addAll(prefix) }
        else { out.add(literal); out.addAll(prefix) }
        val eng = english
        if (eng != null && eng.contains(lower(latin)) && latin.length > 1) out.add(minOf(out.size, 2), latin) // English word typed in Bangla mode
        return out.distinct().take(3)
    }

    /** Word to commit on space: the literal if it is a real word, else the most frequent word that sounds the same. */
    fun banglaAutocorrect(latin: String): String {
        val literal = BanglaPhonetic.transliterate(latin)
        val dict = bangla ?: return literal
        val nfc = java.text.Normalizer.normalize(literal, java.text.Normalizer.Form.NFC)
        val own = maxOf(dict.freq(literal), dict.freq(nfc))
        val sk = BanglaPhonetic.skeleton(literal)
        val best = dict.withKeyPrefix(sk, 10).firstOrNull { it.key == sk } ?: return literal
        // keep a real word the user spelled out; replace unknown/rare spellings by the common word that sounds the same
        return if (own >= 0 && own + 100 >= best.freq) literal else best.word
    }

    // ---------------------------------------------------------------- English

    fun english(word: String): List<String> {
        val dict = english ?: return emptyList()
        if (word.isEmpty()) return emptyList()
        val low = lower(word)
        val completions = dict.withKeyPrefix(low, 4).filter { it.word != low }.map { restoreCase(word, it.word) }
        val known = dict.freq(low) >= 300
        val out = ArrayList<String>()
        when {
            known -> { out.add(word); out.addAll(completions) }                       // "hel" → hel, help, hello
            completions.isNotEmpty() -> { out.addAll(completions.take(1)); out.add(word); out.addAll(completions.drop(1)) } // "keyb" → keyboard
            else -> {                                                                // "beutiful" → beautiful
                val corr = edits1Scored(low).entries.filter { dict.contains(it.key) }
                    .sortedByDescending { dict.freq(it.key) + it.value }.map { restoreCase(word, it.key) }.take(2)
                out.addAll(corr.take(1)); out.add(word); out.addAll(corr.drop(1))
            }
        }
        return out.distinct().take(3)
    }

    /** Conservative auto-correction: only unknown lowercase-ish words, only to a frequent word one edit away. */
    fun englishAutocorrect(word: String): String {
        val dict = english ?: return word
        if (word == "i") return "I"
        if (word.length < 3 || word.any { it in '0'..'9' }) return word
        val low = lower(word)
        val own = dict.freq(low)
        if (own >= 350) return word                                       // a common real word: never touch it
        if (word.drop(1).any { Character.isUpperCase(it) }) return word   // looks like a name / acronym
        val best = bestCorrection(low, dict) ?: return word
        // rare spellings (corpora contain typos such as "teh") lose only to a far more common neighbour
        return if (dict.freq(best) >= 300 && dict.freq(best) >= own + 150) restoreCase(word, best) else word
    }

    private val qwertyRows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private fun adjacent(a: Char, b: Char): Boolean {
        for ((r, row) in qwertyRows.withIndex()) {
            val i = row.indexOf(a); if (i < 0) continue
            for ((r2, row2) in qwertyRows.withIndex()) {
                val j = row2.indexOf(b); if (j >= 0 && Math.abs(r - r2) <= 1 && Math.abs(i - j) <= 1 && !(r == r2 && i == j)) return true
            }
        }
        return false
    }

    /** All strings one edit away, with a likelihood bonus for the most common typo kinds. */
    private fun edits1Scored(w: String): Map<String, Int> {
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        val out = HashMap<String, Int>()
        fun put(x: String, bonus: Int) { if (x != w && (out[x] ?: -1) < bonus) out[x] = bonus }
        for (i in 0..w.length) {
            val a = w.substring(0, i); val b = w.substring(i)
            if (b.isNotEmpty()) put(a + b.substring(1), if (i > 0 && w[i - 1] == b[0]) 120 else 0)           // delete (doubled letter typed twice)
            if (b.length > 1) put(a + b[1] + b[0] + b.substring(2), 150)                                        // transpose
            for (c in letters) {
                if (b.isNotEmpty()) put(a + c + b.substring(1), if (adjacent(b[0], c)) 60 else 0)               // replace (fat finger)
                put(a + c + b, if ((i > 0 && w[i - 1] == c) || (b.isNotEmpty() && b[0] == c)) 200 else 0)        // insert (missed double)
            }
        }
        return out
    }

    private fun edits1(w: String): Set<String> = edits1Scored(w).keys

    private fun bestCorrection(low: String, dict: WordDictionary): String? =
        edits1Scored(low).entries.filter { dict.contains(it.key) }.maxByOrNull2 { dict.freq(it.key) + it.value }?.key

    private fun restoreCase(typed: String, word: String): String =
        if (typed.isNotEmpty() && Character.isUpperCase(typed[0])) Character.toUpperCase(word[0]) + word.substring(1) else word

    private fun lower(s: String): String { val sb = StringBuilder(s.length); for (c in s) sb.append(Character.toLowerCase(c)); return sb.toString() }

    private inline fun <T> Iterable<T>.maxByOrNull2(f: (T) -> Int): T? {
        var best: T? = null; var bv = Int.MIN_VALUE
        for (x in this) { val v = f(x); if (v > bv) { bv = v; best = x } }
        return best
    }
}

/** Bangla phonetic typing with suggestions: composing shows Bangla, Space commits the (auto-corrected) word. */
class BanglaPhoneticComposer(private val suggester: () -> Suggester?, private val autoCorrect: () -> Boolean = { true }) : Composer {
    private val buf = StringBuilder()
    override val composing: String get() = BanglaPhonetic.transliterate(buf.toString())

    override fun input(s: String): String {
        if (s.length == 1) {
            val c = s[0]
            if (c in 'a'..'z' || c in 'A'..'Z' || c == '`' || c == '^' || (c == ':' && buf.isNotEmpty()) || (c in '0'..'9' && buf.isNotEmpty())) {
                buf.append(c); return ""
            }
            if (c in '0'..'9') return BanglaPhonetic.transliterate(s)
        }
        val word = commitWord(s == " " && autoCorrect())
        val tail = if (s == ".") "।" else s
        return word + tail
    }

    private fun commitWord(correct: Boolean): String {
        if (buf.isEmpty()) return ""
        val latin = buf.toString(); buf.setLength(0)
        val sg = suggester()
        return if (correct && sg != null) sg.banglaAutocorrect(latin) else BanglaPhonetic.transliterate(latin)
    }

    override fun backspace(): Boolean {
        if (buf.isEmpty()) return false
        buf.setLength(buf.length - 1); return true
    }

    override fun flush(): String = commitWord(false)

    override fun suggestions(): List<String> {
        if (buf.isEmpty()) return emptyList()
        return suggester()?.bangla(buf.toString()) ?: listOf(composing)
    }

    override fun pick(word: String): String { buf.setLength(0); return "$word " }
}

/** English word composition with suggestions and conservative auto-correct on Space. */
class EnglishWordComposer(private val suggester: () -> Suggester?, private val autoCorrect: () -> Boolean = { true }) : Composer {
    private val buf = StringBuilder()
    override val composing: String get() = buf.toString()

    override fun input(s: String): String {
        if (s.length == 1 && (Character.isLetter(s[0]) || (s[0] == '\'' && buf.isNotEmpty()))) { buf.append(s); return "" }
        val w = buf.toString(); buf.setLength(0)
        val sg = suggester()
        val fixed = if (s == " " && autoCorrect() && sg != null) sg.englishAutocorrect(w) else w
        return fixed + s
    }

    override fun backspace(): Boolean {
        if (buf.isEmpty()) return false
        buf.setLength(buf.length - 1); return true
    }

    override fun flush(): String { val w = buf.toString(); buf.setLength(0); return w }

    override fun suggestions(): List<String> = if (buf.isEmpty()) emptyList() else suggester()?.english(buf.toString()) ?: emptyList()

    override fun pick(word: String): String { buf.setLength(0); return "$word " }
}
