'use strict';
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');

/** Small JSON file store with atomic writes and a debounced save. */
class JsonStore {
  constructor(file, fallback) {
    this.file = file;
    this.fallback = fallback;
    this._timer = null;
    this._pending = null;
    this._writing = Promise.resolve();
  }

  async load() {
    for (const f of [this.file, this.file + '.bak']) {
      try {
        return JSON.parse(await fsp.readFile(f, 'utf8'));
      } catch {
        /* try the backup */
      }
    }
    return this.fallback();
  }

  save(getValue, delay = 400) {
    this._pending = getValue;
    if (this._timer) return;
    this._timer = setTimeout(() => {
      this._timer = null;
      this._writing = this._writing.then(() => this._flush());
    }, delay);
  }

  async flush() {
    if (this._timer) {
      clearTimeout(this._timer);
      this._timer = null;
    }
    this._writing = this._writing.then(() => this._flush());
    await this._writing;
  }

  async _flush() {
    if (!this._pending) return;
    const data = JSON.stringify(this._pending(), null, 2);
    this._pending = null;
    await fsp.mkdir(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    await fsp.writeFile(tmp, data);
    await fsp.copyFile(this.file, this.file + '.bak').catch(() => {});
    await fsp.rename(tmp, this.file);
  }
}

module.exports = { JsonStore };
