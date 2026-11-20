import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { apiFetch, apiUrl, wsUrl } from './api.js';

const PUBLIC = 'public';
const MAX_FILE_BYTES = 10 * 1024 * 1024;
const TYPING_DEBOUNCE_MS = 500; // wait 500ms of typing before announcing
const TYPING_STOP_MS = 2000; // stop announcement after 2s of silence
const TYPING_REMOTE_TTL_MS = 3000; // safety expiry for remote indicators
const SEARCH_DEBOUNCE_MS = 300;

function genClientId() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  return `c-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * The backend HTML-escapes stored content (XSS defense in depth). Decode the
 * entities for DISPLAY only — React then re-escapes on render, so this is safe.
 * <textarea> parses RCDATA: entities decode, tags/scripts never execute.
 */
function decodeEntities(text) {
  if (!text) return '';
  const el = document.createElement('textarea');
  el.innerHTML = text;
  return el.value;
}

function formatTime(iso) {
  try {
    return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
}

export default function Chat({ auth, authRef, onLogout, refreshTokens, onAuthUpdate }) {
  const me = auth.username;

  const [connected, setConnected] = useState(false);
  const [onlineUsers, setOnlineUsers] = useState([]);
  const [activeChat, setActiveChat] = useState(PUBLIC);
  const [conversations, setConversations] = useState({ [PUBLIC]: [] });
  const [unread, setUnread] = useState({});
  const [typing, setTyping] = useState({}); // chatKey -> { username: true }
  const [input, setInput] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [toast, setToast] = useState(null);
  const [twoFaSetup, setTwoFaSetup] = useState(null); // { secret, qrCodeDataUri }

  const clientRef = useRef(null);
  const activeChatRef = useRef(activeChat);
  activeChatRef.current = activeChat;
  const meRef = useRef(me);
  meRef.current = me;
  const typingTimersRef = useRef({}); // remote indicator expiry timers
  const typingActiveRef = useRef(false);
  const typingStartTimerRef = useRef(null);
  const typingStopTimerRef = useRef(null);
  const messagesContainerRef = useRef(null);
  const bottomRef = useRef(null);
  const fileInputRef = useRef(null);
  const toastTimerRef = useRef(null);

  const showToast = useCallback((message, type = 'error') => {
    clearTimeout(toastTimerRef.current);
    setToast({ message, type });
    toastTimerRef.current = setTimeout(() => setToast(null), 3500);
  }, []);

  const publish = useCallback((destination, body) => {
    const client = clientRef.current;
    if (client?.connected) {
      client.publish({ destination, body: JSON.stringify(body) });
      return true;
    }
    return false;
  }, []);

  const sendReadReceipt = useCallback(
    (messageId) => publish('/app/chat.read', { messageId }),
    [publish]
  );

  /* ---------------- incoming frame handlers ---------------- */

  const handleIncoming = useCallback(
    (dto) => {
      const self = meRef.current;
      const key = dto.isPrivate ? (dto.sender === self ? dto.receiver : dto.sender) : PUBLIC;

      setConversations((prev) => {
        const list = prev[key] || [];
        // Reconcile optimistic message by clientId, else dedupe by server id.
        const optimisticIdx = dto.clientId
          ? list.findIndex((m) => m.clientId === dto.clientId && m.id == null)
          : -1;
        let next;
        if (optimisticIdx >= 0) {
          next = [...list];
          next[optimisticIdx] = { ...dto, status: 'sent' };
        } else if (dto.id != null && list.some((m) => m.id === dto.id)) {
          next = list;
        } else {
          next = [...list, { ...dto, status: 'sent' }];
        }
        return { ...prev, [key]: next };
      });

      if (dto.isPrivate && dto.sender !== self) {
        if (activeChatRef.current === key) {
          if (!dto.isRead && dto.id != null) sendReadReceipt(dto.id);
        } else {
          setUnread((prev) => ({ ...prev, [key]: (prev[key] || 0) + 1 }));
        }
      }
    },
    [sendReadReceipt]
  );

  const handleReceipt = useCallback((receipt) => {
    setConversations((prev) => {
      const key = receipt.reader;
      const list = prev[key];
      if (!list) return prev;
      return {
        ...prev,
        [key]: list.map((m) =>
          m.id === receipt.messageId ? { ...m, isRead: true, readAt: receipt.readAt } : m
        ),
      };
    });
  }, []);

  const handleTyping = useCallback((event) => {
    const self = meRef.current;
    if (event.sender === self) return;
    const key = event.receiver ? event.sender : PUBLIC;
    const timerKey = `${key}:${event.sender}`;
    clearTimeout(typingTimersRef.current[timerKey]);

    if (event.typing) {
      setTyping((prev) => ({ ...prev, [key]: { ...(prev[key] || {}), [event.sender]: true } }));
      typingTimersRef.current[timerKey] = setTimeout(() => {
        setTyping((prev) => {
          const users = { ...(prev[key] || {}) };
          delete users[event.sender];
          return { ...prev, [key]: users };
        });
      }, TYPING_REMOTE_TTL_MS);
    } else {
      setTyping((prev) => {
        const users = { ...(prev[key] || {}) };
        delete users[event.sender];
        return { ...prev, [key]: users };
      });
    }
  }, []);

  /* ---------------- STOMP lifecycle ---------------- */

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl()),
      reconnectDelay: 5000,
      // Always CONNECT with the freshest access token (survives 14-min refreshes).
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${authRef.current?.accessToken || ''}` };
      },
      onConnect: () => {
        setConnected(true);
        client.subscribe('/topic/public', (frame) => handleIncoming(JSON.parse(frame.body)));
        client.subscribe('/topic/users', (frame) => setOnlineUsers(JSON.parse(frame.body)));
        client.subscribe('/topic/typing', (frame) => handleTyping(JSON.parse(frame.body)));
        client.subscribe('/user/queue/messages', (frame) => handleIncoming(JSON.parse(frame.body)));
        client.subscribe('/user/queue/typing', (frame) => handleTyping(JSON.parse(frame.body)));
        client.subscribe('/user/queue/read', (frame) => handleReceipt(JSON.parse(frame.body)));
        client.subscribe('/user/queue/errors', (frame) => {
          try {
            showToast(JSON.parse(frame.body).error || 'Message rejected');
          } catch {
            showToast('Message rejected');
          }
        });
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: (frame) => {
        setConnected(false);
        showToast(frame.headers?.message || 'Connection error');
      },
    });
    clientRef.current = client;
    client.activate();
    return () => {
      client.deactivate();
      clientRef.current = null;
      Object.values(typingTimersRef.current).forEach(clearTimeout);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [me]);

  /* ---------------- history loading ---------------- */

  useEffect(() => {
    let cancelled = false;
    const key = activeChat;
    const path =
      key === PUBLIC ? '/api/messages/public' : `/api/messages/private/${encodeURIComponent(key)}`;
    apiFetch(path, { token: authRef.current?.accessToken, onRefresh: refreshTokens })
      .then((data) => {
        if (cancelled) return;
        setConversations((prev) => ({ ...prev, [key]: data }));
        setUnread((prev) => ({ ...prev, [key]: 0 }));
        if (key !== PUBLIC) {
          data
            .filter((m) => m.sender !== meRef.current && !m.isRead && m.id != null)
            .forEach((m) => sendReadReceipt(m.id));
        }
      })
      .catch((err) => !cancelled && showToast(err.message));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeChat]);

  /* ---------------- debounced search ---------------- */

  useEffect(() => {
    const q = searchQuery.trim();
    if (q.length < 2) {
      setSearchResults(null);
      return undefined;
    }
    const handle = setTimeout(() => {
      const path =
        activeChat === PUBLIC
          ? `/api/messages/search?q=${encodeURIComponent(q)}`
          : `/api/messages/search/private/${encodeURIComponent(activeChat)}?q=${encodeURIComponent(q)}`;
      apiFetch(path, { token: authRef.current?.accessToken, onRefresh: refreshTokens })
        .then(setSearchResults)
        .catch((err) => showToast(err.message));
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery, activeChat]);

  /* ---------------- auto scroll ---------------- */

  const visibleMessages = conversations[activeChat] || [];
  useEffect(() => {
    const container = messagesContainerRef.current;
    if (!container) return;
    const nearBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight < 180;
    if (nearBottom) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [visibleMessages.length, activeChat]);
  useEffect(() => {
    bottomRef.current?.scrollIntoView();
  }, [activeChat]);

  /* ---------------- typing (outgoing) ---------------- */

  const publishTyping = useCallback(
    (isTyping) => {
      const key = activeChatRef.current;
      publish('/app/chat.typing', { receiver: key === PUBLIC ? null : key, typing: isTyping });
    },
    [publish]
  );

  const stopTyping = useCallback(() => {
    clearTimeout(typingStartTimerRef.current);
    clearTimeout(typingStopTimerRef.current);
    typingStartTimerRef.current = null;
    if (typingActiveRef.current) {
      publishTyping(false);
      typingActiveRef.current = false;
    }
  }, [publishTyping]);

  const onInputChange = (value) => {
    setInput(value);
    if (!typingActiveRef.current && !typingStartTimerRef.current) {
      typingStartTimerRef.current = setTimeout(() => {
        typingStartTimerRef.current = null;
        typingActiveRef.current = true;
        publishTyping(true);
      }, TYPING_DEBOUNCE_MS);
    }
    clearTimeout(typingStopTimerRef.current);
    typingStopTimerRef.current = setTimeout(stopTyping, TYPING_STOP_MS);
  };

  /* ---------------- sending ---------------- */

  const sendMessage = useCallback(
    (text, attachment) => {
      const key = activeChatRef.current;
      const content = text.trim();
      if (!content && !attachment) return;
      if (!clientRef.current?.connected) {
        showToast('Not connected — retrying automatically…');
        return;
      }
      const clientId = genClientId();
      const optimistic = {
        id: null,
        clientId,
        sender: meRef.current,
        receiver: key === PUBLIC ? null : key,
        content,
        timestamp: new Date().toISOString(),
        isPrivate: key !== PUBLIC,
        isRead: false,
        readAt: null,
        fileType: attachment?.fileType || null,
        fileName: attachment?.fileName || null,
        fileUrl: attachment?.fileUrl || null,
        status: 'sending',
      };
      setConversations((prev) => ({ ...prev, [key]: [...(prev[key] || []), optimistic] }));

      const payload = { content, clientId, ...(attachment || {}) };
      if (key === PUBLIC) {
        publish('/app/chat.send', payload);
      } else {
        publish('/app/chat.private', { receiver: key, ...payload });
      }
      stopTyping();
    },
    [publish, showToast, stopTyping]
  );

  const onSubmit = (e) => {
    e.preventDefault();
    sendMessage(input, null);
    setInput('');
  };

  const onFilePicked = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (file.size > MAX_FILE_BYTES) {
      showToast('File exceeds the 10MB limit');
      return;
    }
    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', file);
      const uploaded = await apiFetch('/api/files/upload', {
        method: 'POST',
        formData,
        token: authRef.current?.accessToken,
        onRefresh: refreshTokens,
      });
      sendMessage(input, {
        fileType: uploaded.fileType,
        fileName: uploaded.fileName,
        fileUrl: uploaded.fileUrl,
      });
      setInput('');
    } catch (err) {
      showToast(err.message);
    } finally {
      setUploading(false);
    }
  };

  const switchChat = (key) => {
    if (key === activeChat) return;
    stopTyping();
    setSearchQuery('');
    setSearchResults(null);
    setActiveChat(key);
  };

  /* ---------------- 2FA ---------------- */

  const open2faSetup = async () => {
    try {
      const data = await apiFetch('/api/auth/2fa/setup', {
        method: 'POST',
        token: authRef.current?.accessToken,
        onRefresh: refreshTokens,
      });
      setTwoFaSetup(data);
    } catch (err) {
      showToast(err.message);
    }
  };

  /* ---------------- derived ---------------- */

  const dmPartners = useMemo(() => {
    const keys = new Set(Object.keys(conversations).filter((k) => k !== PUBLIC));
    onlineUsers.forEach((u) => u !== me && keys.add(u));
    return [...keys].sort((a, b) => {
      const onlineDiff = Number(onlineUsers.includes(b)) - Number(onlineUsers.includes(a));
      return onlineDiff !== 0 ? onlineDiff : a.localeCompare(b);
    });
  }, [conversations, onlineUsers, me]);

  const typingNames = Object.keys(typing[activeChat] || {});
  const onlineCount = onlineUsers.length;

  return (
    <div style={s.shell}>
      {/* ============ Sidebar ============ */}
      <aside style={s.sidebar}>
        <div style={s.sidebarHeader}>
          <span style={{ fontSize: 22 }}>💬</span>
          <div>
            <div style={{ fontWeight: 700, fontSize: 16 }}>ChatSphere</div>
            <div style={{ fontSize: 11, color: '#94a3b8' }}>
              {connected ? '🟢 connected' : '🔴 reconnecting…'}
            </div>
          </div>
        </div>

        <button
          style={{ ...s.chatItem, ...(activeChat === PUBLIC ? s.chatItemActive : {}) }}
          onClick={() => switchChat(PUBLIC)}
        >
          <span style={{ fontSize: 18 }}>🌐</span>
          <span style={{ flex: 1, textAlign: 'left' }}>Public Room</span>
          {unread[PUBLIC] > 0 && <span style={s.badge}>{unread[PUBLIC]}</span>}
        </button>

        <div style={s.sectionLabel}>DIRECT MESSAGES · {onlineCount} ONLINE</div>
        <div style={{ overflowY: 'auto', flex: 1 }}>
          {dmPartners.length === 0 && (
            <div style={{ padding: '8px 16px', fontSize: 12, color: '#64748b' }}>
              No one else is online yet. Open a second browser tab and register another user!
            </div>
          )}
          {dmPartners.map((user) => (
            <button
              key={user}
              style={{ ...s.chatItem, ...(activeChat === user ? s.chatItemActive : {}) }}
              onClick={() => switchChat(user)}
            >
              <span style={{ ...s.dot, background: onlineUsers.includes(user) ? '#22c55e' : '#475569' }} />
              <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {user}
              </span>
              {unread[user] > 0 && <span style={s.badge}>{unread[user]}</span>}
            </button>
          ))}
        </div>

        <div style={s.sidebarFooter}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <div style={s.avatar}>{me[0]?.toUpperCase()}</div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {me}
              </div>
              <div style={{ fontSize: 11, color: auth.twoFactorEnabled ? '#4ade80' : '#94a3b8' }}>
                {auth.twoFactorEnabled ? '🛡️ 2FA on' : '2FA off'}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            {!auth.twoFactorEnabled && (
              <button style={s.iconBtn} title="Enable two-factor authentication" onClick={open2faSetup}>
                🛡️
              </button>
            )}
            <button style={s.iconBtn} title="Log out" onClick={onLogout}>
              ⏻
            </button>
          </div>
        </div>
      </aside>

      {/* ============ Main area ============ */}
      <main style={s.main}>
        <header style={s.header}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 16 }}>
              {activeChat === PUBLIC ? '🌐 Public Room' : `@ ${activeChat}`}
            </div>
            <div style={{ fontSize: 12, color: '#94a3b8' }}>
              {activeChat === PUBLIC
                ? `${onlineCount} user${onlineCount === 1 ? '' : 's'} online`
                : onlineUsers.includes(activeChat)
                  ? 'online'
                  : 'offline'}
            </div>
          </div>
          <input
            style={s.searchInput}
            placeholder="🔎 Search messages…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </header>

        {/* Messages / search results */}
        <div ref={messagesContainerRef} style={s.messages}>
          {searchResults !== null ? (
            <SearchResults
              results={searchResults}
              query={searchQuery}
              me={me}
              onClose={() => {
                setSearchQuery('');
                setSearchResults(null);
              }}
            />
          ) : (
            <>
              {visibleMessages.length === 0 && (
                <div style={s.emptyState}>
                  <div style={{ fontSize: 40 }}>✨</div>
                  <div>No messages yet — say hello!</div>
                </div>
              )}
              {visibleMessages.map((m, i) => (
                <MessageBubble key={m.id ?? m.clientId ?? i} message={m} mine={m.sender === me} />
              ))}
              <div ref={bottomRef} />
            </>
          )}
        </div>

        {/* Typing indicator */}
        <div style={s.typingBar}>
          {typingNames.length > 0 && (
            <span>
              <TypingDots /> {typingNames.join(', ')} {typingNames.length === 1 ? 'is' : 'are'} typing…
            </span>
          )}
        </div>

        {/* Composer */}
        <form onSubmit={onSubmit} style={s.composer}>
          <input
            ref={fileInputRef}
            type="file"
            style={{ display: 'none' }}
            accept=".jpg,.jpeg,.png,.gif,.webp,.pdf,.txt,.md,.doc,.docx,.xls,.xlsx,.csv,.ppt,.pptx,.zip"
            onChange={onFilePicked}
          />
          <button
            type="button"
            style={s.attachBtn}
            title="Attach a file (max 10MB)"
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
          >
            {uploading ? '⏳' : '📎'}
          </button>
          <input
            style={s.messageInput}
            placeholder={activeChat === PUBLIC ? 'Message the room…' : `Message @${activeChat}…`}
            value={input}
            onChange={(e) => onInputChange(e.target.value)}
            maxLength={4000}
          />
          <button type="submit" style={s.sendBtn} disabled={!input.trim() || !connected}>
            ➤
          </button>
        </form>
      </main>

      {/* Toast */}
      {toast && (
        <div style={{ ...s.toast, borderColor: toast.type === 'error' ? '#ef4444' : '#22c55e' }}>
          {toast.message}
        </div>
      )}

      {/* 2FA setup modal */}
      {twoFaSetup && (
        <TwoFactorModal
          setup={twoFaSetup}
          token={authRef.current?.accessToken}
          refreshTokens={refreshTokens}
          onEnabled={() => {
            onAuthUpdate({ twoFactorEnabled: true });
            setTwoFaSetup(null);
            showToast('Two-factor authentication enabled 🛡️', 'success');
          }}
          onClose={() => setTwoFaSetup(null)}
        />
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Sub-components                                                     */
/* ------------------------------------------------------------------ */

function MessageBubble({ message: m, mine }) {
  const text = decodeEntities(m.content);
  return (
    <div style={{ display: 'flex', justifyContent: mine ? 'flex-end' : 'flex-start', marginBottom: 8 }}>
      <div
        style={{
          maxWidth: '68%',
          padding: '8px 12px',
          borderRadius: 14,
          borderBottomRightRadius: mine ? 4 : 14,
          borderBottomLeftRadius: mine ? 14 : 4,
          background: mine ? 'linear-gradient(135deg, #4f46e5, #6d28d9)' : '#1e293b',
          border: mine ? 'none' : '1px solid #334155',
          color: '#f1f5f9',
          fontSize: 14,
          lineHeight: 1.45,
          wordBreak: 'break-word',
        }}
      >
        {!mine && m.isPrivate === false && (
          <div style={{ fontSize: 12, fontWeight: 700, color: '#a5b4fc', marginBottom: 2 }}>{m.sender}</div>
        )}
        {m.fileType === 'IMAGE' && m.fileUrl && (
          <a href={apiUrl(m.fileUrl)} target="_blank" rel="noreferrer">
            <img
              src={apiUrl(m.fileUrl)}
              alt={m.fileName || 'image'}
              style={{ maxWidth: 260, maxHeight: 260, borderRadius: 8, display: 'block', marginBottom: text ? 6 : 0 }}
              loading="lazy"
            />
          </a>
        )}
        {m.fileType === 'FILE' && m.fileUrl && (
          <a
            href={apiUrl(m.fileUrl)}
            download={m.fileName || ''}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              color: '#e0e7ff',
              textDecoration: 'none',
              background: 'rgba(0,0,0,0.25)',
              borderRadius: 8,
              padding: '8px 10px',
              marginBottom: text ? 6 : 0,
              fontSize: 13,
            }}
          >
            <span style={{ fontSize: 20 }}>📄</span>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {m.fileName || 'Download file'}
            </span>
          </a>
        )}
        {text && <span>{text}</span>}
        <span style={{ fontSize: 10, color: mine ? '#c7d2fe' : '#64748b', marginLeft: 8, whiteSpace: 'nowrap', float: 'right', marginTop: 6 }}>
          {formatTime(m.timestamp)}
          {mine && <ReadTicks message={m} />}
        </span>
      </div>
    </div>
  );
}

function ReadTicks({ message: m }) {
  if (m.status === 'sending') return <span title="Sending…"> 🕓</span>;
  if (!m.isPrivate) return <span title="Sent"> ✓</span>;
  if (m.isRead)
    return (
      <span title={`Read ${m.readAt ? formatTime(m.readAt) : ''}`} style={{ color: '#67e8f9', fontWeight: 700 }}>
        {' '}✓✓
      </span>
    );
  return <span title="Delivered"> ✓✓</span>;
}

function TypingDots() {
  return (
    <span style={{ display: 'inline-flex', gap: 3, marginRight: 4 }}>
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          style={{
            width: 5,
            height: 5,
            borderRadius: '50%',
            background: '#94a3b8',
            display: 'inline-block',
            animation: `typingBlink 1.2s ${i * 0.2}s infinite`,
          }}
        />
      ))}
      <style>{`@keyframes typingBlink { 0%, 80%, 100% { opacity: 0.25; } 40% { opacity: 1; } }`}</style>
    </span>
  );
}

function SearchResults({ results, query, me, onClose }) {
  return (
    <div>
      <div style={s.searchHeader}>
        <span>
          {results.length} result{results.length === 1 ? '' : 's'} for “{query.trim()}”
        </span>
        <button style={s.iconBtn} onClick={onClose} title="Close search">
          ✕
        </button>
      </div>
      {results.length === 0 && (
        <div style={s.emptyState}>
          <div style={{ fontSize: 32 }}>🔍</div>
          <div>Nothing matched. Full-text search uses PostgreSQL tsvector.</div>
        </div>
      )}
      {results.map((m) => (
        <div key={m.id} style={s.searchRow}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#94a3b8' }}>
            <b style={{ color: m.sender === me ? '#a5b4fc' : '#e2e8f0' }}>{m.sender}</b>
            <span>{new Date(m.timestamp).toLocaleString()}</span>
          </div>
          <div style={{ fontSize: 14, marginTop: 3 }}>{decodeEntities(m.content)}</div>
        </div>
      ))}
    </div>
  );
}

function TwoFactorModal({ setup, token, refreshTokens, onEnabled, onClose }) {
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const verify = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await apiFetch('/api/auth/2fa/verify', {
        method: 'POST',
        body: { code },
        token,
        onRefresh: refreshTokens,
      });
      onEnabled();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={s.modalOverlay} onClick={onClose}>
      <div style={s.modal} onClick={(e) => e.stopPropagation()}>
        <h3 style={{ margin: '0 0 6px' }}>🛡️ Enable two-factor authentication</h3>
        <p style={{ fontSize: 13, color: '#94a3b8', margin: '0 0 14px' }}>
          Scan with Google Authenticator, Authy or 1Password, then enter the current 6-digit code.
        </p>
        <img
          src={setup.qrCodeDataUri}
          alt="TOTP QR code"
          style={{ width: 190, height: 190, display: 'block', margin: '0 auto 12px', borderRadius: 8, background: '#fff', padding: 6 }}
        />
        <div style={{ fontSize: 11, color: '#64748b', textAlign: 'center', marginBottom: 14, wordBreak: 'break-all' }}>
          Manual key: <code style={{ color: '#a5b4fc' }}>{setup.secret}</code>
        </div>
        <form onSubmit={verify}>
          <input
            style={{ ...s.messageInput, textAlign: 'center', letterSpacing: 8, fontSize: 18, marginBottom: 10 }}
            placeholder="123456"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoFocus
            required
          />
          {error && <div style={{ color: '#fca5a5', fontSize: 13, marginBottom: 10 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" style={{ ...s.iconBtn, flex: 1, padding: '10px 0' }} onClick={onClose}>
              Cancel
            </button>
            <button type="submit" style={{ ...s.sendBtn, flex: 1, borderRadius: 8 }} disabled={busy || code.length !== 6}>
              {busy ? 'Verifying…' : 'Enable 2FA'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Styles (inline, no framework)                                      */
/* ------------------------------------------------------------------ */

const s = {
  shell: { display: 'flex', height: '100%', background: '#0f172a' },
  sidebar: {
    width: 270,
    minWidth: 270,
    borderRight: '1px solid #1e293b',
    display: 'flex',
    flexDirection: 'column',
    background: '#0b1120',
  },
  sidebarHeader: { display: 'flex', gap: 10, alignItems: 'center', padding: '16px 16px 12px' },
  sectionLabel: { fontSize: 10, letterSpacing: 1.2, color: '#64748b', padding: '14px 16px 6px', fontWeight: 700 },
  chatItem: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    width: 'calc(100% - 16px)',
    margin: '2px 8px',
    padding: '9px 10px',
    background: 'transparent',
    border: 'none',
    borderRadius: 8,
    color: '#cbd5e1',
    fontSize: 14,
    cursor: 'pointer',
  },
  chatItemActive: { background: '#1e293b', color: '#fff', fontWeight: 600 },
  dot: { width: 9, height: 9, borderRadius: '50%', flexShrink: 0 },
  badge: {
    background: '#4f46e5',
    color: '#fff',
    fontSize: 11,
    fontWeight: 700,
    borderRadius: 10,
    padding: '1px 7px',
    minWidth: 20,
    textAlign: 'center',
  },
  sidebarFooter: {
    borderTop: '1px solid #1e293b',
    padding: '12px 14px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  avatar: {
    width: 34,
    height: 34,
    borderRadius: '50%',
    background: 'linear-gradient(135deg, #4f46e5, #7c3aed)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontWeight: 700,
    flexShrink: 0,
  },
  iconBtn: {
    background: '#1e293b',
    border: '1px solid #334155',
    color: '#e2e8f0',
    borderRadius: 8,
    padding: '6px 9px',
    cursor: 'pointer',
    fontSize: 14,
  },
  main: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 },
  header: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 16,
    padding: '12px 20px',
    borderBottom: '1px solid #1e293b',
  },
  searchInput: {
    background: '#0b1120',
    border: '1px solid #334155',
    borderRadius: 8,
    color: '#e2e8f0',
    padding: '8px 12px',
    fontSize: 13,
    width: 240,
    outline: 'none',
  },
  messages: { flex: 1, overflowY: 'auto', padding: '18px 20px 6px' },
  emptyState: { textAlign: 'center', color: '#64748b', marginTop: 60, fontSize: 14, display: 'grid', gap: 8, justifyItems: 'center' },
  typingBar: { height: 24, padding: '0 22px', fontSize: 12, color: '#94a3b8', display: 'flex', alignItems: 'center' },
  composer: { display: 'flex', gap: 8, padding: '10px 16px 16px', alignItems: 'center' },
  attachBtn: {
    background: '#1e293b',
    border: '1px solid #334155',
    borderRadius: 10,
    color: '#e2e8f0',
    fontSize: 17,
    padding: '10px 13px',
    cursor: 'pointer',
  },
  messageInput: {
    flex: 1,
    background: '#0b1120',
    border: '1px solid #334155',
    borderRadius: 10,
    color: '#e2e8f0',
    padding: '12px 14px',
    fontSize: 14,
    outline: 'none',
    width: '100%',
  },
  sendBtn: {
    background: 'linear-gradient(135deg, #4f46e5, #7c3aed)',
    border: 'none',
    borderRadius: 10,
    color: '#fff',
    fontSize: 17,
    padding: '10px 18px',
    cursor: 'pointer',
  },
  searchHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    fontSize: 13,
    color: '#94a3b8',
    marginBottom: 12,
    paddingBottom: 10,
    borderBottom: '1px solid #1e293b',
  },
  searchRow: { background: '#1e293b', border: '1px solid #334155', borderRadius: 10, padding: '10px 12px', marginBottom: 8 },
  toast: {
    position: 'fixed',
    bottom: 24,
    left: '50%',
    transform: 'translateX(-50%)',
    background: '#1e293b',
    border: '1px solid',
    borderRadius: 10,
    padding: '11px 18px',
    fontSize: 13,
    color: '#f1f5f9',
    boxShadow: '0 10px 30px rgba(0,0,0,0.5)',
    zIndex: 60,
    maxWidth: '90vw',
  },
  modalOverlay: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(0,0,0,0.6)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 50,
    padding: 16,
  },
  modal: {
    background: '#1e293b',
    border: '1px solid #334155',
    borderRadius: 14,
    padding: 24,
    width: 360,
    maxWidth: '100%',
  },
};
