// Live smoke test against the running Spring Boot + PostgreSQL stack.
// Exercises: health, register, login, refresh rotation + type enforcement,
// file upload/download, history, IDOR guard, PostgreSQL full-text search,
// and the complete TOTP 2FA flow (code computed independently in Node).
import crypto from 'node:crypto';

const BASE = 'http://localhost:8080';
let passed = 0, failed = 0;
const ok = (name, cond) => {
  if (cond) { passed++; console.log(`  ✅ ${name}`); }
  else { failed++; console.log(`  ❌ ${name}`); }
};

const j = async (path, { method = 'GET', token, body, formData } = {}) => {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (formData) payload = formData;
  else if (body) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await fetch(BASE + path, { method, headers, body: payload });
  let data = null;
  try { data = await res.json(); } catch {}
  return { status: res.status, data };
};

// --- independent TOTP implementation (RFC 6238, SHA1/6/30) ---
function base32Decode(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0;
  const out = [];
  for (const c of s.replace(/=+$/, '').toUpperCase()) {
    value = (value << 5) | alphabet.indexOf(c);
    bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  return Buffer.from(out);
}
function totp(secret) {
  const counter = Math.floor(Date.now() / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const hmac = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0xf;
  const code = ((hmac[offset] & 0x7f) << 24 | hmac[offset + 1] << 16 | hmac[offset + 2] << 8 | hmac[offset + 3]) % 1_000_000;
  return String(code).padStart(6, '0');
}

const suffix = crypto.randomBytes(3).toString('hex');
const alice = `alice_${suffix}`, bob = `bob_${suffix}`;

console.log('— Health —');
const health = await j('/actuator/health');
ok('actuator health UP', health.status === 200 && health.data.status === 'UP');

console.log('— Auth —');
const reg = await j('/api/auth/register', { method: 'POST', body: { username: alice, password: 'password123' } });
ok('register 201 + tokens', reg.status === 201 && !!reg.data.accessToken && !!reg.data.refreshToken);
await j('/api/auth/register', { method: 'POST', body: { username: bob, password: 'password123' } });
const dup = await j('/api/auth/register', { method: 'POST', body: { username: alice, password: 'password123' } });
ok('duplicate username 409', dup.status === 409);
const login = await j('/api/auth/login', { method: 'POST', body: { username: alice, password: 'password123' } });
ok('login 200', login.status === 200);
const bad = await j('/api/auth/login', { method: 'POST', body: { username: alice, password: 'nope-nope-1' } });
ok('bad credentials 401', bad.status === 401);
const tok = login.data.accessToken;
const refresh = await j('/api/auth/refresh', { method: 'POST', body: { refreshToken: login.data.refreshToken } });
ok('refresh rotates tokens', refresh.status === 200 && refresh.data.accessToken !== tok);
const wrongType = await j('/api/auth/refresh', { method: 'POST', body: { refreshToken: tok } });
ok('access token rejected as refresh (401)', wrongType.status === 401);
const noAuth = await j('/api/messages/public');
ok('protected route without token 401', noAuth.status === 401);

console.log('— Files —');
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
const fd = new FormData();
fd.append('file', new Blob([png], { type: 'image/png' }), 'pixel.png');
const up = await j('/api/files/upload', { method: 'POST', token: tok, formData: fd });
ok('upload 201 IMAGE', up.status === 201 && up.data.fileType === 'IMAGE');
const dl = await fetch(BASE + up.data.fileUrl);
ok('capability-URL download (no auth) 200', dl.status === 200 && Buffer.from(await dl.arrayBuffer()).equals(png));
const fdBad = new FormData();
fdBad.append('file', new Blob([Buffer.from('MZ')]), 'evil.exe');
const upBad = await j('/api/files/upload', { method: 'POST', token: tok, formData: fdBad });
ok('.exe rejected 415', upBad.status === 415);

console.log('— Messages + PostgreSQL full-text search —');
// Seed via SQL (WS delivery is covered by the Java E2E suite)
const { execSync } = await import('node:child_process');
execSync(`su postgres -c "psql -d chatdb -c \\"INSERT INTO messages (sender, receiver, content, timestamp, is_private, is_read) VALUES ('${alice}', NULL, 'The deployment pipeline finished successfully', now(), false, false), ('${bob}', NULL, 'kubernetes rollout looks healthy', now(), false, false), ('${alice}', '${bob}', 'secret deployment plans inside the DM', now(), true, false);\\""`);
const hist = await j('/api/messages/public', { token: tok });
ok('public history 200', hist.status === 200 && hist.data.length >= 2);
const search = await j('/api/messages/search?q=deployment', { token: tok });
ok('FTS finds public "deployment" (tsvector)', search.status === 200 && search.data.some(m => m.content.includes('deployment pipeline')));
ok('FTS does NOT leak private messages', !search.data.some(m => m.isPrivate));
const stem = await j('/api/messages/search?q=deploying', { token: tok });
ok('FTS english stemming (deploying ~ deployment)', stem.status === 200 && stem.data.length >= 1);
const dm = await j(`/api/messages/private/${bob}?me=${alice}`, { token: tok });
ok('private history scoped', dm.status === 200 && dm.data.length === 1 && dm.data[0].isPrivate);
const idor = await j(`/api/messages/private/${bob}?me=${bob}`, { token: tok });
ok('spoofed me param 403 (IDOR guard)', idor.status === 403);
const dmSearch = await j(`/api/messages/search/private/${bob}?q=deployment`, { token: tok });
ok('private FTS scoped to conversation', dmSearch.status === 200 && dmSearch.data.length === 1);

console.log('— Two-factor authentication —');
const setup = await j('/api/auth/2fa/setup', { method: 'POST', token: tok });
ok('2FA setup returns secret + QR', setup.status === 200 && setup.data.qrCodeDataUri.startsWith('data:image/png;base64,'));
const verify = await j('/api/auth/2fa/verify', { method: 'POST', token: tok, body: { code: totp(setup.data.secret) } });
ok('2FA verify with independently computed TOTP', verify.status === 200 && verify.data.twoFactorEnabled === true);
const need = await j('/api/auth/login', { method: 'POST', body: { username: alice, password: 'password123' } });
ok('login now demands 2FA_REQUIRED', need.status === 401 && need.data.error === '2FA_REQUIRED');
const with2fa = await j('/api/auth/login', { method: 'POST', body: { username: alice, password: 'password123', twoFactorCode: totp(setup.data.secret) } });
ok('login with TOTP succeeds', with2fa.status === 200 && with2fa.data.twoFactorEnabled === true);

console.log(`\nRESULT: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
