// Thin REST helper. All URLs are relative so the same build works behind the
// Vite dev proxy, the Nginx container, or a hosted domain. Set VITE_API_URL
// only when the API lives on a different origin (e.g. Vercel + Railway).
const API_BASE = import.meta.env.VITE_API_URL || '';

export function apiUrl(path) {
  return `${API_BASE}${path}`;
}

export function wsUrl() {
  return `${API_BASE}/ws`;
}

/**
 * fetch with JSON handling + bearer token. On 401 it tries ONE token refresh
 * (via the provided refresh callback) and replays the request.
 * Returns parsed JSON; throws Error with the server's message on failure.
 */
export async function apiFetch(path, { method = 'GET', token, body, formData, onRefresh } = {}) {
  const doFetch = async (bearer) => {
    const headers = {};
    if (bearer) headers.Authorization = `Bearer ${bearer}`;
    let payload;
    if (formData) {
      payload = formData; // browser sets multipart boundary
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    return fetch(apiUrl(path), { method, headers, body: payload });
  };

  let res = await doFetch(token);
  if (res.status === 401 && onRefresh) {
    const newToken = await onRefresh();
    if (newToken) res = await doFetch(newToken);
  }

  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: text };
  }
  if (!res.ok) {
    const err = new Error(data?.error || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}
