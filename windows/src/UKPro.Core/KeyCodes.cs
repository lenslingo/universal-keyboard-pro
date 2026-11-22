namespace UKPro.Core;

/// <summary>W3C KeyboardEvent.code ⇄ Windows virtual-key codes.</summary>
public static class KeyCodes
{
    public static readonly IReadOnlyDictionary<string, ushort> CodeToVk;
    public static readonly IReadOnlyDictionary<ushort, string> VkToCode;
    /// <summary>Keys whose VK requires KEYEVENTF_EXTENDEDKEY when injected.</summary>
    public static readonly IReadOnlySet<ushort> ExtendedVk = new HashSet<ushort>
    {
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, // PgUp..Down, Ins, Del
        0x5B, 0x5C, 0x5D, 0xA3, 0xA5, 0x6F, 0x90, 0x2C,             // Win, Apps, RCtrl, RAlt, Num/, NumLock, PrtSc
        0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB2, 0xB3,                   // volume & media
    };

    static KeyCodes()
    {
        var m = new Dictionary<string, ushort>();
        for (char c = 'A'; c <= 'Z'; c++) m[$"Key{c}"] = c;
        for (char c = '0'; c <= '9'; c++) { m[$"Digit{c}"] = c; m[$"Numpad{c}"] = (ushort)(0x60 + (c - '0')); }
        for (int i = 1; i <= 24; i++) m[$"F{i}"] = (ushort)(0x70 + i - 1);
        void A(string code, ushort vk) => m[code] = vk;
        A("Backspace", 0x08); A("Tab", 0x09); A("Enter", 0x0D); A("NumpadEnter", 0x0D);
        A("ShiftLeft", 0xA0); A("ShiftRight", 0xA1); A("ControlLeft", 0xA2); A("ControlRight", 0xA3);
        A("AltLeft", 0xA4); A("AltRight", 0xA5); A("MetaLeft", 0x5B); A("MetaRight", 0x5C); A("ContextMenu", 0x5D);
        A("Pause", 0x13); A("CapsLock", 0x14); A("Escape", 0x1B); A("Space", 0x20);
        A("PageUp", 0x21); A("PageDown", 0x22); A("End", 0x23); A("Home", 0x24);
        A("ArrowLeft", 0x25); A("ArrowUp", 0x26); A("ArrowRight", 0x27); A("ArrowDown", 0x28);
        A("PrintScreen", 0x2C); A("Insert", 0x2D); A("Delete", 0x2E);
        A("NumpadMultiply", 0x6A); A("NumpadAdd", 0x6B); A("NumpadSubtract", 0x6D); A("NumpadDecimal", 0x6E);
        A("NumpadDivide", 0x6F); A("NumLock", 0x90); A("ScrollLock", 0x91);
        A("Semicolon", 0xBA); A("Equal", 0xBB); A("Comma", 0xBC); A("Minus", 0xBD); A("Period", 0xBE);
        A("Slash", 0xBF); A("Backquote", 0xC0); A("BracketLeft", 0xDB); A("Backslash", 0xDC);
        A("BracketRight", 0xDD); A("Quote", 0xDE); A("IntlBackslash", 0xE2);
        A("AudioVolumeMute", 0xAD); A("AudioVolumeDown", 0xAE); A("AudioVolumeUp", 0xAF);
        A("MediaTrackNext", 0xB0); A("MediaTrackPrevious", 0xB1); A("MediaStop", 0xB2); A("MediaPlayPause", 0xB3);
        CodeToVk = m;
        var r = new Dictionary<ushort, string>();
        foreach (var (k, v) in m) if (k != "NumpadEnter") r.TryAdd(v, k);
        VkToCode = r;
    }

    public static bool IsModifierCode(string code) => code is "ShiftLeft" or "ShiftRight" or "ControlLeft"
        or "ControlRight" or "AltLeft" or "AltRight" or "MetaLeft" or "MetaRight";
}
