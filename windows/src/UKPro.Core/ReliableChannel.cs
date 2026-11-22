namespace UKPro.Core;

/// <summary>
/// Exactly-once, in-order delivery for one peer link (docs/PROTOCOL.md §2.1).
/// Outgoing: stamps seq, keeps an outbox until ACKed, replays it after reconnect / transport switch.
/// Incoming: drops duplicates, buffers gaps, delivers strictly in order.
/// Thread-safe.
/// </summary>
public sealed class ReliableChannel
{
    public const int MaxPending = 1024;
    public const int MaxOutbox = 20_000;
    public static readonly TimeSpan InputTtl = TimeSpan.FromSeconds(10);

    private readonly object _lock = new();
    private readonly string _localDeviceId;
    private readonly Func<long> _clock;
    private long _nextSeq = 1;
    private readonly LinkedList<(Envelope Env, long QueuedAt)> _outbox = new();

    private string? _peerSessionId;
    private long _lastDelivered;
    private readonly SortedDictionary<long, Envelope> _pending = new();
    private readonly LruSet _seenEvents = new(4096);
    private long _lastAckSent;
    private long _lastAckTime;

    public ReliableChannel(string localDeviceId, string? sessionId = null, Func<long>? clockMs = null)
    {
        _localDeviceId = localDeviceId;
        LocalSessionId = sessionId ?? Guid.NewGuid().ToString("N");
        _clock = clockMs ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public string LocalSessionId { get; }
    public string? PeerSessionId { get { lock (_lock) return _peerSessionId; } }
    public long LastDelivered { get { lock (_lock) return _lastDelivered; } }
    public int OutboxCount { get { lock (_lock) return _outbox.Count; } }

    /// <summary>Fills session/source/seq. Reliable messages are retained until acknowledged.</summary>
    public Envelope Stamp(Envelope e)
    {
        lock (_lock)
        {
            e.SessionId = LocalSessionId;
            e.SourceDeviceId = _localDeviceId;
            e.Ts = _clock();
            if (MessageTypes.IsReliable(e.Type))
            {
                e.Seq = _nextSeq++;
                _outbox.AddLast((e, e.Ts));
                while (_outbox.Count > MaxOutbox) _outbox.RemoveFirst(); // peer will RESYNC
            }
            else e.Seq = 0;
            return e;
        }
    }

    public void OnAck(long upTo)
    {
        lock (_lock)
            while (_outbox.First is { } n && n.Value.Env.Seq <= upTo) _outbox.RemoveFirst();
    }

    /// <summary>
    /// Messages to resend after the peer reported <paramref name="peerLastRecv"/> for our session.
    /// Stale input events are replaced by NOOP placeholders so the sequence stays contiguous but
    /// old keystrokes are never typed into whatever window happens to be focused later.
    /// </summary>
    public IReadOnlyList<Envelope> ResendAfterReconnect(string? peerKnowsSessionId, long peerLastRecv)
    {
        lock (_lock)
        {
            if (peerKnowsSessionId != LocalSessionId) peerLastRecv = 0;
            OnAckUnlocked(peerLastRecv);
            var now = _clock();
            var list = new List<Envelope>(_outbox.Count);
            foreach (var (env, at) in _outbox)
            {
                if (MessageTypes.IsTimeSensitive(env.Type) && now - at > InputTtl.TotalMilliseconds)
                    list.Add(new Envelope
                    {
                        Type = MessageTypes.Noop, EventId = env.EventId, SessionId = env.SessionId,
                        SourceDeviceId = env.SourceDeviceId, TargetDeviceId = env.TargetDeviceId,
                        Seq = env.Seq, Ts = env.Ts,
                    });
                else list.Add(env);
            }
            return list;
        }
    }

    private void OnAckUnlocked(long upTo)
    {
        while (_outbox.First is { } n && n.Value.Env.Seq <= upTo) _outbox.RemoveFirst();
    }

    /// <summary>Called when the peer announces its outgoing session id (AUTH handshake).</summary>
    public void BeginPeerSession(string peerSessionId)
    {
        lock (_lock)
        {
            if (_peerSessionId == peerSessionId) return;
            _peerSessionId = peerSessionId;
            _lastDelivered = 0;
            _lastAckSent = 0;
            _pending.Clear();
        }
    }

    /// <summary>Returns the messages that become deliverable (in order). Empty for duplicates/gaps.</summary>
    public IReadOnlyList<Envelope> Receive(Envelope e)
    {
        lock (_lock)
        {
            if (e.Seq == 0)
            {
                if (e.Type is MessageTypes.Ping or MessageTypes.Pong or MessageTypes.Ack) return [e];
                return _seenEvents.Add(e.EventId) ? [e] : [];
            }
            if (_peerSessionId is null || e.SessionId != _peerSessionId) BeginPeerSessionUnlocked(e.SessionId);
            if (e.Seq <= _lastDelivered || _pending.ContainsKey(e.Seq)) return [];
            if (e.Seq != _lastDelivered + 1)
            {
                if (_pending.Count >= MaxPending) throw new ProtocolException("RESYNC", "too many out-of-order messages");
                _pending[e.Seq] = e;
                return [];
            }
            var result = new List<Envelope>();
            Deliver(e, result);
            while (_pending.Remove(_lastDelivered + 1, out var next)) Deliver(next, result);
            return result;
        }
    }

    private void BeginPeerSessionUnlocked(string sid)
    {
        _peerSessionId = sid; _lastDelivered = 0; _lastAckSent = 0; _pending.Clear();
    }

    private void Deliver(Envelope e, List<Envelope> into)
    {
        _lastDelivered = e.Seq;
        if (e.Type == MessageTypes.Noop) return;
        if (_seenEvents.Add(e.EventId)) into.Add(e);
    }

    /// <summary>True if a cumulative ACK should be sent now (every 32 messages or 50 ms).</summary>
    public bool TryTakeAck(out long upTo, bool force = false)
    {
        lock (_lock)
        {
            upTo = _lastDelivered;
            if (upTo == _lastAckSent) return false;
            var now = _clock();
            if (!force && upTo - _lastAckSent < 32 && now - _lastAckTime < 50) return false;
            _lastAckSent = upTo; _lastAckTime = now;
            return true;
        }
    }
}

/// <summary>Bounded insertion-ordered set used for event-id deduplication.</summary>
public sealed class LruSet(int capacity)
{
    private readonly HashSet<string> _set = new();
    private readonly Queue<string> _order = new();

    public bool Add(string id)
    {
        if (!_set.Add(id)) return false;
        _order.Enqueue(id);
        if (_order.Count > capacity) _set.Remove(_order.Dequeue());
        return true;
    }

    public bool Contains(string id) => _set.Contains(id);
}
