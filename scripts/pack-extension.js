'use strict';
// Zips the extension folder (needed for Firefox / store uploads). Uses the system zip tool or PowerShell on Windows.
const { execFileSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const root = path.join(__dirname, '..');
const src = path.join(root, 'extension');
const out = path.join(root, 'dist', 'idm-by-encodal-extension.zip');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.rmSync(out, { force: true });
if (process.platform === 'win32') {
  execFileSync('powershell', ['-NoProfile', '-Command', `Compress-Archive -Path '${src}\\*' -DestinationPath '${out}'`], { stdio: 'inherit' });
} else {
  execFileSync('zip', ['-r', '-q', out, '.'], { cwd: src, stdio: 'inherit' });
}
console.log('wrote', out);
