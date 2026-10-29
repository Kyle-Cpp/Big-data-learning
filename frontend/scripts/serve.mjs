import http from 'node:http';
import https from 'node:https';
import { readFile } from 'node:fs/promises';
import { resolve, dirname, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const option = (key, fallback) => args.includes(key) ? args[args.indexOf(key) + 1] : fallback;
const port = Number(option('--port', process.env.PORT || '5173'));
const backend = option('--backend', process.env.FOOTPRED_API_URL || 'http://127.0.0.1:8080');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../dist');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.json': 'application/json' };
const server = http.createServer(async (req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if (pathname === '/api' || pathname.startsWith('/api/')) {
    const target = new URL(req.url, backend);
    const transport = target.protocol === 'https:' ? https : http;
    const proxy = transport.request(target, { method: req.method, headers: { ...req.headers, host: target.host } }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    proxy.setTimeout(10000, () => proxy.destroy(new Error('Backend timeout')));
    proxy.on('error', () => { if (!res.headersSent) { res.writeHead(502, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: 'backend-unavailable', message: '后端尚未启动。可切换到示例数据，或启动 Spring Boot 后刷新。' })); } else res.destroy(); });
    req.pipe(proxy); return;
  }
  try {
    const decoded = decodeURIComponent(pathname);
    const file = resolve(root, '.' + (decoded === '/' ? '/index.html' : decoded));
    if (!file.startsWith(root + sep)) { res.writeHead(403); res.end(); return; }
    const data = await readFile(file);
    res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream', 'cache-control': 'no-cache' });
    res.end(data);
  } catch { res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }); res.end('未找到页面'); }
});
server.listen(port, '127.0.0.1', () => console.log(`FootPred: http://127.0.0.1:${port}\nAPI proxy: ${backend}`));
for (const event of ['SIGINT', 'SIGTERM']) process.on(event, () => server.close(() => process.exit(0)));
