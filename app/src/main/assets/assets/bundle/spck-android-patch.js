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

/* Add a persistent Android status bar color control to the Appearance settings. */
(function (global) {
  'use strict';

  var document = global.document;
  if (!document) return;

  var defaultColor = '#23293c';
  var presets = ['#23293c', '#111827', '#1e3a5f', '#14532d', '#7c2d12', '#581c87', '#9f1239'];

  function savedColor() {
    try {
      if (global.Android && typeof global.Android.getAppColor === 'function') {
        return global.Android.getAppColor();
      }
      return global.localStorage.getItem('spck-app-color') || defaultColor;
    } catch (error) {
      console.warn('[SpckPatch] Could not read the saved app color:', error);
      return defaultColor;
    }
  }

  function updateThemeColor(color) {
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', color);
  }

  function installColorSetting() {
    var form = document.getElementById('settingsAppearanceForm');
    if (!form) return false;
    if (form.querySelector('#spck-app-color-setting')) return true;

    var section = document.createElement('div');
    section.id = 'spck-app-color-setting';
    section.innerHTML =
      '<div class="spck-app-color-copy">' +
        '<label for="spck-app-color-input">App color</label>' +
        '<span>Change the Android status bar color.</span>' +
      '</div>' +
      '<div class="spck-app-color-controls">' +
        '<div class="spck-app-color-presets" role="group" aria-label="Preset app colors"></div>' +
        '<input id="spck-app-color-input" type="color" aria-label="Choose app color">' +
      '</div>';

    var style = document.getElementById('spck-app-color-style');
    if (!style) {
      style = document.createElement('style');
      style.id = 'spck-app-color-style';
      style.textContent =
        '#spck-app-color-setting{display:flex;align-items:center;justify-content:space-between;gap:12px;' +
          'padding:12px 0;border-bottom:1px solid var(--border,#363944);color:var(--text-color,inherit)}' +
        '#spck-app-color-setting .spck-app-color-copy{display:flex;flex:1;min-width:0;flex-direction:column;gap:4px}' +
        '#spck-app-color-setting label{font:inherit;font-weight:500}' +
        '#spck-app-color-setting .spck-app-color-copy span{font-size:12px;opacity:.72}' +
        '#spck-app-color-setting .spck-app-color-controls{display:flex;align-items:center;gap:9px}' +
        '#spck-app-color-setting .spck-app-color-presets{display:flex;gap:5px}' +
        '#spck-app-color-setting .spck-app-color-presets button{width:22px;height:22px;padding:0;border:2px solid #ffffff55;' +
          'border-radius:50%;box-sizing:border-box}' +
        '#spck-app-color-setting .spck-app-color-presets button[aria-pressed="true"]{outline:2px solid var(--highlight-color,#529cec);outline-offset:2px}' +
        '#spck-app-color-input{width:34px;height:30px;padding:2px;border:1px solid var(--border,#363944);' +
          'border-radius:5px;background:transparent}';
      document.head.appendChild(style);
    }

    var input = section.querySelector('#spck-app-color-input');
    var presetContainer = section.querySelector('.spck-app-color-presets');

    function applyColor(color) {
      if (!/^#[0-9a-fA-F]{6}$/.test(color)) return;
      try {
        if (global.Android && typeof global.Android.setAppColor === 'function') {
          if (!global.Android.setAppColor(color)) {
            console.warn('[SpckPatch] Android rejected the selected app color.');
            return;
          }
        } else {
          global.localStorage.setItem('spck-app-color', color);
        }
        input.value = color;
        updateThemeColor(color);
        var buttons = presetContainer.querySelectorAll('button');
        for (var i = 0; i < buttons.length; i++) {
          buttons[i].setAttribute('aria-pressed', String(buttons[i].dataset.color === color.toLowerCase()));
        }
      } catch (error) {
        console.error('[SpckPatch] Could not apply the selected app color:', error);
      }
    }

    input.value = savedColor();
    updateThemeColor(input.value);
    presets.forEach(function (color) {
      var button = document.createElement('button');
      button.type = 'button';
      button.dataset.color = color;
      button.style.backgroundColor = color;
      button.setAttribute('aria-label', 'Set app color to ' + color);
      button.setAttribute('aria-pressed', String(color === input.value.toLowerCase()));
      button.addEventListener('click', function () {
        applyColor(color);
      });
      presetContainer.appendChild(button);
    });
    input.addEventListener('change', function () {
      applyColor(input.value);
    });
    form.appendChild(section);
    return true;
  }

  var queued = false;
  var observer;
  function scheduleInstall() {
    if (queued) return;
    queued = true;
    var enqueue = global.requestAnimationFrame || function (callback) { global.setTimeout(callback, 0); };
    enqueue(function () {
      queued = false;
      if (installColorSetting() && observer) observer.disconnect();
    });
  }

  observer = new MutationObserver(scheduleInstall);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleInstall, { once: true });
  } else {
    scheduleInstall();
  }
}(typeof globalThis !== 'undefined' ? globalThis : window));

/* Mobile editing accessory bar for the embedded Ace editor. */
(function (global) {
  'use strict';

  var document = global.document;
  if (!document) return;

  function installEditorTools() {
    if (!document.body || document.getElementById('spck-mobile-editor-tools')) return;

    var toolbar = document.createElement('section');
    toolbar.id = 'spck-mobile-editor-tools';
    toolbar.setAttribute('aria-label', 'Editor controls');
    toolbar.hidden = true;
    toolbar.innerHTML =
      '<div class="spck-tools-quick" role="toolbar" aria-label="Code shortcuts">' +
        '<button type="button" data-insert="()" data-cursor-left="1" aria-label="Insert parentheses">( )</button>' +
        '<button type="button" data-insert="if " aria-label="Insert if">if</button>' +
        '<button type="button" data-insert="=" aria-label="Insert equals">=</button>' +
        '<button type="button" data-insert="!" aria-label="Insert exclamation mark">!</button>' +
        '<button type="button" data-insert="&amp;&amp;" aria-label="Insert logical and">&amp;&amp;</button>' +
        '<button type="button" data-insert=":" aria-label="Insert colon">:</button>' +
        '<button type="button" data-insert="." aria-label="Insert period">.</button>' +
        '<button type="button" data-insert="&quot;&quot;" data-cursor-left="1" aria-label="Insert paired quotes">&quot; &quot;</button>' +
      '</div>' +
      '<div class="spck-tools-actions" role="toolbar" aria-label="Edit actions">' +
        '<button type="button" data-action="undo" aria-label="Undo" title="Undo">↶</button>' +
        '<button type="button" data-action="redo" aria-label="Redo" title="Redo">↷</button>' +
        '<button type="button" data-action="copy" aria-label="Copy" title="Copy">▢</button>' +
        '<button type="button" data-action="cut" aria-label="Cut" title="Cut">✂</button>' +
        '<button type="button" data-action="paste" aria-label="Paste" title="Paste">▤</button>' +
        '<button type="button" data-action="more" aria-label="More editor actions" aria-expanded="false" title="More actions">+</button>' +
      '</div>' +
      '<div class="spck-tools-more" role="toolbar" aria-label="Navigation and formatting" hidden>' +
        '<button type="button" data-action="tab" aria-label="Indent">⇥</button>' +
        '<button type="button" data-action="left" aria-label="Move cursor left">‹</button>' +
        '<button type="button" data-action="up" aria-label="Move cursor up">⌃</button>' +
        '<button type="button" data-action="down" aria-label="Move cursor down">⌄</button>' +
        '<button type="button" data-action="right" aria-label="Move cursor right">›</button>' +
        '<button type="button" data-action="comment" aria-label="Toggle line comment">//</button>' +
        '<button type="button" data-action="selectAll" aria-label="Select all">All</button>' +
        '<button type="button" data-action="outdent" aria-label="Outdent">⇤</button>' +
      '</div>' +
      '<div class="spck-tools-status" role="status" aria-live="polite"></div>';

    var style = document.createElement('style');
    style.textContent =
      '#spck-mobile-editor-tools{position:fixed;z-index:2147483000;left:0;right:0;bottom:0;' +
        'padding:4px 8px env(safe-area-inset-bottom,0px);box-sizing:border-box;background:#202127;' +
        'border-top:1px solid #34363e;color:#e4e5e9;font:14px/1.2 system-ui,-apple-system,sans-serif;' +
        'box-shadow:0 -2px 8px #0004;user-select:none;-webkit-user-select:none;touch-action:pan-x}' +
      '#spck-mobile-editor-tools[hidden],#spck-mobile-editor-tools [hidden]{display:none!important}' +
      '#spck-mobile-editor-tools .spck-tools-quick,#spck-mobile-editor-tools .spck-tools-actions,' +
        '#spck-mobile-editor-tools .spck-tools-more{display:flex;align-items:center;justify-content:space-around;gap:4px}' +
      '#spck-mobile-editor-tools .spck-tools-quick{height:32px;overflow-x:auto;justify-content:space-between}' +
      '#spck-mobile-editor-tools button{flex:1 1 0;min-width:32px;height:38px;padding:0 6px;border:0;border-radius:5px;' +
        'background:transparent;color:inherit;font:inherit;font-size:20px;line-height:1;white-space:nowrap;' +
        '-webkit-tap-highlight-color:transparent;touch-action:manipulation}' +
      '#spck-mobile-editor-tools .spck-tools-quick button{height:30px;font-size:14px;font-family:ui-monospace,monospace}' +
      '#spck-mobile-editor-tools .spck-tools-actions,#spck-mobile-editor-tools .spck-tools-more{padding-right:88px}' +
      '#spck-mobile-editor-tools .spck-tools-actions button{height:42px;font-size:23px}' +
      '#spck-mobile-editor-tools .spck-tools-more{border-top:1px solid #34363e;margin-top:3px;padding-top:3px}' +
      '#spck-mobile-editor-tools .spck-tools-more button{font-size:17px}' +
      '#spck-mobile-editor-tools button:active{background:#41434d}' +
      '#spck-mobile-editor-tools button:disabled{opacity:.35}' +
      '#spck-mobile-editor-tools .spck-tools-status{position:absolute;right:8px;top:-25px;padding:4px 8px;' +
        'border-radius:4px;background:#202127;color:#f1f1f1;font-size:12px;pointer-events:none}' +
      '#spck-mobile-editor-tools .spck-tools-status:empty{display:none}' +
      'body.spck-editor-tools-active #appContainer{padding-bottom:82px;box-sizing:border-box}';
    style.textContent +=
      'body.spck-editor-tools-expanded #appContainer{padding-bottom:126px}' +
      'body.spck-keyboard-open #appContainer{height:calc(100% - var(--spck-keyboard-inset,0px))}';
    document.head.appendChild(style);
    document.body.appendChild(toolbar);

    var moreActions = toolbar.querySelector('.spck-tools-more');
    var status = toolbar.querySelector('.spck-tools-status');
    var activeElement = null;
    var statusTimer = null;
    var refreshQueued = false;
    var editorCache = typeof WeakMap === 'function' ? new WeakMap() : null;

    function showStatus(message) {
      status.textContent = message;
      if (statusTimer) global.clearTimeout(statusTimer);
      statusTimer = global.setTimeout(function () {
        status.textContent = '';
      }, 1800);
    }

    function editorElementFrom(node) {
      return node && node.closest ? node.closest('.ace_editor') : null;
    }

    function visibleEditorElement() {
      var focused = editorElementFrom(document.activeElement);
      if (focused) return focused;
      if (activeElement && activeElement.isConnected) {
        var activeRect = activeElement.getBoundingClientRect();
        if (activeRect.width && activeRect.height) return activeElement;
      }
      var editors = document.querySelectorAll('.se-editor-container .ace_editor, .ace_editor');
      for (var i = 0; i < editors.length; i++) {
        var rect = editors[i].getBoundingClientRect();
        if (rect.width && rect.height && global.getComputedStyle(editors[i]).visibility !== 'hidden') {
          return editors[i];
        }
      }
      return null;
    }

    function getEditor() {
      var element = visibleEditorElement();
      if (!element || !global.ace || typeof global.ace.edit !== 'function') return null;
      activeElement = element;
      try {
        var editor = editorCache && editorCache.get(element);
        if (!editor) {
          editor = global.ace.edit(element);
          if (editorCache) editorCache.set(element, editor);
          var session = editor.getSession();
          if (session && session.on) session.on('change', scheduleRefresh);
        }
        return editor;
      } catch (error) {
        console.warn('[SpckPatch] Could not access the visible Ace editor:', error);
        return null;
      }
    }

    function updateToolbar() {
      refreshQueued = false;
      var element = visibleEditorElement();
      toolbar.hidden = !element;
      document.body.classList.toggle('spck-editor-tools-active', !!element);
      document.body.classList.toggle('spck-editor-tools-expanded', !!element && !moreActions.hidden);
      if (!element) return;
      activeElement = element;
      var editor = getEditor();
      if (!editor) return;
      var undoManager = editor.getSession().getUndoManager();
      var undoButton = toolbar.querySelector('[data-action="undo"]');
      var redoButton = toolbar.querySelector('[data-action="redo"]');
      undoButton.disabled = !!(undoManager && undoManager.canUndo && !undoManager.canUndo());
      redoButton.disabled = !!(undoManager && undoManager.canRedo && !undoManager.canRedo());
    }

    function scheduleRefresh() {
      if (refreshQueued) return;
      refreshQueued = true;
      if (global.requestAnimationFrame) global.requestAnimationFrame(updateToolbar);
      else global.setTimeout(updateToolbar, 0);
    }

    function updateKeyboardInset() {
      var viewport = global.visualViewport;
      var inset = viewport
        ? Math.max(0, global.innerHeight - viewport.height - viewport.offsetTop)
        : 0;
      document.documentElement.style.setProperty('--spck-keyboard-inset', inset + 'px');
      document.body.classList.toggle('spck-keyboard-open', inset > 80);
      scheduleRefresh();
    }

    function clipboardWrite(text, editor) {
      if (global.Android && typeof global.Android.copyText === 'function') {
        global.Android.copyText(text);
        return Promise.resolve();
      }
      if (global.navigator.clipboard && global.navigator.clipboard.writeText) {
        return global.navigator.clipboard.writeText(text);
      }
      editor.execCommand('copy');
      return Promise.resolve();
    }

    function selectedText(editor) {
      if (editor.selection.isEmpty()) editor.selection.selectLine();
      return editor.getSelectedText();
    }

    function copy(editor) {
      return clipboardWrite(selectedText(editor), editor).then(function () {
        showStatus('Copied');
      });
    }

    function cut(editor) {
      var text = selectedText(editor);
      return clipboardWrite(text, editor).then(function () {
        editor.getSession().remove(editor.getSelectionRange());
        editor.focus();
        showStatus('Cut');
      });
    }

    function paste(editor) {
      var text;
      if (global.Android && typeof global.Android.clipboardText === 'function') {
        text = global.Android.clipboardText();
        if (!text) {
          showStatus('Clipboard is empty');
          return Promise.resolve();
        }
      } else if (global.navigator.clipboard && global.navigator.clipboard.readText) {
        return global.navigator.clipboard.readText().then(function (clipboardText) {
          if (!clipboardText) {
            showStatus('Clipboard is empty');
            return;
          }
          editor.insert(clipboardText);
          editor.focus();
          showStatus('Pasted');
        });
      } else {
        showStatus('Clipboard access is unavailable');
        return Promise.resolve();
      }
      editor.insert(text);
      editor.focus();
      showStatus('Pasted');
      return Promise.resolve();
    }

    function runAction(action) {
      var editor = getEditor();
      if (!editor) {
        showStatus('Editor is not ready');
        return Promise.resolve();
      }

      switch (action) {
        case 'undo':
          editor.undo();
          break;
        case 'redo':
          editor.redo();
          break;
        case 'copy':
          return copy(editor);
        case 'cut':
          return cut(editor);
        case 'paste':
          return paste(editor);
        case 'tab':
          editor.execCommand('indent');
          break;
        case 'left':
          editor.navigateLeft();
          break;
        case 'up':
          editor.navigateUp();
          break;
        case 'down':
          editor.navigateDown();
          break;
        case 'right':
          editor.navigateRight();
          break;
        case 'comment':
          editor.execCommand('togglecomment');
          break;
        case 'selectAll':
          editor.selectAll();
          break;
        case 'outdent':
          editor.execCommand('outdent');
          break;
        default:
          return Promise.resolve();
      }

      editor.focus();
      scheduleRefresh();
      return Promise.resolve();
    }

    toolbar.addEventListener('pointerdown', function (event) {
      if (event.target.closest('button')) event.preventDefault();
    });
    toolbar.addEventListener('mousedown', function (event) {
      if (event.target.closest('button')) event.preventDefault();
    });
    toolbar.addEventListener('click', function (event) {
      var button = event.target.closest('button');
      if (!button) return;
      if (button.hasAttribute('data-insert')) {
        var editor = getEditor();
        if (!editor) {
          showStatus('Editor is not ready');
          return;
        }
        editor.insert(button.getAttribute('data-insert'));
        var cursorLeft = Number(button.getAttribute('data-cursor-left') || 0);
        while (cursorLeft-- > 0) editor.navigateLeft();
        editor.focus();
        scheduleRefresh();
        return;
      }
      if (button.dataset.action === 'more') {
        moreActions.hidden = !moreActions.hidden;
        button.setAttribute('aria-expanded', String(!moreActions.hidden));
        button.textContent = moreActions.hidden ? '+' : '−';
        button.setAttribute('aria-label', moreActions.hidden ? 'More editor actions' : 'Hide editor actions');
        document.body.classList.toggle('spck-editor-tools-expanded', !moreActions.hidden);
        return;
      }
      Promise.resolve().then(function () {
        return runAction(button.dataset.action);
      }).catch(function (error) {
        console.error('[SpckPatch] Editor action failed:', error);
        showStatus('Action failed');
      });
    });

    document.addEventListener('focusin', function (event) {
      var element = editorElementFrom(event.target);
      if (element) {
        activeElement = element;
        scheduleRefresh();
      }
    }, true);

    var observer = new MutationObserver(scheduleRefresh);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    global.addEventListener('resize', updateKeyboardInset);
    if (global.visualViewport) {
      global.visualViewport.addEventListener('resize', updateKeyboardInset);
      global.visualViewport.addEventListener('scroll', updateKeyboardInset);
    }
    updateKeyboardInset();
    scheduleRefresh();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', installEditorTools, { once: true });
  } else {
    installEditorTools();
  }
}(typeof globalThis !== 'undefined' ? globalThis : window));
