package com.ukpro.android.keyboard

enum class KeyKind { CHAR, MODIFIER, ACTION, APP }

/**
 * One key. [code] is the W3C KeyboardEvent.code sent to Windows. For APP keys [code] is an internal
 * action ("LANG", "PANEL"). [out]/[shiftOut] are the characters produced on the phone.
 */
class Key(
    val code: String,
    val label: String,
    val width: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
    val out: String = "",
    val shiftOut: String = "",
    val shiftLabel: String? = null,
    val alts: List<String> = emptyList(),
    val repeatable: Boolean = false,
    /** Letters whose case follows Shift xor Caps Lock (Latin, Cyrillic, Greek). */
    val cased: Boolean = false,
) {
    val isLetter: Boolean get() = code.startsWith("Key")
}

class Row(val keys: List<Key>, val height: Float = 1f)

/**
 * A language layout on the physical QWERTY positions. Shortcuts (Ctrl+C …) always use the physical
 * key code, so they work in every language. Text is committed as Unicode, so any script reaches Windows.
 */
class Lang(
    val id: String,
    val label: String,        // shown on the language key
    val name: String,         // shown in settings
    val spaceLabel: String,
    /** letter → (normal, shift, long-press extras) */
    val letters: Map<Char, Triple<String, String, List<String>>>,
    /** W3C code → (normal, shift) overrides for punctuation keys */
    val punct: Map<String, Pair<String, String>> = emptyMap(),
    val digits: List<String>? = null,
    val rtl: Boolean = false,
)

object Languages {
    private fun cased(pairs: String, upper: String): Map<Char, Triple<String, String, List<String>>> {
        val qwerty = "qwertyuiopasdfghjklzxcvbnm"
        return qwerty.indices.associate { i -> qwerty[i] to Triple(pairs[i].toString(), upper[i].toString(), emptyList()) }
    }

    private val latinAccents = mapOf(
        'a' to listOf("á", "à", "â", "ä", "ã", "å", "æ", "ā", "ą"), 'c' to listOf("ç", "ć", "č"), 'd' to listOf("đ", "ď"),
        'e' to listOf("é", "è", "ê", "ë", "ē", "ę", "ě"), 'g' to listOf("ğ"), 'i' to listOf("í", "ì", "î", "ï", "ı"),
        'l' to listOf("ł"), 'n' to listOf("ñ", "ń", "ň"), 'o' to listOf("ó", "ò", "ô", "ö", "õ", "ø", "œ", "ő"),
        'r' to listOf("ř"), 's' to listOf("ß", "ś", "š", "ş"), 't' to listOf("ť", "þ"), 'u' to listOf("ú", "ù", "û", "ü", "ū", "ů", "ű"),
        'y' to listOf("ý", "ÿ"), 'z' to listOf("ž", "ź", "ż"),
    )

    val english = Lang("en", "EN", "English (+ accents: á ç ñ ü ß …)", "English",
        "qwertyuiopasdfghjklzxcvbnm".associate { c -> c to Triple(c.toString(), c.uppercaseChar().toString(), latinAccents[c] ?: emptyList()) })

    /** Phonetic-position Bangla ("UK Pro Bangla"). Juktakkhor: consonant + q (্) + consonant. */
    val bangla = Lang("bn", "বাং", "বাংলা (Bangla)", "বাংলা", mapOf(
        'q' to Triple("্", "ঁ", listOf("ঁ", "্")), 'w' to Triple("ৌ", "ঔ", emptyList()), 'e' to Triple("ে", "এ", listOf("ৈ", "ঐ", "এ")),
        'r' to Triple("র", "ড়", listOf("ঋ", "ৃ", "ড়", "ঢ়")), 't' to Triple("ত", "থ", listOf("ট", "ঠ", "থ", "ৎ")),
        'y' to Triple("য়", "য", listOf("য")), 'u' to Triple("ু", "উ", listOf("ূ", "ঊ", "উ")), 'i' to Triple("ি", "ই", listOf("ী", "ঈ", "ই")),
        'o' to Triple("ো", "ও", listOf("ৌ", "ঔ", "ও")), 'p' to Triple("প", "ঢ়", emptyList()),
        'a' to Triple("া", "অ", listOf("আ", "অ")), 's' to Triple("স", "শ", listOf("ষ", "শ")), 'd' to Triple("দ", "ধ", listOf("ড", "ঢ", "ধ")),
        'f' to Triple("ফ", "ৎ", emptyList()), 'g' to Triple("গ", "ঘ", emptyList()), 'h' to Triple("হ", "ঃ", emptyList()),
        'j' to Triple("জ", "ঝ", emptyList()), 'k' to Triple("ক", "খ", emptyList()), 'l' to Triple("ল", "ং", emptyList()),
        'z' to Triple("ট", "ঠ", emptyList()), 'x' to Triple("ষ", "ঢ", emptyList()), 'c' to Triple("চ", "ছ", emptyList()),
        'v' to Triple("ৃ", "ঋ", emptyList()), 'b' to Triple("ব", "ভ", emptyList()), 'n' to Triple("ন", "ণ", listOf("ঞ", "ণ")),
        'm' to Triple("ম", "ঙ", emptyList()),
    ), punct = mapOf("Period" to ("।" to ".")), digits = listOf("১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯", "০"))

    /** Phonetic-position Hindi / Devanagari. Conjuncts: consonant + q (्) + consonant. */
    val hindi = Lang("hi", "हि", "हिन्दी (Hindi / Devanagari)", "हिन्दी", mapOf(
        'q' to Triple("्", "ँ", listOf("ँ", "़", "्")), 'w' to Triple("ौ", "औ", emptyList()), 'e' to Triple("े", "ए", listOf("ै", "ऐ", "ए")),
        'r' to Triple("र", "ृ", listOf("ऋ", "ृ", "ड़", "ढ़")), 't' to Triple("त", "थ", listOf("ट", "ठ", "थ")),
        'y' to Triple("य", "य़", emptyList()), 'u' to Triple("ु", "उ", listOf("ू", "ऊ", "उ")), 'i' to Triple("ि", "इ", listOf("ी", "ई", "इ")),
        'o' to Triple("ो", "ओ", listOf("ौ", "औ", "ओ", "ॉ")), 'p' to Triple("प", "फ", emptyList()),
        'a' to Triple("ा", "अ", listOf("आ", "अ")), 's' to Triple("स", "श", listOf("ष", "श")), 'd' to Triple("द", "ध", listOf("ड", "ढ", "ध")),
        'f' to Triple("फ", "फ़", emptyList()), 'g' to Triple("ग", "घ", listOf("ग़")), 'h' to Triple("ह", "ः", emptyList()),
        'j' to Triple("ज", "झ", listOf("ज़")), 'k' to Triple("क", "ख", listOf("क़", "ख़")), 'l' to Triple("ल", "ं", emptyList()),
        'z' to Triple("ज़", "ञ", emptyList()), 'x' to Triple("क्ष", "त्र", listOf("ज्ञ", "श्र")), 'c' to Triple("च", "छ", emptyList()),
        'v' to Triple("व", "ॐ", emptyList()), 'b' to Triple("ब", "भ", emptyList()), 'n' to Triple("न", "ण", listOf("ञ", "ङ")),
        'm' to Triple("म", "ङ", emptyList()),
    ), punct = mapOf("Period" to ("।" to "॥")), digits = listOf("१", "२", "३", "४", "५", "६", "७", "८", "९", "०"))

    /** Standard Arabic (101) layout. */
    val arabic = Lang("ar", "ع", "العربية (Arabic)", "العربية", mapOf(
        'q' to Triple("ض", "َ", emptyList()), 'w' to Triple("ص", "ً", emptyList()), 'e' to Triple("ث", "ُ", emptyList()),
        'r' to Triple("ق", "ٌ", emptyList()), 't' to Triple("ف", "لإ", emptyList()), 'y' to Triple("غ", "إ", emptyList()),
        'u' to Triple("ع", "‘", emptyList()), 'i' to Triple("ه", "÷", emptyList()), 'o' to Triple("خ", "×", emptyList()),
        'p' to Triple("ح", "؛", emptyList()), 'a' to Triple("ش", "ِ", emptyList()), 's' to Triple("س", "ٍ", emptyList()),
        'd' to Triple("ي", "]", emptyList()), 'f' to Triple("ب", "[", emptyList()), 'g' to Triple("ل", "لأ", emptyList()),
        'h' to Triple("ا", "أ", listOf("أ", "إ", "آ", "ٱ")), 'j' to Triple("ت", "ـ", emptyList()), 'k' to Triple("ن", "،", emptyList()),
        'l' to Triple("م", "/", emptyList()), 'z' to Triple("ئ", "~", emptyList()), 'x' to Triple("ء", "ْ", emptyList()),
        'c' to Triple("ؤ", "}", emptyList()), 'v' to Triple("ر", "{", emptyList()), 'b' to Triple("لا", "لآ", emptyList()),
        'n' to Triple("ى", "آ", emptyList()), 'm' to Triple("ة", "’", emptyList()),
    ), punct = mapOf("BracketLeft" to ("ج" to "<"), "BracketRight" to ("د" to ">"), "Semicolon" to ("ك" to ":"),
        "Quote" to ("ط" to "\""), "Comma" to ("و" to ","), "Period" to ("ز" to "."), "Slash" to ("ظ" to "؟"), "Backquote" to ("ذ" to "ّ")),
        digits = listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"), rtl = true)

    /** Urdu phonetic (common Pakistani phonetic layout positions). */
    val urdu = Lang("ur", "اُ", "اردو (Urdu)", "اردو", mapOf(
        'q' to Triple("ق", "ْ", emptyList()), 'w' to Triple("و", "ّ", emptyList()), 'e' to Triple("ع", "ٰ", emptyList()),
        'r' to Triple("ر", "ڑ", emptyList()), 't' to Triple("ت", "ٹ", emptyList()), 'y' to Triple("ے", "َ", emptyList()),
        'u' to Triple("ء", "ئ", emptyList()), 'i' to Triple("ی", "ِ", emptyList()), 'o' to Triple("ہ", "ۃ", emptyList()),
        'p' to Triple("پ", "ُ", emptyList()), 'a' to Triple("ا", "آ", listOf("آ", "أ")), 's' to Triple("س", "ص", emptyList()),
        'd' to Triple("د", "ڈ", emptyList()), 'f' to Triple("ف", "ً", emptyList()), 'g' to Triple("گ", "غ", emptyList()),
        'h' to Triple("ح", "ھ", emptyList()), 'j' to Triple("ج", "ض", emptyList()), 'k' to Triple("ک", "خ", emptyList()),
        'l' to Triple("ل", "ٔ", emptyList()), 'z' to Triple("ز", "ذ", emptyList()), 'x' to Triple("ش", "ژ", emptyList()),
        'c' to Triple("چ", "ث", emptyList()), 'v' to Triple("ط", "ظ", emptyList()), 'b' to Triple("ب", "ؓ", emptyList()),
        'n' to Triple("ن", "ں", emptyList()), 'm' to Triple("م", "ٗ", emptyList()),
    ), punct = mapOf("Period" to ("۔" to "."), "Comma" to ("،" to ","), "Slash" to ("/" to "؟"), "Semicolon" to ("؛" to ":")),
        digits = listOf("۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹", "۰"), rtl = true)

    /** Russian ЙЦУКЕН. */
    val russian = Lang("ru", "RU", "Русский (Russian)", "Русский",
        cased("йцукенгшщзфывапролдячсмить", "ЙЦУКЕНГШЩЗФЫВАПРОЛДЯЧСМИТЬ"),
        punct = mapOf("BracketLeft" to ("х" to "Х"), "BracketRight" to ("ъ" to "Ъ"), "Semicolon" to ("ж" to "Ж"),
            "Quote" to ("э" to "Э"), "Comma" to ("б" to "Б"), "Period" to ("ю" to "Ю"), "Slash" to ("." to ","), "Backquote" to ("ё" to "Ё")))

    /** Greek. Long-press vowels for tonos. */
    val greek = Lang("el", "ΕΛ", "Ελληνικά (Greek)", "Ελληνικά", cased("ςερτυθιοπασδφγηξκλζχψωβνμ".let { ";$it" },
        "΅ΕΡΤΥΘΙΟΠΑΣΔΦΓΗΞΚΛΖΧΨΩΒΝΜ".let { ":$it" }).mapValues { (c, t) ->
        val extra = mapOf('a' to listOf("ά"), 'e' to listOf("έ"), 'h' to listOf("ή"), 'i' to listOf("ί", "ϊ", "ΐ"), 'o' to listOf("ό"),
            'y' to listOf("ύ", "ϋ", "ΰ"), 'v' to listOf("ώ"))[c] ?: emptyList()
        Triple(t.first, t.second, extra)
    })

    val all = listOf(english, bangla, hindi, urdu, arabic, russian, greek)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: english
}

/** Desktop layouts: full main block, F-row, navigation + numeric keypad panel. */
object KeyboardLayouts {
    private fun ch(code: String, out: String, shift: String, alts: List<String> = emptyList()) =
        Key(code, out, out = out, shiftOut = shift, shiftLabel = shift, alts = alts.ifEmpty { listOf(shift) })

    private fun act(code: String, label: String, w: Float = 1f, repeat: Boolean = false) =
        Key(code, label, w, KeyKind.ACTION, repeatable = repeat)

    private fun mod(code: String, label: String, w: Float) = Key(code, label, w, KeyKind.MODIFIER)
    private fun app(code: String, label: String, w: Float = 1f) = Key(code, label, w, KeyKind.APP)

    val functionRow = Row(listOf(act("Escape", "Esc", 1.5f)) + (1..12).map { act("F$it", "F$it") } +
        listOf(act("Delete", "Del", 1.5f, true)), 0.78f)

    private val shiftDigits = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val latinDigits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    private fun letterFor(c: Char, lang: Lang): Key {
        val (o, s, extra) = lang.letters.getValue(c)
        val isCased = o != s && o.length == 1 && o[0].isLetter() && s == o.uppercase()
        // long-press: language extras, then the shifted character
        val alts = (extra + (if (isCased) emptyList() else listOf(s))).distinct().filter { it != o }
        return Key("Key" + c.uppercaseChar(), o, out = o, shiftOut = s, shiftLabel = if (isCased) null else s,
            alts = if (alts.isEmpty()) listOf(s) else alts, cased = isCased)
    }

    private fun p(code: String, defOut: String, defShift: String, lang: Lang): Key {
        val o = lang.punct[code]
        return if (o == null) ch(code, defOut, defShift) else ch(code, o.first, o.second, listOf(o.second, defOut).distinct())
    }

    fun main(lang: Lang): List<Row> {
        val digits = lang.digits
        val r1 = listOf(p("Backquote", "`", "~", lang)) + latinDigits.indices.map { i ->
            val local = digits?.get(i)
            // native digits with the Latin digit on long-press, or Latin digits when the language has none
            if (local != null) Key("Digit" + latinDigits[i], local, out = local, shiftOut = shiftDigits[i], shiftLabel = shiftDigits[i],
                alts = listOf(latinDigits[i], shiftDigits[i]))
            else ch("Digit" + latinDigits[i], latinDigits[i], shiftDigits[i])
        } + listOf(p("Minus", "-", "_", lang), p("Equal", "=", "+", lang), act("Backspace", "⌫", 2f, true))
        val r2 = listOf(act("Tab", "Tab", 1.5f)) + "qwertyuiop".map { letterFor(it, lang) } +
            listOf(p("BracketLeft", "[", "{", lang), p("BracketRight", "]", "}", lang), p("Backslash", "\\", "|", lang).withWidth(1.5f))
        val r3 = listOf(mod("CapsLock", "Caps", 1.75f)) + "asdfghjkl".map { letterFor(it, lang) } +
            listOf(p("Semicolon", ";", ":", lang), p("Quote", "'", "\"", lang), act("Enter", "Enter ⏎", 2.25f))
        val r4 = listOf(mod("ShiftLeft", "⇧ Shift", 2.25f)) + "zxcvbnm".map { letterFor(it, lang) } +
            listOf(p("Comma", ",", "<", lang), p("Period", ".", ">", lang), p("Slash", "/", "?", lang), mod("ShiftRight", "Shift ⇧", 2.75f))
        val r5 = listOf(mod("ControlLeft", "Ctrl", 1.25f), mod("MetaLeft", "⊞", 1.25f), mod("AltLeft", "Alt", 1.25f),
            app("LANG", "🌐 " + lang.label, 1.5f),
            Key("Space", lang.spaceLabel, 4.25f, KeyKind.CHAR, out = " ", shiftOut = " ", repeatable = true),
            mod("AltRight", "Alt", 1.25f), act("ArrowLeft", "←", 1f, true), act("ArrowUp", "↑", 1f, true),
            act("ArrowDown", "↓", 1f, true), act("ArrowRight", "→", 1f, true), app("PANEL", "Nav", 1.25f))
        return listOf(functionRow, Row(r1), Row(r2), Row(r3), Row(r4), Row(r5))
    }

    private fun num(d: Int) = Key("Numpad$d", d.toString(), out = d.toString(), shiftOut = d.toString())
    private fun numOp(code: String, s: String) = Key(code, s, out = s, shiftOut = s)
    private fun spacer(w: Float = 1f) = Key("", "", w, KeyKind.APP)

    /** Navigation cluster + numeric keypad (shown instead of the letters when "Nav" is pressed). */
    fun navPanel(): List<Row> = listOf(
        functionRow,
        Row(listOf(act("Insert", "Ins"), act("Home", "Home"), act("PageUp", "PgUp"), spacer(0.4f),
            act("NumLock", "Num"), numOp("NumpadDivide", "/"), numOp("NumpadMultiply", "*"), numOp("NumpadSubtract", "-"))),
        Row(listOf(act("Delete", "Del", 1f, true), act("End", "End"), act("PageDown", "PgDn"), spacer(0.4f),
            num(7), num(8), num(9), numOp("NumpadAdd", "+"))),
        Row(listOf(act("PrintScreen", "PrtSc"), act("ScrollLock", "ScrLk"), act("Pause", "Pause"), spacer(0.4f),
            num(4), num(5), num(6), act("Backspace", "⌫", 1f, true))),
        Row(listOf(spacer(), act("ArrowUp", "↑", 1f, true), spacer(), spacer(0.4f),
            num(1), num(2), num(3), act("NumpadEnter", "⏎"))),
        Row(listOf(act("ArrowLeft", "←", 1f, true), act("ArrowDown", "↓", 1f, true), act("ArrowRight", "→", 1f, true), spacer(0.4f),
            num(0).withWidth(2f), numOp("NumpadDecimal", "."), act("Tab", "Tab"))),
        Row(listOf(mod("ControlLeft", "Ctrl", 1.25f), mod("ShiftLeft", "Shift", 1.5f), mod("AltLeft", "Alt", 1.25f),
            mod("MetaLeft", "⊞", 1.25f), Key("Space", "Space", 2.5f, KeyKind.CHAR, out = " ", shiftOut = " ", repeatable = true),
            act("ContextMenu", "☰"), app("PANEL", "ABC", 1.5f))),
    )

    private fun Key.withWidth(w: Float) = Key(code, label, w, kind, out, shiftOut, shiftLabel, alts, repeatable, cased)
}
