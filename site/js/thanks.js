/* Nezzel thank-you page. Loaded in <head> (not deferred) so the state is set before first paint.
   Valid ?os=mac|win|linux[&arch=x64|arm64][&pkg=deb|rpm|appimage][&src=tag][&g=1][&t=token]: shows the steps for that system.
   The download starts once, only on a computer and only with a per-click token `t` (set by the button at click time).
   No token (bookmark, shared link): the page is settled and offers the file as a button. Anything else (or no JavaScript): three plain choices.
   g=1 = the landing page guessed the Mac's chip (no client hints): one helper line points Intel Mac owners to their file. */
(() => {
  const root = document.documentElement;
  const LANG = root.lang || 'ar';
  const q = new URLSearchParams(location.search);
  const OS = ['mac', 'win', 'linux'], ARCH = ['x64', 'arm64'], PKG = ['deb', 'rpm', 'appimage'];

  // Phone/tablet vs computer is decided here too (same rules as dk.js), so a failed dk.js never starts a download on a phone.
  function device() {
    const o = q.get('device');
    if (o) return o;
    const ua = navigator.userAgent;
    const p = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
    if (/android/i.test(ua) || /android/i.test(p)) return 'android';
    if (/iPhone|iPod/.test(ua)) return 'iphone';
    if (/iPad/.test(ua) || (/Mac/.test(ua) && navigator.maxTouchPoints > 1)) return 'ipad';
    if (/mac/i.test(p) || /Macintosh/.test(ua)) return 'mac';
    if (/win/i.test(p) || /Windows/.test(ua)) return 'windows';
    if (/linux|cros/i.test(p) || /Linux|CrOS/.test(ua)) return 'linux';
    return 'unknown';
  }
  const dev = device();
  const phone = dev === 'android' || dev === 'iphone' || dev === 'ipad';
  root.dataset.family = phone ? 'phone' : dev === 'unknown' ? 'unknown' : 'computer';

  // Only these values ever reach the file URL or the page; anything else means "no valid request".
  function request() {
    const os = q.get('os'), arch = q.get('arch'), pkg = q.get('pkg');
    if (!OS.includes(os)) return null;
    if (arch !== null && !ARCH.includes(arch)) return null;
    if (os === 'linux' && !PKG.includes(pkg)) return null;
    const src = q.get('src');
    return { os, arch, pkg: os === 'linux' ? pkg : null, src: /^[a-z0-9_-]{1,40}$/i.test(src || '') ? src : 'desk-thanks-' + LANG };
  }
  const query = (r, o = {}) => {
    const v = { ...r, ...o };
    return `src=${v.src}&os=${v.os}` + (v.arch ? `&arch=${v.arch}` : '') + (v.pkg ? `&pkg=${v.pkg}` : '');
  };
  const newToken = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 5);

  const req = request();
  root.dataset.th = req ? 'ok' : 'pick';
  if (!req) return;

  const fileUrl = '/download/desktop?' + query(req);
  // Per-click token from the button that led here (validated, used only inside the storage key; never shown).
  const tok = /^[a-z0-9]{1,12}$/.test(q.get('t') || '') ? q.get('t') : null;
  const guessed = req.os === 'mac' && req.arch === 'arm64' && q.get('g') === '1';
  const key = 'nz-thanks:' + query(req) + (tok ? '&t=' + tok : '');
  let started = false;

  // No token: nothing starts, and the status is already in its final state before first paint.
  if (!tok) root.dataset.thNt = '1';
  // The stored marker wins over the navigation type, so a duplicated tab (same sessionStorage, type 'navigate')
  // never downloads again. Reload and back/forward never start either. A fresh click carries a new token.
  const nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
  let seen = false;
  try { seen = sessionStorage.getItem(key) === '1'; } catch (e) {}
  const fresh = !!tok && !q.has('again') && !seen && !(nav && (nav.type === 'reload' || nav.type === 'back_forward'));
  // Arrival moment on the mark: 'wait' (tray not drawn yet) -> 'go' (plays once) -> 'done'. Without a download attempt the page is simply settled.
  root.dataset.thAn = fresh ? 'wait' : 'done';

  const $ = (s) => document.querySelector(s);
  const all = (s) => [...document.querySelectorAll(s)];
  let dictionary = null;
  const dict = () => dictionary || (dictionary = JSON.parse(document.getElementById('nz-i18n').textContent));
  const PKGN = { deb: '.deb', rpm: '.rpm', appimage: 'AppImage' };
  const st = () => $('.th-status');
  const state0 = !tok ? 'd' : fresh ? 'a' : 'c';

  /* ---- status line: a = starts automatically, m = download fired, b = three seconds later,
         c = no attempt (reload, back, language switch), d = no token (opened without a click) ---- */
  let said = null;
  const say = (k) => {
    const span = $(`.th-status > [data-th-st="${k}"]`), box = $('[data-th-live]');
    if (!span || !box || said === k) return;
    said = k;
    box.textContent = span.textContent.replace(/\s+/g, ' ').trim();
  };
  const show = (k) => {
    const s = st();
    if (!s) return;
    s.dataset.now = k;
    // The visual spans stay out of the accessibility tree (the live region speaks them); the current one is exposed
    // again only when it holds the download link, so a screen-reader user can still reach it.
    s.querySelectorAll(':scope > [data-th-st]').forEach((el) => {
      el.setAttribute('aria-hidden', String(!(el.dataset.thSt === k && el.querySelector('a'))));
    });
  };

  // Everything that sets the page's size is filled while the page is parsed (calls from inline scripts right after
  // each block, before first paint), so nothing moves afterwards. Each part runs once, when its elements exist.
  const done = {};
  const once = (name, ok, fn) => { if (done[name] || !ok()) return; done[name] = true; fn(); };
  function fill() {
    once('top', () => $('[data-th-meta]') && $('[data-th-phone]') && st(), () => {
      const T = dict();
      const pkgHtml = req.pkg ? `<bdi dir="ltr">${PKGN[req.pkg]}</bdi>` : '';
      const sys = req.os === 'mac' ? (req.arch === 'arm64' ? T.thanks_sys_mac_arm : req.arch === 'x64' ? T.thanks_sys_mac_intel : T.plat.mac)
        : req.os === 'win' ? (req.arch === 'arm64' ? T.thanks_sys_win_arm : T.plat.windows)
        : (req.arch === 'arm64' ? T.thanks_sys_linux_arm : T.thanks_sys_linux).replace('{pkg}', pkgHtml);
      $('[data-th-what]').innerHTML = T.thanks_what.replace('{sys}', sys);
      $('[data-th-meta]').innerHTML = T.desk_meta;
      const get = $('[data-th-get]');
      if (get) get.innerHTML = T.thanks_get.replace('{sys}', sys);
      all('[data-th-direct]').forEach((a) => { a.href = fileUrl; });

      // Guessed Mac chip: one helper line under the "what" line, with the Intel file one click away (fresh token at click time).
      const g = $('[data-th-guess]');
      if (g && guessed) {
        const intel = location.pathname + '?' + query(req, { arch: 'x64' });
        g.innerHTML = `${T.thanks_guess} <a data-th-fresh href="${intel}">${T.thanks_guess_intel}</a> ${T.thanks_guess_how}`;
        g.hidden = false;
      }

      if (phone) {
        // One job: get the address onto the visitor's computer. The copy button itself lives in dk.js (shared with the landing picker).
        $('[data-th-phone]').hidden = false;
        const sec = $('.th-ok .th'); if (sec) sec.setAttribute('aria-labelledby', 'th-h1p');
        const addr = $('[data-addr]');
        if (addr) addr.textContent = location.host + T.page_desktop.replace(/\/$/, '');
      } else show(state0);
    });
    once('alt', () => $('[data-th-chip]'), () => {
      const T = dict(), chip = $('[data-th-chip]');
      let to = null, label = '';
      if (req.os === 'mac') { to = req.arch === 'x64' ? 'arm64' : 'x64'; label = req.arch === 'x64' ? T.other_arch_mac_arm : T.other_arch_mac; }
      else if (req.os === 'win') { to = req.arch === 'arm64' ? 'x64' : 'arm64'; label = req.arch === 'arm64' ? T.other_arch_win_x64 : T.other_arch_win; }
      else if (req.arch !== 'arm64') { to = 'arm64'; label = T.other_arch_linux; }
      if (to) { chip.innerHTML = label; chip.href = location.pathname + '?' + query(req, { arch: to }); chip.dataset.thFresh = ''; chip.hidden = false; }
    });
    once('steps', () => $('[data-th-os="linux"] > .th-more'), () => {
      all('[data-th-os]').forEach((el) => { el.hidden = el.dataset.thOs !== req.os; });
      all('[data-th-pkg]').forEach((el) => { el.hidden = el.dataset.thPkg !== req.pkg; });
    });
  }
  window.__thFill = fill;
  fill();

  // Links back to this page get a new token when they are used (press, key, middle-click, context menu), never when rendered:
  // coming Back and choosing again must count as a new request.
  ['pointerdown', 'click', 'auxclick', 'contextmenu'].forEach((ev) => document.addEventListener(ev, (e) => {
    const a = e.target.closest && e.target.closest('a[data-th-fresh]');
    if (!a) return;
    const u = new URL(a.getAttribute('href'), location.href);
    u.searchParams.set('t', newToken());
    a.setAttribute('href', u.pathname + u.search);
  }, true));

  document.addEventListener('DOMContentLoaded', () => {
    fill();
    /* ---- language switch keeps the request, and never starts a second download ---- */
    all('a[hreflang]').forEach((a) => { a.href = a.getAttribute('href').split('?')[0] + '?' + query(req) + (guessed ? '&g=1' : '') + '&again=1'; });

    if (phone) {
      // dk.js fills the copy button. If it did not load, never leave an empty area: the file and the landing page as plain links.
      if (!$('[data-th-phone] [data-copy-btn] .cp')) {
        const cta = $('.th-cta'); if (cta) cta.hidden = true;
        const land = $('[data-th-land]');
        if (land) { land.href = dict().page_desktop; land.hidden = false; }
      }
      return;
    }
    say(state0);
    if (!fresh) return;

    // Count the second only while the page is on screen (not prerendered, not in a hidden tab). If the page was hidden
    // when the second ran out, wait until it is visible again and count a full second there.
    const ready = () => !document.prerendering && !document.hidden && document.visibilityState !== 'hidden';
    const go = () => setTimeout(() => {
      if (started) return;
      if (!ready()) { wait(); return; }
      started = true;
      try { sessionStorage.setItem(key, '1'); } catch (e) {}
      location.assign(fileUrl);
      // The arrival moment starts with the download; it never blocks anything and is released once it has played.
      const s = st();
      if (s) s.classList.add('fx');
      root.dataset.thAn = 'go';
      show('m'); say('m');
      setTimeout(() => { root.dataset.thAn = 'done'; }, 900);
      setTimeout(() => { show('b'); say('b'); }, 3000);
    }, 1000);
    function wait() {
      const wake = () => {
        if (!ready()) return;
        document.removeEventListener('visibilitychange', wake);
        document.removeEventListener('prerenderingchange', wake);
        go();
      };
      document.addEventListener('visibilitychange', wake);
      document.addEventListener('prerenderingchange', wake);
    }
    if (ready()) go(); else wait();
  });

  // Restored from the back/forward cache: the page is exactly as it was left; nothing starts again.
  addEventListener('pageshow', (e) => { if (e.persisted) started = true; });
})();
