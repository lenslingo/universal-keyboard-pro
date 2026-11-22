using System.Globalization;
using System.Text;

namespace UKPro.Core;

/// <summary>
/// Unicode helpers shared by keyboard capture and injection so every script works:
/// Latin with accents, Bangla, Devanagari, Arabic/Urdu (RTL), Cyrillic, Greek, CJK, emoji.
/// </summary>
public static class TextUtil
{
    /// <summary>Spacing accent produced by a dead key → combining mark (U+03xx).</summary>
    private static readonly Dictionary<char, char> DeadToCombining = new()
    {
        ['´'] = '́', ['\''] = '́', ['`'] = '̀', ['^'] = '̂', ['ˆ'] = '̂',
        ['¨'] = '̈', ['"'] = '̈', ['~'] = '̃', ['˜'] = '̃', ['¸'] = '̧',
        ['ˇ'] = '̌', ['°'] = '̊', ['˚'] = '̊', ['¯'] = '̄', ['˘'] = '̆',
        ['˙'] = '̇', ['˝'] = '̋', ['˛'] = '̨',
    };

    /// <summary>
    /// Combines a dead key with the next typed text the way Windows does: "´" + "e" → "é".
    /// Space after a dead key yields the accent itself; a base that cannot combine yields both characters.
    /// </summary>
    public static string ComposeDeadKey(char dead, string next)
    {
        if (next.Length == 0) return dead.ToString();
        if (next == " ") return dead.ToString();
        if (!DeadToCombining.TryGetValue(dead, out var mark)) return dead + next;
        var composed = (next + mark).Normalize(NormalizationForm.FormC);
        // composed into a single text element (é, ñ, ü, ç, š…) → done; otherwise keep the characters apart
        return new StringInfo(composed).LengthInTextElements == new StringInfo(next).LengthInTextElements
               && composed.Length <= next.Length ? composed : dead + next;
    }

    /// <summary>Number of user-perceived characters (grapheme clusters), e.g. for Backspace counting.</summary>
    public static int Graphemes(string s) => new StringInfo(s).LengthInTextElements;

    /// <summary>True for text that is safe to type: no control characters except \n and \t.</summary>
    public static bool IsTypeable(string s)
    {
        foreach (var r in s.EnumerateRunes())
        {
            if (r.Value is '\n' or '\t') continue;
            var cat = Rune.GetUnicodeCategory(r);
            if (cat == UnicodeCategory.Control) return false;
        }
        return true;
    }
}
