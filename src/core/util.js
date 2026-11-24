'use strict';

function abortError() {
  const e = new Error('Aborted');
  e.name = 'AbortError';
  e.code = 'ABORT_ERR';
  return e;
}

/** Promise-based sleep that rejects with AbortError when the signal fires. */
function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal && signal.aborted) return reject(abortError());
    const t = setTimeout(() => {
      if (signal) signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    function onAbort() {
      clearTimeout(t);
      reject(abortError());
    }
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
  });
}

class HttpError extends Error {
  constructor(status, message) {
    super(message || `HTTP ${status}`);
    this.name = 'HttpError';
    this.status = status;
  }
}

/** An error that retrying will not fix. */
class FatalError extends Error {
  constructor(message, code) {
    super(message);
    this.name = 'FatalError';
    this.fatal = true;
    this.code = code;
  }
}

const isAbort = (e) =>
  !!e && (e.name === 'AbortError' || e.code === 'ABORT_ERR' || e.code === 'UND_ERR_ABORTED');

function formatBytes(n) {
  if (n == null || n < 0) return '?';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i ? 2 : 0)} ${u[i]}`;
}

async function exists(p) {
  try {
    await require('fs').promises.access(p);
    return true;
  } catch {
    return false;
  }
}

module.exports = { abortError, sleep, HttpError, FatalError, isAbort, formatBytes, exists };
