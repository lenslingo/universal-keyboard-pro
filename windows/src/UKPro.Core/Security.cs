using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace UKPro.Core;

/// <summary>Pairing cryptography: ephemeral ECDH P-256 + HKDF-SHA256 + HMAC key confirmation (PROTOCOL §4.1).</summary>
public static class PairingCrypto
{
    private const string Crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static ECDiffieHellman NewKey() => ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

    /// <summary>Uncompressed point 0x04‖X‖Y, base64url.</summary>
    public static string ExportPublic(ECDiffieHellman key)
    {
        var p = key.ExportParameters(false);
        var raw = new byte[65];
        raw[0] = 4;
        p.Q.X!.CopyTo(raw, 1);
        p.Q.Y!.CopyTo(raw, 33);
        return Base64Url.Encode(raw);
    }

    public static ECDiffieHellman ImportPublic(string b64)
    {
        var raw = Base64Url.Decode(b64);
        if (raw.Length != 65 || raw[0] != 4) throw new CryptographicException("bad public key");
        return ECDiffieHellman.Create(new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = raw[1..33], Y = raw[33..65] },
        });
    }

    /// <summary>Imports a fixed key pair (test vectors only).</summary>
    public static ECDiffieHellman ImportPrivate(byte[] d, string publicB64)
    {
        var raw = Base64Url.Decode(publicB64);
        return ECDiffieHellman.Create(new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256, D = d,
            Q = new ECPoint { X = raw[1..33], Y = raw[33..65] },
        });
    }

    public static byte[] ExportPrivateD(ECDiffieHellman key) => key.ExportParameters(true).D!;

    public static byte[] SharedSecret(ECDiffieHellman priv, string peerPublicB64)
    {
        using var peer = ImportPublic(peerPublicB64);
        return priv.DeriveRawSecretAgreement(peer.PublicKey);
    }

    public static byte[] DeriveSecret(byte[] z, string code, string hubId, string clientId) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, z, 32,
            salt: Encoding.UTF8.GetBytes(NormalizeCode(code)),
            info: Encoding.UTF8.GetBytes($"UKPro pair v1|{hubId}|{clientId}"));

    public static string Transcript(string hubId, string clientId, string serverPk, string clientPk, string fp) =>
        $"{hubId}|{clientId}|{serverPk}|{clientPk}|{fp}";

    public static string Confirm(byte[] secret, string label, string transcript) =>
        Base64Url.Encode(HMACSHA256.HashData(secret, Encoding.UTF8.GetBytes(label + "|" + transcript)));

    /// <summary>6-digit number both screens show in manual pairing mode.</summary>
    public static string VerificationNumber(string transcript)
    {
        var h = SHA256.HashData(Encoding.UTF8.GetBytes(transcript));
        int v = (h[0] << 12) | (h[1] << 4) | (h[2] >> 4); // first 20 bits
        return (v % 1_000_000).ToString("D6");
    }

    public static string NewCode()
    {
        Span<char> c = stackalloc char[8];
        for (int i = 0; i < 8; i++) c[i] = Crockford[RandomNumberGenerator.GetInt32(32)];
        return new string(c);
    }

    public static string NormalizeCode(string code)
    {
        var sb = new StringBuilder(code.Length);
        foreach (var ch in code.ToUpperInvariant())
        {
            char x = ch switch { 'I' or 'L' => '1', 'O' => '0', _ => ch };
            if (Crockford.Contains(x)) sb.Append(x);
        }
        return sb.ToString();
    }

    public static bool FixedEquals(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
}

/// <summary>Per-connection mutual authentication (PROTOCOL §4.2).</summary>
public static class SessionAuth
{
    public static string NewNonce() => Base64Url.Encode(RandomNumberGenerator.GetBytes(32));

    public static string ServerMac(byte[] secret, string nC, string nS, string fp) =>
        Base64Url.Encode(HMACSHA256.HashData(secret, Encoding.UTF8.GetBytes($"S|{nC}|{nS}|{fp}")));

    public static string ClientMac(byte[] secret, string nC, string nS, string fp) =>
        Base64Url.Encode(HMACSHA256.HashData(secret, Encoding.UTF8.GetBytes($"C|{nC}|{nS}|{fp}")));

    public static byte[] SessionKey(byte[] secret, string nC, string nS) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, secret, 32,
            salt: [.. Base64Url.Decode(nC), .. Base64Url.Decode(nS)],
            info: Encoding.UTF8.GetBytes("UKPro session v1"));
}

public static class Certificates
{
    /// <summary>Self-signed ECDSA P-256 server certificate (10 years). Identity is pinned by fingerprint, not by name.</summary>
    public static X509Certificate2 CreateSelfSigned(string deviceName)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var req = new CertificateRequest($"CN=UK Pro {Sanitize(deviceName)}", key, HashAlgorithmName.SHA256);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension([new Oid("1.3.6.1.5.5.7.3.1")], false));
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("ukpro.local");
        req.CertificateExtensions.Add(san.Build());
        using var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        // round-trip through PKCS#12 so SslStream on Windows gets a persisted private key
        return X509CertificateLoader.LoadPkcs12(cert.Export(X509ContentType.Pkcs12), null,
            X509KeyStorageFlags.Exportable);
    }

    public static string Fingerprint(X509Certificate2 cert) => Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant();

    private static string Sanitize(string s) => new(s.Where(c => char.IsLetterOrDigit(c) || c is ' ' or '-' or '_').Take(40).ToArray());
}
