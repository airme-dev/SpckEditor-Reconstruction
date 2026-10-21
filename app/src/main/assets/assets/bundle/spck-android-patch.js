/**
 * Spck Android WebView Compatibility Patch  v3
 *
 * 0. Force touch-device mode so the editor keyboard toolbar is always visible.
 *    Android WebView sometimes fails ontouchstart detection, making the UI
 *    framework add sp-notouch to <html> which hides all toolbar buttons.
 * 1. Force zip.js to use in-thread pure-JS Inflater/Deflater (useWebWorkers = false).
 * 2. Blob-backed Worker fallback for file:// origin.
 * 3. Guard WebAssembly.instantiateStreaming on file:// origin.
 * 4. Yield file <input> click() to event loop for reliable file-picker trigger.
 * 5. Promise.any polyfill.
 * 6. navigator.clipboard polyfill (Android bridge-backed).
 * 7. Editor-keyboard toolbar dispatcher (window.__spckEditorAction).
 */
(function (global) {
  'use strict';

  /* ─────────────────────────────────────────────────────────────────────────
   *  0.  Force touch-device mode — make the editor keyboard toolbar visible
   *
   *  The Spck UI framework (vendor.bundle.js) runs:
   *    e.$html.addClass(e.support.touch ? "sp-touch" : "sp-notouch")
   *  to toggle visibility of all hidden:"notouch" components including the
   *  editor keyboard toolbar (se-editor-kb). On Android WebView the touch
   *  check uses `"ontouchstart" in document.documentElement`, which can
   *  return false before any touch event has fired. We guarantee the correct
   *  class is set BEFORE the bundles run, and we watch the <html> element to
   *  immediately swap sp-notouch → sp-touch if the framework writes it.
   * ─────────────────────────────────────────────────────────────────────────*/
  (function forceTouchMode() {
    try {
      // 1. Ensure the browser reports touch support
      if (!('ontouchstart' in global.document.documentElement)) {
        try {
          Object.defineProperty(global.document.documentElement, 'ontouchstart', {
            configurable: true, enumerable: true, get: function() { return null; }
          });
        } catch (e) { /* ignore — some engines protect this */ }
      }

      var html = global.document.documentElement;

      // 2. Pre-set sp-touch now (before bundles load)
      html.classList.remove('sp-notouch');
      html.classList.add('sp-touch');

      // 3. MutationObserver: if the framework later swaps back to sp-notouch, fix it
      if (global.MutationObserver) {
        var obs = new MutationObserver(function(mutations) {
          mutations.forEach(function(m) {
            if (m.attributeName === 'class') {
              var cl = html.classList;
              if (cl.contains('sp-notouch')) {
                cl.remove('sp-notouch');
                cl.add('sp-touch');
              }
            }
          });
        });
        obs.observe(html, { attributes: true, attributeFilter: ['class'] });
      }

      // 4. Belt-and-suspenders CSS override: force the toolbar rows visible
      //    even if sp-hidden-notouch slips through on some elements.
      var style = global.document.createElement('style');
      style.id = 'spck-touch-override';
      style.textContent = [
        /* Force keyboard toolbar row always visible */
        '.sp-hidden-notouch { display: flex !important; }',
        '.se-editor-kb { display: flex !important; }',
        '.se-editor-kb.webix_hidden { display: flex !important; }',
        /* Hide the extra AI prompt history key we dont use */
        /* Keep the toolbar pinned above the soft keyboard */
        '.se-editor-kb { position: relative; z-index: 100; }'
      ].join('\n');
      (global.document.head || global.document.documentElement).appendChild(style);

      console.log('[SpckPatch] Touch mode forced: html.sp-touch set, MutationObserver watching.');
    } catch (e) {
      console.warn('[SpckPatch] forceTouchMode error:', e);
    }
  })();

  /* ─────────────────────────────────────────────────────────────────────────
   *  1.  zip.js: Force useWebWorkers = false
   * ─────────────────────────────────────────────────────────────────────────*/
  try {
    var _zipInstance = undefined;
    Object.defineProperty(global, 'zip', {
      configurable: true,
      enumerable: true,
      get: function () { return _zipInstance; },
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
          } catch (e) { _zipInstance.useWebWorkers = false; }
        }
      }
    });
  } catch (e) {
    console.warn('[SpckPatch] Failed to define zip property:', e);
  }

  /* ─────────────────────────────────────────────────────────────────────────
   *  2.  Blob-backed Worker fallback for file:// origin
   * ─────────────────────────────────────────────────────────────────────────*/
  var _RealWorker = global.Worker;
  if (_RealWorker) {
    var _scriptCache = Object.create(null);

    function fetchScriptSync(url) {
      if (_scriptCache[url]) return _scriptCache[url];
      try {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, false);
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
    try { global.Worker.prototype = _RealWorker.prototype; } catch (e) { /* ignore */ }
  }

  /* ─────────────────────────────────────────────────────────────────────────
   *  3.  WebAssembly streaming guard
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
        return _origInstStream
          ? _origInstStream.apply(global.WebAssembly, arguments)
          : Promise.reject(new Error('SpckPatch: instantiateStreaming not available'));
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

  /* ─────────────────────────────────────────────────────────────────────────
   *  6.  navigator.clipboard polyfill  (Android JavascriptInterface bridge)
   *
   *  Android WebView on file:// origin rejects every Clipboard API call with
   *  NotAllowedError (requires secure context). Spck's copyText2() calls:
   *    navigator.clipboard.writeText(text)
   *  and pasteText2() calls:
   *    navigator.clipboard.readText()
   *  We replace navigator.clipboard with an object backed by
   *  Android.setClipboard(text) and Android.getClipboard() — methods exposed
   *  by NativeFileBridge via @JavascriptInterface.
   * ─────────────────────────────────────────────────────────────────────────*/
  (function patchClipboard() {
    try {
      var bridge = global.Android;
      // Only patch when the Android bridge is present (i.e. in the editor WebView,
      // not in the isolated preview WebView which has no native interface).
      if (!bridge) return;

      var androidClipboard = {
        writeText: function (text) {
          return new Promise(function (resolve, reject) {
            try {
              bridge.setClipboard(String(text == null ? '' : text));
              resolve();
            } catch (e) { reject(e); }
          });
        },
        readText: function () {
          return new Promise(function (resolve, reject) {
            try {
              var text = (typeof bridge.getClipboard === 'function')
                ? bridge.getClipboard()
                : '';
              resolve(String(text == null ? '' : text));
            } catch (e) { reject(e); }
          });
        },
        read: function () {
          return androidClipboard.readText().then(function (text) {
            return [new ClipboardItem({
              'text/plain': new Blob([text], { type: 'text/plain' })
            })];
          });
        },
        write: function (items) {
          if (!items || !items.length) return Promise.resolve();
          var item = items[0];
          if (item && typeof item.getType === 'function') {
            return item.getType('text/plain')
              .then(function (blob) { return blob.text(); })
              .then(function (text) { return androidClipboard.writeText(text); });
          }
          return Promise.resolve();
        }
      };

      // Install polyfill
      try {
        Object.defineProperty(global.navigator, 'clipboard', {
          configurable: true,
          enumerable: true,
          get: function () { return androidClipboard; }
        });
      } catch (defErr) {
        try { global.navigator.clipboard = androidClipboard; } catch (e2) { /* ignore */ }
      }

      // Also patch document.execCommand('copy') — Spck's secondary fallback path
      var _origExecCommand = document.execCommand
        ? document.execCommand.bind(document)
        : null;
      document.execCommand = function (cmd) {
        var lc = String(cmd || '').toLowerCase();
        if (lc === 'copy' || lc === 'cut') {
          try {
            var sel = global.getSelection ? global.getSelection() : null;
            var selectedText = sel ? sel.toString() : '';
            if (!selectedText) {
              // nothing selected — try active element value selection
              var ae = document.activeElement;
              if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) {
                selectedText = ae.value.substring(ae.selectionStart, ae.selectionEnd);
              }
            }
            if (selectedText) {
              bridge.setClipboard(selectedText);
              if (lc === 'cut') {
                var sel2 = global.getSelection ? global.getSelection() : null;
                if (sel2 && sel2.rangeCount > 0) {
                  sel2.getRangeAt(0).deleteContents();
                }
              }
              return true;
            }
          } catch (e) { /* ignore */ }
          return false;
        }
        if (lc === 'paste') {
          // Synchronous paste is not feasible; the async path handles this
          return false;
        }
        return _origExecCommand
          ? _origExecCommand.apply(document, arguments)
          : false;
      };

      console.log('[SpckPatch] navigator.clipboard polyfill active (Android bridge).');
    } catch (e) {
      console.warn('[SpckPatch] clipboard polyfill error:', e);
    }
  })();

  /* ─────────────────────────────────────────────────────────────────────────
   *  7.  Editor toolbar action dispatcher  (window.__spckEditorAction)
   *
   *  Exposes a global dispatcher that maps action names to Ace editor commands.
   *  Spck's bundled UI already wires most buttons, but on Android WebView some
   *  paths are unreachable before the clipboard polyfill is in place (above).
   *  Any code can call:
   *    window.__spckEditorAction('undo')
   *    window.__spckEditorAction('redo')
   *    window.__spckEditorAction('copy')  // etc.
   * ─────────────────────────────────────────────────────────────────────────*/
  (function wireToolbar() {
    function getActiveEditor() {
      try {
        var ui = global.$$;
        if (typeof ui === 'function') {
          var me = ui('mainEditor');
          if (me && me.$editor && typeof me.$editor.execCommand === 'function') return me.$editor;
          var pe = ui('promptEditor');
          if (pe && pe.$editor && typeof pe.$editor.execCommand === 'function') return pe.$editor;
        }
      } catch (e) { /* ignore */ }
      return null;
    }

    global.__spckEditorAction = function dispatchAction(action) {
      var editor = getActiveEditor();
      if (!editor) { return false; }
      try {
        switch (String(action)) {
          case 'undo':
            editor.undo ? editor.undo() : editor.execCommand('undo'); break;
          case 'redo':
            editor.redo ? editor.redo() : editor.execCommand('redo'); break;
          case 'selectAll':
            editor.selectAll ? editor.selectAll() : editor.execCommand('selectall'); break;
          case 'copy':
            if (typeof editor.copyText2 === 'function') editor.copyText2();
            else { var t = editor.getSelectedText ? editor.getSelectedText() : '';
              if (t && global.Android) global.Android.setClipboard(t); }
            break;
          case 'cut':
            if (typeof editor.cutText2 === 'function') editor.cutText2(); break;
          case 'paste':
            if (typeof editor.pasteText2 === 'function') editor.pasteText2(); break;
          case 'indent':    editor.execCommand('indent');        break;
          case 'outdent':   editor.execCommand('outdent');       break;
          case 'comment':   editor.execCommand('togglecomment'); break;
          case 'find':      editor.execCommand('find');          break;
          case 'gotoline':  editor.execCommand('gotoline');      break;
          default:          editor.execCommand(action);          break;
        }
        return true;
      } catch (e) {
        console.warn('[SpckPatch] toolbar action error:', action, e);
        return false;
      }
    };
    console.log('[SpckPatch] Editor toolbar dispatcher ready (window.__spckEditorAction).');
  })();

  console.log('[SpckPatch] v2 applied: zip, blob-worker, WASM-guard, clipboard-bridge, toolbar-wire.');

}(typeof globalThis !== 'undefined' ? globalThis : window));
