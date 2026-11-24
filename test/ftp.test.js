'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { makeData, sha, tmpDir, makeTask, waitSettled } = require('./helpers');

test('ftp:// download completes and matches', async () => {
  const FtpSrv = require('ftp-srv');
  const root = tmpDir('ftp-root-');
  const data = makeData(3 * 1024 * 1024 + 123, 9);
  fs.writeFileSync(path.join(root, 'sample.bin'), data);
  const srv = new FtpSrv({ url: 'ftp://127.0.0.1:0', anonymous: true, pasv_url: '127.0.0.1', pasv_min: 40100, pasv_max: 40200 });
  srv.on('login', (_d, resolve) => resolve({ root }));
  await srv.listen();
  const port = srv.server.address().port;
  const { task, dir } = makeTask({ url: `ftp://127.0.0.1:${port}/sample.bin`, kind: 'ftp' });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'sample.bin'))), sha(data));
  await srv.close();
});
