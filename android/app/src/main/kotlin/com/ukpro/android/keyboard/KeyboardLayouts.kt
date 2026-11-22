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
 * A language layout on the physical QWERTY positions. Shortcuts (Ctrl+C …) always use a physical key
 * code, so they work in every language. Text is committed as Unicode, so any script reaches Windows.
 */
class Lang(
    val id: String,
    val label: String,        // shown on the language key
    val name: String,         // shown in settings
    val spaceLabel: String,
    /** QWERTY position → (normal, shift, long-press extras) */
    val letters: Map<Char, Triple<String, String, List<String>>>,
    /** W3C code → (normal, shift) overrides for punctuation keys */
    val punct: Map<String, Pair<String, String>> = emptyMap(),
    val digits: List<String>? = null,
    val rtl: Boolean = false,
    /** Latin layouts: the key code follows the letter (AZERTY "a" sends KeyA) so Ctrl+A stays Ctrl+A. */
    val latin: Boolean = false,
    /** Scripts that need composition (Korean, Japanese, Vietnamese Telex). */
    val composer: (() -> com.ukpro.core.Composer?)? = null,
    val group: String = "",
)

object Languages {
    private const val QWERTY = "qwertyuiopasdfghjklzxcvbnm"

    private fun cased(lower: String, upper: String): Map<Char, Triple<String, String, List<String>>> =
        QWERTY.indices.associate { i -> QWERTY[i] to Triple(lower[i].toString(), upper[i].toString(), emptyList<String>()) }

    private fun plain(lower: String, shift: String): Map<Char, Triple<String, String, List<String>>> =
        QWERTY.indices.associate { i -> QWERTY[i] to Triple(lower[i].toString(), shift[i].toString(), emptyList<String>()) }

    private fun Map<Char, Triple<String, String, List<String>>>.extras(vararg e: Pair<Char, List<String>>) =
        mapValues { (k, t) -> Triple(t.first, t.second, (e.firstOrNull { it.first == k }?.second ?: emptyList()) + t.third) }

    private val latinAccents = mapOf(
        'a' to listOf("á", "à", "â", "ä", "ã", "å", "æ", "ā", "ą"), 'c' to listOf("ç", "ć", "č"), 'd' to listOf("đ", "ď"),
        'e' to listOf("é", "è", "ê", "ë", "ē", "ę", "ě"), 'g' to listOf("ğ"), 'i' to listOf("í", "ì", "î", "ï", "ı"),
        'l' to listOf("ł"), 'n' to listOf("ñ", "ń", "ň"), 'o' to listOf("ó", "ò", "ô", "ö", "õ", "ø", "œ", "ő"),
        'r' to listOf("ř"), 's' to listOf("ß", "ś", "š", "ş"), 't' to listOf("ť", "þ"), 'u' to listOf("ú", "ù", "û", "ü", "ū", "ů", "ű"),
        'y' to listOf("ý", "ÿ"), 'z' to listOf("ž", "ź", "ż"),
    )

    /** Latin-script language: QWERTY (or AZERTY / QWERTZ letter swaps) with its own accents first on long-press. */
    private fun latin(id: String, label: String, name: String, space: String, first: Map<Char, List<String>> = emptyMap(),
                      swaps: Map<Char, Char> = emptyMap(), punct: Map<String, Pair<String, String>> = emptyMap(),
                      composer: (() -> com.ukpro.core.Composer?)? = null) =
        Lang(id, label, name, space, QWERTY.associate { pos ->
            val c = swaps[pos] ?: pos
            pos to Triple(c.toString(), c.uppercaseChar().toString(), ((first[c] ?: emptyList()) + (latinAccents[c] ?: emptyList())).distinct())
        }, punct = punct, latin = true, composer = composer, group = "Latin")

    private val azerty = mapOf('q' to 'a', 'a' to 'q', 'w' to 'z', 'z' to 'w')
    private val qwertz = mapOf('y' to 'z', 'z' to 'y')

    private val sg = { com.ukpro.android.UkRuntime.suggester }
    private val ac = { com.ukpro.android.UkRuntime.settings.current.autoCorrect }

    val english = latin("en", "EN", "English (+ accents, suggestions, auto-correct)", "English",
        composer = { if (com.ukpro.android.UkRuntime.settings.current.suggestions) com.ukpro.core.EnglishWordComposer(sg, ac) else null })

    /** Avro-style phonetic: type Bangla with English letters (ami banglay gan gai → আমি বাংলায় গান গাই). */
    val banglaPhonetic = latin("bn-ph", "অA", "বাংলা ফোনেটিক — English অক্ষরে বাংলা (ami → আমি)", "বাংলা ফোনেটিক",
        composer = { com.ukpro.core.BanglaPhoneticComposer(sg, ac) }).let {
        Lang(it.id, it.label, it.name, it.spaceLabel, it.letters, it.punct, it.digits, it.rtl, it.latin, it.composer, "South Asia")
    }

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
    ), punct = mapOf("Period" to ("।" to ".")), digits = listOf("১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯", "০"), group = "South Asia")

    /** Phonetic-position Devanagari. Conjuncts: consonant + q (्) + consonant. Other Indic scripts are derived from it. */
    private val devanagari: Map<Char, Triple<String, String, List<String>>> = mapOf(
        'q' to Triple("्", "ँ", listOf("ँ", "़", "्")), 'w' to Triple("ौ", "औ", emptyList()), 'e' to Triple("े", "ए", listOf("ै", "ऐ", "ए")),
        'r' to Triple("र", "ृ", listOf("ऋ", "ृ", "ड़", "ढ़")), 't' to Triple("त", "थ", listOf("ट", "ठ", "थ")),
        'y' to Triple("य", "य़", emptyList()), 'u' to Triple("ु", "उ", listOf("ू", "ऊ", "उ")), 'i' to Triple("ि", "इ", listOf("ी", "ई", "इ")),
        'o' to Triple("ो", "ओ", listOf("ौ", "औ", "ओ", "ॉ")), 'p' to Triple("प", "फ", emptyList()),
        'a' to Triple("ा", "अ", listOf("आ", "अ")), 's' to Triple("स", "श", listOf("ष", "श")), 'd' to Triple("द", "ध", listOf("ड", "ढ", "ध")),
        'f' to Triple("फ", "ऽ", emptyList()), 'g' to Triple("ग", "घ", emptyList()), 'h' to Triple("ह", "ः", emptyList()),
        'j' to Triple("ज", "झ", emptyList()), 'k' to Triple("क", "ख", emptyList()), 'l' to Triple("ल", "ं", listOf("ळ", "ं")),
        'z' to Triple("ट", "ठ", emptyList()), 'x' to Triple("ष", "ढ", listOf("क्ष", "ज्ञ", "त्र", "श्र")), 'c' to Triple("च", "छ", emptyList()),
        'v' to Triple("व", "ॐ", emptyList()), 'b' to Triple("ब", "भ", emptyList()), 'n' to Triple("न", "ण", listOf("ञ", "ङ")),
        'm' to Triple("म", "ङ", emptyList()),
    )
    private val devanagariDigits = listOf("१", "२", "३", "४", "५", "६", "७", "८", "९", "०")

    private fun devanagariLang(id: String, label: String, name: String, space: String) =
        Lang(id, label, name, space, devanagari, punct = mapOf("Period" to ("।" to "॥")), digits = devanagariDigits, group = "South Asia")

    /** Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam: the Devanagari layout shifted to the script's block. */
    private fun indic(id: String, label: String, name: String, space: String, base: Int): Lang {
        fun conv(x: String) = com.ukpro.core.IndicScripts.fromDevanagari(x, base)
        val letters = devanagari.mapValues { (_, t) ->
            val o = conv(t.first); val sh = conv(t.second)
            val normal = o ?: sh ?: ""
            Triple(normal, sh ?: normal, t.third.mapNotNull { conv(it) })
        }
        return Lang(id, label, name, space, letters, punct = mapOf("Period" to ("।" to "॥")),
            digits = devanagariDigits.map { conv(it) ?: it }, group = "South Asia")
    }

    private val arabicLetters = mapOf(
        'q' to Triple("ض", "َ", emptyList<String>()), 'w' to Triple("ص", "ً", emptyList()), 'e' to Triple("ث", "ُ", emptyList()),
        'r' to Triple("ق", "ٌ", emptyList()), 't' to Triple("ف", "لإ", emptyList()), 'y' to Triple("غ", "إ", emptyList()),
        'u' to Triple("ع", "‘", emptyList()), 'i' to Triple("ه", "÷", emptyList()), 'o' to Triple("خ", "×", emptyList()),
        'p' to Triple("ح", "؛", emptyList()), 'a' to Triple("ش", "ِ", emptyList()), 's' to Triple("س", "ٍ", emptyList()),
        'd' to Triple("ي", "]", emptyList()), 'f' to Triple("ب", "[", emptyList()), 'g' to Triple("ل", "لأ", emptyList()),
        'h' to Triple("ا", "أ", listOf("أ", "إ", "آ", "ٱ")), 'j' to Triple("ت", "ـ", emptyList()), 'k' to Triple("ن", "،", emptyList()),
        'l' to Triple("م", "/", emptyList()), 'z' to Triple("ئ", "~", emptyList()), 'x' to Triple("ء", "ْ", emptyList()),
        'c' to Triple("ؤ", "}", emptyList()), 'v' to Triple("ر", "{", emptyList()), 'b' to Triple("لا", "لآ", emptyList()),
        'n' to Triple("ى", "آ", emptyList()), 'm' to Triple("ة", "’", emptyList()),
    )
    private val arabicDigits = listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠")
    private val persianDigits = listOf("۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹", "۰")

    private val russianLetters = cased("йцукенгшщзфывапролдячсмить", "ЙЦУКЕНГШЩЗФЫВАПРОЛДЯЧСМИТЬ")
    private val russianPunct = mapOf("BracketLeft" to ("х" to "Х"), "BracketRight" to ("ъ" to "Ъ"), "Semicolon" to ("ж" to "Ж"),
        "Quote" to ("э" to "Э"), "Comma" to ("б" to "Б"), "Period" to ("ю" to "Ю"), "Slash" to ("." to ","), "Backquote" to ("ё" to "Ё"))

    val all: List<Lang> = listOf(
        // ---- Latin script
        english,
        latin("es", "ES", "Español", "Español", first = mapOf('n' to listOf("ñ"), 'a' to listOf("á"), 'e' to listOf("é"), 'i' to listOf("í"),
            'o' to listOf("ó"), 'u' to listOf("ú", "ü")), punct = mapOf("Slash" to ("/" to "¿"), "Backquote" to ("`" to "¡"))),
        latin("pt", "PT", "Português", "Português", first = mapOf('a' to listOf("ã", "á", "â", "à"), 'c' to listOf("ç"), 'e' to listOf("é", "ê"),
            'o' to listOf("õ", "ó", "ô"))),
        latin("fr", "FR", "Français (AZERTY)", "Français", first = mapOf('e' to listOf("é", "è", "ê", "ë"), 'a' to listOf("à", "â"),
            'c' to listOf("ç"), 'u' to listOf("ù", "û"), 'o' to listOf("ô", "œ")), swaps = azerty),
        latin("de", "DE", "Deutsch (QWERTZ)", "Deutsch", first = mapOf('a' to listOf("ä"), 'o' to listOf("ö"), 'u' to listOf("ü"), 's' to listOf("ß")),
            swaps = qwertz, punct = mapOf("BracketLeft" to ("ü" to "Ü"), "Semicolon" to ("ö" to "Ö"), "Quote" to ("ä" to "Ä"), "Minus" to ("ß" to "?"))),
        latin("it", "IT", "Italiano", "Italiano", first = mapOf('a' to listOf("à"), 'e' to listOf("è", "é"), 'i' to listOf("ì"), 'o' to listOf("ò"), 'u' to listOf("ù"))),
        latin("nl", "NL", "Nederlands", "Nederlands", first = mapOf('e' to listOf("é", "ë"), 'i' to listOf("ï", "ĳ"))),
        latin("tr", "TR", "Türkçe", "Türkçe", first = mapOf('i' to listOf("ı", "İ"), 'g' to listOf("ğ"), 's' to listOf("ş"), 'c' to listOf("ç"),
            'o' to listOf("ö"), 'u' to listOf("ü"))),
        latin("pl", "PL", "Polski", "Polski", first = mapOf('a' to listOf("ą"), 'c' to listOf("ć"), 'e' to listOf("ę"), 'l' to listOf("ł"),
            'n' to listOf("ń"), 'o' to listOf("ó"), 's' to listOf("ś"), 'z' to listOf("ż", "ź"))),
        latin("ro", "RO", "Română", "Română", first = mapOf('a' to listOf("ă", "â"), 'i' to listOf("î"), 's' to listOf("ș"), 't' to listOf("ț"))),
        latin("cs", "CZ", "Čeština", "Čeština", first = mapOf('c' to listOf("č"), 'e' to listOf("ě", "é"), 'r' to listOf("ř"), 's' to listOf("š"),
            'z' to listOf("ž"), 'u' to listOf("ů", "ú"), 'y' to listOf("ý"))),
        latin("sv", "SV", "Svenska / Norsk / Dansk", "Svenska", first = mapOf('a' to listOf("å", "ä", "æ"), 'o' to listOf("ö", "ø"))),
        latin("id", "ID", "Bahasa Indonesia / Melayu", "Indonesia"),
        latin("sw", "SW", "Kiswahili", "Kiswahili"),
        latin("tl", "TL", "Filipino", "Filipino", first = mapOf('n' to listOf("ñ"))),
        latin("vi", "VI", "Tiếng Việt (Telex: aa=â, ow=ơ, dd=đ, s f r x j = dấu)", "Tiếng Việt", composer = { com.ukpro.core.TelexComposer() }),
        // ---- South Asia
        banglaPhonetic,
        bangla,
        devanagariLang("hi", "हि", "हिन्दी (Hindi)", "हिन्दी"),
        devanagariLang("mr", "मर", "मराठी (Marathi)", "मराठी"),
        devanagariLang("ne", "ने", "नेपाली (Nepali)", "नेपाली"),
        indic("pa", "ਪੰ", "ਪੰਜਾਬੀ (Punjabi, Gurmukhi)", "ਪੰਜਾਬੀ", com.ukpro.core.IndicScripts.GURMUKHI),
        indic("gu", "ગુ", "ગુજરાતી (Gujarati)", "ગુજરાતી", com.ukpro.core.IndicScripts.GUJARATI),
        indic("or", "ଓ", "ଓଡ଼ିଆ (Odia)", "ଓଡ଼ିଆ", com.ukpro.core.IndicScripts.ORIYA),
        indic("ta", "த", "தமிழ் (Tamil)", "தமிழ்", com.ukpro.core.IndicScripts.TAMIL),
        indic("te", "తె", "తెలుగు (Telugu)", "తెలుగు", com.ukpro.core.IndicScripts.TELUGU),
        indic("kn", "ಕ", "ಕನ್ನಡ (Kannada)", "ಕನ್ನಡ", com.ukpro.core.IndicScripts.KANNADA),
        indic("ml", "മ", "മലയാളം (Malayalam)", "മലയാളം", com.ukpro.core.IndicScripts.MALAYALAM),
        Lang("ur", "اُ", "اردو (Urdu)", "اردو", mapOf(
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
            digits = persianDigits, rtl = true, group = "South Asia"),
        // ---- Middle East
        Lang("ar", "ع", "العربية (Arabic)", "العربية", arabicLetters,
            punct = mapOf("BracketLeft" to ("ج" to "<"), "BracketRight" to ("د" to ">"), "Semicolon" to ("ك" to ":"),
                "Quote" to ("ط" to "\""), "Comma" to ("و" to ","), "Period" to ("ز" to "."), "Slash" to ("ظ" to "؟"), "Backquote" to ("ذ" to "ّ")),
            digits = arabicDigits, rtl = true, group = "Middle East"),
        Lang("fa", "فا", "فارسی (Persian)", "فارسی", arabicLetters + mapOf(
            'd' to Triple("ی", "ي", emptyList()), 'z' to Triple("ظ", "ة", emptyList()), 'x' to Triple("ط", "ي", emptyList()),
            'c' to Triple("ز", "ژ", emptyList()), 'b' to Triple("ذ", "إ", emptyList()), 'n' to Triple("د", "أ", emptyList()),
            'm' to Triple("پ", "ء", emptyList())),
            punct = mapOf("BracketLeft" to ("ج" to "}"), "BracketRight" to ("چ" to "{"), "Semicolon" to ("ک" to ":"),
                "Quote" to ("گ" to "\""), "Comma" to ("و" to "<"), "Period" to ("." to ">"), "Slash" to ("/" to "؟")),
            digits = persianDigits, rtl = true, group = "Middle East"),
        Lang("he", "עב", "עברית (Hebrew)", "עברית", plain("/'קראטוןםפשדגכעיחלךזסבהנמצ", "QWERTYUIOPASDFGHJKLZXCVBNM"),
            punct = mapOf("Semicolon" to ("ף" to ":"), "Quote" to ("," to "\""), "Comma" to ("ת" to ">"), "Period" to ("ץ" to "<"), "Slash" to ("." to "?")),
            rtl = true, group = "Middle East"),
        // ---- Europe & Central Asia (other scripts)
        Lang("ru", "RU", "Русский (Russian)", "Русский", russianLetters, punct = russianPunct, group = "Cyrillic"),
        Lang("uk", "УК", "Українська (Ukrainian)", "Українська",
            russianLetters + mapOf('s' to Triple("і", "І", emptyList()), 'u' to Triple("г", "Г", listOf("ґ", "Ґ"))),
            punct = russianPunct + mapOf("BracketRight" to ("ї" to "Ї"), "Quote" to ("є" to "Є"), "Backquote" to ("'" to "₴")), group = "Cyrillic"),
        Lang("kk", "ҚЗ", "Қазақша (Kazakh)", "Қазақша", russianLetters.extras('f' to listOf("ә"), 'j' to listOf("ө"), 'e' to listOf("ү", "ұ"),
            'r' to listOf("қ"), 'y' to listOf("ң"), 'u' to listOf("ғ"), 'b' to listOf("і"), '[' to emptyList()), punct = russianPunct, group = "Cyrillic"),
        Lang("sr", "СР", "Српски (Serbian Cyrillic)", "Српски", cased("љњертзуиопасдфгхјклѕџцвбнм", "ЉЊЕРТЗУИОПАСДФГХЈКЛЅЏЦВБНМ"),
            punct = mapOf("BracketLeft" to ("ш" to "Ш"), "BracketRight" to ("ђ" to "Ђ"), "Semicolon" to ("ч" to "Ч"), "Quote" to ("ћ" to "Ћ"),
                "Backslash" to ("ж" to "Ж")), group = "Cyrillic"),
        Lang("el", "ΕΛ", "Ελληνικά (Greek)", "Ελληνικά", cased(";ςερτυθιοπασδφγηξκλζχψωβνμ", ":΅ΕΡΤΥΘΙΟΠΑΣΔΦΓΗΞΚΛΖΧΨΩΒΝΜ")
            .extras('a' to listOf("ά"), 'e' to listOf("έ"), 'h' to listOf("ή"), 'i' to listOf("ί", "ϊ", "ΐ"), 'o' to listOf("ό"),
                'y' to listOf("ύ", "ϋ", "ΰ"), 'v' to listOf("ώ")), group = "Europe"),
        Lang("ka", "ქა", "ქართული (Georgian)", "ქართული", plain("ქწერტყუიოპასდფგჰჯკლზხცვბნმ", "ქჭეღთყუიოპაშდფგჰჟკლძხჩვბნმ"), group = "Europe"),
        Lang("hy", "Հայ", "Հայերեն (Armenian, phonetic)", "Հայերեն", cased("քոերտյւիօպասդֆգհջկլզխցվբնմ", "ՔՈԵՐՏՅՒԻՕՊԱՍԴՖԳՀՋԿԼԶԽՑՎԲՆՄ")
            .extras('u' to listOf("ու", "ը"), 'e' to listOf("է"), 'c' to listOf("չ", "ծ"), 'j' to listOf("ճ"), 'r' to listOf("ռ"),
                't' to listOf("թ"), 'p' to listOf("փ"), 'k' to listOf("ք"), 'x' to listOf("ղ"), 'z' to listOf("ժ"), 's' to listOf("շ")), group = "Europe"),
        // ---- East & Southeast Asia
        Lang("th", "ไท", "ไทย (Thai, Kedmanee)", "ไทย", plain("ๆไำพะัีรนยฟหกดเ้่าสผปแอิืท", "๐\"ฎฑธํ๊ณฯญฤฆฏโฌ็๋ษศ()ฉฮฺ์?"),
            punct = mapOf("BracketLeft" to ("บ" to "ฐ"), "BracketRight" to ("ล" to ","), "Semicolon" to ("ว" to "ซ"), "Quote" to ("ง" to "."),
                "Comma" to ("ม" to "ฒ"), "Period" to ("ใ" to "ฬ"), "Slash" to ("ฝ" to "ฦ"), "Minus" to ("ข" to "ฃ"), "Equal" to ("ช" to "ฅ")),
            digits = listOf("ๅ", "/", "-", "ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ"), group = "Asia"),
        Lang("ko", "한", "한국어 (Korean, 2-set)", "한국어", plain("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ", "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"),
            composer = { com.ukpro.core.HangulComposer() }, group = "Asia"),
        latin("ja", "あ", "日本語 ひらがな (Japanese, romaji)", "日本語 ローマ字", composer = { com.ukpro.core.KanaComposer() }).copyAs("Asia"),
        latin("ja-kana", "ア", "日本語 カタカナ (Japanese katakana)", "カタカナ", composer = { com.ukpro.core.KanaComposer(katakana = true) }).copyAs("Asia"),
    )

    private fun Lang.copyAs(g: String) = Lang(id, label, name, spaceLabel, letters, punct, digits, rtl, latin, composer, g)

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
        val alts = (extra + (if (isCased) emptyList() else listOf(s))).distinct().filter { it != o && it.isNotEmpty() }
        // Latin layouts (AZERTY/QWERTZ): Ctrl+A must be Ctrl+A wherever the "a" key is
        val code = if (lang.latin && o.length == 1 && o[0] in 'a'..'z') "Key" + o[0].uppercaseChar() else "Key" + c.uppercaseChar()
        return Key(code, o, out = o, shiftOut = s, shiftLabel = if (isCased || s == o) null else s,
            alts = if (alts.isEmpty() && s.isNotEmpty() && s != o) listOf(s) else alts, cased = isCased)
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
