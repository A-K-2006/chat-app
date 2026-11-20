import React, { useCallback, useEffect, useRef, useState } from 'react';
import Chat from './Chat.jsx';
import { apiFetch } from './api.js';

const STORAGE_KEY = 'chatsphere.auth';
const REFRESH_INTERVAL_MS = 14 * 60 * 1000; // access tokens live 15 min — refresh at 14

function loadStoredAuth() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export default function App() {
  const [auth, setAuth] = useState(loadStoredAuth);
  const authRef = useRef(auth);
  authRef.current = auth;

  const persistAuth = useCallback((next) => {
    authRef.current = next;
    setAuth(next);
    if (next) localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else localStorage.removeItem(STORAGE_KEY);
  }, []);

  const logout = useCallback(() => persistAuth(null), [persistAuth]);

  /** Exchanges the refresh token for a new pair. Returns the new access token or null. */
  const refreshTokens = useCallback(async () => {
    const current = authRef.current;
    if (!current?.refreshToken) return null;
    try {
      const data = await apiFetch('/api/auth/refresh', {
        method: 'POST',
        body: { refreshToken: current.refreshToken },
      });
      const next = {
        accessToken: data.accessToken,
        refreshToken: data.refreshToken,
        username: data.username,
        twoFactorEnabled: data.twoFactorEnabled,
      };
      persistAuth(next);
      return next.accessToken;
    } catch {
      logout();
      return null;
    }
  }, [persistAuth, logout]);

  // Proactive refresh every 14 minutes while logged in.
  useEffect(() => {
    if (!auth) return undefined;
    const id = setInterval(refreshTokens, REFRESH_INTERVAL_MS);
    return () => clearInterval(id);
  }, [auth?.username, refreshTokens]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!auth) {
    return <AuthScreen onAuthenticated={persistAuth} />;
  }
  return (
    <Chat
      auth={auth}
      authRef={authRef}
      onLogout={logout}
      refreshTokens={refreshTokens}
      onAuthUpdate={(patch) => persistAuth({ ...authRef.current, ...patch })}
    />
  );
}

/* ------------------------------------------------------------------ */
/*  Login / Register screen (with 2FA challenge support)               */
/* ------------------------------------------------------------------ */

function AuthScreen({ onAuthenticated }) {
  const [mode, setMode] = useState('login'); // 'login' | 'register'
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [twoFactorCode, setTwoFactorCode] = useState('');
  const [needs2fa, setNeeds2fa] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const path = mode === 'login' ? '/api/auth/login' : '/api/auth/register';
      const body =
        mode === 'login'
          ? { username, password, twoFactorCode: needs2fa ? twoFactorCode : undefined }
          : { username, password };
      const data = await apiFetch(path, { method: 'POST', body });
      onAuthenticated({
        accessToken: data.accessToken,
        refreshToken: data.refreshToken,
        username: data.username,
        twoFactorEnabled: data.twoFactorEnabled,
      });
    } catch (err) {
      if (err.message === '2FA_REQUIRED') {
        setNeeds2fa(true);
        setError('Enter the 6-digit code from your authenticator app.');
      } else if (err.message === 'INVALID_2FA_CODE') {
        setNeeds2fa(true);
        setError('That code is incorrect — try the current one.');
      } else {
        setError(err.message);
      }
    } finally {
      setBusy(false);
    }
  };

  const switchMode = (next) => {
    setMode(next);
    setError('');
    setNeeds2fa(false);
    setTwoFactorCode('');
  };

  return (
    <div style={styles.authWrap}>
      <div style={styles.authCard}>
        <div style={{ fontSize: 44, textAlign: 'center' }}>💬</div>
        <h1 style={styles.authTitle}>ChatSphere</h1>
        <p style={styles.authSubtitle}>
          Real-time chat · Spring Boot + React + WebSocket
        </p>

        <div style={styles.tabs}>
          <button
            style={{ ...styles.tab, ...(mode === 'login' ? styles.tabActive : {}) }}
            onClick={() => switchMode('login')}
            type="button"
          >
            Sign in
          </button>
          <button
            style={{ ...styles.tab, ...(mode === 'register' ? styles.tabActive : {}) }}
            onClick={() => switchMode('register')}
            type="button"
          >
            Create account
          </button>
        </div>

        <form onSubmit={submit}>
          <input
            style={styles.input}
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
            minLength={3}
            maxLength={30}
            pattern="[a-zA-Z0-9_]+"
            title="Letters, digits and underscores only"
          />
          <input
            style={styles.input}
            placeholder="Password (min 8 characters)"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            required
            minLength={8}
            maxLength={72}
          />
          {needs2fa && (
            <input
              style={{ ...styles.input, letterSpacing: 8, textAlign: 'center', fontSize: 18 }}
              placeholder="123456"
              value={twoFactorCode}
              onChange={(e) => setTwoFactorCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              inputMode="numeric"
              autoFocus
              required
            />
          )}
          {error && <div style={styles.errorBox}>{error}</div>}
          <button style={styles.primaryBtn} type="submit" disabled={busy}>
            {busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}
          </button>
        </form>

        <p style={{ fontSize: 12, color: '#64748b', textAlign: 'center', marginTop: 16 }}>
          Passwords are BCrypt-hashed · JWT auth · optional TOTP 2FA
        </p>
      </div>
    </div>
  );
}

const styles = {
  authWrap: {
    minHeight: '100%',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: 'linear-gradient(135deg, #0f172a 0%, #1e1b4b 60%, #172554 100%)',
    padding: 16,
  },
  authCard: {
    width: 380,
    maxWidth: '100%',
    background: '#1e293b',
    border: '1px solid #334155',
    borderRadius: 16,
    padding: '32px 28px',
    boxShadow: '0 20px 60px rgba(0,0,0,0.5)',
  },
  authTitle: { margin: '8px 0 0', textAlign: 'center', fontSize: 26, color: '#f1f5f9' },
  authSubtitle: { margin: '6px 0 20px', textAlign: 'center', fontSize: 13, color: '#94a3b8' },
  tabs: { display: 'flex', gap: 8, marginBottom: 18 },
  tab: {
    flex: 1,
    padding: '9px 0',
    background: 'transparent',
    color: '#94a3b8',
    border: '1px solid #334155',
    borderRadius: 8,
    cursor: 'pointer',
    fontSize: 14,
  },
  tabActive: { background: '#4f46e5', borderColor: '#4f46e5', color: '#fff', fontWeight: 600 },
  input: {
    width: '100%',
    padding: '11px 12px',
    marginBottom: 12,
    background: '#0f172a',
    border: '1px solid #334155',
    borderRadius: 8,
    color: '#e2e8f0',
    fontSize: 14,
    outline: 'none',
  },
  errorBox: {
    background: 'rgba(239,68,68,0.12)',
    border: '1px solid rgba(239,68,68,0.4)',
    color: '#fca5a5',
    borderRadius: 8,
    padding: '9px 12px',
    fontSize: 13,
    marginBottom: 12,
  },
  primaryBtn: {
    width: '100%',
    padding: '12px 0',
    background: 'linear-gradient(90deg, #4f46e5, #7c3aed)',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    fontSize: 15,
    fontWeight: 600,
    cursor: 'pointer',
  },
};
