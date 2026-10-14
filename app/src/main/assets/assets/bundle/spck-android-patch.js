/**
 * Spck Android WebView Compatibility Patch
 *
 * 1. Force zip.js to use in-thread pure JS Inflater/Deflater (useWebWorkers = false).
 *    Android WebView blocks new Worker() on file:// origin. With useWebWorkers = false,
 *    zip.js uses the pure-JS zlib/jzlib Inflater built into vendor.bundle.js without workers.
 * 2. Fallback Worker construction via Blob URLs for any background language workers.
 * 3. Guard WebAssembly.instantiateStreaming on file:// origin.
 * 4. Yield file input click() to event loop for reliable file picker trigger.
 * 5. Polyfill Promise.any if needed.
 */
(function (global) {
  'use strict';

  /* ─────────────────────────────────────────────────────────────────────────
   *  1.  zip.js: Force useWebWorkers = false
   *
   *  vendor.bundle.js initializes window.zip. We intercept the definition and
   *  lock useWebWorkers to false so that zip extraction and compression always
   *  use the built-in pure JavaScript Inflater/Deflater.
   * ─────────────────────────────────────────────────────────────────────────*/
  try {
    var _zipInstance = undefined;
    Object.defineProperty(global, 'zip', {
      configurable: true,
      enumerable: true,
      get: function () {
        return _zipInstance;
      },
      set: function (val) {
        _zipInstance = val;
        if (_zipInstance && typeof _zipInstance === 'object') {
          _zipInstance.useWebWorkers = false;
          try {
            Object.defineProperty(_zipInstance, 'useWebWorkers', {
              configurable: true,
              enumerable: true,
              get: function () { return false; },
              set: function () { /* locked to false */ }
            });
          } catch (e) {
            _zipInstance.useWebWorkers = false;
          }
        }
      }
    });
  } catch (e) {
    console.warn('[SpckPatch] Failed to define zip property:', e);
  }

  /* ─────────────────────────────────────────────────────────────────────────
   *  2.  Blob-backed Worker Fallback for file:// origin
   *
   *  If any other code calls new Worker(url) from file:///android_asset/,
   *  the browser throws SecurityError. We catch this, fetch the script text
   *  synchronously via XHR (which WebView permits for local assets), and
   *  construct the real Worker using a Blob URL.
   * ─────────────────────────────────────────────────────────────────────────*/
  var _RealWorker = global.Worker;
  if (_RealWorker) {
    var _scriptCache = Object.create(null);

    function fetchScriptSync(url) {
      if (_scriptCache[url]) return _scriptCache[url];
      try {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, false); // synchronous
        xhr.send(null);
        if (xhr.status === 0 || xhr.status === 200) {
          _scriptCache[url] = xhr.responseText;
          return xhr.responseText;
        }
      } catch (e) { /* fall through */ }
      return '';
    }

    global.Worker = function SafeWorker(url, opts) {
      try {
        return new _RealWorker(url, opts);
      } catch (err) {
        var urlStr = String(url || '');
        var src = fetchScriptSync(urlStr);
        if (src) {
          try {
            var blob = new Blob([src], { type: 'application/javascript' });
            var blobUrl = URL.createObjectURL(blob);
            return new _RealWorker(blobUrl, opts);
          } catch (blobErr) {
            console.warn('[SpckPatch] Blob worker fallback failed for', urlStr, blobErr);
          }
        }
        throw err;
      }
    };
    try {
      global.Worker.prototype = _RealWorker.prototype;
    } catch (e) { /* ignore */ }
  }

  /* ─────────────────────────────────────────────────────────────────────────
   *  3.  WebAssembly streaming guard
   *
   *  WebAssembly.instantiateStreaming fails on file:// origin with TypeError.
   *  Reject immediately so libraries gracefully fallback to ArrayBuffer/JS.
   * ─────────────────────────────────────────────────────────────────────────*/
  try {
    if (global.WebAssembly && global.WebAssembly.instantiateStreaming) {
      var _origInstStream = global.WebAssembly.instantiateStreaming;
      global.WebAssembly.instantiateStreaming = function (source, imports) {
        try {
          if (source && typeof source.then === 'function') {
            return Promise.reject(new Error('SpckPatch: WASM streaming blocked on file:// origin'));
          }
        } catch (e) { /* ignore */ }
        return _origInstStream ? _origInstStream.apply(global.WebAssembly, arguments)
          : Promise.reject(new Error('SpckPatch: WebAssembly.instantiateStreaming not available'));
      };
    }
  } catch (e) { /* ignore */ }

  /* ─────────────────────────────────────────────────────────────────────────
   *  4.  File <input> click timing yield
   * ─────────────────────────────────────────────────────────────────────────*/
  try {
    var _origClick = HTMLInputElement.prototype.click;
    HTMLInputElement.prototype.click = function () {
      var el = this;
      if (el.type === 'file') {
        setTimeout(function () {
          try { _origClick.call(el); } catch (e) { /* ignore */ }
        }, 0);
        return;
      }
      return _origClick.call(this);
    };
  } catch (e) { /* ignore */ }

  /* ─────────────────────────────────────────────────────────────────────────
   *  5.  Promise.any polyfill
   * ─────────────────────────────────────────────────────────────────────────*/
  if (!Promise.any) {
    Promise.any = function (promises) {
      return new Promise(function (resolve, reject) {
        var errors = [];
        var remaining = promises.length;
        if (remaining === 0) {
          reject(new AggregateError([], 'All promises were rejected'));
          return;
        }
        promises.forEach(function (p, i) {
          Promise.resolve(p).then(resolve, function (e) {
            errors[i] = e;
            if (--remaining === 0) reject(new AggregateError(errors, 'All promises were rejected'));
          });
        });
      });
    };
  }

  console.log('[SpckPatch] Android WebView compatibility patch applied (pure JS zip, blob worker fallback).');

}(typeof globalThis !== 'undefined' ? globalThis : window));
