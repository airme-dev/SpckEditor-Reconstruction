/**
 * Spck Android WebView Compatibility Patch
 *
 * This script runs synchronously before the main bundles to fix three issues
 * specific to Android WebView's file:// origin:
 *
 * 1. Web Workers cannot be created from file:// origin → fake them inline.
 * 2. ZIP import uses window.crypto / Blob URLs that must be polyfilled.
 * 3. File <input> click() may not trigger the chooser from async context →
 *    store pending file inputs and let the native bridge initiate via a real
 *    user-gesture wrapper.
 */
(function (global) {
  'use strict';

  /* ─────────────────────────────────────────────────────────────────────────
   *  1.  Fake Web Worker via MessageChannel + synchronous importScripts eval
   *
   *  zip.js creates workers with paths like "assets/workers/zip/z-worker.js"
   *  and "assets/workers/worker_inflate.js".  Android WebView blocks Worker
   *  construction from file:// so we intercept new Worker(url) and simulate
   *  the worker inside the page using a MessageChannel.  The worker scripts
   *  call importScripts(), addEventListener('message', …) and postMessage()
   *  which we redirect to the channel ports.
   * ─────────────────────────────────────────────────────────────────────────*/
  var _RealWorker = global.Worker;

  /**
   * Cache of worker script source text keyed by URL.
   * We XHR-sync-fetch each script once then cache it.
   */
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

  /**
   * FakeWorker: runs worker scripts inline using a sandboxed function scope
   * that provides the globals workers expect (self, postMessage, addEventListener,
   * importScripts, close).
   */
  function FakeWorker(url) {
    var channel = new MessageChannel();
    /** port1 = outside (the page), port2 = inside (the worker) */
    this._outside = channel.port1;
    var insidePort = channel.port2;

    /* Listeners registered by the worker via addEventListener('message', …) */
    var workerMsgListeners = [];

    /* The sandboxed "worker global" object */
    var workerGlobal = {
      self: null,          // filled below
      postMessage: function (data, transfer) {
        // Worker → page: dispatch on the outside port
        try {
          channel.port1.dispatchEvent(
            Object.assign(new MessageEvent('message', { data: data }), {})
          );
        } catch (e) {
          // MessageEvent constructor not available everywhere — use Event
          var ev = new Event('message');
          ev.data = data;
          channel.port1.dispatchEvent(ev);
        }
      },
      addEventListener: function (type, listener) {
        if (type === 'message') workerMsgListeners.push(listener);
      },
      removeEventListener: function (type, listener) {
        if (type === 'message') {
          workerMsgListeners = workerMsgListeners.filter(function (l) { return l !== listener; });
        }
      },
      importScripts: function () {
        for (var i = 0; i < arguments.length; i++) {
          var src = fetchScriptSync(arguments[i]);
          if (src) {
            try {
              /* jshint evil:true */
              new Function(
                'self', 'postMessage', 'addEventListener', 'removeEventListener', 'importScripts', 'close',
                src
              ).call(
                workerGlobal,
                workerGlobal, workerGlobal.postMessage,
                workerGlobal.addEventListener, workerGlobal.removeEventListener,
                workerGlobal.importScripts, workerGlobal.close
              );
            } catch (err) {
              console.warn('[SpckPatch] importScripts error for', arguments[i], err);
            }
          }
        }
      },
      close: function () { /* no-op */ },
    };
    workerGlobal.self = workerGlobal;

    /* Wire insidePort → worker message listeners */
    insidePort.onmessage = function (event) {
      var ev = event || { data: undefined };
      workerMsgListeners.forEach(function (fn) {
        try { fn(ev); } catch (e) { console.warn('[SpckPatch] worker listener error', e); }
      });
    };
    insidePort.start();

    /* Load and execute the primary worker script */
    var src = fetchScriptSync(url);
    if (src) {
      try {
        new Function(
          'self', 'postMessage', 'addEventListener', 'removeEventListener', 'importScripts', 'close',
          src
        ).call(
          workerGlobal,
          workerGlobal, workerGlobal.postMessage,
          workerGlobal.addEventListener, workerGlobal.removeEventListener,
          workerGlobal.importScripts, workerGlobal.close
        );
      } catch (err) {
        console.warn('[SpckPatch] Worker init error for', url, err);
      }
    }

    /* Public API mirroring real Worker */
    this.onmessage = null;
    var self = this;

    channel.port1.onmessage = function (event) {
      if (self.onmessage) self.onmessage(event);
      if (self._externalListeners) {
        self._externalListeners.forEach(function (fn) {
          try { fn(event); } catch (e) { /* ignore */ }
        });
      }
    };
    channel.port1.start();
    this._insidePort = insidePort;
    this._externalListeners = [];
  }

  FakeWorker.prototype.postMessage = function (data, transfer) {
    /* Page → Worker: forward to insidePort */
    try {
      this._insidePort.postMessage(data, transfer || []);
    } catch (e) {
      this._insidePort.postMessage(data);
    }
  };

  FakeWorker.prototype.addEventListener = function (type, listener) {
    if (type === 'message') this._externalListeners.push(listener);
  };

  FakeWorker.prototype.removeEventListener = function (type, listener) {
    this._externalListeners = this._externalListeners.filter(function (l) { return l !== listener; });
  };

  FakeWorker.prototype.terminate = function () { /* no-op */ };

  /**
   * Replace the global Worker constructor.
   * Only intercept zip-related workers; let any other workers use the real
   * constructor (if available and functional).
   */
  global.Worker = function FakeWorkerGate(url, opts) {
    var urlStr = String(url || '');
    var isZipWorker = urlStr.indexOf('z-worker') !== -1
      || urlStr.indexOf('wasm_flate') !== -1
      || urlStr.indexOf('worker_inflate') !== -1
      || urlStr.indexOf('worker_deflate') !== -1
      || urlStr.indexOf('zip/') !== -1
      || urlStr.indexOf('workers/') !== -1;

    if (isZipWorker) {
      return new FakeWorker(urlStr);
    }
    if (_RealWorker) {
      try { return new _RealWorker(url, opts); } catch (e) { /* fall through */ }
    }
    return new FakeWorker(urlStr);
  };

  // Preserve static properties
  if (_RealWorker) {
    try { global.Worker.prototype = _RealWorker.prototype; } catch (e) { /* ignore */ }
  }

  /* ─────────────────────────────────────────────────────────────────────────
   *  2.  WebAssembly guard — wasm_flate.js tries to instantiateStreaming
   *      from a file:// URL which always fails.  Disable WASM so zip.js
   *      falls back to the pure-JS Inflater path in worker_inflate.js.
   * ─────────────────────────────────────────────────────────────────────────*/
  try {
    if (global.WebAssembly) {
      var _origInstStream = global.WebAssembly.instantiateStreaming;
      global.WebAssembly.instantiateStreaming = function (source, imports) {
        // If the source is a file:// fetch (Response), reject immediately so
        // zip.js falls back gracefully.
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
   *  3.  File <input> trigger fix
   *
   *  The JS bundle calls n.click() from an async generator context, which on
   *  some Android WebView versions doesn't trigger the file chooser because
   *  there's no live user gesture.  We wrap HTMLInputElement.click so that
   *  any click on a file input of type file/zip is intercepted and a
   *  MutationObserver watches the file list.  This doesn't change anything
   *  for normal clicks; it only makes a best-effort retry via requestAnimationFrame.
   * ─────────────────────────────────────────────────────────────────────────*/
  try {
    var _origClick = HTMLInputElement.prototype.click;
    HTMLInputElement.prototype.click = function () {
      var el = this;
      if (el.type === 'file') {
        // Yield to browser event loop so the gesture window is as wide as possible
        setTimeout(function () {
          try { _origClick.call(el); } catch (e) { /* ignore */ }
        }, 0);
        return;
      }
      return _origClick.call(this);
    };
  } catch (e) { /* ignore */ }

  /* ─────────────────────────────────────────────────────────────────────────
   *  4.  Polyfill missing Promise.any (used by zip.js in some code paths)
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

  console.log('[SpckPatch] Android WebView compatibility patch applied.');

}(typeof globalThis !== 'undefined' ? globalThis : window));
