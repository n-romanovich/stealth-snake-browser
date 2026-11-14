/*
 * Stealth Snake stealth core.
 *
 * Injected with WebViewCompat.addDocumentStartJavaScript() so it runs in every
 * frame before any page script. The Kotlin host replaces the CONFIG placeholder
 * below with {"seed": "<8 hex chars>", "blocklist": [...]}.
 *
 * Design rules:
 *  - Every replaced function/accessor keeps the native shape: same name, same
 *    length, same property attributes, no `prototype`, and a masked toString().
 *  - Nothing is exposed on the global object (only one non-enumerable marker
 *    symbol used to make installation idempotent per realm).
 *  - Intrinsics are captured once, before page code can tamper with them.
 *  - Noise is deterministic per (session seed, origin, input shape) so repeated
 *    reads inside a session agree with each other, while a new session differs.
 */
(function () {
  'use strict';

  /* Replaced by the Kotlin host. Must appear exactly once in this file. */
  const CONFIG = __SS_CONFIG_JSON__;

  function install(G, cfg) {
    'use strict';
    if (!G || typeof G !== 'object') return;

    const flags = (cfg && cfg.flags) || {};
    // All privacy hardening features default to enabled except blockWebSockets (VPN Kill-Switch opt-in).
    const on = (k) => (k === 'blockWebSockets' ? flags[k] === true : flags[k] !== false);

    // ---------------------------------------------------------------- intrinsics
    const O = G.Object || Object;
    const R = G.Reflect || Reflect;
    const apply = R.apply;
    const construct = R.construct;
    const defineProperty = O.defineProperty;
    const gOPD = O.getOwnPropertyDescriptor;
    const gOPDs = O.getOwnPropertyDescriptors;
    const getProto = O.getPrototypeOf;
    const setProto = O.setPrototypeOf;
    const hasOwn = O.prototype.hasOwnProperty;
    const ownKeys = R.ownKeys;
    const isView = ArrayBuffer.isView;
    const objToString = O.prototype.toString;
    // Realm-independent typed-array / WebIDL tag checks: `instanceof` fails across same-origin iframes.
    const typedTag = (v) => (isView(v) ? apply(objToString, v, []).slice(8, -1) : '');
    const isTag = (v, tag) => v != null && typeof v === 'object' && apply(objToString, v, []) === '[object ' + tag + ']';
    const { floor, round, sqrt, log, cos, imul, PI } = Math;
    const wmGet = WeakMap.prototype.get;
    const wmSet = WeakMap.prototype.set;
    const wmHas = WeakMap.prototype.has;
    const wmDelete = WeakMap.prototype.delete;
    const wsAdd = typeof WeakSet === 'function' ? WeakSet.prototype.add : null;
    const wsHas = typeof WeakSet === 'function' ? WeakSet.prototype.has : null;
    const GFn = G.Function || Function;
    const gFnProto = GFn && GFn.prototype;
    const promiseThen = Promise.prototype.then;
    const captureStackTrace = Error.captureStackTrace;
    const origFnToString = (gFnProto && gFnProto.toString) || Function.prototype.toString;
    const setIntervalOrig = G.setInterval;
    const clearIntervalOrig = G.clearInterval;
    const setTimeoutOrig = G.setTimeout;
    const clearTimeoutOrig = G.clearTimeout;
    const ael = EventTarget.prototype.addEventListener;
    const dispatch = EventTarget.prototype.dispatchEvent;
    const stopImmediate = Event.prototype.stopImmediatePropagation;
    const origPerfNow = Performance.prototype.now;
    const origDocHasFocus = Document.prototype.hasFocus;
    const origUserAgentGetter = gOPD(Navigator.prototype, 'userAgent').get;
    const DOMRectC = G.DOMRect;
    const ResponseC = G.Response;
    const RequestC = G.Request;
    const EventC = G.Event;
    const URLC = G.URL;
    const DeviceMotionEventC = G.DeviceMotionEvent;
    const DeviceOrientationEventC = G.DeviceOrientationEvent;

    // Install exactly once per realm (the iframe fallback may race the native injection).
    // The marker must be shared between the native injection and the iframe fallback, so it lives in
    // the global symbol registry; it deliberately does NOT embed the session seed, otherwise a page
    // could read it back via Symbol.keyFor() and replay the per-session noise PRNG.
    const MARK = Symbol.for('ss.i');
    if (hasOwn.call(G, MARK)) return;
    defineProperty(G, MARK, { value: true });

    // ------------------------------------------------------------------- PRNGs
    function fnv1a(str) {
      let h = 0x811c9dc5;
      for (let i = 0; i < str.length; i++) {
        h ^= str.charCodeAt(i);
        h = imul(h, 0x01000193);
      }
      return h >>> 0;
    }
    function mix32(x) {
      x = imul(x ^ (x >>> 16), 0x85ebca6b);
      x = imul(x ^ (x >>> 13), 0xc2b2ae35);
      return (x ^ (x >>> 16)) >>> 0;
    }
    function mulberry32(a) {
      return function () {
        a = (a + 0x6d2b79f5) | 0;
        let t = imul(a ^ (a >>> 15), 1 | a);
        t = (t + imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
      };
    }
    function gaussian(rng) {
      // Box-Muller; 1 - rng() keeps the log argument in (0, 1].
      return sqrt(-2 * log(1 - rng())) * cos(2 * PI * rng());
    }

    let originString = 'null';
    try { originString = String(G.location.origin); } catch (e) { /* opaque origin */ }
    const siteSeed = fnv1a(cfg.seed + '|' + originString);
    const timingRng = mulberry32(mix32(siteSeed ^ 0x54494d45));
    const sensorRng = mulberry32(mix32(siteSeed ^ 0x53454e53));

    // ------------------------------------------------------ native-shape helpers
    const nativeText = new WeakMap();
    function maskAs(fn, name, keepProto) {
      if (!keepProto && gFnProto && getProto(fn) !== gFnProto) setProto(fn, gFnProto);
      apply(wmSet, nativeText, [fn, 'function ' + name + '() { [native code] }']);
      return fn;
    }
    /** Drop our frames from an error's stack so it looks like it came from the caller. */
    function trim(e, fn) {
      if (e instanceof Error && typeof captureStackTrace === 'function') captureStackTrace(e, fn);
      return e;
    }
    function fixLength(fn, length) {
      defineProperty(fn, 'length', { value: length });
    }

    const toStringReplacement = ({
      toString() {
        const text = apply(wmGet, nativeText, [this]);
        if (text !== undefined) return text;
        try {
          return apply(origFnToString, this, []);
        } catch (e) {
          throw trim(e, toStringReplacement);
        }
      },
    }).toString;
    maskAs(toStringReplacement, 'toString');
    {
      const targetFnProto = gFnProto || Function.prototype;
      const d = gOPD(targetFnProto, 'toString');
      if (d) {
        defineProperty(targetFnProto, 'toString', {
          value: toStringReplacement, writable: d.writable, enumerable: d.enumerable, configurable: d.configurable,
        });
      }
    }

    /** Replace a data-property method. handler(orig, self, args) => result. */
    function replaceMethod(target, key, handler) {
      const d = gOPD(target, key);
      if (!d || typeof d.value !== 'function') return false;
      const orig = d.value;
      const name = orig.name;
      const wrapper = ({
        [name](...args) {
          try {
            return handler(orig, this, args);
          } catch (e) {
            throw trim(e, wrapper);
          }
        },
      })[name];
      fixLength(wrapper, orig.length);
      maskAs(wrapper, name);
      defineProperty(target, key, { value: wrapper, writable: d.writable, enumerable: d.enumerable, configurable: d.configurable });
      return true;
    }

    /** Replace an accessor getter. valueFn(origGet, self) => value. */
    function replaceGetter(target, key, valueFn) {
      const d = gOPD(target, key);
      if (!d || typeof d.get !== 'function') return false;
      const origGet = d.get;
      const getter = gOPD({
        get [key]() {
          try {
            return valueFn(origGet, this);
          } catch (e) {
            throw trim(e, getter);
          }
        },
      }, key).get;
      maskAs(getter, 'get ' + key);
      defineProperty(target, key, { get: getter, set: d.set, enumerable: d.enumerable, configurable: d.configurable });
      return true;
    }

    /** Replace an accessor setter. onSet(origSet, self, value) => void. */
    function replaceSetter(target, key, onSet) {
      const d = gOPD(target, key);
      if (!d || typeof d.set !== 'function') return false;
      const origSet = d.set;
      const setter = gOPD({
        set [key](v) {
          try {
            onSet(origSet, this, v);
          } catch (e) {
            throw trim(e, setter);
          }
        },
      }, key).set;
      maskAs(setter, 'set ' + key);
      defineProperty(target, key, { get: d.get, set: setter, enumerable: d.enumerable, configurable: d.configurable });
      return true;
    }

    /** Throws the native "Illegal invocation" TypeError unless `self` is a Navigator. */
    function navigatorBrandCheck(self) {
      apply(origUserAgentGetter, self, []);
    }

    function wrapConstructor(name, Orig, mapArgs, onConstructed) {
      const W = ({
        [name]: function (...args) {
          if (!new.target) return apply(Orig, this, args); // native "use the new operator" error
          try {
            const inst = construct(Orig, mapArgs ? mapArgs(args) : args, new.target);
            if (onConstructed) onConstructed(inst);
            return inst;
          } catch (e) {
            throw trim(e, W);
          }
        },
      })[name];
      fixLength(W, Orig.length);
      defineProperty(W, 'prototype', { value: Orig.prototype, writable: false });
      defineProperty(Orig.prototype, 'constructor', { value: W });
      setProto(W, getProto(Orig));
      const statics = gOPDs(Orig);
      for (const key of ownKeys(statics)) {
        if (key !== 'prototype' && key !== 'length' && key !== 'name') defineProperty(W, key, statics[key]);
      }
      maskAs(W, name, true);
      return W;
    }

    // ------------------------------------------------ 1. visibility / focus lock
    if (on('hideVisibilityFocus')) {
      replaceGetter(Document.prototype, 'hidden', (g, self) => { apply(g, self, []); return false; });
      replaceGetter(Document.prototype, 'visibilityState', (g, self) => { apply(g, self, []); return 'visible'; });
      replaceGetter(Document.prototype, 'webkitHidden', (g, self) => { apply(g, self, []); return false; });
      replaceGetter(Document.prototype, 'webkitVisibilityState', (g, self) => { apply(g, self, []); return 'visible'; });
      replaceMethod(Document.prototype, 'hasFocus', (orig, self) => { apply(orig, self, []); return true; });
    }

    const realHasFocus = () => apply(origDocHasFocus, G.document, []);
    let isTopFrame = false;
    try { isTopFrame = G.top === G; } catch (e) { /* cross-origin top: treat as nested frame */ }

    const ALWAYS_HIDDEN = new Set();
    if (on('hideVisibilityFocus')) {
      ALWAYS_HIDDEN.add('visibilitychange');
      ALWAYS_HIDDEN.add('webkitvisibilitychange');
      ALWAYS_HIDDEN.add('pagehide');
      ALWAYS_HIDDEN.add('freeze');
      ALWAYS_HIDDEN.add('resume');
    }
    if (on('lockViewport')) {
      ALWAYS_HIDDEN.add('resize');
      ALWAYS_HIDDEN.add('orientationchange');
    }
    function lifecycleSuppressor(event) {
      if (!event.isTrusted) return; // page-synthesised events are the page's own business
      const type = event.type;
      const target = event.target;
      const doc = G.document;
      let hide = false;
      if (ALWAYS_HIDDEN.has(type)) {
        hide = true;
      } else if (on('hideVisibilityFocus') && (type === 'blur' || type === 'focus')) {
        hide = target === G || target === doc || (isTopFrame && !realHasFocus());
      } else if (on('hideVisibilityFocus') && (type === 'focusin' || type === 'focusout')) {
        hide = isTopFrame && !realHasFocus();
      } else if (on('hideVisibilityFocus') && (type === 'mouseleave' || type === 'mouseout')) {
        hide = (target === G || target === doc || target === doc.documentElement) && event.relatedTarget === null;
      }
      if (hide) apply(stopImmediate, event, []);
    }
    // Capture listeners on window run before any listener the page can register.
    for (const t of ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze', 'resume',
      'resize', 'orientationchange',
      'blur', 'focus', 'focusin', 'focusout', 'mouseleave', 'mouseout']) {
      apply(ael, G, [t, lifecycleSuppressor, true]);
    }
    if (on('lockViewport')) {
      if (G.visualViewport) {
        apply(ael, G.visualViewport, ['resize', (e) => { if (e.isTrusted) apply(stopImmediate, e, []); }, true]);
      }
      if (G.screen && G.screen.orientation) {
        apply(ael, G.screen.orientation, ['change', (e) => { if (e.isTrusted) apply(stopImmediate, e, []); }, true]);
      }

      // Lock viewport dimensions & normalize screen/DPR after <meta name="viewport"> can settle.
      const latchCallbacks = [];
      const canLatchViewport = () => G.document && G.document.readyState !== 'loading';
      function lockPositiveGetter(target, key, waitForReady) {
        let locked = 0;
        const capture = (g, self) => {
          const v = apply(g, self, []);
          if (locked <= 0 && v > 0 && (!waitForReady || canLatchViewport())) locked = v;
          return locked > 0 ? locked : v;
        };
        if (waitForReady) {
          const d = gOPD(target, key);
          if (d && typeof d.get === 'function') {
            const origGet = d.get;
            latchCallbacks.push(() => {
              try {
                const self = target === G ? G : G.visualViewport;
                if (self && locked <= 0 && canLatchViewport()) {
                  const v = apply(origGet, self, []);
                  if (v > 0) locked = v;
                }
              } catch (e) { /* ignore */ }
            });
          }
        }
        replaceGetter(target, key, capture);
      }
      for (const prop of ['innerWidth', 'innerHeight', 'outerWidth', 'outerHeight']) {
        lockPositiveGetter(G, prop, true);
      }
      if (typeof G.VisualViewport === 'function') {
        lockPositiveGetter(G.VisualViewport.prototype, 'width', true);
        lockPositiveGetter(G.VisualViewport.prototype, 'height', true);
        lockPositiveGetter(G.VisualViewport.prototype, 'scale', true);
      }
      if (latchCallbacks.length > 0 && G.document) {
        apply(ael, G.document, ['DOMContentLoaded', () => {
          for (const fn of latchCallbacks) fn();
        }, true]);
      }
      replaceGetter(G, 'devicePixelRatio', () => 2);
      if (typeof G.Screen === 'function') {
        let lockedW = 0;
        let lockedH = 0;
        replaceGetter(G.Screen.prototype, 'width', (g, self) => {
          const v = apply(g, self, []);
          if (lockedW <= 0 && v > 0) lockedW = v;
          return lockedW > 0 ? lockedW : v;
        });
        replaceGetter(G.Screen.prototype, 'height', (g, self) => {
          const v = apply(g, self, []);
          if (lockedH <= 0 && v > 0) lockedH = v;
          return lockedH > 0 ? lockedH : v;
        });
        replaceGetter(G.Screen.prototype, 'availWidth', (g, self) => {
          const v = apply(g, self, []);
          if (lockedW <= 0 && G.screen) lockedW = G.screen.width || v;
          return lockedW > 0 ? lockedW : v;
        });
        replaceGetter(G.Screen.prototype, 'availHeight', (g, self) => {
          const v = apply(g, self, []);
          if (lockedH <= 0 && G.screen) lockedH = G.screen.height || v;
          return lockedH > 0 ? lockedH : v;
        });
        replaceGetter(G.Screen.prototype, 'colorDepth', () => 24);
        replaceGetter(G.Screen.prototype, 'pixelDepth', () => 24);
      }
    }

    // ----------------------------------------- 1b. Navigator & environment leaks
    // The Kotlin host passes the installed WebView's Chrome version so the JS-visible identity always
    // matches the HTTP User-Agent header (a header/JS mismatch is a trivial fingerprint).
    const CHROME_MAJOR = (cfg && Number.isInteger(cfg.chromeMajor) && cfg.chromeMajor >= 100 && cfg.chromeMajor < 1000)
      ? cfg.chromeMajor : 124;
    const CHROME_VERSION = CHROME_MAJOR + '.0.0.0';
    const CHROME_FULL_VERSION = (cfg && typeof cfg.chromeFullVersion === 'string'
      && /^\d+\.\d+\.\d+\.\d+$/.test(cfg.chromeFullVersion)
      && cfg.chromeFullVersion.split('.')[0] === String(CHROME_MAJOR))
      ? cfg.chromeFullVersion
      : (CHROME_MAJOR === 124 ? '124.0.6367.82' : CHROME_VERSION);
    const UNIFIED_UA = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + CHROME_VERSION + ' Mobile Safari/537.36';
    const UNIFIED_APP_VERSION = '5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + CHROME_VERSION + ' Mobile Safari/537.36';
    const UA_BRANDS = O.freeze([
      O.freeze({ brand: 'Chromium', version: String(CHROME_MAJOR) }),
      O.freeze({ brand: 'Not-A.Brand', version: '99' }),
    ]);
    const SPOOFED_CORES = (siteSeed & 1) === 0 ? 4 : 8;
    const SPOOFED_MEMORY = (siteSeed & 2) === 0 ? 4 : 8;

    replaceGetter(Navigator.prototype, 'userAgent', (g, self) => { apply(g, self, []); return UNIFIED_UA; });
    replaceGetter(Navigator.prototype, 'appVersion', (g, self) => { apply(g, self, []); return UNIFIED_APP_VERSION; });
    replaceGetter(Navigator.prototype, 'platform', (g, self) => { apply(g, self, []); return 'Linux armv8l'; });
    if (gOPD(Navigator.prototype, 'hardwareConcurrency')) {
      replaceGetter(Navigator.prototype, 'hardwareConcurrency', (g, self) => { apply(g, self, []); return SPOOFED_CORES; });
    }
    if (gOPD(Navigator.prototype, 'deviceMemory')) {
      replaceGetter(Navigator.prototype, 'deviceMemory', (g, self) => { apply(g, self, []); return SPOOFED_MEMORY; });
    }
    if (gOPD(Navigator.prototype, 'connection')) {
      const fakeConn = O.freeze({
        effectiveType: '4g',
        rtt: 50,
        downlink: 10,
        saveData: false,
        addEventListener: maskAs(() => {}, 'addEventListener'),
        removeEventListener: maskAs(() => {}, 'removeEventListener'),
      });
      replaceGetter(Navigator.prototype, 'connection', (g, self) => { apply(g, self, []); return fakeConn; });
    }
    if (gOPD(Navigator.prototype, 'userAgentData')) {
      const brands = UA_BRANDS;
      const fullList = O.freeze([
        O.freeze({ brand: 'Chromium', version: CHROME_FULL_VERSION }),
        O.freeze({ brand: 'Not-A.Brand', version: '99.0.0.0' }),
      ]);
      const getHighEntropyValues = maskAs(async (hints) => {
        const res = { brands, mobile: true, platform: 'Android' };
        if (Array.isArray(hints)) {
          for (const h of hints) {
            if (h === 'platformVersion') res.platformVersion = '14.0.0';
            else if (h === 'architecture') res.architecture = 'arm';
            else if (h === 'bitness') res.bitness = '64';
            else if (h === 'model') res.model = 'K';
            else if (h === 'uaFullVersion') res.uaFullVersion = CHROME_FULL_VERSION;
            else if (h === 'fullVersionList') res.fullVersionList = fullList;
            else if (h === 'wow64') res.wow64 = false;
          }
        }
        return res;
      }, 'getHighEntropyValues');
      const fakeUAData = O.freeze({
        brands,
        mobile: true,
        platform: 'Android',
        getHighEntropyValues,
        toJSON: maskAs(() => ({ brands, mobile: true, platform: 'Android' }), 'toJSON'),
      });
      replaceGetter(Navigator.prototype, 'userAgentData', (g, self) => { apply(g, self, []); return fakeUAData; });
    }
    if (gOPD(Navigator.prototype, 'serviceWorker')) {
      replaceGetter(Navigator.prototype, 'serviceWorker', (g, self) => {
        apply(g, self, []);
        return undefined;
      });
    }

    // ------------------------------------------------------------ 2. fuzzy timing
    if (on('fuzzPerformanceNow')) {
      const PerfC = G.Performance || Performance;
      if (PerfC && PerfC.prototype) {
        let lastNow = 0;
        replaceMethod(PerfC.prototype, 'now', (orig, self) => {
          const t = apply(orig, self, []);
          const quantised = round(t / 0.02) * 0.02; // 20 microseconds
          const rawDelta = gaussian(timingRng) * 0.003; // sigma = 3 microseconds
          const clampedDelta = rawDelta > 0.009 ? 0.009 : rawDelta < -0.009 ? -0.009 : rawDelta;
          let v = quantised + clampedDelta;
          if (v < lastNow) v = lastNow; // the clock must stay monotonic
          lastNow = v;
          return v;
        });
      }

      const GDateForNow = G.Date || Date;
      if (GDateForNow && typeof GDateForNow.now === 'function') {
        const origDateNow = GDateForNow.now;
        let lastDateNow = 0;
        const fuzzedDateNow = maskAs(function now() {
          const t = apply(origDateNow, GDateForNow, []);
          const q = round(t / 2) * 2;
          if (q < lastDateNow) return lastDateNow;
          lastDateNow = q;
          return q;
        }, 'now');
        defineProperty(GDateForNow, 'now', { value: fuzzedDateNow, writable: true, enumerable: false, configurable: true });
      }

      const GEvent = G.Event || Event;
      if (GEvent && GEvent.prototype) {
        replaceGetter(GEvent.prototype, 'timeStamp', (g, self) => {
          const t = apply(g, self, []);
          return round(t / 0.02) * 0.02;
        });
      }

      if (typeof G.PerformanceEntry === 'function') {
        for (const key of ['startTime', 'duration']) {
          replaceGetter(G.PerformanceEntry.prototype, key, (g, self) => {
            const v = apply(g, self, []);
            return typeof v === 'number' && v > 0 ? round(v / 0.02) * 0.02 : v;
          });
        }
      }

      if (typeof G.requestAnimationFrame === 'function') {
        replaceMethod(G, 'requestAnimationFrame', (orig, self, args) => {
          const cb = args[0];
          if (typeof cb !== 'function') return apply(orig, self, args);
          return apply(orig, self, [(ts) => cb(round(ts / 0.02) * 0.02)]);
        });
      }
    }

    // ------------------------------------------------------------ 2b. timezone normalization
    if (on('spoofTimezone')) {
      const OrigDate = G.Date || Date;
      const origDateProto = OrigDate.prototype;
      const origGetTzOffset = origDateProto.getTimezoneOffset;
      const origDateParse = OrigDate.parse;
      const origDateUTC = OrigDate.UTC;
      const origGetTime = origDateProto.getTime;
      const origGetUTCDay = origDateProto.getUTCDay;
      const origGetUTCMonth = origDateProto.getUTCMonth;
      const origGetUTCDate = origDateProto.getUTCDate;
      const origGetUTCFullYear = origDateProto.getUTCFullYear;
      const origGetUTCHours = origDateProto.getUTCHours;
      const origGetUTCMinutes = origDateProto.getUTCMinutes;
      const origGetUTCSeconds = origDateProto.getUTCSeconds;
      const TZ_DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
      const TZ_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      const pad2 = (n) => (n < 10 ? '0' + n : String(n));
      const pad4 = (n) => {
        const neg = n < 0;
        let s = String(neg ? -n : n);
        while (s.length < 4) s = '0' + s;
        return neg ? '-' + s : s;
      };
      const utcDateStr = (d) =>
        TZ_DAYS[apply(origGetUTCDay, d, [])] + ' ' +
        TZ_MONTHS[apply(origGetUTCMonth, d, [])] + ' ' +
        pad2(apply(origGetUTCDate, d, [])) + ' ' +
        pad4(apply(origGetUTCFullYear, d, []));
      const utcTimeStr = (d) =>
        pad2(apply(origGetUTCHours, d, [])) + ':' +
        pad2(apply(origGetUTCMinutes, d, [])) + ':' +
        pad2(apply(origGetUTCSeconds, d, [])) + ' GMT+0000 (Coordinated Universal Time)';

      replaceMethod(origDateProto, 'getTimezoneOffset', (orig, self) => {
        const r = apply(orig, self, []);
        return Number.isNaN(r) ? r : 0;
      });

      // Local-time accessors/mutators must agree with the spoofed UTC offset; otherwise
      // getHours() vs getTimezoneOffset() trivially exposes the real zone.
      const LOCAL_TO_UTC = {
        getFullYear: 'getUTCFullYear', getMonth: 'getUTCMonth', getDate: 'getUTCDate', getDay: 'getUTCDay',
        getHours: 'getUTCHours', getMinutes: 'getUTCMinutes', getSeconds: 'getUTCSeconds',
        getMilliseconds: 'getUTCMilliseconds',
        setFullYear: 'setUTCFullYear', setMonth: 'setUTCMonth', setDate: 'setUTCDate', setHours: 'setUTCHours',
        setMinutes: 'setUTCMinutes', setSeconds: 'setUTCSeconds', setMilliseconds: 'setUTCMilliseconds',
      };
      for (const key of O.keys(LOCAL_TO_UTC)) {
        const utcFn = origDateProto[LOCAL_TO_UTC[key]];
        replaceMethod(origDateProto, key, (orig, self, args) => apply(utcFn, self, args));
      }
      replaceMethod(origDateProto, 'getYear', (orig, self) => apply(origGetUTCFullYear, self, []) - 1900);

      // Date components / zone-less date strings are *input* in local time. Re-interpret them as UTC so
      // `new Date(2024, 0, 1).getDate()` still round-trips now that the getters above read UTC fields.
      const DATE_ONLY_ISO = /^\s*[+-]?\d{4,6}(?:-\d{2}(?:-\d{2})?)?\s*$/;
      const STRING_HAS_ZONE = /(?:[zZ]|[+-]\d{2}(?::?\d{2})?|\b(?:GMT|UTC|UT|[ECMP][SD]T)\b)\s*(?:\([^)]*\))?\s*$/;
      const spoofParse = (s) => {
        const t = apply(origDateParse, OrigDate, [s]);
        if (Number.isNaN(t) || DATE_ONLY_ISO.test(s) || STRING_HAS_ZONE.test(s)) return t;
        // Parsed as real-local time: shift by the real offset so the same wall-clock reads back in UTC.
        return t - apply(origGetTzOffset, new OrigDate(t), []) * 60000;
      };
      replaceMethod(OrigDate, 'parse', (orig, self, args) => {
        const a = args[0];
        return spoofParse(typeof a === 'string' ? a : String(a));
      });
      if (typeof OrigDate === 'function' && G.Date === OrigDate) {
        const DateProxy = new Proxy(OrigDate, {
          construct(target, args, newTarget) {
            let a = args;
            if (args.length >= 2) {
              a = [apply(origDateUTC, OrigDate, args)];
            } else if (args.length === 1 && typeof args[0] === 'string') {
              const t = spoofParse(args[0]);
              if (!Number.isNaN(t)) a = [t];
            }
            return construct(target, a, newTarget);
          },
          apply() {
            // Date() called as a function returns the current time as a string.
            return apply(origDateProto.toString, new OrigDate(), []);
          },
        });
        maskAs(DateProxy, 'Date');
        const dDesc = gOPD(G, 'Date');
        if (dDesc) defineProperty(G, 'Date', { ...dDesc, value: DateProxy });
        defineProperty(origDateProto, 'constructor', {
          value: DateProxy, writable: true, enumerable: false, configurable: true,
        });
      }
      replaceMethod(origDateProto, 'toDateString', (orig, self) => {
        const t = apply(origGetTime, self, []);
        if (Number.isNaN(t)) return apply(orig, self, []);
        return utcDateStr(self);
      });
      replaceMethod(origDateProto, 'toTimeString', (orig, self) => {
        const t = apply(origGetTime, self, []);
        if (Number.isNaN(t)) return apply(orig, self, []);
        return utcTimeStr(self);
      });
      replaceMethod(origDateProto, 'toString', (orig, self) => {
        const t = apply(origGetTime, self, []);
        if (Number.isNaN(t)) return apply(orig, self, []);
        return utcDateStr(self) + ' ' + utcTimeStr(self);
      });
      for (const key of ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString']) {
        replaceMethod(origDateProto, key, (orig, self, args) => {
          const t = apply(origGetTime, self, []);
          if (Number.isNaN(t)) return apply(orig, self, args);
          const opts = args[1];
          const isObj = opts !== null && typeof opts === 'object';
          if (isObj && opts.timeZone !== undefined) return apply(orig, self, args);
          const nextOpts = opts === undefined ? { timeZone: 'UTC' } : (isObj ? O.assign({}, opts, { timeZone: 'UTC' }) : opts);
          return apply(orig, self, [args[0], nextOpts]);
        });
      }

      if (G.Intl && typeof G.Intl.DateTimeFormat === 'function') {
        const OrigDTF = G.Intl.DateTimeFormat;
        const tzExplicitInstances = new WeakSet();
        const WrappedDTF = ({
          DateTimeFormat: function (locales, options) {
            try {
              const isObj = options !== null && typeof options === 'object';
              const hasExplicitTz = isObj && options.timeZone !== undefined;
              const effectiveOpts = options === undefined
                ? { timeZone: 'UTC' }
                : (isObj && !hasExplicitTz ? O.assign({}, options, { timeZone: 'UTC' }) : options);
              const inst = new.target
                ? construct(OrigDTF, [locales, effectiveOpts], new.target)
                : apply(OrigDTF, this, [locales, effectiveOpts]);
              if (hasExplicitTz && wsAdd) apply(wsAdd, tzExplicitInstances, [inst]);
              return inst;
            } catch (e) {
              throw trim(e, WrappedDTF);
            }
          },
        }).DateTimeFormat;
        fixLength(WrappedDTF, OrigDTF.length);
        defineProperty(WrappedDTF, 'prototype', { value: OrigDTF.prototype, writable: false });
        defineProperty(OrigDTF.prototype, 'constructor', { value: WrappedDTF });
        setProto(WrappedDTF, getProto(OrigDTF));
        const dtfStatics = gOPDs(OrigDTF);
        for (const k of ownKeys(dtfStatics)) {
          if (k !== 'prototype' && k !== 'length' && k !== 'name') defineProperty(WrappedDTF, k, dtfStatics[k]);
        }
        maskAs(WrappedDTF, 'DateTimeFormat', true);
        const dtfDesc = gOPD(G.Intl, 'DateTimeFormat');
        if (dtfDesc) defineProperty(G.Intl, 'DateTimeFormat', { ...dtfDesc, value: WrappedDTF });

        replaceMethod(OrigDTF.prototype, 'resolvedOptions', (orig, self, args) => {
          const opts = apply(orig, self, args);
          const explicit = wsHas && apply(wsHas, tzExplicitInstances, [self]);
          if (opts && typeof opts === 'object' && !explicit) opts.timeZone = 'UTC';
          return opts;
        });
      }
    }

    // -------------------------------------------- 3. deterministic pixel / sample noise
    if (on('noiseCanvasWebgl')) {
      /**
       * Check if all opaque pixels share the exact same colour (solid-colour fill).
       * Fingerprinters often draw a solid rectangle to detect canvas noise.
       */
      function isSolidImage(bytes, start, pixelCount, channels) {
        let refR = -1, refG = 0, refB = 0, refA = 0;
        for (let p = 0; p < pixelCount; p++) {
          const base = start + p * channels;
          const a = channels === 4 ? bytes[base + 3] : 255;
          if (a === 0) continue;
          if (refR === -1) {
            refR = bytes[base]; refG = bytes[base + 1]; refB = bytes[base + 2]; refA = a;
          } else if (bytes[base] !== refR || bytes[base + 1] !== refG || bytes[base + 2] !== refB || a !== refA) {
            return false;
          }
        }
        return true;
      }

      /**
       * Coordinate-keyed +-1 pixel noise so sub-region getImageData(sx, sy, sw, sh)
       * matches full-canvas toDataURL() / toBlob() identically at every (x, y).
       */
      function noisePixels(bytes, start, width, height, channels, originX, originY) {
        const pixelCount = width * height;
        if (pixelCount <= 0 || isSolidImage(bytes, start, pixelCount, channels)) return;
        for (let py = 0; py < height; py++) {
          const gy = originY + py;
          const rowHash = imul(gy + 1, 0x85ebca6b);
          for (let px = 0; px < width; px++) {
            const gx = originX + px;
            const h = mix32(siteSeed ^ imul(gx + 1, 0x9e3779b1) ^ rowHash);
            if ((h & 31) !== 0) continue;
            const base = start + (py * width + px) * channels;
            if (channels === 4 && bytes[base + 3] === 0) continue;
            const c = ((h >>> 5) & 3) % 3;
            const delta = (h & 0x80) !== 0 ? -1 : 1;
            const idx = base + c;
            const next = bytes[idx] + delta;
            bytes[idx] = next < 0 || next > 255 ? bytes[idx] - delta : next;
          }
        }
      }

      const origGetImageData2D = CanvasRenderingContext2D.prototype.getImageData;
      const origPutImageData2D = CanvasRenderingContext2D.prototype.putImageData;
      const origDrawImage2D = CanvasRenderingContext2D.prototype.drawImage;
      const origCreateElement = Document.prototype.createElement;
      const origGetContext = HTMLCanvasElement.prototype.getContext;
      const canvasWidthDesc = gOPD(HTMLCanvasElement.prototype, 'width');
      const origCanvasWidthGet = canvasWidthDesc && canvasWidthDesc.get;

      function noiseImageData(imageData, sx, sy) {
        noisePixels(imageData.data, 0, imageData.width, imageData.height, 4, sx | 0, sy | 0);
        return imageData;
      }

      /** Returns a noised 2D copy of a canvas-like source, or null when it is empty. */
      function noisedCopyHtml(canvas) {
        const w = origCanvasWidthGet ? apply(origCanvasWidthGet, canvas, []) : canvas.width;
        const h = canvas.height;
        if (w === 0 || h === 0) return null;
        const copy = apply(origCreateElement, G.document, ['canvas']);
        copy.width = w;
        copy.height = h;
        const ctx = apply(origGetContext, copy, ['2d']);
        apply(origDrawImage2D, ctx, [canvas, 0, 0]);
        const data = apply(origGetImageData2D, ctx, [0, 0, w, h]);
        noiseImageData(data, 0, 0);
        apply(origPutImageData2D, ctx, [data, 0, 0]);
        return copy;
      }

      function withNoisedHtmlCanvas(orig, self, args) {
        if (origCanvasWidthGet) apply(origCanvasWidthGet, self, []);
        let copy;
        try {
          copy = noisedCopyHtml(self);
        } catch (e) {
          // Tainted canvases must fail exactly like the native call does.
          if (e && e.name === 'SecurityError') return apply(orig, self, args);
          throw e;
        }
        return apply(orig, copy === null ? self : copy, args);
      }

      replaceMethod(CanvasRenderingContext2D.prototype, 'getImageData', (orig, self, args) =>
        noiseImageData(apply(orig, self, args), args[0], args[1]));
      if (typeof OffscreenCanvasRenderingContext2D === 'function') {
        replaceMethod(OffscreenCanvasRenderingContext2D.prototype, 'getImageData', (orig, self, args) =>
          noiseImageData(apply(orig, self, args), args[0], args[1]));
      }
      replaceMethod(HTMLCanvasElement.prototype, 'toDataURL', withNoisedHtmlCanvas);
      replaceMethod(HTMLCanvasElement.prototype, 'toBlob', withNoisedHtmlCanvas);

      // TextMetrics.width: slight deterministic perturbation keyed on siteSeed + text.
      if (typeof TextMetrics === 'function' && CanvasRenderingContext2D.prototype.measureText) {
        const noisedTextWidths = new WeakMap();
        replaceMethod(CanvasRenderingContext2D.prototype, 'measureText', (orig, self, args) => {
          const metrics = apply(orig, self, args);
          const text = args.length > 0 ? String(args[0]) : '';
          if (text.length === 0 || !metrics) return metrics;
          let h = siteSeed ^ imul(text.length, 0x9e3779b1);
          for (let i = 0; i < Math.min(text.length, 32); i++) {
            h = mix32(h ^ imul(text.charCodeAt(i), i + 1));
          }
          const delta = ((h & 0xffff) / 65535 - 0.5) * 0.002;
          apply(wmSet, noisedTextWidths, [metrics, delta]);
          return metrics;
        });
        replaceGetter(TextMetrics.prototype, 'width', (g, self) => {
          const w = apply(g, self, []);
          const delta = apply(wmGet, noisedTextWidths, [self]);
          return delta !== undefined ? w + delta : w;
        });
      }

      if (typeof OffscreenCanvas === 'function') {
        const OffCtxP = typeof OffscreenCanvasRenderingContext2D === 'function' ? OffscreenCanvasRenderingContext2D.prototype : null;
        const origOffGetContext = OffscreenCanvas.prototype.getContext;
        const origOffGetImageData = OffCtxP && OffCtxP.getImageData;
        const origOffPutImageData = OffCtxP && OffCtxP.putImageData;
        const origOffDrawImage = OffCtxP && OffCtxP.drawImage;
        replaceMethod(OffscreenCanvas.prototype, 'convertToBlob', (orig, self, args) => {
          const w = self.width;
          const h = self.height;
          if (w === 0 || h === 0 || !origOffGetImageData) return apply(orig, self, args);
          let copy;
          try {
            copy = new OffscreenCanvas(w, h);
            const ctx = apply(origOffGetContext, copy, ['2d']);
            apply(origOffDrawImage, ctx, [self, 0, 0]);
            const data = apply(origOffGetImageData, ctx, [0, 0, w, h]);
            noiseImageData(data, 0, 0);
            apply(origOffPutImageData, ctx, [data, 0, 0]);
          } catch (e) {
            if (e && e.name === 'SecurityError') return apply(orig, self, args);
            throw e;
          }
          return apply(orig, copy, args);
        });
      }

      // WebGL readPixels (UNSIGNED_BYTE RGBA / RGB) + WEBGL_debug_renderer_info spoofing.
      const GL_RGBA = 0x1908;
      const GL_RGB = 0x1907;
      const GL_VENDOR = 0x1F00;
      const GL_RENDERER = 0x1F01;
      const UNMASKED_VENDOR_WEBGL = 0x9245;
      const UNMASKED_RENDERER_WEBGL = 0x9246;
      function patchWebGL(proto) {
        if (!proto) return;
        replaceMethod(proto, 'readPixels', (orig, self, args) => {
          const result = apply(orig, self, args);
          const pixels = args[6];
          const pixelTag = typedTag(pixels);
          if (pixelTag === 'Uint8Array' || pixelTag === 'Uint8ClampedArray') {
            const format = args[4];
            const channels = format === GL_RGBA ? 4 : format === GL_RGB ? 3 : 0;
            if (channels !== 0) {
              const x = args[0] | 0;
              const y = args[1] | 0;
              const w = args[2] | 0;
              const h = args[3] | 0;
              const offset = (args[7] >>> 0) || 0;
              const available = floor((pixels.length - offset) / channels);
              const safeH = w > 0 ? Math.min(h, floor(available / w)) : 0;
              if (w > 0 && safeH > 0) noisePixels(pixels, offset, w, safeH, channels, (x + 0x57474c) | 0, y);
            }
          }
          return result;
        });
        replaceMethod(proto, 'getParameter', (orig, self, args) => {
          const pname = args[0];
          if (pname === UNMASKED_VENDOR_WEBGL) return 'Google Inc. (ARM)';
          if (pname === UNMASKED_RENDERER_WEBGL) return 'ANGLE (ARM, Mali-G715, OpenGL ES 3.2)';
          return apply(orig, self, args);
        });
      }
      if (typeof WebGLRenderingContext === 'function') patchWebGL(WebGLRenderingContext.prototype);
      if (typeof WebGL2RenderingContext === 'function') patchWebGL(WebGL2RenderingContext.prototype);
    }

    // WebAudio: noise is a pure function of (channel, sample index) so any slice is consistent.
    if (on('noiseAudio')) {
      const audioKey = mix32(siteSeed ^ 0x41554449);
      function sampleNoise(channel, index) {
        const h = mix32(audioKey ^ imul(index + 1, 0x9e3779b1) ^ imul(channel + 1, 0x85ebca6b));
        if ((h & 31) !== 0) return 0;
        return ((h >>> 5) & 0xffff) / 65535 * 2 - 1;
      }
      function noiseSamples(array, from, to, channel, amplitude, indexBase, quantize) {
        for (let i = from; i < to; i++) {
          if (quantize) array[i] = round(array[i] * 4194304) / 4194304;
          const n = sampleNoise(channel, indexBase + i - from);
          if (n !== 0) array[i] += n * amplitude;
        }
      }
      const noisedChannels = new WeakMap(); // AudioBuffer -> Set(channel) already noised in place
      function channelSet(buffer) {
        let s = apply(wmGet, noisedChannels, [buffer]);
        if (!s) { s = new Set(); apply(wmSet, noisedChannels, [buffer, s]); }
        return s;
      }
      replaceMethod(AudioBuffer.prototype, 'getChannelData', (orig, self, args) => {
        const data = apply(orig, self, args);
        const channel = args[0] >>> 0;
        const done = channelSet(self);
        if (!done.has(channel)) {
          done.add(channel);
          noiseSamples(data, 0, data.length, channel, 2e-7, 0, true);
        }
        return data;
      });
      replaceMethod(AudioBuffer.prototype, 'copyFromChannel', (orig, self, args) => {
        const result = apply(orig, self, args);
        const dest = args[0];
        const channel = args[1] >>> 0;
        const start = args[2] >>> 0;
        if (!channelSet(self).has(channel) && typedTag(dest) === 'Float32Array') {
          const length = Math.min(dest.length, self.length - start);
          noiseSamples(dest, 0, length, channel, 2e-7, start, true);
        }
        return result;
      });
      if (typeof AnalyserNode === 'function') {
        function isSilentUniform(arr) {
          if (!arr || arr.length === 0) return true;
          const first = arr[0];
          for (let i = 1; i < arr.length; i++) {
            if (arr[i] !== first) return false;
          }
          return true;
        }
        for (const key of ['getFloatFrequencyData', 'getFloatTimeDomainData']) {
          replaceMethod(AnalyserNode.prototype, key, (orig, self, args) => {
            const result = apply(orig, self, args);
            const arr = args[0];
            if (typedTag(arr) === 'Float32Array' && !isSilentUniform(arr)) {
              noiseSamples(arr, 0, arr.length, key.length, 1e-4, 0, false);
            }
            return result;
          });
        }
        for (const key of ['getByteFrequencyData', 'getByteTimeDomainData']) {
          replaceMethod(AnalyserNode.prototype, key, (orig, self, args) => {
            const result = apply(orig, self, args);
            const arr = args[0];
            if (typedTag(arr) === 'Uint8Array' && !isSilentUniform(arr)) {
              for (let i = 0; i < arr.length; i++) {
                const n = sampleNoise(key.length, i);
                if (n !== 0) {
                  const next = arr[i] + (n > 0 ? 1 : -1);
                  arr[i] = next < 0 ? 0 : next > 255 ? 255 : next;
                }
              }
            }
            return result;
          });
        }
      }
    }

    // DOMRect: sub-pixel jitter on width/height, stable for identical geometry.
    if (on('noiseDomRect')) {
      function jitterRect(r) {
        if (r.width === 0 && r.height === 0) return r;
        const k = mix32(siteSeed ^ imul(round(r.width * 64), 0x9e3779b1) ^ imul(round(r.height * 64), 0x85ebca6b));
        const dw = ((k & 0xffff) / 65535 - 0.5) * 0.002;
        const dh = (((k >>> 16) & 0xffff) / 65535 - 0.5) * 0.002;
        return new DOMRectC(r.x, r.y, r.width + dw, r.height + dh);
      }
      function jitterRectList(list) {
        const len = list.length;
        const rects = new Array(len);
        for (let i = 0; i < len; i++) rects[i] = jitterRect(list[i]);
        if (typeof G.DOMRectList !== 'function' || !G.DOMRectList.prototype) {
          rects.item = (idx) => (idx >= 0 && idx < len ? rects[idx] : null);
          return rects;
        }
        const out = O.create(G.DOMRectList.prototype);
        for (let i = 0; i < len; i++) {
          defineProperty(out, i, { value: rects[i], enumerable: true, configurable: true });
        }
        defineProperty(out, 'length', { get: maskAs(() => len, 'get length'), enumerable: false, configurable: true });
        defineProperty(out, 'item', {
          value: maskAs(function item(idx) {
            const n = idx | 0;
            return n >= 0 && n < len ? rects[n] : null;
          }, 'item'),
          enumerable: false,
          configurable: true,
        });
        if (typeof Symbol === 'function' && Symbol.iterator) {
          defineProperty(out, Symbol.iterator, {
            value: maskAs(function values() {
              let idx = 0;
              return { next: () => (idx < len ? { value: rects[idx++], done: false } : { value: undefined, done: true }) };
            }, 'values'),
            enumerable: false,
            configurable: true,
          });
        }
        return out;
      }
      replaceMethod(Element.prototype, 'getBoundingClientRect', (orig, self, args) => jitterRect(apply(orig, self, args)));
      replaceMethod(Range.prototype, 'getBoundingClientRect', (orig, self, args) => jitterRect(apply(orig, self, args)));
      replaceMethod(Element.prototype, 'getClientRects', (orig, self, args) => jitterRectList(apply(orig, self, args)));
      replaceMethod(Range.prototype, 'getClientRects', (orig, self, args) => jitterRectList(apply(orig, self, args)));
    }

    // Media devices: one entry per kind, hiding device counts.
    if (typeof MediaDevices === 'function') {
      replaceMethod(MediaDevices.prototype, 'enumerateDevices', (orig, self, args) =>
        apply(promiseThen, apply(orig, self, args), [(list) => {
          const seen = new Set();
          return list.filter((d) => (seen.has(d.kind) ? false : (seen.add(d.kind), true)));
        }]));
    }

    // ------------------------------------------------------------ 4. sensors
    if (on('sensorFallback')) {
    const STUB_ERRORS = new Set(['NotAllowedError', 'NotReadableError', 'SecurityError', 'NotSupportedError']);
    const SENSOR_KINDS = {
      Accelerometer: { vec: [0, 0, 9.81], jitter: 0.002 },
      LinearAccelerationSensor: { vec: [0, 0, 0], jitter: 0.002 },
      GravitySensor: { vec: [0, 0, 9.81], jitter: 0.0005 },
      Gyroscope: { vec: [0, 0, 0], jitter: 0.0005 },
      Magnetometer: { vec: [22.5, -4.2, -41.0], jitter: 0.05 },
      AbsoluteOrientationSensor: { quat: [0, 0, 0, 1], jitter: 0.0002 },
      RelativeOrientationSensor: { quat: [0, 0, 0, 1], jitter: 0.0002 },
      AmbientLightSensor: { lux: 120, jitter: 0.5 },
    };
    const SensorBase = typeof G.Sensor === 'function' ? G.Sensor : null;
    const stubs = new WeakMap(); // sensor -> { kind, timer, reading }
    const armed = new WeakMap();

    function kindOf(sensor) {
      for (const name of O.keys(SENSOR_KINDS)) {
        if (typeof G[name] === 'function' && sensor instanceof G[name]) return name;
      }
      return null;
    }
    function nextReading(kind) {
      const spec = SENSOR_KINDS[kind];
      const j = () => gaussian(sensorRng) * spec.jitter;
      if (spec.vec) return { x: spec.vec[0] + j(), y: spec.vec[1] + j(), z: spec.vec[2] + j() };
      if (spec.quat) return { quaternion: [spec.quat[0] + j(), spec.quat[1] + j(), spec.quat[2] + j(), spec.quat[3]] };
      return { illuminance: spec.lux + j() };
    }
    function beginStub(sensor) {
      if (apply(wmHas, stubs, [sensor])) return;
      const kind = kindOf(sensor);
      if (kind === null) return;
      const st = { kind, timer: 0, reading: nextReading(kind), timestamp: apply(origPerfNow, G.performance, []) };
      apply(wmSet, stubs, [sensor, st]);
      apply(setTimeoutOrig, G, [() => {
        if (apply(wmGet, stubs, [sensor]) !== st) return;
        apply(dispatch, sensor, [new EventC('activate')]);
        st.timer = apply(setIntervalOrig, G, [() => {
          st.reading = nextReading(kind);
          st.timestamp = apply(origPerfNow, G.performance, []);
          apply(dispatch, sensor, [new EventC('reading')]);
        }, 17]);
      }, 0]);
    }
    function endStub(sensor) {
      const st = apply(wmGet, stubs, [sensor]);
      if (st) {
        apply(clearIntervalOrig, G, [st.timer]);
        st.timer = 0;
        apply(wmDelete, stubs, [sensor]);
      }
    }
    function stubOf(sensor) {
      const st = apply(wmGet, stubs, [sensor]);
      return st && st.timer !== 0 ? st : undefined;
    }
    function onSensorError(event) {
      const err = event.error;
      if (!err || !STUB_ERRORS.has(err.name)) return;
      apply(stopImmediate, event, []);
      beginStub(event.target);
    }

    if (SensorBase) {
      const P = SensorBase.prototype;
      replaceMethod(P, 'start', (orig, self, args) => {
        if (!apply(wmHas, armed, [self])) {
          apply(wmSet, armed, [self, true]);
          apply(ael, self, ['error', onSensorError, true]);
        }
        try {
          return apply(orig, self, args);
        } catch (e) {
          if (e && STUB_ERRORS.has(e.name)) {
            beginStub(self);
            return;
          }
          throw e;
        }
      });
      replaceMethod(P, 'stop', (orig, self, args) => {
        const result = apply(orig, self, args);
        endStub(self);
        return result;
      });
      replaceGetter(P, 'activated', (g, self) => (stubOf(self) ? (apply(g, self, []), true) : apply(g, self, [])));
      replaceGetter(P, 'hasReading', (g, self) => (stubOf(self) ? (apply(g, self, []), true) : apply(g, self, [])));
      replaceGetter(P, 'timestamp', (g, self) => {
        const st = stubOf(self);
        const real = apply(g, self, []);
        return st ? st.timestamp : real;
      });
      for (const name of O.keys(SENSOR_KINDS)) {
        const C = G[name];
        if (typeof C !== 'function') continue;
        const d = gOPD(G, name);
        if (d) {
          const W = wrapConstructor(name, C, null, (inst) => {
            if (!apply(wmHas, armed, [inst])) {
              apply(wmSet, armed, [inst, true]);
              // Arm at construction time so this runs before any listener the page registers.
              apply(ael, inst, ['error', onSensorError, true]);
            }
          });
          defineProperty(G, name, { ...d, value: W });
        }
        const members = SENSOR_KINDS[name].vec ? ['x', 'y', 'z']
          : SENSOR_KINDS[name].quat ? ['quaternion'] : ['illuminance'];
        for (const m of members) {
          replaceGetter(C.prototype, m, (g, self) => {
            const st = stubOf(self);
            const real = apply(g, self, []);
            return st ? st.reading[m] : real;
          });
        }
      }
    }

    // devicemotion / deviceorientation: synthesise events when the OS delivers none.
    const motionWatch = {};
    function syncMotionTimer(type) {
      const st = motionWatch[type];
      if (!st) return;
      const hasActive = st.capture.size > 0 || st.bubble.size > 0 || st.hasProp;
      if (hasActive && st.timerId === 0) {
        st.timerId = apply(setIntervalOrig, G, [st.tick, 17]);
      } else if (!hasActive && st.timerId !== 0) {
        apply(clearIntervalOrig, G, [st.timerId]);
        st.timerId = 0;
      }
    }
    function ensureMotionWatch(type) {
      if (motionWatch[type]) return motionWatch[type];
      const ctor = type === 'devicemotion' ? DeviceMotionEventC : DeviceOrientationEventC;
      if (typeof ctor !== 'function') return null;
      const st = {
        lastReal: -Infinity,
        since: apply(origPerfNow, G.performance, []),
        capture: new Set(),
        bubble: new Set(),
        hasProp: false,
        timerId: 0,
        tick: () => {
          const now = apply(origPerfNow, G.performance, []);
          if (now - st.since < 1000 || now - st.lastReal < 1000) return;
          const j = (s) => gaussian(sensorRng) * s;
          const ev = type === 'devicemotion'
            ? new ctor(type, {
              acceleration: { x: j(0.002), y: j(0.002), z: j(0.002) },
              accelerationIncludingGravity: { x: j(0.002), y: j(0.002), z: 9.81 + j(0.002) },
              rotationRate: { alpha: j(0.0005), beta: j(0.0005), gamma: j(0.0005) },
              interval: 16,
            })
            : new ctor(type, { alpha: 0, beta: j(0.01), gamma: j(0.01), absolute: false });
          apply(dispatch, G, [ev]);
        },
      };
      motionWatch[type] = st;
      apply(ael, G, [type, (e) => {
        if (!e.isTrusted) return;
        const hasReal = type === 'devicemotion'
          ? (e.accelerationIncludingGravity && e.accelerationIncludingGravity.z !== null)
          : (e.alpha !== null || e.beta !== null || e.gamma !== null);
        if (hasReal) {
          st.lastReal = apply(origPerfNow, G.performance, []);
        } else {
          // Drop Chromium's all-null dummy event when hardware sensors are absent/blocked.
          apply(stopImmediate, e, []);
        }
      }, true]);
      return st;
    }
    function isCaptureOpt(opt) {
      return typeof opt === 'boolean' ? opt : !!(opt && typeof opt === 'object' && opt.capture);
    }
    function isValidListener(fn) {
      return typeof fn === 'function' || (fn !== null && typeof fn === 'object' && typeof fn.handleEvent === 'function');
    }
    replaceMethod(EventTarget.prototype, 'addEventListener', (orig, self, args) => {
      // Bare addEventListener(...) passes undefined as `this`, which WebIDL coerces to window.
      if ((self === G || self == null) && (args[0] === 'devicemotion' || args[0] === 'deviceorientation') && isValidListener(args[1])) {
        const st = ensureMotionWatch(args[0]);
        if (st) {
          const type = args[0];
          const listener = args[1];
          const set = isCaptureOpt(args[2]) ? st.capture : st.bubble;
          set.add(listener);
          syncMotionTimer(type);
          const opts = args[2];
          const forget = () => { set.delete(listener); syncMotionTimer(type); };
          if (opts !== null && typeof opts === 'object') {
            // `{ once: true }` and `{ signal }` remove the listener natively without calling
            // removeEventListener; mirror that or the 17ms timer would run until pagehide.
            if (opts.once === true) apply(ael, G, [type, forget, { capture: !!opts.capture, once: true }]);
            const sig = opts.signal;
            if (sig && typeof sig === 'object' && typeof sig.addEventListener === 'function') {
              if (sig.aborted) forget();
              else apply(ael, sig, ['abort', forget, { once: true }]);
            }
          }
        }
      }
      return apply(orig, self, args);
    });
    replaceMethod(EventTarget.prototype, 'removeEventListener', (orig, self, args) => {
      if ((self === G || self == null) && (args[0] === 'devicemotion' || args[0] === 'deviceorientation') && motionWatch[args[0]]) {
        const st = motionWatch[args[0]];
        (isCaptureOpt(args[2]) ? st.capture : st.bubble).delete(args[1]);
        syncMotionTimer(args[0]);
      }
      return apply(orig, self, args);
    });
    for (const prop of ['ondevicemotion', 'ondeviceorientation']) {
      replaceSetter(G, prop, (set, self, v) => {
        const type = prop.slice(2);
        const st = typeof v === 'function' ? ensureMotionWatch(type) : motionWatch[type];
        if (st) {
          st.hasProp = typeof v === 'function';
          syncMotionTimer(type);
        }
        apply(set, self, [v]);
      });
    }
    apply(ael, G, ['pagehide', () => {
      for (const type of ['devicemotion', 'deviceorientation']) {
        const st = motionWatch[type];
        if (st && st.timerId !== 0) {
          apply(clearIntervalOrig, G, [st.timerId]);
          st.timerId = 0;
        }
      }
    }, true]);
    } // end sensorFallback

    // ------------------------------------------------------ 5. out-of-band network
    const hosts = [];
    const paths = [];
    for (const rule of cfg.blocklist) {
      if (rule.startsWith('||')) hosts.push(rule.slice(2).toLowerCase());
      else if (rule.startsWith('path:')) paths.push(rule.slice(5).toLowerCase());
    }
    function isBlocked(url, subresource) {
      if (!subresource) return false; // main-frame navigations are never blocked
      const host = url.hostname.toLowerCase().replace(/\.$/, '');
      for (const d of hosts) if (host === d || host.endsWith('.' + d)) return true;
      const path = url.pathname.toLowerCase();
      for (const p of paths) if (path === p || path.startsWith(p + '/')) return true;
      return false;
    }

    if (on('blockWebSockets') && typeof G.WebSocket === 'function') {
      const BlockedWS = wrapConstructor('WebSocket', G.WebSocket, () => {
        throw new DOMException('WebSocket blocked by VPN Kill-Switch', 'SecurityError');
      });
      const wsDesc = gOPD(G, 'WebSocket');
      if (wsDesc) defineProperty(G, 'WebSocket', { ...wsDesc, value: BlockedWS });
    }

    if (on('blockBeacons')) {
      replaceMethod(Navigator.prototype, 'sendBeacon', (orig, self, args) => {
        navigatorBrandCheck(self);
        if (args.length < 1) {
          throw new TypeError("Failed to execute 'sendBeacon' on 'Navigator': 1 argument required, but only 0 present.");
        }
        return true; // accepted and dropped
      });

      if (typeof G.fetch === 'function') {
        replaceMethod(G, 'fetch', (orig, self, args) => {
          const input = args[0];
          const init = args[1];
          let keepalive = false;
          let url = null;
          try {
            const isReq = input instanceof RequestC || isTag(input, 'Request');
            keepalive = (init != null && typeof init === 'object' && init.keepalive === true)
              || (isReq && input.keepalive === true);
            if (keepalive) url = new URLC(isReq ? input.url : String(input), G.location.href);
          } catch (e) {
            url = null; // let the native call report the malformed input
          }
          if (url !== null && isBlocked(url, true)) return Promise.resolve(new ResponseC('', { status: 200 }));
          return apply(orig, self, args);
        });
      }
    }

    // WebRTC: force relay-only ICE so host/server-reflexive candidates never exist.
    function forceRelay(config) {
      if (config === undefined || config === null) return { iceTransportPolicy: 'relay' };
      if (typeof config !== 'object') return config; // native code throws the proper TypeError
      return O.assign({}, config, { iceTransportPolicy: 'relay' });
    }
    if (on('blockWebRtcIce') && typeof G.RTCPeerConnection === 'function') {
      const Orig = G.RTCPeerConnection;
      const W = wrapConstructor('RTCPeerConnection', Orig, (args) => {
        const mapped = args.slice();
        mapped[0] = forceRelay(args[0]);
        return mapped;
      });
      replaceMethod(Orig.prototype, 'setConfiguration', (orig, self, args) => apply(orig, self, [forceRelay(args[0])]));
      for (const alias of ['RTCPeerConnection', 'webkitRTCPeerConnection']) {
        const d = gOPD(G, alias);
        if (d && d.value === Orig) defineProperty(G, alias, { ...d, value: W });
      }
    }

    // ------------------------------------------- 5b. Worker & SharedWorker protection
    if (typeof G.SharedWorker === 'function') {
      const BlockedSW = wrapConstructor('SharedWorker', G.SharedWorker, () => {
        throw new DOMException('SharedWorker disabled', 'SecurityError');
      });
      const swDesc = gOPD(G, 'SharedWorker');
      if (swDesc) defineProperty(G, 'SharedWorker', { ...swDesc, value: BlockedSW });
    }
    if (typeof G.Worker === 'function' && typeof G.Blob === 'function' && G.URL && typeof G.URL.createObjectURL === 'function') {
      const OrigWorker = G.Worker;
      const OrigBlob = G.Blob;
      const OrigMessageEvent = G.MessageEvent;
      const OrigErrorEvent = G.ErrorEvent;
      const createObjectURL = G.URL.createObjectURL.bind(G.URL);
      const revokeObjectURL = typeof G.URL.revokeObjectURL === 'function' ? G.URL.revokeObjectURL.bind(G.URL) : null;
      const workerBlobUrls = new WeakMap();
      const workerFallbacks = new WeakMap();
      const workerInitMsgs = new WeakMap();
      const rethrownErrors = typeof WeakSet === 'function' ? new WeakSet() : null;
      let blobWorkerForbiddenByCsp = false;

      if (G.document) {
        apply(ael, G.document, ['securitypolicyviolation', (e) => {
          const dir = String(e.effectiveDirective || e.violatedDirective || '').toLowerCase();
          const uri = String(e.blockedURI || '').toLowerCase();
          if (uri.startsWith('blob') && (dir.startsWith('worker-src') || dir.startsWith('child-src') || dir.startsWith('script-src'))) {
            blobWorkerForbiddenByCsp = true;
          }
        }, true]);
      }

      const revokeWorkerBlob = (inst) => {
        const u = apply(wmGet, workerBlobUrls, [inst]);
        if (u) {
          apply(wmDelete, workerBlobUrls, [inst]);
          if (revokeObjectURL) try { revokeObjectURL(u); } catch (e) { /* ignore */ }
        }
      };
      const origWorkerTerminate = OrigWorker.prototype && OrigWorker.prototype.terminate;
      const origWorkerPostMessage = OrigWorker.prototype && OrigWorker.prototype.postMessage;
      if (origWorkerTerminate) {
        replaceMethod(OrigWorker.prototype, 'terminate', (orig, self, args) => {
          revokeWorkerBlob(self);
          const fb = apply(wmGet, workerFallbacks, [self]);
          return apply(orig, fb || self, args);
        });
      }
      if (origWorkerPostMessage) {
        replaceMethod(OrigWorker.prototype, 'postMessage', (orig, self, args) => {
          const fb = apply(wmGet, workerFallbacks, [self]);
          if (fb) return apply(orig, fb, args);
          const pending = apply(wmGet, workerInitMsgs, [self]);
          if (pending && (!args[1] || (Array.isArray(args[1]) && args[1].length === 0))) {
            pending.push(args.slice());
          }
          return apply(orig, self, args);
        });
      }

      function attachFallbackBridge(inst, scriptUrl, opts) {
        const pending = [];
        let settled = false;
        apply(wmSet, workerInitMsgs, [inst, pending]);
        const settle = () => {
          if (settled) return;
          settled = true;
          apply(wmDelete, workerInitMsgs, [inst]);
        };
        apply(ael, inst, ['message', settle, true]);
        apply(setTimeoutOrig, G, [settle, 2000]);
        apply(ael, inst, ['error', (errEv) => {
          if (settled || (rethrownErrors && wsHas && apply(wsHas, rethrownErrors, [errEv]))) return;
          if (!errEv.isTrusted) return;
          settled = true;
          const queuedMsgs = pending.slice();
          apply(wmDelete, workerInitMsgs, [inst]);
          apply(stopImmediate, errEv, []);
          if (typeof errEv.preventDefault === 'function') errEv.preventDefault();
          apply(setTimeoutOrig, G, [() => {
            if (!blobWorkerForbiddenByCsp) {
              const ev = typeof OrigErrorEvent === 'function'
                ? new OrigErrorEvent('error', { message: errEv.message, filename: errEv.filename, lineno: errEv.lineno, colno: errEv.colno, error: errEv.error })
                : new EventC('error');
              if (rethrownErrors && wsAdd) apply(wsAdd, rethrownErrors, [ev]);
              apply(dispatch, inst, [ev]);
              return;
            }
            revokeWorkerBlob(inst);
            if (origWorkerTerminate) {
              try { apply(origWorkerTerminate, inst, []); } catch (ignored) { /* ignore */ }
            }
            // Under VPN Kill-Switch, never silently fall back to an unpatched worker that would retain
            // raw WebSocket access.
            if (on('blockWebSockets')) {
              const ev = typeof OrigErrorEvent === 'function'
                ? new OrigErrorEvent('error', { message: 'Worker blocked while WebSocket Kill-Switch is active' })
                : new EventC('error');
              if (rethrownErrors && wsAdd) apply(wsAdd, rethrownErrors, [ev]);
              apply(dispatch, inst, [ev]);
              return;
            }
            try {
              const fb = construct(OrigWorker, opts !== undefined ? [scriptUrl, opts] : [scriptUrl]);
              apply(wmSet, workerFallbacks, [inst, fb]);
              apply(ael, fb, ['message', (m) => {
                if (typeof OrigMessageEvent === 'function') {
                  apply(dispatch, inst, [new OrigMessageEvent('message', {
                    data: m.data,
                    origin: m.origin,
                    lastEventId: m.lastEventId,
                    ports: m.ports,
                  })]);
                }
              }, true]);
              apply(ael, fb, ['error', (fe) => {
                const ev = typeof OrigErrorEvent === 'function'
                  ? new OrigErrorEvent('error', { message: fe.message, filename: fe.filename, lineno: fe.lineno, colno: fe.colno, error: fe.error })
                  : new EventC('error');
                if (rethrownErrors && wsAdd) apply(wsAdd, rethrownErrors, [ev]);
                apply(dispatch, inst, [ev]);
              }, true]);
              if (origWorkerPostMessage) {
                for (const pArgs of queuedMsgs) apply(origWorkerPostMessage, fb, pArgs);
              }
            } catch (e2) {
              const ev = typeof OrigErrorEvent === 'function'
                ? new OrigErrorEvent('error', { message: String(e2 && e2.message || e2), error: e2 })
                : new EventC('error');
              if (rethrownErrors && wsAdd) apply(wsAdd, rethrownErrors, [ev]);
              apply(dispatch, inst, [ev]);
            }
          }, 0]);
        }, true]);
      }

      /**
       * Self-contained (no closure references): injected into worker blobs so worker clocks match the
       * quantised main-thread clock when fuzzPerformanceNow is enabled.
       */
      function workerTimingShim(scope) {
        try {
          var Perf = scope.Performance;
          var perf = scope.performance;
          if (Perf && Perf.prototype && typeof Perf.prototype.now === 'function') {
            var origNow = Perf.prototype.now;
            var lastNow = 0;
            Perf.prototype.now = function now() {
              var q = Math.round(origNow.call(this) / 0.02) * 0.02;
              if (q < lastNow) return lastNow;
              lastNow = q;
              return q;
            };
          } else if (perf && typeof perf.now === 'function') {
            var origNowInst = perf.now;
            var lastNowInst = 0;
            perf.now = function now() {
              var q = Math.round(origNowInst.call(this) / 0.02) * 0.02;
              if (q < lastNowInst) return lastNowInst;
              lastNowInst = q;
              return q;
            };
          }
          var D = scope.Date;
          if (D && typeof D.now === 'function') {
            var origDNow = D.now;
            var lastDNow = 0;
            D.now = function now() {
              var q = Math.round(origDNow.call(D) / 2) * 2;
              if (q < lastDNow) return lastDNow;
              lastDNow = q;
              return q;
            };
          }
        } catch (e) { /* never break the worker bootstrap */ }
      }

      /**
       * Self-contained (no closure references): enforces relay-only ICE inside workers if
       * RTCPeerConnection is ever exposed in worker scope.
       */
      function workerRtcShim(scope) {
        try {
          var Orig = scope.RTCPeerConnection;
          if (typeof Orig !== 'function') return;
          var forceRelay = function (cfg) {
            if (cfg === undefined || cfg === null) return { iceTransportPolicy: 'relay' };
            if (typeof cfg !== 'object') return cfg;
            return Object.assign({}, cfg, { iceTransportPolicy: 'relay' });
          };
          var W = new Proxy(Orig, {
            construct: function (target, args, newTarget) {
              var mapped = args.slice();
              mapped[0] = forceRelay(args[0]);
              return Reflect.construct(target, mapped, newTarget);
            },
          });
          if (Orig.prototype && typeof Orig.prototype.setConfiguration === 'function') {
            var origSetCfg = Orig.prototype.setConfiguration;
            Orig.prototype.setConfiguration = function setConfiguration(cfg) {
              return origSetCfg.call(this, forceRelay(cfg));
            };
          }
          ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (alias) {
            if (scope[alias] === Orig) {
              Object.defineProperty(scope, alias, { value: W, writable: true, enumerable: true, configurable: true });
            }
          });
        } catch (e) { /* never break the worker bootstrap */ }
      }

      /**
       * Self-contained (no closure references): its source text is injected into the worker blob, so
       * workers see the same UTC-normalised Date/Intl as the page instead of leaking the real zone.
       */
      function workerTimezoneShim(scope) {
        try {
          var D = scope.Date;
          var P = D.prototype;
          var getOffset = P.getTimezoneOffset;
          var getTime = P.getTime;
          var localMap = {
            getFullYear: 'getUTCFullYear', getMonth: 'getUTCMonth', getDate: 'getUTCDate', getDay: 'getUTCDay',
            getHours: 'getUTCHours', getMinutes: 'getUTCMinutes', getSeconds: 'getUTCSeconds',
            getMilliseconds: 'getUTCMilliseconds',
            setFullYear: 'setUTCFullYear', setMonth: 'setUTCMonth', setDate: 'setUTCDate', setHours: 'setUTCHours',
            setMinutes: 'setUTCMinutes', setSeconds: 'setUTCSeconds', setMilliseconds: 'setUTCMilliseconds'
          };
          Object.keys(localMap).forEach(function (k) {
            var u = P[localMap[k]];
            P[k] = function () { return u.apply(this, arguments); };
          });
          P.getTimezoneOffset = function () { var r = getOffset.call(this); return r !== r ? r : 0; };
          var DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
          var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
          var p2 = function (n) { return n < 10 ? '0' + n : String(n); };
          var y4 = function (n) { var s = String(Math.abs(n)); while (s.length < 4) s = '0' + s; return n < 0 ? '-' + s : s; };
          var g = function (s, n) { return P['getUTC' + n].call(s); };
          var ds = function (s) { return DAYS[g(s, 'Day')] + ' ' + MONTHS[g(s, 'Month')] + ' ' + p2(g(s, 'Date')) + ' ' + y4(g(s, 'FullYear')); };
          var ts = function (s) { return p2(g(s, 'Hours')) + ':' + p2(g(s, 'Minutes')) + ':' + p2(g(s, 'Seconds')) + ' GMT+0000 (Coordinated Universal Time)'; };
          var oToString = P.toString;
          var oDateString = P.toDateString;
          var oTimeString = P.toTimeString;
          P.toString = function () { var t = getTime.call(this); return t !== t ? oToString.call(this) : ds(this) + ' ' + ts(this); };
          P.toDateString = function () { var t = getTime.call(this); return t !== t ? oDateString.call(this) : ds(this); };
          P.toTimeString = function () { var t = getTime.call(this); return t !== t ? oTimeString.call(this) : ts(this); };
          ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (k) {
            var o = P[k];
            P[k] = function (l, opts) {
              var isObj = opts !== null && typeof opts === 'object';
              if (isObj && opts.timeZone !== undefined) return o.call(this, l, opts);
              return o.call(this, l, opts === undefined ? { timeZone: 'UTC' } : (isObj ? Object.assign({}, opts, { timeZone: 'UTC' }) : opts));
            };
          });
          var I = scope.Intl;
          if (I && typeof I.DateTimeFormat === 'function') {
            var OD = I.DateTimeFormat;
            var W = function DateTimeFormat(l, o) {
              var isObj = o !== null && typeof o === 'object';
              var e = o === undefined ? { timeZone: 'UTC' } : (isObj && o.timeZone === undefined ? Object.assign({}, o, { timeZone: 'UTC' }) : o);
              return new.target ? Reflect.construct(OD, [l, e], new.target) : OD.call(this, l, e);
            };
            W.prototype = OD.prototype;
            W.supportedLocalesOf = OD.supportedLocalesOf;
            Object.defineProperty(OD.prototype, 'constructor', { value: W, writable: true, configurable: true });
            Object.defineProperty(I, 'DateTimeFormat', { value: W, writable: true, configurable: true });
          }
          var parse = D.parse;
          var utc = D.UTC;
          var DATE_ONLY = /^\s*[+-]?\d{4,6}(?:-\d{2}(?:-\d{2})?)?\s*$/;
          var HAS_ZONE = /(?:[zZ]|[+-]\d{2}(?::?\d{2})?|\b(?:GMT|UTC|UT|[ECMP][SD]T)\b)\s*(?:\([^)]*\))?\s*$/;
          var spoofParse = function (s) {
            var t = parse.call(D, s);
            if (t !== t || DATE_ONLY.test(s) || HAS_ZONE.test(s)) return t;
            return t - getOffset.call(new D(t)) * 60000;
          };
          D.parse = function parse(s) { return spoofParse(typeof s === 'string' ? s : String(s)); };
          scope.Date = new Proxy(D, {
            construct: function (target, args, newTarget) {
              var a = args;
              if (args.length >= 2) a = [utc.apply(D, args)];
              else if (args.length === 1 && typeof args[0] === 'string') {
                var t = spoofParse(args[0]);
                if (t === t) a = [t];
              }
              return Reflect.construct(target, a, newTarget);
            },
            apply: function () { return P.toString.call(new D()); }
          });
          Object.defineProperty(P, 'constructor', { value: scope.Date, writable: true, configurable: true });
        } catch (e) { /* never break the worker bootstrap */ }
      }

      const WrappedWorker = ({
        Worker: function (scriptUrl, opts) {
          if (!new.target) return apply(OrigWorker, this, arguments);
          if (blobWorkerForbiddenByCsp) {
            if (on('blockWebSockets')) {
              throw trim(new DOMException('Worker blocked while WebSocket Kill-Switch is active', 'SecurityError'), WrappedWorker);
            }
            try {
              return construct(OrigWorker, arguments, new.target);
            } catch (e) {
              throw trim(e, WrappedWorker);
            }
          }
          let createdBlobUrl = null;
          let wrappedArgs = arguments;
          try {
            const isUrlObj = (scriptUrl instanceof URLC || isTag(scriptUrl, 'URL')) && typeof scriptUrl.href === 'string';
            const rawUrl = isUrlObj ? scriptUrl.href : (typeof scriptUrl === 'string' ? scriptUrl : String(scriptUrl));
            const absUrl = new URLC(rawUrl, G.location.href).href;
            const isModule = !!(opts && opts.type === 'module');
            const navShim = 'try{Object.defineProperties(self.navigator,{userAgent:{get:()=>' + JSON.stringify(UNIFIED_UA) + '},appVersion:{get:()=>' + JSON.stringify(UNIFIED_APP_VERSION) + '},platform:{get:()=>"Linux armv8l"},hardwareConcurrency:{get:()=>' + SPOOFED_CORES + '},deviceMemory:{get:()=>' + SPOOFED_MEMORY + '}});}catch(e){}'
              + 'try{if("userAgentData" in self.navigator){var b=' + JSON.stringify(UA_BRANDS) + ',d={brands:b,mobile:true,platform:"Android"};Object.defineProperty(self.navigator,"userAgentData",{get:()=>({brands:b,mobile:true,platform:"Android",getHighEntropyValues:()=>Promise.resolve(d),toJSON:()=>d})});}}catch(e){}';
            const wsShim = on('blockWebSockets')
              ? 'try{self.WebSocket=function(){throw new DOMException("WebSocket blocked","SecurityError");};}catch(e){}'
              : '';
            const perfShim = on('fuzzPerformanceNow')
              ? '(' + apply(origFnToString, workerTimingShim, []) + ')(self);'
              : '';
            const rtcShim = on('blockWebRtcIce')
              ? '(' + apply(origFnToString, workerRtcShim, []) + ')(self);'
              : '';
            const tzShim = on('spoofTimezone')
              ? '(' + apply(origFnToString, workerTimezoneShim, []) + ')(self);'
              : '';
            const loader = isModule ? ('import ' + JSON.stringify(absUrl) + ';') : ('importScripts(' + JSON.stringify(absUrl) + ');');
            const code = navShim + wsShim + perfShim + rtcShim + tzShim + loader;
            createdBlobUrl = createObjectURL(new OrigBlob([code], { type: 'application/javascript' }));
            wrappedArgs = [createdBlobUrl, opts];
          } catch (e) {
            createdBlobUrl = null;
            wrappedArgs = arguments;
          }
          try {
            const inst = construct(OrigWorker, wrappedArgs, new.target);
            if (createdBlobUrl) {
              apply(wmSet, workerBlobUrls, [inst, createdBlobUrl]);
              attachFallbackBridge(inst, scriptUrl, opts);
              apply(setTimeoutOrig, G, [() => revokeWorkerBlob(inst), 5000]);
            }
            return inst;
          } catch (e) {
            if (createdBlobUrl && revokeObjectURL) {
              try { revokeObjectURL(createdBlobUrl); } catch (ignored) { /* ignore */ }
              if (e && e.name === 'SecurityError') {
                blobWorkerForbiddenByCsp = true;
                if (on('blockWebSockets')) {
                  throw trim(new DOMException('Worker blocked while WebSocket Kill-Switch is active', 'SecurityError'), WrappedWorker);
                }
                try {
                  return construct(OrigWorker, [scriptUrl, opts], new.target);
                } catch (e2) {
                  throw trim(e2, WrappedWorker);
                }
              }
            }
            throw trim(e, WrappedWorker);
          }
        },
      }).Worker;
      fixLength(WrappedWorker, OrigWorker.length);
      defineProperty(WrappedWorker, 'prototype', { value: OrigWorker.prototype, writable: false });
      defineProperty(OrigWorker.prototype, 'constructor', { value: WrappedWorker });
      setProto(WrappedWorker, getProto(OrigWorker));
      const wStatics = gOPDs(OrigWorker);
      for (const k of ownKeys(wStatics)) {
        if (k !== 'prototype' && k !== 'length' && k !== 'name') defineProperty(WrappedWorker, k, wStatics[k]);
      }
      maskAs(WrappedWorker, 'Worker', true);
      const wDesc = gOPD(G, 'Worker');
      if (wDesc) defineProperty(G, 'Worker', { ...wDesc, value: WrappedWorker });
    }

    // ------------------------------------------- 6. same-origin frame fallback (CSP-safe)
    // Call install(win, cfg) directly without win.eval() so strict CSP cannot break it,
    // and hook MutationObserver + window.frames so newly inserted iframes are patched immediately.
    function ensureInstalled(win) {
      try {
        if (win && win !== G && !hasOwn.call(win, MARK)) install(win, cfg);
      } catch (e) { /* cross-origin frame: native DocumentStartScript handles it */ }
    }
    function patchFramesCollection() {
      try {
        const len = G.frames ? G.frames.length : 0;
        for (let i = 0; i < len; i++) ensureInstalled(G.frames[i]);
      } catch (e) { /* ignore */ }
    }
    function nodeMayContainFrame(node) {
      if (!node || typeof node !== 'object') return false;
      const nt = node.nodeType;
      if (nt === 1) {
        if (node.tagName === 'IFRAME' || node.tagName === 'FRAME') return true;
        return node.firstElementChild !== null && typeof node.querySelector === 'function' && node.querySelector('iframe,frame') !== null;
      }
      if (nt === 11) {
        return node.firstElementChild !== null && typeof node.querySelector === 'function' && node.querySelector('iframe,frame') !== null;
      }
      return false;
    }
    const NodeCtor = G.Node || (typeof Node === 'function' ? Node : null);
    if (NodeCtor && NodeCtor.prototype) {
      for (const method of ['appendChild', 'insertBefore', 'replaceChild']) {
        if (typeof NodeCtor.prototype[method] === 'function') {
          replaceMethod(NodeCtor.prototype, method, (orig, self, args) => {
            const scan = nodeMayContainFrame(args[0]);
            const res = apply(orig, self, args);
            if (scan) patchFramesCollection();
            return res;
          });
        }
      }
    }
    const IFrameC = G.HTMLIFrameElement || (typeof HTMLIFrameElement === 'function' ? HTMLIFrameElement : null);
    if (IFrameC && IFrameC.prototype) {
      replaceGetter(IFrameC.prototype, 'contentWindow', (g, self) => {
        const win = apply(g, self, []);
        if (win) ensureInstalled(win);
        return win;
      });
      replaceGetter(IFrameC.prototype, 'contentDocument', (g, self) => {
        const doc = apply(g, self, []);
        if (doc && doc.defaultView) ensureInstalled(doc.defaultView);
        return doc;
      });
    }
    // Observe the Document itself: `documentElement` is still null at document_start, which made the
    // previous guard skip the observer entirely for the top-level page.
    if (typeof MutationObserver === 'function' && G.document) {
      try {
        const mo = new MutationObserver((mutations) => {
          for (const m of mutations) {
            for (const node of m.addedNodes) {
              if (node && node.tagName === 'IFRAME' && node.contentWindow) ensureInstalled(node.contentWindow);
            }
          }
          patchFramesCollection();
        });
        mo.observe(G.document, { childList: true, subtree: true });
      } catch (e) { /* ignore */ }
    }
  }

  install(globalThis, CONFIG);
})();
