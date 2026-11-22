package com.ukpro.core

/**
 * Input composers for scripts where one key press is not one character. Text stays "composing"
 * (shown underlined on the phone, never sent to the laptop) until it is committed — exactly the
 * PROTOCOL rule "never send unfinished composition".
 */
interface Composer {
    /** Feeds the output of one key. Returns text to commit now ("" if everything is still composing). */
    fun input(s: String): String
    /** Current uncommitted text. */
    val composing: String
    /** Handles Backspace inside the composition; false if nothing was composing. */
    fun backspace(): Boolean
    /** Commits everything pending and resets. */
    fun flush(): String
    /** Word suggestions for the current composition (shown above the keyboard). */
    fun suggestions(): List<String> = emptyList()
    /** User tapped a suggestion: returns the text to commit (word + space) and resets. */
    fun pick(word: String): String { flush(); return "$word " }
}

/** Korean 2-set (Dubeolsik): compatibility jamo → Hangul syllables (U+AC00). */
class HangulComposer : Composer {
    private val choSet = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val jungSet = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val jongSet = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val vowelPairs = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
    private val finalPairs = mapOf("ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ',
        "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ')

    private var cho: Char? = null
    private var jung: Char? = null
    private var jong: Char? = null

    private fun isVowel(c: Char) = jungSet.indexOf(c) >= 0
    private fun isConsonant(c: Char) = choSet.indexOf(c) >= 0 || jongSet.indexOf(c) > 0

    override val composing: String get() {
        val c = cho; val v = jung; val f = jong
        if (c != null && v != null) {
            val code = 0xAC00 + (choSet.indexOf(c) * 21 + jungSet.indexOf(v)) * 28 + (if (f == null) 0 else jongSet.indexOf(f))
            return code.toChar().toString()
        }
        return (c?.toString() ?: "") + (v?.toString() ?: "")
    }

    private fun take(): String { val s = composing; cho = null; jung = null; jong = null; return s }

    override fun input(s: String): String {
        if (s.length != 1) return flush() + s
        val ch = s[0]
        if (isConsonant(ch)) {
            val c = cho; val v = jung; val f = jong
            when {
                c == null && v == null -> { cho = ch; return "" }
                v == null -> { val out = take(); cho = ch; return out }                 // two consonants in a row
                c == null -> { val out = take(); cho = ch; return out }                 // lone vowel then consonant
                f == null -> return if (jongSet.indexOf(ch) > 0) { jong = ch; "" } else { val out = take(); cho = ch; out }
                else -> {
                    val pair = finalPairs["$f$ch"]
                    return if (pair != null) { jong = pair; "" } else { val out = take(); cho = ch; out }
                }
            }
        }
        if (isVowel(ch)) {
            val c = cho; val v = jung; val f = jong
            when {
                f != null -> {
                    // the final consonant moves to the next syllable: 각 + ㅏ → 가가
                    val split = finalPairs.entries.firstOrNull { it.value == f }
                    val moved: Char
                    if (split != null) { jong = split.key[0]; moved = split.key[1] } else { jong = null; moved = f }
                    val out = take(); cho = moved; jung = ch; return out
                }
                v != null -> {
                    val pair = vowelPairs["$v$ch"]
                    return if (pair != null) { jung = pair; "" }
                    else { val out = take(); jung = ch; out }
                }
                c != null -> { jung = ch; return "" }
                else -> { jung = ch; return "" }
            }
        }
        return flush() + s
    }

    override fun backspace(): Boolean {
        val f = jong; val v = jung
        when {
            f != null -> { jong = finalPairs.entries.firstOrNull { it.value == f }?.key?.get(0) }
            v != null -> { jung = vowelPairs.entries.firstOrNull { it.value == v }?.key?.get(0) }
            cho != null -> cho = null
            else -> return false
        }
        return true
    }

    override fun flush(): String = take()
}

/** Japanese romaji → hiragana (or katakana). Kanji conversion needs a dictionary IME (use Universal text). */
class KanaComposer(var katakana: Boolean = false) : Composer {
    private val table: Map<String, String> = HashMap<String, String>().apply {
        val rows = mapOf(
            "" to "あいうえお", "k" to "かきくけこ", "g" to "がぎぐげご", "s" to "さしすせそ", "z" to "ざじずぜぞ",
            "t" to "たちつてと", "d" to "だぢづでど", "n" to "なにぬねの", "h" to "はひふへほ", "b" to "ばびぶべぼ",
            "p" to "ぱぴぷぺぽ", "m" to "まみむめも", "r" to "らりるれろ", "x" to "ぁぃぅぇぉ", "l" to "ぁぃぅぇぉ")
        val vowels = "aiueo"
        for ((c, kana) in rows) for (i in 0 until 5) put(c + vowels[i], kana[i].toString())
        for ((k, v) in listOf("ya" to "や", "yu" to "ゆ", "yo" to "よ", "wa" to "わ", "wo" to "を", "nn" to "ん", "n'" to "ん",
            "shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ", "ji" to "じ", "si" to "し", "ti" to "ち", "tu" to "つ", "hu" to "ふ",
            "zi" to "じ", "di" to "ぢ", "du" to "づ", "xtu" to "っ", "ltu" to "っ", "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ", "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ", "je" to "じぇ",
            "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "she" to "しぇ", "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
            "che" to "ちぇ", "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ", "ye" to "いぇ", "wi" to "うぃ", "we" to "うぇ",
            "-" to "ー", "." to "。", "," to "、", "[" to "「", "]" to "」", "?" to "？", "!" to "！")) put(k, v)
        for ((c, i) in listOf("k" to "き", "g" to "ぎ", "s" to "し", "z" to "じ", "t" to "ち", "d" to "ぢ", "n" to "に", "h" to "ひ",
            "b" to "び", "p" to "ぴ", "m" to "み", "r" to "り")) {
            put(c + "ya", i + "ゃ"); put(c + "yu", i + "ゅ"); put(c + "yo", i + "ょ")
        }
    }
    private val prefixes: Set<String> = table.keys.flatMap { k -> (1..k.length).map { k.substring(0, it) } }.toSet()

    private val kana = StringBuilder()
    private var pending = ""

    override val composing: String get() = convertOut(kana.toString()) + pending

    private fun convertOut(h: String): String = if (!katakana) h else {
        val sb = StringBuilder()
        for (c in h) sb.append(if (c in 'ぁ'..'ゖ') (c + 0x60) else c)
        sb.toString()
    }

    private fun convert() {
        while (pending.isNotEmpty()) {
            var matched = false
            for (len in minOf(4, pending.length) downTo 1) {
                val v = table[pending.substring(0, len)]
                if (v != null && !(pending.substring(0, len) == "n" && len == pending.length)) {
                    // "n" alone could still become "na"/"nya": wait
                    kana.append(v); pending = pending.substring(len); matched = true; break
                }
            }
            if (matched) continue
            val c0 = pending[0]
            if (pending.length >= 2 && pending[1] == c0 && c0 in "bcdfghjkmpqrstvwxz") { kana.append("っ"); pending = pending.substring(1); continue }
            if (c0 == 'n' && pending.length >= 2 && pending[1] !in "aiueoyn'") { kana.append("ん"); pending = pending.substring(1); continue }
            if (pending in prefixes) return // wait for more letters
            kana.append(c0); pending = pending.substring(1)
        }
    }

    override fun input(s: String): String {
        if (s.length == 1) {
            val c = s[0]
            val lower = if (c in 'A'..'Z') c + 32 else c
            if (lower in 'a'..'z' || c == '\'' || c == '-' || (table.containsKey(s) && pending.isEmpty())) {
                pending += lower; convert(); return ""
            }
        }
        return flush() + s
    }

    override fun backspace(): Boolean {
        if (pending.isNotEmpty()) { pending = pending.substring(0, pending.length - 1); return true }
        if (kana.isNotEmpty()) { kana.setLength(kana.length - 1); return true }
        return false
    }

    override fun flush(): String {
        convert()
        if (pending == "n") kana.append("ん") else kana.append(pending)
        pending = ""
        val out = convertOut(kana.toString())
        kana.setLength(0)
        return out
    }
}

/** Vietnamese Telex: aa→â, aw→ă, ee→ê, oo→ô, ow→ơ, uw→ư, dd→đ; tones s f r x j, z removes the tone. */
class TelexComposer : Composer {
    private val word = StringBuilder()
    override val composing: String get() = word.toString()

    // tone index: 0 none, 1 sắc (s), 2 huyền (f), 3 hỏi (r), 4 ngã (x), 5 nặng (j)
    private val toned = arrayOf("aáàảãạ", "ăắằẳẵặ", "âấầẩẫậ", "eéèẻẽẹ", "êếềểễệ", "iíìỉĩị", "oóòỏõọ", "ôốồổỗộ", "ơớờởỡợ", "uúùủũụ", "ưứừửữự", "yýỳỷỹỵ")
    private val toneKeys = "sfrxj"

    private fun baseOf(c: Char): Pair<Int, Int>? { // (row, tone)
        val lc = Character.toLowerCase(c)
        for ((r, row) in toned.withIndex()) { val i = row.indexOf(lc); if (i >= 0) return r to i }
        return null
    }

    private fun withTone(c: Char, tone: Int): Char {
        val (r, _) = baseOf(c) ?: return c
        val out = toned[r][tone]
        return if (Character.isUpperCase(c)) Character.toUpperCase(out) else out
    }

    private fun vowelIndexForTone(): Int {
        val idx = word.indices.filter { baseOf(word[it]) != null }
        if (idx.isEmpty()) return -1
        // a vowel with a diacritic (â ă ê ô ơ ư) takes the tone; in "ươ" it is the ơ, so take the last one
        idx.lastOrNull { baseOf(word[it])!!.first in setOf(1, 2, 4, 7, 8, 10) }?.let { return it }
        if (idx.size == 1) return idx[0]
        val last = idx.last()
        val endsWithVowel = last == word.length - 1
        val pair = Character.toLowerCase(word[idx[idx.size - 2]]).toString() + Character.toLowerCase(word[last])
        if (endsWithVowel && pair !in setOf("oa", "oe", "uy")) return idx[idx.size - 2]
        return last
    }

    override fun input(s: String): String {
        if (s.length != 1 || !s[0].isLetter()) return flush() + s
        val c = s[0]; val lc = Character.toLowerCase(c)
        if (word.isNotEmpty()) {
            val prev = word.last(); val lp = Character.toLowerCase(prev)
            val prevBase = baseOf(prev)
            fun replaceLast(n: Char) { word.setCharAt(word.length - 1, if (Character.isUpperCase(prev)) Character.toUpperCase(n) else n) }
            val tone = prevBase?.second ?: 0
            when {
                lc == 'd' && lp == 'd' -> { replaceLast('đ'); return "" }
                lc == 'a' && prevBase?.first == 0 -> { replaceLast(withTone('â', tone)); return "" }
                lc == 'e' && prevBase?.first == 3 -> { replaceLast(withTone('ê', tone)); return "" }
                lc == 'o' && prevBase?.first == 6 -> { replaceLast(withTone('ô', tone)); return "" }
                lc == 'w' && prevBase?.first == 0 -> { replaceLast(withTone('ă', tone)); return "" }
                lc == 'w' && prevBase?.first == 6 -> { replaceLast(withTone('ơ', tone)); return "" }
                lc == 'w' && prevBase?.first == 9 -> { replaceLast(withTone('ư', tone)); return "" }
            }
            val t = toneKeys.indexOf(lc)
            if (t >= 0 || lc == 'z') {
                val i = vowelIndexForTone()
                if (i >= 0) { word.setCharAt(i, withTone(word[i], if (lc == 'z') 0 else t + 1)); return "" }
            }
        }
        word.append(c)
        return ""
    }

    override fun backspace(): Boolean {
        if (word.isEmpty()) return false
        word.setLength(word.length - 1); return true
    }

    override fun flush(): String { val s = word.toString(); word.setLength(0); return s }
}

/**
 * Indic scripts share one ISCII-derived Unicode layout, so a Devanagari layout maps to Gurmukhi, Gujarati,
 * Odia, Tamil, Telugu, Kannada and Malayalam by a fixed block offset. Returns null when the target script has
 * no such letter (e.g. Tamil has no aspirated consonants).
 */
object IndicScripts {
    const val DEVANAGARI = 0x0900; const val BENGALI = 0x0980; const val GURMUKHI = 0x0A00; const val GUJARATI = 0x0A80
    const val ORIYA = 0x0B00; const val TAMIL = 0x0B80; const val TELUGU = 0x0C00; const val KANNADA = 0x0C80; const val MALAYALAM = 0x0D00

    fun fromDevanagari(s: String, base: Int): String? {
        val sb = StringBuilder()
        for (c in s) {
            val code = c - '\u0000'
            if (code in 0x0900..0x097F && code != 0x0964 && code != 0x0965) { // danda ।॥ is shared by all
                val t = base + (code - 0x0900)
                if (!Character.isDefined(t)) return null
                sb.append(t.toChar())
            } else sb.append(c)
        }
        return sb.toString()
    }
}
