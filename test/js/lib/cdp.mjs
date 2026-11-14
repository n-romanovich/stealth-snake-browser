// Minimal Chrome DevTools Protocol harness (no third-party dependencies).
// Requires Node >= 20.10 started with --experimental-websocket.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import http from 'node:http';

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean);

export function findChrome() {
  const found = CHROME_CANDIDATES.find((p) => existsSync(p));
  if (!found) throw new Error('No Chrome/Chromium found. Set CHROME_PATH.');
  return found;
}

class Connection {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Set();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id) {
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new Error(`${p.method}: ${msg.error.message}`));
        else p.resolve(msg.result);
      } else {
        for (const l of this.listeners) l(msg);
      }
    });
  }

  send(method, params = {}, sessionId) {
    const id = this.nextId++;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      this.ws.send(JSON.stringify(payload));
    });
  }
}

export class Page {
  constructor(conn, sessionId, targetId) {
    this.conn = conn;
    this.sessionId = sessionId;
    this.targetId = targetId;
    this.events = [];
    conn.listeners.add((msg) => {
      if (msg.sessionId === sessionId) this.events.push(msg);
    });
  }

  send(method, params) {
    return this.conn.send(method, params, this.sessionId);
  }

  /** Evaluate an expression (awaiting promises) and return its JSON value. */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression, awaitPromise: true, returnByValue: true, userGesture: false,
    });
    if (r.exceptionDetails) {
      throw new Error(`Page exception: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
    }
    return r.result.value;
  }

  async goto(url) {
    const loaded = this.waitForEvent('Page.loadEventFired');
    await this.send('Page.navigate', { url });
    await loaded;
  }

  waitForEvent(method, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timeout waiting for ${method}`)), timeoutMs);
      const listener = (msg) => {
        if (msg.sessionId === this.sessionId && msg.method === method) {
          clearTimeout(timer);
          this.conn.listeners.delete(listener);
          resolve(msg.params);
        }
      };
      this.conn.listeners.add(listener);
    });
  }

  async close() {
    await this.conn.send('Target.closeTarget', { targetId: this.targetId });
  }
}

export class Browser {
  static async launch() {
    const userDataDir = mkdtempSync(join(tmpdir(), 'gs-chrome-'));
    const proc = spawn(findChrome(), [
      '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${userDataDir}`,
      '--no-first-run', '--no-default-browser-check', '--disable-extensions',
      '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
      '--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE 127.0.0.1',
      '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
      '--autoplay-policy=no-user-gesture-required', 'about:blank',
    ], { stdio: 'ignore' });
    const portFile = join(userDataDir, 'DevToolsActivePort');
    let wsPath = null;
    for (let i = 0; i < 200 && !wsPath; i++) {
      await new Promise((r) => setTimeout(r, 100));
      if (existsSync(portFile)) {
        const [port, path] = readFileSync(portFile, 'utf8').trim().split('\n');
        wsPath = `ws://127.0.0.1:${port}${path}`;
      }
    }
    if (!wsPath) { proc.kill(); throw new Error('Chrome did not start'); }
    const ws = new WebSocket(wsPath);
    await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });
    return new Browser(proc, new Connection(ws), userDataDir);
  }

  constructor(proc, conn, userDataDir) {
    this.proc = proc;
    this.conn = conn;
    this.userDataDir = userDataDir;
  }

  /** Opens a tab; `script` is injected in every frame before any page script. */
  async newPage(script) {
    const { targetId } = await this.conn.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await this.conn.send('Target.attachToTarget', { targetId, flatten: true });
    const page = new Page(this.conn, sessionId, targetId);
    await page.send('Page.enable');
    await page.send('Runtime.enable');
    if (script) await page.send('Page.addScriptToEvaluateOnNewDocument', { source: script });
    return page;
  }

  async close() {
    try { this.conn.ws.close(); } catch { /* already closed */ }
    this.proc.kill();
    await new Promise((r) => setTimeout(r, 300));
    try { rmSync(this.userDataDir, { recursive: true, force: true }); } catch { /* chrome may still hold files */ }
  }
}

/** Static test server: routes map path -> {status, headers, body}. */
export function startServer(routes) {
  const server = http.createServer((req, res) => {
    const path = req.url.split('?')[0];
    const route = routes[path];
    if (!route) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(route.status ?? 200, { 'content-type': 'text/html; charset=utf-8', ...(route.headers ?? {}) });
    res.end(route.body ?? '');
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, origin: `http://127.0.0.1:${server.address().port}` }));
  });
}
