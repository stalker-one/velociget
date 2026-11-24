'use strict';
const { request, Agent, ProxyAgent } = require('undici');

const COMMON = {
  connections: 64,
  pipelining: 0,
  keepAliveTimeout: 30_000,
  headersTimeout: 30_000,
  bodyTimeout: 60_000,
};

const dispatchers = new Map();

/** Returns a shared undici dispatcher, optionally routed through an HTTP(S) proxy. */
function dispatcherFor(proxy) {
  const hasProxy = proxy && proxy.host && proxy.port;
  const key = hasProxy ? `${proxy.host}:${proxy.port}:${proxy.user || ''}` : 'direct';
  let d = dispatchers.get(key);
  if (d) return d;
  if (!hasProxy) {
    d = new Agent(COMMON);
  } else {
    const opts = { uri: `http://${proxy.host}:${proxy.port}`, ...COMMON };
    if (proxy.user) {
      opts.token = 'Basic ' + Buffer.from(`${proxy.user}:${proxy.pass || ''}`).toString('base64');
    }
    d = new ProxyAgent(opts);
  }
  dispatchers.set(key, d);
  return d;
}

function normalizeHeaders(h) {
  const out = {};
  for (const [k, v] of Object.entries(h || {})) {
    if (v !== undefined && v !== null && v !== '') out[k.toLowerCase()] = String(v);
  }
  return out;
}

const REDIRECTS = new Set([301, 302, 303, 307, 308]);

/**
 * GET/HEAD with manual redirect handling (undici.request does not follow them).
 * Credentials embedded in the URL become a Basic Authorization header, and
 * Authorization/Cookie are dropped when a redirect leaves the original origin.
 */
async function fetchFollow(url, { method = 'GET', headers = {}, signal, dispatcher, maxRedirects = 10 } = {}) {
  const h = normalizeHeaders(headers);
  let current = new URL(url);
  if (current.username) {
    h.authorization = 'Basic ' + Buffer.from(`${decodeURIComponent(current.username)}:${decodeURIComponent(current.password)}`).toString('base64');
    current.username = '';
    current.password = '';
  }
  for (let i = 0; i <= maxRedirects; i++) {
    const res = await request(current.toString(), {
      method,
      headers: h,
      signal,
      dispatcher: dispatcher || dispatcherFor(null),
    });
    // Destroying a body early makes undici emit an "aborted" error on it; with no listener that
    // would crash the process, and it is expected here, so swallow it.
    res.body.on('error', () => {});
    if (REDIRECTS.has(res.statusCode) && res.headers.location) {
      try {
        await res.body.dump({ limit: 64 * 1024 });
      } catch {
        /* ignore */
      }
      const next = new URL(res.headers.location, current);
      if (next.origin !== current.origin) {
        delete h.authorization;
        delete h.cookie;
      }
      current = next;
      continue;
    }
    return { res, url: current.toString() };
  }
  throw new Error('Too many redirects');
}

module.exports = { fetchFollow, dispatcherFor, normalizeHeaders };
