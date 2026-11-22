using System;
using System.Runtime.InteropServices;

namespace UKPro.Desktop.Services;

/// <summary>
/// Advertises `_ukpro._tcp.local` with the native Windows DNS-SD API (dnsapi.dll, Windows 10 1809+),
/// so Android NSD finds the laptop without typing an IP. TXT: id, name, fp, v.
/// </summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private IntPtr _instance;
    private DNS_SERVICE_REGISTER_REQUEST _request;
    private readonly RegisterComplete _callback;
    private bool _registered;

    public MdnsAdvertiser() => _callback = (_, _, inst) => { if (inst != IntPtr.Zero) DnsServiceFreeInstance(inst); };

    public string? Error { get; private set; }

    public void Start(string deviceName, string deviceId, string fingerprint, int port)
    {
        try
        {
            var safe = new string(Array.FindAll(deviceName.ToCharArray(), c => char.IsLetterOrDigit(c) || c == '-'));
            string service = $"UKPro-{(safe.Length == 0 ? "PC" : safe)}._ukpro._tcp.local";
            string host = Environment.MachineName + ".local";
            string[] keys = ["id", "name", "fp", "v"];
            string[] values = [deviceId, deviceName, fingerprint, "1"];
            _instance = DnsServiceConstructInstance(service, host, IntPtr.Zero, IntPtr.Zero, (ushort)port, 0, 0,
                (uint)keys.Length, keys, values);
            if (_instance == IntPtr.Zero) { Error = "DnsServiceConstructInstance failed"; return; }
            _request = new DNS_SERVICE_REGISTER_REQUEST
            {
                Version = 1, InterfaceIndex = 0, pServiceInstance = _instance,
                pRegisterCompletionCallback = Marshal.GetFunctionPointerForDelegate(_callback),
            };
            var r = DnsServiceRegister(ref _request, IntPtr.Zero);
            _registered = r == 9506 /* DNS_REQUEST_PENDING */;
            if (!_registered) Error = $"DnsServiceRegister: {r}";
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            Error = "mDNS needs Windows 10 1809 or newer — use QR or manual IP.";
        }
    }

    public void Dispose()
    {
        if (_registered) DnsServiceDeRegister(ref _request, IntPtr.Zero);
        if (_instance != IntPtr.Zero && !_registered) DnsServiceFreeInstance(_instance);
        _registered = false; _instance = IntPtr.Zero;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void RegisterComplete(uint status, IntPtr context, IntPtr instance);

    [StructLayout(LayoutKind.Sequential)]
    private struct DNS_SERVICE_REGISTER_REQUEST
    {
        public uint Version;
        public uint InterfaceIndex;
        public IntPtr pServiceInstance;
        public IntPtr pRegisterCompletionCallback;
        public IntPtr pQueryContext;
        public IntPtr hCredentials;
        [MarshalAs(UnmanagedType.Bool)] public bool unicastEnabled;
    }

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DnsServiceConstructInstance(string serviceName, string hostName, IntPtr ip4, IntPtr ip6,
        ushort port, ushort priority, ushort weight, uint propertiesCount,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] keys,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] values);

    [DllImport("dnsapi.dll")] private static extern uint DnsServiceRegister(ref DNS_SERVICE_REGISTER_REQUEST request, IntPtr cancel);
    [DllImport("dnsapi.dll")] private static extern uint DnsServiceDeRegister(ref DNS_SERVICE_REGISTER_REQUEST request, IntPtr cancel);
    [DllImport("dnsapi.dll")] private static extern void DnsServiceFreeInstance(IntPtr instance);
}
