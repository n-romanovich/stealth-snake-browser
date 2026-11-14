// Behavioural tests for snake_core.js, executed inside real Chrome (headless) via CDP.
// Run:  node --experimental-websocket --test test/js/snake.test.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Browser, startServer } from './lib/cdp.mjs';
import { buildScript, readBlocklistRules } from './lib/build.mjs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const SEED_A = '0123abcd';
const SEED_B = 'fedcba98';
const here = dirname(fileURLToPath(import.meta.url));
const CASES = JSON.parse(readFileSync(join(here, '..', '..', 'testdata', 'blocklist_cases.json'), 'utf8'));
const PNG_1X1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

let browser;
let main; // main test origin
let other; // second origin (cross-origin frames / tainting)
const hits = { sink: 0 };
const scriptA = buildScript(SEED_A);
const scriptB = buildScript(SEED_B);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  main = await startServer({
    '/blank': { body: '<!doctype html><meta charset=utf-8><title>t</title><body><div id=a style="font:15.37px serif;display:inline-block">Fractional width text sample</div><input id=i1><input id=i2></body>' },
    '/sink': { body: 'ok' },
    '/csp': {
      headers: { 'content-security-policy': "script-src 'self' 'unsafe-inline'" },
      body: '<!doctype html><body>csp</body>',
    },
    '/csp-worker': {
      headers: { 'content-security-policy': "script-src 'self' 'unsafe-inline'; worker-src 'self'" },
      body: '<!doctype html><body>csp-worker</body>',
    },
    '/w.js': {
      headers: { 'content-type': 'application/javascript' },
      body: 'postMessage({ ua: navigator.userAgent, tz: new Date().getTimezoneOffset(), cores: navigator.hardwareConcurrency });',
    },
    '/w-tz.js': {
      headers: { 'content-type': 'application/javascript' },
      body: 'postMessage({ h: new Date(Date.UTC(2023, 10, 14, 22, 13, 20)).getHours(), c: new Date(2024, 0, 1, 10, 30).toISOString(), tz: Intl.DateTimeFormat().resolvedOptions().timeZone, str: new Date(0).toString(), off: new Date().getTimezoneOffset() });',
    },
    '/child': { body: '<!doctype html><body>child</body>' },
  });
  // Count requests that reach the sink.
  main.server.on('request', (req) => { if (req.url.startsWith('/sink')) hits.sink++; });
  other = await startServer({
    '/child': { body: '<!doctype html><body>cross</body>' },
    '/img.png': { headers: { 'content-type': 'image/png' }, body: PNG_1X1 },
  });
  browser = await Browser.launch();
});

after(async () => {
  await browser?.close();
  main?.server.close();
  other?.server.close();
});

async function open(script, path = '/blank') {
  const page = await browser.newPage(script);
  await page.goto(`${main.origin}${path}`);
  return page;
}
const snake = (seed = SEED_A) => open(seed === SEED_A ? scriptA : scriptB);
const control = () => open(null);

// ------------------------------------------------------------------ shape helpers
const SHAPE_PROBE = `(() => {
  const gopd = Object.getOwnPropertyDescriptor;
  const fnShape = (fn) => ({
    name: fn.name, length: fn.length,
    own: Object.getOwnPropertyNames(fn).join(','),
    hasPrototype: Object.prototype.hasOwnProperty.call(fn, 'prototype'),
    str: Function.prototype.toString.call(fn),
    protoIsFunctionPrototype: Object.getPrototypeOf(fn) === Function.prototype,
    tag: Object.prototype.toString.call(fn),
    constructible: (() => { try { Reflect.construct(String, [], fn); return true; } catch (e) { return false; } })(),
  });
  const targets = [
    ['Document.prototype', 'hidden'], ['Document.prototype', 'visibilityState'],
    ['Document.prototype', 'webkitHidden'], ['Document.prototype', 'webkitVisibilityState'],
    ['Document.prototype', 'hasFocus'], ['Performance.prototype', 'now'],
    ['HTMLCanvasElement.prototype', 'toDataURL'], ['HTMLCanvasElement.prototype', 'toBlob'],
    ['CanvasRenderingContext2D.prototype', 'getImageData'],
    ['OffscreenCanvas.prototype', 'convertToBlob'],
    ['WebGLRenderingContext.prototype', 'readPixels'], ['WebGL2RenderingContext.prototype', 'readPixels'],
    ['AudioBuffer.prototype', 'getChannelData'], ['AudioBuffer.prototype', 'copyFromChannel'],
    ['AnalyserNode.prototype', 'getFloatFrequencyData'],
    ['Element.prototype', 'getBoundingClientRect'], ['Range.prototype', 'getBoundingClientRect'],
    ['MediaDevices.prototype', 'enumerateDevices'],
    ['Navigator.prototype', 'sendBeacon'], ['window', 'fetch'],
    ['EventTarget.prototype', 'addEventListener'], ['Function.prototype', 'toString'],
    ['HTMLIFrameElement.prototype', 'contentWindow'], ['HTMLIFrameElement.prototype', 'contentDocument'],
    ['Sensor.prototype', 'start'], ['Sensor.prototype', 'stop'], ['Sensor.prototype', 'activated'],
    ['Sensor.prototype', 'hasReading'], ['Sensor.prototype', 'timestamp'],
    ['Accelerometer.prototype', 'x'], ['Accelerometer.prototype', 'z'], ['Gyroscope.prototype', 'y'],
    ['RTCPeerConnection.prototype', 'setConfiguration'],
    ['window', 'innerWidth'], ['window', 'innerHeight'],
    ['window', 'outerWidth'], ['window', 'outerHeight'],
    ['VisualViewport.prototype', 'width'], ['VisualViewport.prototype', 'height'], ['VisualViewport.prototype', 'scale'],
    ['window', 'ondevicemotion'], ['window', 'ondeviceorientation'],
  ];
  const out = {};
  for (const [objExpr, key] of targets) {
    const obj = new Function('return ' + objExpr)();
    const d = obj && gopd(obj, key);
    if (!d) { out[objExpr + '.' + key] = null; continue; }
    out[objExpr + '.' + key] = {
      w: d.writable, e: d.enumerable, c: d.configurable,
      value: typeof d.value === 'function' ? fnShape(d.value) : typeof d.value,
      get: d.get ? fnShape(d.get) : undefined,
      set: d.set ? fnShape(d.set) : undefined,
    };
  }
  const pd = gopd(RTCPeerConnection, 'prototype');
  out.RTCPeerConnection = {
    ctor: fnShape(RTCPeerConnection),
    prototypeDesc: { w: pd.writable, e: pd.enumerable, c: pd.configurable, same: pd.value === RTCPeerConnection.prototype },
    constructorLink: RTCPeerConnection.prototype.constructor === RTCPeerConnection,
    statics: Object.getOwnPropertyNames(RTCPeerConnection).join(','),
    aliasSame: window.webkitRTCPeerConnection === window.RTCPeerConnection,
    generateCertificate: typeof RTCPeerConnection.generateCertificate,
  };
  if (typeof Accelerometer === 'function') {
    out.Accelerometer = {
      ctor: fnShape(Accelerometer),
      protoParentIsSensor: Object.getPrototypeOf(Accelerometer) === Sensor,
      constructorLink: Accelerometer.prototype.constructor === Accelerometer,
      statics: Object.getOwnPropertyNames(Accelerometer).join(','),
    };
  }
  out.docProtoKeys = Reflect.ownKeys(Document.prototype).map(String).join(',');
  out.navProtoKeys = Reflect.ownKeys(Navigator.prototype).map(String).join(',');
  out.perfProtoKeys = Reflect.ownKeys(Performance.prototype).map(String).join(',');
  out.windowStringKeys = Reflect.ownKeys(window).filter((k) => typeof k === 'string').join(',');
  out.windowSymbolCount = Reflect.ownKeys(window).filter((k) => typeof k === 'symbol').length;
  return JSON.stringify(out);
})()`;

// -------------------------------------------------------------------- 1. visibility
test('visibility and focus getters are locked', async () => {
  const p = await snake();
  const r = JSON.parse(await p.eval(`JSON.stringify({
    hidden: document.hidden, state: document.visibilityState,
    wh: document.webkitHidden, ws: document.webkitVisibilityState, focus: document.hasFocus() })`));
  assert.deepEqual(r, { hidden: false, state: 'visible', wh: false, ws: 'visible', focus: true });
});

test('trusted lifecycle events never reach page listeners while a control page sees them', async () => {
  const listenerSrc = `
    window.__ev = [];
    for (const t of ['visibilitychange','blur','focus','pagehide','freeze','resume'])
      for (const target of [window, document]) target.addEventListener(t, e => __ev.push(t), false);
    window.onblur = () => __ev.push('onblur');
    document.onvisibilitychange = () => __ev.push('onvisibilitychange');`;
  const s = await snake();
  const c = await control();
  await s.eval(listenerSrc);
  await c.eval(listenerSrc);
  const other = await open(null);
  await other.send('Page.bringToFront');
  await sleep(600);
  await s.send('Page.setWebLifecycleState', { state: 'frozen' });
  await sleep(300);
  await s.send('Page.setWebLifecycleState', { state: 'active' });
  await s.send('Page.bringToFront');
  await sleep(600);
  assert.deepEqual(JSON.parse(await s.eval('JSON.stringify(__ev)')), [], 'snake page must see nothing');
  assert.ok(JSON.parse(await c.eval('JSON.stringify(__ev)')).length > 0, 'control page must see events');
  await other.close();
});

test('page-synthesised lifecycle events are left alone', async () => {
  const s = await snake();
  const got = await s.eval(`new Promise((res) => {
    const seen = [];
    addEventListener('blur', () => seen.push('blur'));
    document.addEventListener('visibilitychange', () => seen.push('vis'));
    window.dispatchEvent(new Event('blur'));
    document.dispatchEvent(new Event('visibilitychange'));
    setTimeout(() => res(seen.join()), 50);
  })`);
  assert.equal(got, 'blur,vis');
});

test('ordinary element focus/blur inside the page keeps working', async () => {
  const s = await snake();
  await s.send('Page.bringToFront');
  await sleep(300);
  const r = await s.eval(`new Promise((res) => {
    const seen = [];
    const [a, b] = [i1, i2];
    a.addEventListener('focus', () => seen.push('a-focus'));
    a.addEventListener('blur', () => seen.push('a-blur'));
    b.addEventListener('focus', () => seen.push('b-focus'));
    a.focus(); b.focus();
    setTimeout(() => res(seen.join()), 100);
  })`);
  assert.equal(r, 'a-focus,a-blur,b-focus');
});

test('viewport dimensions stay locked and trusted resize/orientationchange events are suppressed', async () => {
  const s = await snake();
  const c = await control();
  const initial = JSON.parse(await s.eval(`JSON.stringify({
    iw: innerWidth, ih: innerHeight, vww: visualViewport.width, vwh: visualViewport.height
  })`));
  assert.ok(initial.iw > 0 && initial.ih > 0);
  const setup = `
    window.__resizeEv = [];
    window.addEventListener('resize', () => __resizeEv.push('win-resize'));
    window.addEventListener('orientationchange', () => __resizeEv.push('orient'));
    visualViewport.addEventListener('resize', () => __resizeEv.push('vv-resize'));
  `;
  await s.eval(setup);
  await c.eval(setup);
  for (const p of [s, c]) {
    await p.send('Emulation.setDeviceMetricsOverride', {
      width: 512, height: 320, deviceScaleFactor: 2, mobile: false,
    });
  }
  await sleep(200);
  const afterS = JSON.parse(await s.eval(`JSON.stringify({
    iw: innerWidth, ih: innerHeight, vww: visualViewport.width, vwh: visualViewport.height, ev: __resizeEv
  })`));
  const afterC = JSON.parse(await c.eval(`JSON.stringify({
    iw: innerWidth, ih: innerHeight, vww: visualViewport.width, vwh: visualViewport.height, ev: __resizeEv
  })`));
  assert.deepEqual(afterS, { ...initial, ev: [] }, 'snake viewport and resize listeners must not budge');
  assert.equal(afterC.iw, 512);
  assert.equal(afterC.ih, 320);
  assert.ok(afterC.ev.length > 0, 'control page must see resize events');
  // Page-synthesised resize must still pass through
  const synth = await s.eval(`(() => { window.dispatchEvent(new Event('resize')); return __resizeEv.join(); })()`);
  assert.equal(synth, 'win-resize');
});

// ---------------------------------------------------------------- 2. native shape
test('patched members are indistinguishable from native ones', async () => {
  const s = await snake();
  const c = await control();
  const a = JSON.parse(await s.eval(SHAPE_PROBE));
  const b = JSON.parse(await c.eval(SHAPE_PROBE));
  assert.equal(a.windowSymbolCount, b.windowSymbolCount + 1, 'only the install marker symbol is added');
  delete a.windowSymbolCount; delete b.windowSymbolCount;
  assert.deepEqual(a, b);
  assert.ok(a['Document.prototype.hidden'], 'probe must have found the targets');
});

test('toString of toString is native and unmasked functions still print their source', async () => {
  const s = await snake();
  const r = JSON.parse(await s.eval(`JSON.stringify({
    self: Function.prototype.toString.call(Function.prototype.toString),
    user: Function.prototype.toString.call(function foo(a) { return a; }),
    err: (() => { try { Function.prototype.toString.call({}); } catch (e) { return e.constructor === TypeError && e.message; } })(),
  })`));
  assert.equal(r.self, 'function toString() { [native code] }');
  assert.equal(r.user, 'function foo(a) { return a; }');
  assert.match(r.err, /Function\.prototype\.toString requires that 'this' be a Function/);
});

test('illegal-invocation errors match native message and stack depth', async () => {
  const probe = `(() => {
    const get = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden').get;
    function caller() { try { get.call({}); } catch (e) { return { m: e.message, n: e.name, lines: e.stack.split('\\n').length, stack: e.stack }; } }
    return JSON.stringify(caller());
  })()`;
  const a = JSON.parse(await (await snake()).eval(probe));
  const b = JSON.parse(await (await control()).eval(probe));
  assert.equal(a.m, b.m);
  assert.equal(a.n, b.n);
  assert.equal(a.lines, b.lines, `stack depth differs:\n${a.stack}\n--- vs ---\n${b.stack}`);
});

test('accessing hooked getters is cheap (no proxy-like overhead)', async () => {
  const p = await snake();
  const ms = await p.eval(`(() => { const t = Date.now(); for (let i = 0; i < 200000; i++) void document.hidden; return Date.now() - t; })()`);
  assert.ok(ms < 1500, `200k reads took ${ms}ms`);
});

// -------------------------------------------------------------------------- timing
test('performance.now is monotonic, fuzzy at 20us and keeps real time', async () => {
  const p = await snake();
  const r = JSON.parse(await p.eval(`(async () => {
    const xs = []; for (let i = 0; i < 20000; i++) xs.push(performance.now());
    let monotonic = true; for (let i = 1; i < xs.length; i++) if (xs[i] < xs[i - 1]) monotonic = false;
    const devs = xs.map((v) => Math.abs(v - Math.round(v / 0.02) * 0.02));
    const meanDev = devs.reduce((a, b) => a + b, 0) / devs.length;
    const t0 = performance.now(); await new Promise((r) => setTimeout(r, 200)); const dt = performance.now() - t0;
    return JSON.stringify({ monotonic, meanDev, dt });
  })()`));
  assert.ok(r.monotonic);
  assert.ok(r.meanDev > 0.0003 && r.meanDev < 0.01, `noise level ${r.meanDev}`);
  assert.ok(r.dt > 190 && r.dt < 400, `elapsed ${r.dt}`);
});

// -------------------------------------------------------------------------- canvas
const CANVAS_PROBE = `(() => {
  const c = document.createElement('canvas'); c.width = 220; c.height = 60;
  const x = c.getContext('2d');
  const g = x.createLinearGradient(0, 0, 220, 0); g.addColorStop(0, '#f60'); g.addColorStop(1, '#069');
  x.fillStyle = g; x.fillRect(0, 0, 220, 60);
  x.fillStyle = 'rgba(30,80,200,0.6)'; x.font = '20px serif'; x.fillText('Cwm fjordbank glyphs vext quiz', 4, 36);
  return c;
})()`;
const hashJs = `(b) => { let h = 2166136261; for (let i = 0; i < b.length; i++) { h ^= b[i]; h = Math.imul(h, 16777619); } return h >>> 0; }`;

test('canvas readbacks are stable in a session, noisy vs native, tiny, and differ across sessions', async () => {
  const expr = `(() => {
    const hash = ${hashJs};
    const c = ${CANVAS_PROBE};
    const x = c.getContext('2d');
    const d1 = x.getImageData(0, 0, 220, 60).data, d2 = x.getImageData(0, 0, 220, 60).data;
    return JSON.stringify({ h1: hash(d1), h2: hash(d2), url1: c.toDataURL(), url2: c.toDataURL(), px: Array.from(d1) });
  })()`;
  const a1 = JSON.parse(await (await snake(SEED_A)).eval(expr));
  const a2 = JSON.parse(await (await snake(SEED_A)).eval(expr));
  const b = JSON.parse(await (await snake(SEED_B)).eval(expr));
  const n = JSON.parse(await (await control()).eval(expr));
  assert.equal(a1.h1, a1.h2);
  assert.equal(a1.url1, a1.url2);
  assert.deepEqual(a1, a2, 'same seed + origin => same output (origin port differs? no: same server)');
  assert.notEqual(a1.h1, b.h1, 'different session seed => different hash');
  assert.notEqual(a1.h1, n.h1, 'noise must change the native hash');
  assert.notEqual(a1.url1, n.url1);
  let changed = 0;
  for (let i = 0; i < n.px.length; i++) {
    const d = Math.abs(a1.px[i] - n.px[i]);
    assert.ok(d <= 1, 'noise must be at most 1 LSB');
    if (d) changed++;
  }
  assert.ok(changed >= 20 && changed < n.px.length / 8, `changed ${changed} of ${n.px.length}`);
  for (let i = 3; i < n.px.length; i += 4) assert.equal(a1.px[i], n.px[i], 'alpha untouched');
});

test('toBlob matches toDataURL and OffscreenCanvas is noised consistently', async () => {
  const p = await snake();
  const r = JSON.parse(await p.eval(`(async () => {
    const c = ${CANVAS_PROBE};
    const blob = await new Promise((res) => c.toBlob(res, 'image/png'));
    const b64 = btoa(String.fromCharCode(...new Uint8Array(await blob.arrayBuffer())));
    const urlB64 = c.toDataURL().split(',')[1];
    const o = new OffscreenCanvas(40, 20); const ox = o.getContext('2d'); ox.fillStyle = '#3366cc'; ox.fillRect(0, 0, 40, 20);
    const o1 = btoa(String.fromCharCode(...new Uint8Array(await (await o.convertToBlob()).arrayBuffer())));
    const o2 = btoa(String.fromCharCode(...new Uint8Array(await (await o.convertToBlob()).arrayBuffer())));
    return JSON.stringify({ same: b64 === urlB64, o1eqo2: o1 === o2, olen: o1.length });
  })()`));
  assert.ok(r.same, 'toBlob and toDataURL of the same canvas must agree');
  assert.ok(r.o1eqo2 && r.olen > 50);
});

test('empty canvases stay empty and zero-size canvases behave natively', async () => {
  const run = (p) => p.eval(`(() => {
    const c = document.createElement('canvas'); c.width = 50; c.height = 50;
    const empty = c.toDataURL();
    const z = document.createElement('canvas'); z.width = 0; z.height = 0;
    let zero; try { zero = z.toDataURL(); } catch (e) { zero = e.name; }
    const d = c.getContext('2d').getImageData(0, 0, 50, 50).data;
    return JSON.stringify({ empty, zero, allZero: d.every((v) => v === 0) });
  })()`);
  assert.deepEqual(JSON.parse(await run(await snake())), JSON.parse(await run(await control())));
});

test('tainted canvases fail with the native error', async () => {
  const run = (p) => p.eval(`new Promise((res) => {
    const img = new Image(); img.onload = () => {
      const c = document.createElement('canvas'); c.width = 4; c.height = 4;
      c.getContext('2d').drawImage(img, 0, 0);
      const out = {};
      try { c.toDataURL(); out.url = 'no error'; } catch (e) { out.url = e.name + ':' + e.message; }
      try { c.getContext('2d').getImageData(0, 0, 1, 1); out.gid = 'no error'; } catch (e) { out.gid = e.name; }
      res(JSON.stringify(out));
    };
    img.src = '${other.origin}/img.png';
  })`);
  const a = JSON.parse(await run(await snake()));
  const b = JSON.parse(await run(await control()));
  assert.match(b.url, /SecurityError/);
  assert.deepEqual(a, b);
});

// --------------------------------------------------------------------------- webgl
const GL_PROBE = `(() => {
  const hash = ${hashJs};
  const c = document.createElement('canvas'); c.width = 64; c.height = 64;
  const gl = c.getContext('webgl', { preserveDrawingBuffer: true });
  if (!gl) return JSON.stringify({ unavailable: true });
  gl.clearColor(0.5, 0.25, 0.75, 1); gl.clear(gl.COLOR_BUFFER_BIT);
  gl.enable(gl.SCISSOR_TEST); gl.scissor(0, 0, 32, 64);
  gl.clearColor(0.25, 0.75, 0.5, 1); gl.clear(gl.COLOR_BUFFER_BIT);
  gl.disable(gl.SCISSOR_TEST);
  const read = () => { const b = new Uint8Array(64 * 64 * 4); gl.readPixels(0, 0, 64, 64, gl.RGBA, gl.UNSIGNED_BYTE, b); return b; };
  const r1 = read(), r2 = read();
  const sub = new Uint8Array(4 * 4 * 4); gl.readPixels(0, 0, 4, 4, gl.RGBA, gl.UNSIGNED_BYTE, sub);
  return JSON.stringify({ h1: hash(r1), h2: hash(r2), px: Array.from(r1), subLen: sub.length });
})()`;

test('WebGL readPixels is stable, 1-LSB noisy and never touches alpha', async () => {
  const a = JSON.parse(await (await snake(SEED_A)).eval(GL_PROBE));
  if (a.unavailable) return; // no WebGL in this environment
  const b = JSON.parse(await (await snake(SEED_B)).eval(GL_PROBE));
  const n = JSON.parse(await (await control()).eval(GL_PROBE));
  assert.equal(a.h1, a.h2);
  assert.notEqual(a.h1, n.h1);
  assert.notEqual(a.h1, b.h1);
  let changed = 0;
  for (let i = 0; i < n.px.length; i++) {
    const d = Math.abs(a.px[i] - n.px[i]);
    assert.ok(d <= 1);
    if (d) { changed++; assert.notEqual(i % 4, 3, 'alpha must not change'); }
  }
  assert.ok(changed > 20, `changed ${changed}`);
  assert.equal(a.subLen, 64);
});

// --------------------------------------------------------------------------- audio
const AUDIO_PROBE = `(async () => {
  const ctx = new OfflineAudioContext(1, 44100, 44100);
  const osc = ctx.createOscillator(); osc.type = 'triangle'; osc.frequency.value = 10000;
  const comp = ctx.createDynamicsCompressor();
  osc.connect(comp); comp.connect(ctx.destination); osc.start(0);
  const buf = await ctx.startRendering();
  const full = buf.getChannelData(0);
  const fullCopy = Array.from(full.slice(5000, 5100));
  const dest = new Float32Array(100); buf.copyFromChannel(dest, 0, 5000);
  const ctx2 = new OfflineAudioContext(1, 44100, 44100);
  const o2 = ctx2.createOscillator(); o2.type = 'triangle'; o2.frequency.value = 10000;
  const c2 = ctx2.createDynamicsCompressor(); o2.connect(c2); c2.connect(ctx2.destination); o2.start(0);
  const buf2 = await ctx2.startRendering();
  const early = new Float32Array(100); buf2.copyFromChannel(early, 0, 5000); // before getChannelData
  const late = buf2.getChannelData(0).slice(5000, 5100);
  return JSON.stringify({
    sameObject: buf.getChannelData(0) === full,
    slice: fullCopy, copy: Array.from(dest), early: Array.from(early), late: Array.from(late),
    sum: full.reduce((a, b) => a + Math.abs(b), 0),
  });
})()`;

test('WebAudio samples carry tiny deterministic noise consistent across read paths', async () => {
  const a = JSON.parse(await (await snake(SEED_A)).eval(AUDIO_PROBE));
  const a2 = JSON.parse(await (await snake(SEED_A)).eval(AUDIO_PROBE));
  const b = JSON.parse(await (await snake(SEED_B)).eval(AUDIO_PROBE));
  const n = JSON.parse(await (await control()).eval(AUDIO_PROBE));
  assert.ok(a.sameObject);
  assert.deepEqual(a.slice, a.copy, 'copyFromChannel after getChannelData must agree');
  assert.deepEqual(a.early, a.late, 'copyFromChannel before getChannelData must agree');
  assert.deepEqual(a, a2);
  assert.notDeepEqual(a.slice, b.slice);
  assert.notDeepEqual(a.slice, n.slice);
  let maxDiff = 0;
  for (let i = 0; i < 100; i++) maxDiff = Math.max(maxDiff, Math.abs(a.slice[i] - n.slice[i]));
  assert.ok(maxDiff <= 4e-7, `noise ${maxDiff}`);
  assert.ok(Math.abs(a.sum - n.sum) < 0.05, 'signal energy preserved');
});

// ---------------------------------------------------------------------------- rect
test('DOMRect jitter is sub-pixel, stable and keeps the native class', async () => {
  const run = (p) => p.eval(`(() => {
    const a = document.getElementById('a');
    const r1 = a.getBoundingClientRect(), r2 = a.getBoundingClientRect();
    const hidden = document.createElement('div'); hidden.style.display = 'none'; document.body.append(hidden);
    const h = hidden.getBoundingClientRect();
    const range = document.createRange(); range.selectNodeContents(a);
    return JSON.stringify({ w1: r1.width, h1: r1.height, w2: r2.width, x: r1.x, y: r1.y, isRect: r1 instanceof DOMRect,
      tag: Object.prototype.toString.call(r1), hz: [h.width, h.height], rangeW: range.getBoundingClientRect().width });
  })()`);
  const a = JSON.parse(await run(await snake()));
  const n = JSON.parse(await run(await control()));
  assert.equal(a.w1, a.w2);
  assert.ok(a.isRect);
  assert.equal(a.tag, '[object DOMRect]');
  assert.deepEqual(a.hz, [0, 0]);
  assert.notEqual(a.w1, n.w1);
  assert.ok(Math.abs(a.w1 - n.w1) < 0.002 && Math.abs(a.h1 - n.h1) < 0.002);
  assert.equal(a.x, n.x);
  assert.equal(a.y, n.y);
});

// ---------------------------------------------------------------------- media devices
test('enumerateDevices returns at most one entry per kind', async () => {
  const p = await snake();
  const kinds = JSON.parse(await p.eval(`navigator.mediaDevices.enumerateDevices().then((l) => JSON.stringify(l.map((d) => d.kind)))`));
  assert.equal(new Set(kinds).size, kinds.length);
});

// -------------------------------------------------------------------------- sensors
const SENSOR_PROBE = (cls) => `new Promise((res) => {
  if (typeof ${cls} !== 'function') return res(JSON.stringify({ unavailable: true }));
  const s = new ${cls}({ frequency: 30 });
  const out = { errors: 0, readings: 0, xs: [] };
  s.addEventListener('error', () => out.errors++);
  s.onerror = () => out.errors++;
  s.addEventListener('reading', () => { out.readings++; out.xs.push([s.x, s.y, s.z]); });
  s.start();
  setTimeout(() => {
    out.activated = s.activated; out.hasReading = s.hasReading; out.ts = typeof s.timestamp;
    s.stop(); out.activatedAfterStop = s.activated; res(JSON.stringify(out));
  }, 1500);
})`;

test('Accelerometer/Gyroscope fall back to plausible resting data when the OS denies sensors', async () => {
  const c = JSON.parse(await (await control()).eval(SENSOR_PROBE('Accelerometer')));
  if (c.unavailable) return;
  assert.ok(c.errors > 0 || c.readings === 0, 'control must be denied/unavailable for this test to be meaningful');
  const a = JSON.parse(await (await snake()).eval(SENSOR_PROBE('Accelerometer')));
  assert.equal(a.errors, 0, 'page must not observe the denial');
  assert.ok(a.readings > 5);
  assert.ok(a.activated && a.hasReading);
  assert.equal(a.activatedAfterStop, false);
  for (const [x, y, z] of a.xs) {
    assert.ok(Math.abs(x) < 0.05 && Math.abs(y) < 0.05 && Math.abs(z - 9.81) < 0.05);
  }
  const g = JSON.parse(await (await snake()).eval(SENSOR_PROBE('Gyroscope')));
  assert.equal(g.errors, 0);
  for (const [x, y, z] of g.xs) assert.ok(Math.abs(x) < 0.01 && Math.abs(y) < 0.01 && Math.abs(z) < 0.01);
});

test('devicemotion/deviceorientation listeners receive resting events when none arrive', async () => {
  const p = await snake();
  const r = JSON.parse(await p.eval(`new Promise((res) => {
    const out = { motion: 0, orient: 0, z: [], trusted: false };
    addEventListener('devicemotion', (e) => { out.motion++; out.z.push(e.accelerationIncludingGravity.z); out.trusted = e.isTrusted; });
    ondeviceorientation = () => out.orient++;
    setTimeout(() => res(JSON.stringify(out)), 2500);
  })`));
  assert.ok(r.motion > 5 && r.orient > 5, JSON.stringify(r));
  for (const z of r.z) assert.ok(Math.abs(z - 9.81) < 0.05);
});

// -------------------------------------------------------------------------- network
test('sendBeacon is accepted and dropped; argument validation stays native', async () => {
  const before = hits.sink;
  const s = await snake();
  const c = await control();
  const ret = await s.eval(`navigator.sendBeacon('${main.origin}/sink', 'x')`);
  const retC = await c.eval(`navigator.sendBeacon('${main.origin}/sink', 'x')`);
  await sleep(500);
  assert.equal(ret, true);
  assert.equal(retC, true);
  assert.equal(hits.sink - before, 1, 'only the control beacon may reach the network');
  const err = (p) => p.eval(`(() => { try { navigator.sendBeacon(); } catch (e) { return e.name + ':' + e.message; } })()`);
  assert.equal(await err(s), await err(c));
  const brand = (p) => p.eval(`(() => { try { navigator.sendBeacon.call({}, 'x'); } catch (e) { return e.name + ':' + e.message; } })()`);
  assert.equal(await brand(s), await brand(c));
});

test('keepalive fetches follow the shared blocklist vectors', async () => {
  const s = await snake();
  for (const { url, subresource, blocked } of CASES) {
    if (!subresource) continue;
    const r = await s.eval(`fetch(${JSON.stringify(url)}, { keepalive: true, method: 'GET' })
      .then(async (res) => 'resolved:' + res.status + ':' + (await res.text()).length, (e) => 'rejected:' + e.name)`);
    if (blocked) assert.equal(r, 'resolved:200:0', `${url} should be faked`);
    else assert.match(r, /^rejected:TypeError$/, `${url} must hit the (failing) network, got ${r}`);
  }
});

test('Request objects and non-keepalive fetches are handled correctly', async () => {
  const s = await snake();
  const viaRequest = await s.eval(`fetch(new Request('https://www.google-analytics.com/collect', { keepalive: true, method: 'POST', body: 'x' })).then((r) => r.status)`);
  assert.equal(viaRequest, 200);
  const plain = await s.eval(`fetch('https://www.google-analytics.com/collect').then(() => 'resolved', () => 'rejected')`);
  assert.equal(plain, 'rejected', 'JS layer only fakes keepalive requests; the native layer handles the rest');
  const before = hits.sink;
  const real = await s.eval(`fetch('${main.origin}/sink', { keepalive: true }).then((r) => r.text())`);
  assert.equal(real, 'ok');
  assert.equal(hits.sink - before, 1, 'non-blocked keepalive requests pass through');
  const badUrl = (p) => p.eval(`fetch('http://', { keepalive: true }).then(() => 'resolved', (e) => e.name)`);
  assert.equal(await badUrl(s), await badUrl(await control()));
});

// --------------------------------------------------------------------------- webrtc
const ICE_PROBE = `new Promise((res) => {
  const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.invalid:3478' }], iceTransportPolicy: 'all' });
  const cands = [];
  pc.onicecandidate = (e) => { if (e.candidate) cands.push(e.candidate.candidate); };
  pc.createDataChannel('x');
  pc.createOffer().then((o) => pc.setLocalDescription(o));
  const done = () => res(JSON.stringify({ cands, policy: pc.getConfiguration().iceTransportPolicy }));
  pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') done(); };
  setTimeout(done, 4000);
})`;

test('RTCPeerConnection never produces local candidates and keeps its native shape', async () => {
  const s = JSON.parse(await (await snake()).eval(ICE_PROBE));
  const c = JSON.parse(await (await control()).eval(ICE_PROBE));
  assert.ok(c.cands.length > 0, 'control must gather host candidates');
  assert.equal(c.policy, 'all');
  assert.equal(s.policy, 'relay');
  assert.equal(s.cands.length, 0);
});

test('RTCPeerConnection wrapper: setConfiguration, no-arg, no-new and instanceof', async () => {
  const p = await snake();
  const c = await control();
  const probe = `(() => {
    const out = {};
    const pc = new RTCPeerConnection();
    out.noArg = pc.getConfiguration().iceTransportPolicy;
    pc.setConfiguration({ iceTransportPolicy: 'all' });
    out.afterSet = pc.getConfiguration().iceTransportPolicy;
    out.instance = pc instanceof RTCPeerConnection && Object.getPrototypeOf(pc) === RTCPeerConnection.prototype;
    out.sub = new (class X extends RTCPeerConnection {})() instanceof RTCPeerConnection;
    try { RTCPeerConnection(); } catch (e) { out.noNew = e.name + ':' + e.message; }
    try { new RTCPeerConnection(5); } catch (e) { out.badArg = e.name; }
    return JSON.stringify(out);
  })()`;
  const a = JSON.parse(await p.eval(probe));
  const b = JSON.parse(await c.eval(probe));
  assert.equal(a.noArg, 'relay');
  assert.equal(a.afterSet, 'relay');
  assert.equal(a.noNew, b.noNew);
  assert.equal(a.badArg, b.badArg);
  assert.ok(a.instance && a.sub);
});

// ----------------------------------------------------------------------------- frames
const FRAME_SHAPE = `(w) => {
  const gopd = Object.getOwnPropertyDescriptor;
  const f = w.Function.prototype.toString;
  const g = gopd(w.Document.prototype, 'hidden').get;
  return JSON.stringify({
    hidden: w.document.hidden, state: w.document.visibilityState, focus: w.document.hasFocus(),
    getterStr: f.call(g), protoOk: Object.getPrototypeOf(g) === w.Function.prototype,
    nowStr: f.call(w.Performance.prototype.now), nowProto: Object.getPrototypeOf(w.Performance.prototype.now) === w.Function.prototype,
    selfStr: f.call(f),
    beacon: f.call(w.Navigator.prototype.sendBeacon),
  });
}`;

test('natively injected frames are patched and idempotent when the parent touches them', async () => {
  const p = await snake();
  const r = JSON.parse(await p.eval(`new Promise((res) => {
    const shape = ${FRAME_SHAPE};
    const f = document.createElement('iframe'); f.src = '/child'; f.onload = () => {
      const viaGetter = shape(f.contentWindow); // contentWindow hook runs, must not double-install harmfully
      res(JSON.stringify({ viaGetter, viaDoc: shape(f.contentDocument.defaultView) }));
    }; document.body.append(f);
  })`));
  const a = JSON.parse(r.viaGetter);
  assert.equal(a.hidden, false);
  assert.equal(a.state, 'visible');
  assert.equal(a.getterStr, 'function get hidden() { [native code] }');
  assert.ok(a.protoOk && a.nowProto);
  assert.equal(a.selfStr, 'function toString() { [native code] }');
  assert.deepEqual(JSON.parse(r.viaDoc), a);
});

test('fallback: script-created about:blank frames are patched through the contentWindow hook', async () => {
  const p = await open(null); // no native injection at all
  await p.eval(scriptA); // top frame only, like a late injection
  const r = JSON.parse(await p.eval(`(() => {
    const shape = ${FRAME_SHAPE};
    const f = document.createElement('iframe'); document.body.append(f);
    const w = f.contentWindow;
    const s = JSON.parse(shape(w));
    const again = JSON.parse(shape(f.contentWindow)); // second access must not reinstall
    return JSON.stringify({ s, same: JSON.stringify(s) === JSON.stringify(again) });
  })()`));
  assert.equal(r.s.getterStr, 'function get hidden() { [native code] }');
  assert.ok(r.s.protoOk && r.s.nowProto, 'child functions must belong to the child realm');
  assert.equal(r.s.selfStr, 'function toString() { [native code] }');
  assert.ok(r.same);
});

test('fallback degrades gracefully under a CSP that forbids eval, and for cross-origin frames', async () => {
  const p = await open(null, '/csp');
  await p.eval(scriptA);
  const r = JSON.parse(await p.eval(`new Promise((res) => {
    const a = document.createElement('iframe'); document.body.append(a);
    let aOk; try { aOk = !!a.contentWindow; } catch (e) { aOk = 'threw ' + e.name; }
    const b = document.createElement('iframe'); b.src = '${other.origin}/child';
    b.onload = () => { let bOk; try { bOk = !!b.contentWindow && b.contentDocument === null; } catch (e) { bOk = 'threw ' + e.name; } res(JSON.stringify({ aOk, bOk })); };
    document.body.append(b);
  })`));
  assert.deepEqual(r, { aOk: true, bOk: true });
});

test('every frame sees the same blocklist: keepalive fetch inside a child frame is faked', async () => {
  const p = await snake();
  const r = await p.eval(`new Promise((res) => {
    const f = document.createElement('iframe'); f.src = '/child';
    f.onload = () => f.contentWindow.fetch('https://sentry.io/x', { keepalive: true }).then((r) => res(r.status), (e) => res(e.name));
    document.body.append(f);
  })`);
  assert.equal(r, 200);
});

test('installation is idempotent per realm and exposes no globals', async () => {
  const p = await snake();
  const again = await p.eval(`(() => { const before = Reflect.ownKeys(window).length; return before; })()`);
  await p.eval(scriptA); // second install attempt in the same realm
  const after = await p.eval(`Reflect.ownKeys(window).length`);
  assert.equal(after, again);
  const toStr = await p.eval(`Function.prototype.toString.call(Object.getOwnPropertyDescriptor(Document.prototype, 'hidden').get)`);
  assert.equal(toStr, 'function get hidden() { [native code] }');
});

test('blocklist asset parses into rules the core understands', () => {
  const rules = readBlocklistRules();
  assert.ok(rules.length > 30);
  for (const r of rules) assert.match(r, /^(\|\|[a-z0-9.-]+|path:\/[a-z0-9._~\/-]*)$/);
  assert.equal(new Set(rules).size, rules.length, 'blocklist rules should not contain exact duplicates');
});

test('serviceWorker getter preserves enumerable descriptor and Navigator brand check', async () => {
  const p = await snake();
  const r = JSON.parse(await p.eval(`(() => {
    const d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'serviceWorker');
    let illegal = false;
    try { d.get.call({}); } catch (e) { illegal = e instanceof TypeError; }
    return JSON.stringify({ hasDesc: !!d, enumerable: d?.enumerable, val: navigator.serviceWorker, illegal });
  })()`));
  assert.equal(r.hasDesc, true);
  assert.equal(r.enumerable, true);
  assert.equal(r.val, undefined);
  assert.equal(r.illegal, true);
});

test('WebGL readPixels sub-regions match full readback and GL_VENDOR stays native', async () => {
  const p = await snake(SEED_A);
  const r = JSON.parse(await p.eval(`(() => {
    const c = document.createElement('canvas'); c.width = 32; c.height = 32;
    const gl = c.getContext('webgl', { preserveDrawingBuffer: true });
    if (!gl) return JSON.stringify({ unavailable: true });
    gl.clearColor(0.5, 0.25, 0.75, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.enable(gl.SCISSOR_TEST); gl.scissor(0, 0, 16, 32);
    gl.clearColor(0.25, 0.75, 0.5, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.SCISSOR_TEST);
    const full = new Uint8Array(32 * 32 * 4);
    gl.readPixels(0, 0, 32, 32, gl.RGBA, gl.UNSIGNED_BYTE, full);
    const sub = new Uint8Array(16 * 32 * 4);
    gl.readPixels(8, 0, 16, 32, gl.RGBA, gl.UNSIGNED_BYTE, sub);
    let mismatch = 0;
    for (let y = 0; y < 32; y++) {
      for (let x = 0; x < 16; x++) {
        for (let ch = 0; ch < 4; ch++) {
          const fi = (y * 32 + (8 + x)) * 4 + ch;
          const si = (y * 16 + x) * 4 + ch;
          if (full[fi] !== sub[si]) mismatch++;
        }
      }
    }
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return JSON.stringify({
      mismatch,
      vendor: gl.getParameter(gl.VENDOR),
      unmaskedVendor: ext ? gl.getParameter(ext.UNMASKED_VENDOR_WEBGL) : null,
    });
  })()`));
  if (r.unavailable) return;
  assert.equal(r.mismatch, 0);
  assert.notEqual(r.vendor, 'Google Inc. (ARM)');
  if (r.unmaskedVendor !== null) assert.equal(r.unmaskedVendor, 'Google Inc. (ARM)');
});

test('timezone spoofing is consistent across Date and Intl while honoring explicit timeZone and spoofTimezone=false', async () => {
  const s = await snake(SEED_A);
  const r = JSON.parse(await s.eval(`(() => {
    const d = new Date(1700000000000); // 2023-11-14T22:13:20.000Z
    const bad = new Date(NaN);
    const defaultIntl = new Intl.DateTimeFormat('en-GB', { hour12: false, dateStyle: 'short', timeStyle: 'medium' });
    const tokyoIntl = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Tokyo' });
    const bareIntl = Intl.DateTimeFormat();
    return JSON.stringify({
      offset: d.getTimezoneOffset(),
      badOffset: Number.isNaN(bad.getTimezoneOffset()),
      badTimeStr: bad.toTimeString(),
      timeStr: d.toTimeString(),
      dateStr: d.toDateString(),
      fullStr: d.toString(),
      localeStr: d.toLocaleString('en-GB', { hour12: false }),
      resolvedDefaultTz: defaultIntl.resolvedOptions().timeZone,
      resolvedBareTz: bareIntl.resolvedOptions().timeZone,
      resolvedTokyoTz: tokyoIntl.resolvedOptions().timeZone,
      formattedDefault: defaultIntl.format(d),
    });
  })()`));
  assert.equal(r.offset, 0);
  assert.equal(r.badOffset, true);
  assert.equal(r.badTimeStr, 'Invalid Date');
  assert.equal(r.timeStr, '22:13:20 GMT+0000 (Coordinated Universal Time)');
  assert.equal(r.dateStr, 'Tue Nov 14 2023');
  assert.equal(r.fullStr, 'Tue Nov 14 2023 22:13:20 GMT+0000 (Coordinated Universal Time)');
  assert.match(r.localeStr, /22:13:20/);
  assert.equal(r.resolvedDefaultTz, 'UTC');
  assert.equal(r.resolvedBareTz, 'UTC');
  assert.equal(r.resolvedTokyoTz, 'Asia/Tokyo');
  assert.match(r.formattedDefault, /22:13:20/);

  // Flag decoupling check: fuzzPerformanceNow=true with spoofTimezone=false
  const noTz = await open(buildScript(SEED_A, readBlocklistRules(), { fuzzPerformanceNow: true, spoofTimezone: false }));
  const dtfName = await noTz.eval(`Function.prototype.toString.call(Intl.DateTimeFormat)`);
  assert.match(dtfName, /\[native code\]/);
});

// Opens a page whose *real* device zone is Moscow (UTC+3, no DST) so zone leaks are observable on any host.
async function openInMoscow(script, path = '/blank') {
  const page = await browser.newPage(script);
  await page.send('Emulation.setTimezoneOverride', { timezoneId: 'Europe/Moscow' });
  await page.goto(`${main.origin}${path}`);
  return page;
}

test('local Date accessors, constructor and parse agree with the spoofed UTC zone', async () => {
  const control = JSON.parse(await (await openInMoscow(null)).eval(
    `JSON.stringify({ off: new Date().getTimezoneOffset(), h: new Date(Date.UTC(2023, 10, 14, 22, 13, 20)).getHours() })`));
  assert.equal(control.off, -180, 'precondition: the emulated real zone is UTC+3');
  assert.notEqual(control.h, 22, 'precondition: native getHours reflects the real zone');

  const page = await openInMoscow(scriptA);
  const r = JSON.parse(await page.eval(`(() => {
    const d = new Date(Date.UTC(2023, 10, 14, 22, 13, 20));
    const c = new Date(2024, 0, 1, 10, 30);
    const m = new Date(0); m.setHours(5);
    return JSON.stringify({
      offset: d.getTimezoneOffset(), hours: d.getHours(), minutes: d.getMinutes(), seconds: d.getSeconds(),
      date: d.getDate(), day: d.getDay(), month: d.getMonth(), year: d.getFullYear(),
      ctor: c.toISOString(), ctorHours: c.getHours(),
      parseLocal: new Date('2024-01-01T10:30:00').toISOString(),
      parseZoned: new Date('2024-01-01T10:30:00+03:00').toISOString(),
      parseDateOnly: new Date('2024-01-01').toISOString(),
      parseStatic: Date.parse('2024-01-01T10:30:00') === Date.UTC(2024, 0, 1, 10, 30),
      parseInvalid: Number.isNaN(Date.parse('definitely not a date')),
      setHours: m.toISOString(),
      callIsString: typeof Date(), ctorLink: Date.prototype.constructor === Date, inst: c instanceof Date,
      toStr: Function.prototype.toString.call(Date), len: Date.length, name: Date.name,
      sub: new (class X extends Date {})(2024, 0, 1).toISOString(),
      now: Math.abs(new Date().getTime() - Date.now()) < 1000,
    });
  })()`));
  assert.equal(r.offset, 0);
  assert.deepEqual([r.hours, r.minutes, r.seconds], [22, 13, 20]);
  assert.deepEqual([r.date, r.day, r.month, r.year], [14, 2, 10, 2023]);
  assert.equal(r.ctor, '2024-01-01T10:30:00.000Z');
  assert.equal(r.ctorHours, 10);
  assert.equal(r.parseLocal, '2024-01-01T10:30:00.000Z');
  assert.equal(r.parseZoned, '2024-01-01T07:30:00.000Z');
  assert.equal(r.parseDateOnly, '2024-01-01T00:00:00.000Z');
  assert.equal(r.parseStatic, true);
  assert.equal(r.parseInvalid, true);
  assert.equal(r.setHours, '1970-01-01T05:00:00.000Z');
  assert.equal(r.callIsString, 'string');
  assert.equal(r.ctorLink, true);
  assert.equal(r.inst, true);
  assert.equal(r.toStr, 'function Date() { [native code] }');
  assert.equal(r.len, 7);
  assert.equal(r.name, 'Date');
  assert.equal(r.sub, '2024-01-01T00:00:00.000Z');
  assert.equal(r.now, true);
});

test('workers see the same UTC-normalised Date/Intl as the page', async () => {
  const page = await openInMoscow(scriptA);
  const r = await page.eval(`new Promise((res, rej) => {
    const w = new Worker('/w-tz.js');
    w.onmessage = (e) => res(JSON.stringify(e.data));
    w.onerror = () => rej(new Error('worker failed'));
  })`);
  const w = JSON.parse(r);
  assert.equal(w.h, 22);
  assert.equal(w.c, '2024-01-01T10:30:00.000Z');
  assert.equal(w.tz, 'UTC');
  assert.equal(w.str, 'Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)');
  assert.equal(w.off, 0);
});

test('install marker does not leak the session seed', async () => {
  const p = await snake(SEED_A);
  const keys = JSON.parse(await p.eval(
    `JSON.stringify(Object.getOwnPropertySymbols(window).map((s) => Symbol.keyFor(s)).filter((k) => k !== undefined))`));
  assert.ok(keys.length >= 1, 'marker is still shared through the registry for idempotence');
  for (const k of keys) assert.ok(!k.includes(SEED_A), `registry key "${k}" must not embed the seed`);
});

test('JS-visible Chrome version follows the configured WebView version (matches the HTTP header)', async () => {
  const script = buildScript(SEED_A, readBlocklistRules(), undefined, { chromeMajor: 131, chromeFullVersion: '131.0.6778.135' });
  const page = await open(script);
  const r = JSON.parse(await page.eval(`(async () => {
    const he = await navigator.userAgentData.getHighEntropyValues(['uaFullVersion', 'fullVersionList']);
    return JSON.stringify({
      ua: navigator.userAgent, app: navigator.appVersion, brand: navigator.userAgentData.brands[0],
      full: he.uaFullVersion, list: he.fullVersionList[0],
    });
  })()`));
  assert.match(r.ua, /Chrome\/131\.0\.0\.0 /);
  assert.match(r.app, /Chrome\/131\.0\.0\.0 /);
  assert.deepEqual(r.brand, { brand: 'Chromium', version: '131' });
  assert.equal(r.full, '131.0.6778.135');
  assert.equal(r.list.version, '131.0.6778.135');
  const w = await page.eval(`new Promise((res) => { const w = new Worker('/w.js'); w.onmessage = (e) => res(e.data.ua); })`);
  assert.match(w, /Chrome\/131\.0\.0\.0 /);
  // Bogus config values fall back to the built-in default instead of producing a malformed UA.
  const bad = await open(buildScript(SEED_A, readBlocklistRules(), undefined, { chromeMajor: 'x', chromeFullVersion: '1.2' }));
  assert.match(await bad.eval(`navigator.userAgent`), /Chrome\/124\.0\.0\.0 /);
});

test('getClientRects returns DOMRectList, TextMetrics uses prototype getter, and canvas checks brand', async () => {
  const s = await snake(SEED_A);
  const r = JSON.parse(await s.eval(`(() => {
    const a = document.getElementById('a');
    const rects = a.getClientRects();
    const ctx = document.createElement('canvas').getContext('2d');
    const tm = ctx.measureText('hello');
    const ownWidthDesc = Object.getOwnPropertyDescriptor(tm, 'width');
    const protoWidthDesc = Object.getOwnPropertyDescriptor(TextMetrics.prototype, 'width');
    let canvasErr = '';
    try { HTMLCanvasElement.prototype.toDataURL.call({}); } catch (e) { canvasErr = e.name + ':' + e.message; }
    return JSON.stringify({
      isDOMRectList: rects instanceof DOMRectList,
      isArray: Array.isArray(rects),
      tag: Object.prototype.toString.call(rects),
      len: rects.length,
      item0IsRect: rects.item(0) instanceof DOMRect,
      itemOutIsNull: rects.item(-1) === null && rects.item(999) === null,
      spreadLen: [...rects].length,
      ownWidthDesc: !!ownWidthDesc,
      protoHasWidth: !!protoWidthDesc,
      tmWidthPositive: typeof tm.width === 'number' && tm.width > 0,
      canvasErr,
    });
  })()`));
  assert.equal(r.isDOMRectList, true);
  assert.equal(r.isArray, false);
  assert.equal(r.tag, '[object DOMRectList]');
  assert.ok(r.len >= 1 && r.spreadLen === r.len);
  assert.equal(r.item0IsRect, true);
  assert.equal(r.itemOutIsNull, true);
  assert.equal(r.ownWidthDesc, false);
  assert.equal(r.protoHasWidth, true);
  assert.equal(r.tmWidthPositive, true);
  assert.match(r.canvasErr, /TypeError:.*Illegal invocation/);
});

test('Worker supports URL objects, CSP worker-src self fallback, terminate cleanup, and blockWebSockets', async () => {
  const s = await snake(SEED_A);
  const workerMsg = JSON.parse(await s.eval(`new Promise((res, rej) => {
    const w = new Worker(new URL('/w.js', location.href));
    w.onmessage = (e) => { w.terminate(); res(JSON.stringify(e.data)); };
    w.onerror = (e) => rej(new Error(e.message || 'worker error'));
  })`));
  assert.ok(workerMsg.ua.includes('Android 10; K'));
  assert.equal(workerMsg.tz, 0);

  const cspPage = await open(scriptA, '/csp-worker');
  const cspOk = JSON.parse(await cspPage.eval(`new Promise((res, rej) => {
    const w = new Worker('/w.js');
    w.onmessage = (e) => { w.terminate(); res(JSON.stringify(e.data)); };
    w.onerror = (e) => rej(new Error(e.message || 'csp worker failed'));
  })`));
  assert.ok(cspOk && typeof cspOk.ua === 'string');

  const wsBlocked = await open(buildScript(SEED_A, readBlocklistRules(), { blockWebSockets: true }));
  const wsErr = await wsBlocked.eval(`(() => {
    try { new WebSocket('wss://example.com'); return ''; } catch (e) { return e.name; }
  })()`);
  assert.equal(wsErr, 'SecurityError');
});

test('CSP worker-src self blocks unpatched fallback when blockWebSockets is enabled', async () => {
  const cspWsBlocked = await open(buildScript(SEED_A, readBlocklistRules(), { blockWebSockets: true }), '/csp-worker');
  const firstResult = await cspWsBlocked.eval(`new Promise((res) => {
    const w = new Worker('/w.js');
    w.onmessage = () => res('ran-unpatched');
    w.onerror = () => res('errored');
  })`);
  assert.equal(firstResult, 'errored');
  const secondErr = await cspWsBlocked.eval(`(() => {
    try { new Worker('/w.js'); return ''; } catch (e) { return e.name; }
  })()`);
  assert.equal(secondErr, 'SecurityError');
});

test('Same-origin iframe is patched synchronously via window.frames and handles cross-realm Date, URL, and Request', async () => {
  const s = await snake(SEED_A);
  const res = JSON.parse(await s.eval(`(async () => {
    const f = document.createElement('iframe');
    document.body.appendChild(f);
    const frameWin = window.frames[0];
    const ua = frameWin.navigator.userAgent;
    const tzOffset = new frameWin.Date().getTimezoneOffset();
    const tzHours = new frameWin.Date(2024, 0, 1, 10, 30).getHours();
    const keepaliveStatus = await fetch(new frameWin.Request('https://www.google-analytics.com/collect', {
      keepalive: true, method: 'POST', body: 'x',
    })).then((r) => r.status);
    const workerData = await new Promise((resolve, reject) => {
      const w = new Worker(new frameWin.URL('/w.js', location.href));
      w.onmessage = (e) => { w.terminate(); resolve(e.data); };
      w.onerror = (e) => reject(new Error(e.message || 'worker error'));
    });
    return JSON.stringify({ ua, tzOffset, tzHours, keepaliveStatus, workerUa: workerData.ua });
  })()`));
  assert.ok(res.ua.includes('Android 10; K'));
  assert.equal(res.tzOffset, 0);
  assert.equal(res.tzHours, 10);
  assert.equal(res.keepaliveStatus, 200);
  assert.ok(res.workerUa.includes('Android 10; K'));
});
