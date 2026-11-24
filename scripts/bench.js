'use strict';
// Loopback throughput benchmark of the download engine: node scripts/bench.js [MB] [connections]
const http = require('http');
const { makeTask, waitSettled } = require('../test/helpers');
const MB = Number(process.argv[2] || 600);
const CONN = Number(process.argv[3] || 16);
const size = MB * 1024 * 1024;
const block = Buffer.alloc(1024 * 1024, 7);
const LAT = Number(process.env.LAT || 0); // ms before the first byte of every response
const PACE = Number(process.env.PACE || 0); // ms between 256 KB writes (simulates a per-connection rate cap)
const server = http.createServer((req, res) => { setTimeout(() => {
  let start = 0, end = size - 1, status = 200;
  const h = { 'content-type': 'application/octet-stream', 'accept-ranges': 'bytes', etag: '"b"' };
  const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
  if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], size - 1); status = 206; h['content-range'] = `bytes ${start}-${end}/${size}`; }
  h['content-length'] = end - start + 1;
  res.writeHead(status, h);
  let left = end - start + 1;
  const pump = () => {
    while (left > 0) {
      const n = Math.min(left, block.length);
      left -= n;
      if (!res.write(n === block.length ? block : block.subarray(0, n))) return res.once('drain', pump);
      if (PACE && left > 0) return void setTimeout(pump, PACE);
    }
    res.end();
  };
  pump();
}, LAT); });
server.listen(0, '127.0.0.1', async () => {
  const { task, dir } = makeTask({ url: `http://127.0.0.1:${server.address().port}/big.bin`, connections: CONN }, { maxConnections: CONN });
  const t0 = Date.now();
  task.start();
  await waitSettled(task);
  const s = (Date.now() - t0) / 1000;
  console.log(`${MB} MB, ${CONN} connections: ${s.toFixed(2)} s = ${(MB / s).toFixed(0)} MB/s  state=${task.rec.state}`);
  server.closeAllConnections?.(); server.close();
  require('fs').rmSync(dir, { recursive: true, force: true });
});
