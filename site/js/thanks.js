/* Nezzel thank-you page. Loaded in <head> (not deferred) so the state is set before first paint.
   Valid ?os=mac|win|linux[&arch=x64|arm64][&pkg=deb|rpm|appimage][&src=tag]: shows the steps for that system and
   starts the download once. Anything else (or no JavaScript): the page stays on its three plain choices. */
(() => {
  const root = document.documentElement;
  const LANG = root.lang || 'ar';
  const q = new URLSearchParams(location.search);
  const OS = ['mac', 'win', 'linux'], ARCH = ['x64', 'arm64'], PKG = ['deb', 'rpm', 'appimage'];

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

  const req = request();
  root.dataset.th = req ? 'ok' : 'pick';
  if (!req) return;

  const fileUrl = '/download/desktop?' + query(req);
  const key = 'nz-thanks:' + query(req);
  let started = false;

  document.addEventListener('DOMContentLoaded', () => {
    const t = JSON.parse(document.getElementById('nz-i18n').textContent);
    const $ = (s) => document.querySelector(s);
    const all = (s) => [...document.querySelectorAll(s)];
    const PKGN = { deb: '.deb', rpm: '.rpm', appimage: 'AppImage' };

    /* ---- what is downloading ---- */
    const pkgHtml = req.pkg ? `<bdi dir="ltr">${PKGN[req.pkg]}</bdi>` : '';
    const sys = req.os === 'mac' ? (req.arch === 'arm64' ? t.thanks_sys_mac_arm : req.arch === 'x64' ? t.thanks_sys_mac_intel : t.plat.mac)
      : req.os === 'win' ? (req.arch === 'arm64' ? t.thanks_sys_win_arm : t.plat.windows)
      : (req.arch === 'arm64' ? t.thanks_sys_linux_arm : t.thanks_sys_linux).replace('{pkg}', pkgHtml);
    const what = $('[data-th-what]');
    if (what) what.innerHTML = t.thanks_what.replace('{sys}', sys);
    const meta = $('[data-th-meta]');
    if (meta) meta.innerHTML = t.desk_meta;

    /* ---- steps for this system only ---- */
    all('[data-th-os]').forEach((el) => { el.hidden = el.dataset.thOs !== req.os; });
    all('[data-th-pkg]').forEach((el) => { el.hidden = el.dataset.thPkg !== req.pkg; });

    /* ---- direct link, other chip ---- */
    all('[data-th-direct]').forEach((a) => { a.href = fileUrl; });
    const chip = $('[data-th-chip]');
    if (chip) {
      let to = null, label = '';
      if (req.os === 'mac') { to = req.arch === 'x64' ? 'arm64' : 'x64'; label = req.arch === 'x64' ? t.other_arch_mac_arm : t.other_arch_mac; }
      else if (req.os === 'win') { to = req.arch === 'arm64' ? 'x64' : 'arm64'; label = req.arch === 'arm64' ? t.other_arch_win_x64 : t.other_arch_win; }
      else if (req.arch !== 'arm64') { to = 'arm64'; label = t.other_arch_linux; }
      if (to) { chip.innerHTML = label; chip.href = location.pathname + '?' + query(req, { arch: to }); chip.hidden = false; }
    }

    /* ---- language switch keeps the request, and never starts a second download ---- */
    all('a[hreflang]').forEach((a) => { a.href = a.getAttribute('href').split('?')[0] + '?' + query(req) + '&again=1'; });

    /* ---- phones and tablets: the file is for a computer ---- */
    const share = $('[data-th-share]');
    if (share && root.dataset.family === 'phone') {
      share.closest('[data-th-send]').hidden = false;
      const say = (m) => { const s = $('[data-th-sent]'); if (s) s.textContent = m; };
      share.addEventListener('click', () => {
        const url = location.origin + location.pathname + '?' + query(req);
        const copy = () => { try { Promise.resolve(navigator.clipboard.writeText(url)).then(() => say(t.copied), () => say(url)); } catch (e) { say(url); } };
        try {
          if (!navigator.share) copy();
          else Promise.resolve(navigator.share({ title: 'Nezzel', url })).catch((e) => { if (!e || e.name !== 'AbortError') copy(); });
        } catch (e) { copy(); }
      });
    }

    /* ---- status line: a = starting, b = three seconds after the attempt, c = no attempt (reload, back, language switch) ---- */
    const show = (k) => all('[data-th-st]').forEach((el) => { el.hidden = el.dataset.thSt !== k; });
    show('a');

    // Navigation type is the main guard: reload and back/forward never start. sessionStorage backs it up where the
    // Navigation Timing entry is missing. A fresh visit to the same URL (clicking the button again) does start.
    const nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
    let seen = false;
    try { seen = sessionStorage.getItem(key) === '1'; } catch (e) {}
    const fresh = !q.has('again') && (nav ? nav.type === 'navigate' : !seen);
    if (!fresh) { show('c'); return; }

    setTimeout(() => {
      if (started) return;
      started = true;
      try { sessionStorage.setItem(key, '1'); } catch (e) {}
      location.assign(fileUrl);
      setTimeout(() => show('b'), 3000);
    }, 1000);
  });

  // Restored from the back/forward cache: the page is exactly as it was left; nothing starts again.
  addEventListener('pageshow', (e) => { if (e.persisted) started = true; });
})();
