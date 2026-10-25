(function () {
  'use strict';

  // Every string from status.json is untrusted. It is only ever placed with textContent.
  // The one exception is the QR code SVG, which passes vetSvg() first. Thumbnails are
  // loaded from thumb/<integer> on this same origin.

  var POLL_MS = 700;
  var SLOW_POLL_MS = 2000;
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var TITLE = 'GallerySlim Receiver';
  var MAX_TILES = 12;

  var $ = function (id) { return document.getElementById(id); };

  var ui = {
    phase: undefined,
    qrSource: null,
    qrShown: false,
    rowsKey: null,
    problemsKey: null,
    phonesKey: null,
    pairedKey: null,
    confirmForget: null,
    nameDirty: false,
    loginPending: false,
    busy: false,
    closed: false,
    failures: 0,
    liveBucket: -1,
    last: null
  };

  var hasIntl = typeof Intl !== 'undefined' && typeof Intl.NumberFormat === 'function';
  var numberFormat = hasIntl ? new Intl.NumberFormat(undefined) : null;
  var decimalFormats = hasIntl ? [
    new Intl.NumberFormat(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 0 }),
    new Intl.NumberFormat(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 })
  ] : null;
  function num(n) { return numberFormat ? numberFormat.format(n) : String(n); }
  function str(v) { return typeof v === 'string' ? v : ''; }
  function int(v) { return (typeof v === 'number' && isFinite(v) && v > 0) ? Math.floor(v) : 0; }

  function setText(el, s) {
    s = s == null ? '' : String(s);
    if (el.textContent !== s) { el.textContent = s; }
  }
  function show(el, on) { el.hidden = !on; }
  function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }
  function make(tag, cls, text) {
    var el = document.createElement(tag);
    if (cls) { el.className = cls; }
    if (text != null) { el.textContent = text; }
    return el;
  }
  function plural(n, one, many) { return num(n) + ' ' + (n === 1 ? one : many); }

  function fmtBytes(n) {
    n = int(n);
    if (n < 1000) { return n === 1 ? '1 byte' : num(n) + ' bytes'; }
    var units = ['KB', 'MB', 'GB', 'TB'];
    var v = n / 1000;
    var i = 0;
    while (v >= 999.95 && i < units.length - 1) { v /= 1000; i++; }
    var digits = v < 10 ? 1 : 0;
    var text = decimalFormats ? decimalFormats[digits].format(v) : v.toFixed(digits);
    return text + ' ' + units[i];
  }

  function features() { return (ui.last && ui.last.features) || {}; }

  // ---- QR code: a vetted SVG, parsed as XML and imported as nodes, never set as an HTML string ----

  var SVG_ELEMENTS = { svg: 1, g: 1, rect: 1, path: 1 };
  var SVG_ATTRS = {
    xmlns: 1, viewBox: 1, width: 1, height: 1, x: 1, y: 1, fill: 1, d: 1,
    'shape-rendering': 1, role: 1, 'aria-label': 1
  };

  function vetSvg(source) {
    if (typeof source !== 'string' || source.length === 0 || source.length > 1048576) { return null; }
    if (source.slice(0, 4) !== '<svg') { return null; }
    var low = source.toLowerCase();
    var banned = ['<script', 'href', '<!', '<?', '&', 'url(', 'javascript:'];
    for (var i = 0; i < banned.length; i++) {
      if (low.indexOf(banned[i]) !== -1) { return null; }
    }
    if (/\bon[a-z]+\s*=/i.test(source)) { return null; }
    if (typeof DOMParser === 'undefined') { return null; }
    var doc;
    try { doc = new DOMParser().parseFromString(source, 'image/svg+xml'); } catch (e) { return null; }
    if (!doc || doc.getElementsByTagName('parsererror').length > 0) { return null; }
    var root = doc.documentElement;
    if (!root || root.namespaceURI !== SVG_NS || root.localName !== 'svg') { return null; }
    var all = [root].concat(Array.prototype.slice.call(root.getElementsByTagName('*')));
    for (var j = 0; j < all.length; j++) {
      var el = all[j];
      if (el.namespaceURI !== SVG_NS || !SVG_ELEMENTS.hasOwnProperty(el.localName)) { return null; }
      for (var k = 0; k < el.attributes.length; k++) {
        var a = el.attributes[k];
        var isXmlns = a.name === 'xmlns' && a.value === SVG_NS;
        if (!isXmlns && (a.namespaceURI !== null || !SVG_ATTRS.hasOwnProperty(a.name))) { return null; }
      }
    }
    return document.importNode(root, true);
  }

  // renderQr returns whether a QR code is now on the page.
  function renderQr(source) {
    if (source === ui.qrSource) { return ui.qrShown; }
    ui.qrSource = source;
    var box = $('pr-qr');
    clear(box);
    var node = vetSvg(source);
    if (node) { box.appendChild(node); }
    ui.qrShown = !!node;
    return ui.qrShown;
  }

  // ---- Progress ring (drawn here, no libraries) ----

  var RING_R = 52;
  var RING_C = 2 * Math.PI * RING_R;
  var ringBar = null;

  function circle(cls) {
    var c = document.createElementNS(SVG_NS, 'circle');
    c.setAttribute('class', cls);
    c.setAttribute('cx', '60');
    c.setAttribute('cy', '60');
    c.setAttribute('r', String(RING_R));
    c.setAttribute('fill', 'none');
    c.setAttribute('stroke-width', '12');
    return c;
  }

  function buildRing() {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 120 120');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    svg.appendChild(circle('track'));
    ringBar = circle('bar');
    ringBar.setAttribute('stroke-dasharray', RING_C.toFixed(2));
    ringBar.setAttribute('stroke-dashoffset', RING_C.toFixed(2));
    svg.appendChild(ringBar);
    $('rc-ring').insertBefore(svg, $('rc-pct'));
  }

  function setRing(pct) {
    ringBar.setAttribute('stroke-linecap', pct > 0.5 ? 'round' : 'butt');
    ringBar.setAttribute('stroke-dashoffset', (RING_C * (1 - pct / 100)).toFixed(2));
  }

  // ---- Time left: a moving average over the last 30 seconds of snapshots ----

  var eta = { samples: [], total: -1, rate: 0 };

  function etaReset() { eta.samples = []; eta.total = -1; eta.rate = 0; }

  // etaSeconds returns the estimated seconds left, or -1 while it is not known yet.
  function etaSeconds(st) {
    var byBytes = int(st.bytesTotal) > 0;
    var total = byBytes ? int(st.bytesTotal) : int(st.filesTotal);
    var done = byBytes ? int(st.bytesDone) : int(st.filesDone);
    var now = Date.now();
    var s = eta.samples;
    if (total !== eta.total || (s.length && done < s[s.length - 1].v)) { etaReset(); s = eta.samples; }
    eta.total = total;
    if (!s.length || s[s.length - 1].v !== done) { s.push({ t: now, v: done }); }
    while (s.length > 2 && now - s[1].t > 30000) { s.shift(); }
    if (int(st.etaSeconds) > 0) { return int(st.etaSeconds); }
    if (total <= 0 || done >= total || s.length < 2) { return -1; }
    var first = s[0];
    var span = (now - first.t) / 1000; // measured to now, so a stall slows the estimate
    if (span < 6 || done <= first.v) { return -1; }
    var rate = (done - first.v) / span;
    eta.rate = eta.rate > 0 ? eta.rate * 0.7 + rate * 0.3 : rate;
    return (total - done) / eta.rate;
  }

  function fmtEta(sec) {
    if (sec < 0) { return 'Working out the time left…'; }
    if (sec < 45) { return 'Less than a minute left'; }
    if (sec < 90) { return 'About 1 minute left'; }
    var mins = Math.round(sec / 60);
    if (mins < 60) { return 'About ' + num(mins) + ' minutes left'; }
    var hours = Math.floor(mins / 60);
    var rest = Math.round((mins % 60) / 10) * 10;
    if (rest === 60) { hours++; rest = 0; }
    var text = 'About ' + plural(hours, 'hour', 'hours');
    if (rest > 0 && hours < 10) { text += ' ' + num(rest) + ' minutes'; }
    return text + ' left';
  }

  // ---- Rows ----

  var STATES = {
    waiting: ['○', 'Waiting'],
    receiving: ['↓', 'Receiving…'],
    saved: ['✓', 'Saved, checking…'],
    verified: ['✓', 'Checked and safe'],
    skipped: ['–', 'Skipped'],
    failed: ['!', 'Not saved'],
    uploading: ['↑', 'Sending…'],
    sent: ['✓', 'Back on your phone'],
    'not-found': ['?', 'Not found on this computer']
  };

  function toPhone(st) { return str(st && st.direction) === 'to-phone'; }

  function makeRow() {
    var li = make('li', 'row');
    var icon = make('span', 'icon');
    icon.setAttribute('aria-hidden', 'true');
    li.appendChild(icon);
    li.appendChild(make('span', 'name'));
    var meta = make('span', 'meta');
    meta.appendChild(make('span', 'state'));
    meta.appendChild(make('span', 'size'));
    meta.appendChild(make('span', 'detail'));
    li.appendChild(meta);
    return li;
  }

  function fillRow(li, r) {
    var st = STATES[r.state] || STATES.waiting;
    li.className = 'row s-' + (STATES[r.state] ? r.state : 'waiting');
    var parts = li.children;
    setText(parts[0], st[0]);
    setText(parts[1], str(r.name));
    var meta = parts[2].children;
    setText(meta[0], st[1]);
    setText(meta[1], int(r.size) > 0 ? ' · ' + fmtBytes(r.size) : '');
    setText(meta[2], str(r.detail) ? ' · ' + str(r.detail) : '');
  }

  // Shows rows newest first, reusing list items so a poll only touches what changed.
  function renderRows(list, rows) {
    var n = rows.length;
    while (list.children.length > n) { list.removeChild(list.lastChild); }
    while (list.children.length < n) { list.appendChild(makeRow()); }
    for (var d = 0; d < n; d++) {
      var r = rows[n - 1 - d] || {};
      var key = str(r.state) + '\u0000' + str(r.name) + '\u0000' + int(r.size) + '\u0000' + str(r.detail);
      var li = list.children[d];
      if (li.gsKey !== key) { fillRow(li, r); li.gsKey = key; }
    }
  }

  // ---- Thumbnails of what arrived ----

  var tiles = {}; // row index -> <li>
  // State -> [badge, spoken label]; the badge is ✓ once the file is checked on the other side.
  var TILE_STATES = {
    saved: ['…', ', saved, checking'],
    verified: ['✓', ', checked'],
    uploading: ['…', ', sending'],
    sent: ['✓', ', back on your phone']
  };

  function fileKind(name) {
    var m = /\.([A-Za-z0-9]{1,5})$/.exec(name);
    return m ? m[1].toUpperCase() : 'FILE';
  }

  function plainTile(li, name) {
    li.insertBefore(make('span', 'kind', fileKind(name)), li.firstChild);
  }

  function makeTile(r, idx, thumbs) {
    var li = make('li', 'tile');
    var name = str(r.name);
    li.title = name;
    if (thumbs && r.thumb === true) {
      var img = document.createElement('img');
      img.alt = '';
      img.decoding = 'async';
      img.addEventListener('error', function () {
        if (img.parentNode === li) { li.removeChild(img); plainTile(li, name); }
      });
      img.src = 'thumb/' + idx;
      li.appendChild(img);
    } else {
      plainTile(li, name);
    }
    li.gsBadge = make('span', 'badge');
    li.gsBadge.setAttribute('aria-hidden', 'true');
    li.appendChild(li.gsBadge);
    li.gsLabel = make('span', 'sr-only');
    li.appendChild(li.gsLabel);
    li.gsName = name;
    return li;
  }

  function renderThumbs(rows) {
    var thumbs = !!features().thumbs;
    var latest = [];
    for (var i = rows.length - 1; i >= 0 && latest.length < MAX_TILES; i--) {
      var r = rows[i];
      if (r && TILE_STATES.hasOwnProperty(r.state)) { latest.push(r); }
    }
    var list = $('rc-thumbs');
    var keep = {};
    latest.forEach(function (r) {
      var idx = int(r.index);
      var li = tiles[idx];
      if (li && li.gsName !== str(r.name)) { li = null; } // a new transfer reused the index
      if (!li) { li = makeTile(r, idx, thumbs); tiles[idx] = li; }
      keep[idx] = true;
      li.className = 'tile s-' + r.state;
      setText(li.gsBadge, TILE_STATES[r.state][0]);
      setText(li.gsLabel, str(r.name) + TILE_STATES[r.state][1]);
      list.appendChild(li); // moves existing tiles into newest-first order
    });
    Object.keys(tiles).forEach(function (k) {
      if (!keep[k]) {
        if (tiles[k].parentNode === list) { list.removeChild(tiles[k]); }
        delete tiles[k];
      }
    });
    show($('rc-thumbs-box'), latest.length > 0);
  }

  // ---- Views ----

  var VIEWS = ['loading', 'choose-folder', 'connect', 'receiving', 'done', 'error', 'closed'];
  var MSGS = ['cf-msg', 'fd-msg', 'dn-msg', 'er-msg', 'send-msg'];

  // showView reports whether the view changed; the caller then announces its heading.
  function showView(name) {
    for (var i = 0; i < VIEWS.length; i++) { show($('v-' + VIEWS[i]), VIEWS[i] === name); }
    show($('btn-quit'), name !== 'receiving' && name !== 'closed' && !!features().quit);
    if (name === ui.phase) { return false; }
    ui.phase = name;
    MSGS.forEach(function (id) { setMsg($(id), '', false); });
    if (name !== 'receiving') { etaReset(); ui.liveBucket = -1; }
    return true;
  }

  function announce(name) {
    var h = document.querySelector('#v-' + name + ' h1');
    if (h) { setText($('announce'), h.textContent); }
  }

  function setMsg(el, text, bad) {
    setText(el, text);
    el.classList.toggle('bad', !!bad);
  }

  function percent(st) {
    if (int(st.bytesTotal) > 0) { return Math.min(100, 100 * int(st.bytesDone) / int(st.bytesTotal)); }
    if (int(st.filesTotal) > 0) { return Math.min(100, 100 * int(st.filesDone) / int(st.filesTotal)); }
    return 0;
  }

  function render(st) {
    ui.last = st;
    var phase = str(st.phase);
    var view = (phase === 'finding' || phase === 'pairing') ? 'connect' : phase;
    if (VIEWS.indexOf(view) === -1 || view === 'closed') { view = 'loading'; }
    var changed = showView(view);
    var folder = str(st.folder);
    var title = TITLE;
    renderHeader(st);
    renderSettings(st);

    if (view === 'choose-folder') {
      renderChooseFolder(st, folder);
    } else if (view === 'connect') {
      renderConnect(st, phase, folder);
    } else if (view === 'receiving') {
      title = renderReceiving(st, folder) + '% · ' + TITLE;
    } else if (view === 'done') {
      renderDone(st, folder);
      title = 'Done · ' + TITLE;
    } else if (view === 'error') {
      setText($('er-text'), str(st.error) || 'The transfer stopped.');
      show($('btn-retry'), !!features().retry);
    }
    if (document.title !== title) { document.title = title; }
    if (changed) { announce(view); }
  }

  function renderHeader(st) {
    var name = str(st.computerName);
    show($('host'), name !== '');
    setText($('host-name'), name);
    show($('btn-rename'), !!features().rename);
    var v = str(st.version) ? 'Version ' + str(st.version) : '';
    setText($('version'), v);
    setText($('st-version'), v ? 'GallerySlim Receiver · ' + v : '');
  }

  function renderChooseFolder(st, folder) {
    var f = features();
    setText($('cf-folder'), folder || 'No folder suggested yet.');
    var canUse = folder !== '' && !!f.setFolder;
    show($('btn-use'), canUse);
    show($('btn-choose'), !!f.chooseFolder);
    var choose = $('btn-choose');
    choose.className = 'btn ' + (canUse ? 'secondary' : 'primary');
    setText(choose, canUse ? 'Choose another…' : 'Choose a folder…');
    show($('cf-type'), !!f.setFolder);
    setText($('cf-note'), str(st.folderNote));
    show($('cf-note'), str(st.folderNote) !== '');
  }

  function renderConnect(st, phase, folder) {
    var f = features();
    var waiting = phase === 'finding' ? str(st.waitingFor) : '';
    var code = str(st.code);
    var hasQr = renderQr(str(st.qrSvg));
    var confirm = !hasQr && phase === 'pairing' && code !== '';
    var err = phase === 'finding' ? str(st.error) : '';
    var phones = Array.isArray(st.phones) ? st.phones : [];
    // An approved phone that accepts uploads ("Bring back"): only the send panel matters.
    var ready = st.phoneReceiving === true && (!!f.pickAndSend || !!f.sendFiles);
    show($('cn-ready'), ready);
    show($('cn-send'), ready);
    show($('fd-folder-line'), !ready);
    if (ready) {
      setText($('h-connect'), 'Your phone is ready');
      setText($('cn-ready-name'), str(st.phoneName) || str(st.waitingFor) || 'Your phone');
      setText($('drop-title'), f.sendFiles ? 'Drop photos here to send them to your phone' : 'Send photos and videos to your phone');
      show($('btn-pick-send'), !!f.pickAndSend || !!f.sendFiles);
      ['cn-problem', 'cn-waiting', 'cn-confirm', 'cn-looking', 'cn-new-toggle', 'cn-qr-card', 'fd-phones-box', 'cn-addr']
        .forEach(function (id) { show($(id), false); });
      return;
    }

    setText($('h-connect'), waiting ? 'Ready to receive' : confirm ? 'Check the code' : 'Connect your phone');
    setText($('cn-problem-text'), err);
    show($('cn-problem'), err !== '');
    show($('btn-search'), !!f.retry);

    setText($('cn-waiting-name'), waiting);
    show($('cn-waiting'), waiting !== '');
    setText($('cn-confirm-code'), code);
    show($('cn-confirm'), confirm);
    show($('cn-looking'), !waiting && !hasQr && !confirm && err === '');

    // With a remembered phone, everything for a new phone sits behind "Use a different phone".
    var extra = hasQr || phones.length > 0 || !!f.address;
    var toggle = $('cn-new-toggle');
    show(toggle, waiting !== '' && extra);
    var open = !waiting || toggle.open;
    show($('cn-qr-card'), hasQr && open);
    setText($('pr-code'), code);
    setText($('pr-phone'), str(st.phoneName));
    show($('pr-phone-line'), phase === 'pairing' && str(st.phoneName) !== '');
    renderPhones(phones);
    show($('fd-phones-box'), phones.length > 0 && open && !confirm);
    show($('cn-addr'), !!f.address && open && !confirm);

    setText($('fd-folder'), folder);
    setText($('fd-note'), str(st.folderNote));
    show($('fd-note'), str(st.folderNote) !== '');
  }

  // renderReceiving returns the whole percentage for the window title.
  function renderReceiving(st, folder) {
    var pct = percent(st);
    var who = str(st.phoneName);
    var out = toPhone(st);
    if (out) {
      setText($('h-receiving'), who ? 'Sending to ' + who : 'Sending to your phone');
    } else {
      setText($('h-receiving'), who ? 'Receiving from ' + who : 'Receiving your photos');
    }
    setText($('rc-thumbs-title'), out ? 'Sending' : 'Just arrived');
    show($('rc-folder-line'), !out);
    show($('rc-phone-line'), out);
    setRing(pct);
    var whole = Math.floor(pct);
    setText($('rc-pct'), whole + '%');
    var count = num(int(st.filesDone)) + ' of ' + plural(int(st.filesTotal), 'photo', 'photos');
    var bytes = int(st.bytesTotal) > 0 ? fmtBytes(st.bytesDone) + ' of ' + fmtBytes(st.bytesTotal) : '';
    var left = fmtEta(etaSeconds(st));
    setText($('rc-count'), count);
    setText($('rc-bytes'), bytes);
    show($('rc-bytes'), bytes !== '');
    setText($('rc-eta'), left);
    var ring = $('rc-ring');
    ring.setAttribute('aria-valuenow', String(whole));
    ring.setAttribute('aria-valuetext', whole + '%, ' + count + (bytes ? ', ' + bytes : '') + '. ' + left);
    // Screen readers hear progress every 10%, not every poll.
    var bucket = Math.floor(pct / 10);
    if (bucket !== ui.liveBucket) {
      ui.liveBucket = bucket;
      setText($('rc-live'), whole + ' percent. ' + count + '. ' + left);
    }
    setText($('rc-current'), str(st.current));
    show($('rc-current-line'), str(st.current) !== '');
    setText($('rc-folder'), folder);
    var rows = Array.isArray(st.rows) ? st.rows : [];
    renderThumbs(rows);
    renderAllFiles();
    return whole;
  }

  function renderAllFiles() {
    var st = ui.last || {};
    var details = $('rc-rows').parentNode;
    if (!details.open) { return; }
    var rows = Array.isArray(st.rows) ? st.rows : [];
    var key = JSON.stringify(rows);
    if (key !== ui.rowsKey) { ui.rowsKey = key; renderRows($('rc-rows'), rows); }
    var hidden = int(st.rowsHidden);
    setText($('rc-more'), hidden > 0 ? 'and ' + plural(hidden, 'earlier file', 'earlier files') : '');
    show($('rc-more'), hidden > 0);
  }

  function renderPhones(phones) {
    var key = JSON.stringify(phones);
    if (key === ui.phonesKey) { return; }
    ui.phonesKey = key;
    var box = $('fd-phones');
    clear(box);
    phones.forEach(function (p) {
      var name = str(p && p.name) || 'Phone';
      var code = str(p && p.code);
      var addr = str(p && p.addr);
      var b = make('button', 'btn secondary', code ? name + ' · code ' + code : name);
      b.type = 'button';
      b.setAttribute('data-action', '');
      b.disabled = ui.busy;
      b.addEventListener('click', function () {
        act('address', { address: addr }, $('fd-msg'), 'Connecting to ' + name + '…');
      });
      box.appendChild(b);
    });
  }

  function renderDone(st, folder) {
    if (toPhone(st)) { renderDoneToPhone(st); return; }
    show($('dn-folder-line'), true);
    show($('dn-phone-line'), false);
    setText($('btn-more'), 'Receive more');
    $('btn-more').className = 'btn secondary';
    setText($('dn-problems-hint'), 'These are still on your phone. Tap Receive more to try again.');
    var f = features();
    var c = st.counts || {};
    var verified = int(c.verified), saved = int(c.saved), skipped = int(c.skipped), failed = int(c.failed);
    var received = verified + saved;
    var lines = [];
    if (verified + saved + skipped + failed === 0) { received = int(st.filesDone); }
    if (verified > 0 && saved + skipped + failed > 0) { lines.push(plural(verified, 'photo', 'photos') + ' saved and checked.'); }
    if (verified > 0 && saved + skipped + failed === 0) { lines.push('Every photo was saved and checked. Your phone knows they are safe here.'); }
    if (saved > 0) { lines.push(plural(saved, 'photo was', 'photos were') + ' saved but not confirmed by the phone. Keep them on the phone for now.'); }
    if (skipped > 0) { lines.push(plural(skipped, 'photo was', 'photos were') + ' skipped.'); }
    if (failed > 0) { lines.push(plural(failed, 'photo', 'photos') + ' could not be saved. They are still on your phone.'); }
    var key = lines.join('\n');
    var list = $('dn-summary');
    if (list.gsKey !== key) {
      list.gsKey = key;
      clear(list);
      lines.forEach(function (l) { list.appendChild(make('li', null, l)); });
    }
    var total = plural(received, 'photo', 'photos') + ' received';
    if (int(st.bytesDone) > 0) { total += ' · ' + fmtBytes(st.bytesDone); }
    setText($('dn-total'), total);
    $('dn-icon').parentNode.classList.toggle('partial', failed > 0);
    setText($('dn-icon'), failed > 0 ? '!' : '✓');
    setText($('h-done'), failed > 0 ? 'Finished, but some photos need another try' : 'All done!');
    setText($('dn-folder'), folder);
    show($('btn-open'), !!f.openFolder);
    show($('btn-more'), !!f.retry);
    var problems = (Array.isArray(st.rows) ? st.rows : []).filter(function (r) {
      return r && (r.state === 'failed' || r.state === 'skipped');
    });
    setText($('dn-problems-title'), failed > 0 ? (skipped > 0 ? 'Skipped or not saved' : 'Not saved') : 'Skipped');
    show($('dn-problems-hint'), failed > 0);
    renderProblems(problems);
  }

  function renderProblems(problems) {
    var pkey = JSON.stringify(problems);
    if (pkey !== ui.problemsKey) { ui.problemsKey = pkey; renderRows($('dn-problems'), problems); }
    show($('dn-problems-box'), problems.length > 0);
  }

  function renderDoneToPhone(st) {
    var f = features();
    var c = st.counts || {};
    var sent = int(c.sent), notFound = int(c.notFound), failed = int(c.failed), skipped = int(c.skipped);
    var lines = [];
    if (notFound > 0) { lines.push(plural(notFound, 'photo was', 'photos were') + ' not found on this computer, so ' + (notFound === 1 ? 'it was' : 'they were') + ' left out.'); }
    if (skipped > 0) { lines.push(plural(skipped, 'photo was', 'photos were') + ' skipped.'); }
    if (failed > 0) { lines.push(plural(failed, 'photo', 'photos') + ' could not be sent. You can try again.'); }
    if (lines.length === 0 && sent > 0) { lines.push('Every photo arrived and was checked on your phone.'); }
    var key = 'phone\n' + lines.join('\n');
    var list = $('dn-summary');
    if (list.gsKey !== key) {
      list.gsKey = key;
      clear(list);
      lines.forEach(function (l) { list.appendChild(make('li', null, l)); });
    }
    var problem = failed + notFound > 0;
    setText($('dn-total'), plural(sent, 'photo', 'photos') + ' back on your phone' + (int(st.bytesDone) > 0 ? ' · ' + fmtBytes(st.bytesDone) : ''));
    $('dn-icon').parentNode.classList.toggle('partial', problem);
    setText($('dn-icon'), problem ? '!' : '✓');
    setText($('h-done'), problem ? 'Finished, but some photos weren’t sent' : 'All sent!');
    show($('dn-folder-line'), false);
    show($('dn-phone-line'), true);
    show($('btn-open'), false);
    show($('btn-more'), !!f.retry);
    setText($('btn-more'), 'Start again');
    $('btn-more').className = 'btn primary';
    var problems = (Array.isArray(st.rows) ? st.rows : []).filter(function (r) {
      return r && (r.state === 'failed' || r.state === 'skipped' || r.state === 'not-found');
    });
    setText($('dn-problems-title'), 'Not sent');
    show($('dn-problems-hint'), false);
    renderProblems(problems);
  }

  // ---- Settings ----

  function fmtSeen(s) {
    if (!s) { return ''; }
    if (/^\d{4}-\d\d-\d\dT/.test(s)) {
      var d = new Date(s);
      if (!isNaN(d.getTime())) {
        try {
          return 'Last seen ' + d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
        } catch (e) {
          return 'Last seen ' + d.toLocaleString();
        }
      }
    }
    return 'Last seen ' + s;
  }

  function renderSettings(st) {
    var f = features();
    setText($('st-folder'), str(st.folder) || 'Not chosen yet');
    show($('btn-change-folder'), !!f.changeFolder);

    show($('st-name-sec'), !!f.rename);
    var input = $('st-name');
    if (!ui.nameDirty && document.activeElement !== input) { input.value = str(st.computerName); }

    show($('st-login-sec'), !!f.startAtLogin);
    if (!ui.loginPending) { $('st-login').checked = st.startAtLogin === true; }

    show($('st-install-sec'), !!f.install && st.installed !== true);

    var paired = Array.isArray(st.pairedPhones) ? st.pairedPhones : [];
    show($('st-phones-sec'), !!f.forget || paired.length > 0);
    show($('st-phones-empty'), paired.length === 0);
    var key = JSON.stringify(paired) + '\u0000' + ui.confirmForget + '\u0000' + !!f.forget;
    if (key !== ui.pairedKey) {
      ui.pairedKey = key;
      renderPaired(paired, !!f.forget);
    }
  }

  function renderPaired(paired, canForget) {
    var list = $('st-phones');
    var hadFocus = list.contains(document.activeElement);
    clear(list);
    var focusTarget = null;
    paired.forEach(function (p) {
      var name = str(p && p.name);
      if (!name) { return; }
      var li = make('li');
      var who = make('span', 'who');
      who.appendChild(make('strong', null, name));
      var seen = fmtSeen(str(p && p.lastSeen));
      if (seen) { who.appendChild(make('span', 'seen', seen)); }
      li.appendChild(who);
      if (canForget && ui.confirmForget !== name) {
        var b = make('button', 'btn secondary compact', 'Forget');
        b.type = 'button';
        b.setAttribute('aria-label', 'Forget ' + name);
        b.addEventListener('click', function () {
          ui.confirmForget = name;
          renderSettings(ui.last || {});
        });
        li.appendChild(b);
      } else if (canForget) {
        var box = make('div', 'confirm');
        box.setAttribute('role', 'group');
        box.setAttribute('aria-label', 'Forget ' + name + '?');
        box.appendChild(make('span', null, 'Forget ' + name + '? Next time it will need the QR code again.'));
        var yes = make('button', 'btn danger compact', 'Forget');
        yes.type = 'button';
        yes.setAttribute('data-action', '');
        yes.disabled = ui.busy;
        yes.addEventListener('click', function () {
          act('forget', { phone: name }, $('st-phones-msg'), 'Forgetting…', function (res) {
            if (res && res.ok) {
              ui.confirmForget = null;
              setMsg($('st-phones-msg'), name + ' was forgotten.', false);
              return true;
            }
            return false;
          });
        });
        var no = make('button', 'btn secondary compact', 'Keep');
        no.type = 'button';
        no.addEventListener('click', function () {
          ui.confirmForget = null;
          renderSettings(ui.last || {});
        });
        box.appendChild(yes);
        box.appendChild(no);
        li.appendChild(box);
        focusTarget = no;
      }
      list.appendChild(li);
    });
    if (focusTarget && hadFocus) { focusTarget.focus(); }
  }

  var sheet = $('settings');

  function openSettings() {
    if (ui.last) { renderSettings(ui.last); }
    if (typeof sheet.showModal === 'function') {
      if (!sheet.open) { sheet.showModal(); }
    } else {
      sheet.setAttribute('open', '');
    }
  }

  function closeSettings() {
    if (typeof sheet.close === 'function') { sheet.close(); } else { sheet.removeAttribute('open'); }
  }

  sheet.addEventListener('close', function () {
    ui.confirmForget = null;
    ui.nameDirty = false;
    $('btn-settings').focus();
  });
  // A click on the dimmed backdrop (the dialog element itself, outside its content) closes it.
  sheet.addEventListener('click', function (e) { if (e.target === sheet) { closeSettings(); } });
  $('btn-settings').addEventListener('click', openSettings);
  $('btn-settings-close').addEventListener('click', closeSettings);

  // ---- Polling ----

  function poll() {
    if (ui.closed) { return; }
    fetch('status.json', { cache: 'no-store', credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (r) {
        if (!r.ok) { throw new Error('status ' + r.status); }
        return r.json();
      })
      .then(function (st) {
        ui.failures = 0;
        show($('lost'), false);
        if (!ui.closed && st && typeof st === 'object') { render(st); }
      })
      .catch(function () {
        ui.failures++;
        if (ui.failures >= 3 && !ui.closed) { show($('lost'), true); }
      })
      .then(function () {
        if (!ui.closed) { setTimeout(poll, ui.failures >= 3 ? SLOW_POLL_MS : POLL_MS); }
      });
  }

  // ---- Actions ----

  function setBusy(on) {
    ui.busy = on;
    var buttons = document.querySelectorAll('[data-action]');
    for (var i = 0; i < buttons.length; i++) { buttons[i].disabled = on; }
  }

  // post sends one POST and resolves with the JSON reply (always an object).
  function post(name, body, contentType, extraHeaders) {
    var headers = { 'Content-Type': contentType, 'Accept': 'application/json' };
    Object.keys(extraHeaders || {}).forEach(function (k) { headers[k] = extraHeaders[k]; });
    return fetch(name, { method: 'POST', cache: 'no-store', credentials: 'same-origin', headers: headers, body: body })
      .then(function (r) {
        return r.json().catch(function () { return { ok: false }; });
      })
      .then(function (res) { return res && typeof res === 'object' ? res : { ok: false }; });
  }

  // act posts one action and shows its result in msgEl. onResult may handle the reply itself.
  function act(name, body, msgEl, pendingText, onResult) {
    if (ui.busy) { return; }
    setBusy(true);
    if (msgEl) { setMsg(msgEl, pendingText || '', false); }
    post(name, JSON.stringify(body || {}), 'application/json')
      .then(function (res) {
        if (onResult && onResult(res)) { return; }
        if (msgEl) {
          setMsg(msgEl, res.ok ? '' : (str(res.error) || 'That didn’t work. Please try again.'), !res.ok);
        }
      }, function () {
        if (onResult && onResult(null)) { return; }
        if (msgEl) { setMsg(msgEl, 'Could not reach the receiver. Is it still open?', true); }
      })
      .then(function () {
        setBusy(false);
        if (!ui.closed) { setTimeout(poll, 0); }
      });
  }

  function onEnter(input, fn) {
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); fn(); } });
  }

  function folderPath() { return str(ui.last && ui.last.folder); }

  // A folder dialog, from the first screen (choose-folder) or from Settings (change-folder).
  function pickFolder(action, msgEl, typeBox, typeInput) {
    act(action, {}, msgEl,
      'A folder window has opened. It may be behind this browser window.',
      function (res) {
        if (!res) { return false; }
        if (res.ok) {
          var p = str(res.path);
          setMsg(msgEl, p ? 'Saved. Photos will go to ' + p : '', false);
          return true;
        }
        if (res.needPath && features().setFolder) {
          show(typeBox, true);
          typeBox.open = true;
          setMsg(msgEl, str(res.error), true);
          typeInput.focus();
          return true;
        }
        return false;
      });
  }

  function useTyped(input, msgEl) {
    var p = input.value;
    if (!p.trim()) {
      setMsg(msgEl, 'Please type the full path of a folder first.', true);
      input.focus();
      return;
    }
    act('set-folder', { path: p }, msgEl, 'Saving…');
  }

  $('btn-choose').addEventListener('click', function () {
    pickFolder('choose-folder', $('cf-msg'), $('cf-type'), $('cf-path'));
  });
  $('btn-use').addEventListener('click', function () {
    act('set-folder', { path: folderPath() }, $('cf-msg'), 'Saving…');
  });
  $('btn-use-typed').addEventListener('click', function () { useTyped($('cf-path'), $('cf-msg')); });
  onEnter($('cf-path'), function () { useTyped($('cf-path'), $('cf-msg')); });

  $('btn-change-folder').addEventListener('click', function () {
    pickFolder('change-folder', $('st-folder-msg'), $('st-type'), $('st-path'));
  });
  $('btn-st-use-typed').addEventListener('click', function () { useTyped($('st-path'), $('st-folder-msg')); });
  onEnter($('st-path'), function () { useTyped($('st-path'), $('st-folder-msg')); });

  function connect() {
    var a = $('fd-addr').value;
    if (!a.trim()) {
      setMsg($('fd-msg'), 'Please type the address shown on the phone first.', true);
      $('fd-addr').focus();
      return;
    }
    act('address', { address: a }, $('fd-msg'), 'Connecting…');
  }
  $('btn-addr').addEventListener('click', connect);
  onEnter($('fd-addr'), connect);

  $('cn-new-toggle').addEventListener('toggle', function () { if (ui.last) { render(ui.last); } });
  $('rc-rows').parentNode.addEventListener('toggle', function () { ui.rowsKey = null; renderAllFiles(); });

  $('btn-search').addEventListener('click', function () {
    act('retry', {}, $('fd-msg'), 'Searching again…');
  });
  $('btn-retry').addEventListener('click', function () {
    act('retry', {}, $('er-msg'), 'Trying again…');
  });
  $('btn-more').addEventListener('click', function () {
    act('retry', {}, $('dn-msg'), 'Getting ready…');
  });
  $('btn-open').addEventListener('click', function () {
    act('open-folder', {}, $('dn-msg'), 'Opening the folder…');
  });

  // ---- Computer name: inline in the header, and in Settings ----

  function rename(value, msgEl, done) {
    var n = value.trim();
    if (!n) {
      setMsg(msgEl, 'Please type a name for this computer.', true);
      return;
    }
    act('rename', { name: n }, msgEl, 'Saving…', function (res) {
      if (res && res.ok) {
        setMsg(msgEl, '', false);
        if (done) { done(); }
        return true;
      }
      return false;
    });
  }

  function editName(on) {
    show($('host-view'), !on);
    show($('host-edit'), on);
    if (on) {
      var input = $('host-input');
      input.value = str(ui.last && ui.last.computerName);
      input.focus();
      input.select();
    } else {
      setMsg($('host-msg'), '', false);
      $('btn-rename').focus();
    }
  }

  $('btn-rename').addEventListener('click', function () { editName(true); });
  $('btn-rename-cancel').addEventListener('click', function () { editName(false); });
  function saveHeaderName() { rename($('host-input').value, $('host-msg'), function () { editName(false); }); }
  $('btn-rename-save').addEventListener('click', saveHeaderName);
  $('host-input').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); saveHeaderName(); }
    if (e.key === 'Escape') { e.preventDefault(); editName(false); }
  });

  $('st-name').addEventListener('input', function () { ui.nameDirty = true; });
  function saveSettingsName() {
    rename($('st-name').value, $('st-name-msg'), function () {
      ui.nameDirty = false;
      setMsg($('st-name-msg'), 'Saved.', false);
    });
  }
  $('btn-st-name').addEventListener('click', saveSettingsName);
  onEnter($('st-name'), saveSettingsName);

  // ---- Start with this computer, Install ----

  $('st-login').addEventListener('change', function () {
    var box = $('st-login');
    var want = box.checked;
    ui.loginPending = true;
    act('start-at-login', { on: want }, $('st-login-msg'), want ? 'Turning on…' : 'Turning off…', function (res) {
      ui.loginPending = false;
      if (!res || !res.ok) { box.checked = !want; }
      return false;
    });
  });

  $('btn-install').addEventListener('click', function () {
    act('install', {}, $('st-install-msg'), 'Installing…', function (res) {
      if (res && res.ok) {
        setMsg($('st-install-msg'), 'Installed.', false);
        return true;
      }
      return false;
    });
  });

  // ---- Bring back to phone: the file picker, or files dropped on the page ----

  var MEDIA_EXT = /\.(jpe?g|png|gif|webp|heic|heif|avif|dng|bmp|tiff?|mp4|mov|m4v|3gp|mkv|webm|avi)$/i;
  function isMedia(file) { return /^(image|video)\//.test(str(file.type)) || MEDIA_EXT.test(str(file.name)); }
  function canDrop() {
    return ui.phase === 'connect' && !!ui.last && ui.last.phoneReceiving === true && !!features().sendFiles;
  }

  // sendDropped uploads each file to "drop" (bytes only; the name goes in a header, cleaned
  // by the receiver) and then asks the receiver to send them all with "drop-send".
  function sendDropped(list) {
    var msg = $('send-msg');
    if (ui.busy) { return; }
    var files = list.filter(isMedia);
    var left = list.length - files.length;
    if (files.length === 0) {
      setMsg(msg, 'Only photos and videos can be sent to the phone.', true);
      return;
    }
    if (files.length > 500) {
      setMsg(msg, 'That\u2019s a lot of files at once. Please send at most 500 at a time.', true);
      return;
    }
    setBusy(true);
    var ids = [];
    var i = 0;
    function next() {
      if (i >= files.length) {
        setMsg(msg, 'Sending to your phone…', false);
        return post('drop-send', JSON.stringify({ ids: ids }), 'application/json');
      }
      var file = files[i];
      setMsg(msg, 'Getting ' + num(i + 1) + ' of ' + plural(files.length, 'file', 'files') + ' ready: ' + str(file.name), false);
      return post('drop', file, 'application/octet-stream', { 'X-Drop-Name': encodeURIComponent(str(file.name) || 'photo') })
        .then(function (res) {
          if (!res.ok || typeof res.id !== 'string') {
            throw new Error(str(res.error) || 'Couldn\u2019t get ' + str(file.name) + ' ready. Please try again.');
          }
          ids.push(res.id);
          i++;
          return next();
        });
    }
    next()
      .then(function (res) {
        if (!res.ok) { setMsg(msg, str(res.error) || 'That didn\u2019t work. Please try again.', true); return; }
        setMsg(msg, left > 0 ? plural(left, 'file was', 'files were') + ' left out because ' + (left === 1 ? 'it isn\u2019t a photo or video.' : 'they aren\u2019t photos or videos.') : '', false);
      }, function (err) {
        setMsg(msg, (err && err.message && err.message !== 'Failed to fetch') ? err.message : 'Could not reach the receiver. Is it still open?', true);
      })
      .then(function () {
        setBusy(false);
        if (!ui.closed) { setTimeout(poll, 0); }
      });
  }

  var zone = $('dropzone');
  // Dropping a file anywhere must never make the browser leave this page to open it.
  document.addEventListener('dragover', function (e) {
    e.preventDefault();
    var ok = canDrop() && !ui.busy;
    if (e.dataTransfer) { e.dataTransfer.dropEffect = ok ? 'copy' : 'none'; }
    zone.classList.toggle('over', ok);
  });
  document.addEventListener('dragleave', function (e) {
    if (!e.relatedTarget) { zone.classList.remove('over'); }
  });
  document.addEventListener('drop', function (e) {
    e.preventDefault();
    zone.classList.remove('over');
    if (!canDrop() || !e.dataTransfer) { return; }
    sendDropped(Array.prototype.slice.call(e.dataTransfer.files || []));
  });

  $('btn-pick-send').addEventListener('click', function () {
    if (features().pickAndSend) {
      act('pick-send', {}, $('send-msg'), 'A window to choose photos has opened. It may be behind this browser window.');
    } else if (features().sendFiles) {
      $('send-input').click();
    }
  });
  $('send-input').addEventListener('change', function () {
    var input = $('send-input');
    var files = Array.prototype.slice.call(input.files || []);
    input.value = '';
    if (files.length) { sendDropped(files); }
  });

  // ---- Quit ----

  $('btn-quit').addEventListener('click', function () {
    act('quit', {}, null, '', function (res) {
      if (res && !res.ok) { return false; }
      // A failed request also means the receiver has already gone.
      ui.closed = true;
      closeSettings();
      if (showView('closed')) { announce('closed'); }
      document.title = TITLE;
      return true;
    });
  });

  buildRing();
  poll();
})();
