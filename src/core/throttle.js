'use strict';
const { sleep } = require('./util');

/**
 * Shared bandwidth limiter. One instance is shared by every task so the limit
 * applies to the whole application, not to each download.
 */
class Throttle {
  constructor(bytesPerSecond = 0) {
    this.setLimit(bytesPerSecond);
  }

  setLimit(bps) {
    this.bps = Math.max(0, Math.floor(Number(bps) || 0));
    this.nextFree = 0;
  }

  /** Resolve once `n` more bytes may be processed. */
  async take(n, signal) {
    if (!this.bps) return;
    const now = Date.now();
    this.nextFree = Math.max(this.nextFree, now) + (n / this.bps) * 1000;
    const wait = this.nextFree - now - 250; // allow a 250 ms burst
    if (wait > 0) await sleep(wait, signal);
  }
}

module.exports = { Throttle };
