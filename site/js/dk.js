/* Nezzel site (option A). Device-aware downloads, copy buttons, entrance reveals.
   Preview overrides: ?device=android|mac|mac-intel|windows|windows-arm|linux|iphone|ipad|unknown */
(() => {
  const root = document.documentElement;
  const q = new URLSearchParams(location.search);
  const t = JSON.parse(document.getElementById('nz-i18n').textContent);
  const LANG = root.lang;
  // Every download goes through the site's own routes (never straight to GitHub).
  const KEYS = { 'mac-arm': ['mac', 'arm64'], 'mac-intel': ['mac', 'x64'], 'win-x64': ['win', 'x64'], 'win-arm': ['win', 'arm64'], deb: ['linux'], rpm: ['linux'], appimage: ['linux'] };
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');

  /* ---------------- device ---------------- */
  function detect() {
    const o = q.get('device');
    if (o) return o;
    const ua = navigator.userAgent;
    const p = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
    if (/android/i.test(ua) || /android/i.test(p)) return 'android';
    if (/iPhone|iPod/.test(ua)) return 'iphone';
    if (/iPad/.test(ua) || (/Mac/.test(ua) && navigator.maxTouchPoints > 1)) return 'ipad';
    if (/mac/i.test(p) || /Macintosh/.test(ua)) return 'mac';
    if (/win/i.test(p) || /Windows/.test(ua)) return /ARM64|aarch64/i.test(ua) ? 'windows-arm' : 'windows';
    if (/linux|cros/i.test(p) || /Linux|CrOS/.test(ua)) return 'linux';
    return 'unknown';
  }
  let device = detect();
  const family = (d) => (d === 'android' || d === 'iphone' || d === 'ipad' ? 'phone' : d === 'unknown' ? 'unknown' : 'computer');
  const base = (d) => d.replace(/-.*/, '');
  root.dataset.device = device;
  root.dataset.family = family(device);
  let linuxArch = /aarch64|arm64/i.test(navigator.userAgent) ? 'arm64' : 'x64';

  // Refine architecture where the browser tells us (Chromium UA-CH). Never on ?device= previews.
  // A Mac's chip is only known once the client-hints lookup has answered (Safari and Firefox have none, so it stays a guess).
  // A ?device= preview counts as known. Until it is known, a link to the Apple-chip file carries g=1 (set at click time).
  let macKnown = !!q.get('device');
  if (!q.get('device') && navigator.userAgentData && navigator.userAgentData.getHighEntropyValues && (device === 'mac' || device === 'windows' || device === 'linux')) {
    navigator.userAgentData.getHighEntropyValues(['architecture']).then((v) => {
      if (device === 'mac' && v.architecture) macKnown = true;
      if (device === 'mac' && v.architecture === 'x86') setDevice('mac-intel', false);
      if (device === 'windows' && v.architecture === 'arm') setDevice('windows-arm', false);
      if (device === 'linux' && v.architecture === 'arm') { linuxArch = 'arm64'; setDevice('linux', false); }
    }).catch(() => {});
  }

  /* ---------------- download block ---------------- */
  const ico = (id) => `<svg class="i" aria-hidden="true"><use href="#i-${id}"/></svg>`;
  // Download buttons lead to the thank-you page of this language, which starts the file through /download/desktop.
  // The static links in the HTML keep pointing at /download/desktop, so they still work without JavaScript.
  // No token here: it is added when the link is used (see stampLinks), so coming Back and choosing again is a new request.
  const a = (key, p, linuxA) => {
    const [os, k] = KEYS[key];
    const arch = os === 'linux' ? (linuxA || linuxArch) : k;
    return `${t.page_desktop}thanks/?src=desk-${p}-${LANG}&os=${os}` + (arch ? `&arch=${arch}` : '') + (os === 'linux' ? `&pkg=${key}` : '');
  };
  // Primary Apple-chip links (not the explicit "Mac with Apple chip?" choice) are marked: they get g=1 while the chip is a guess.
  const GUESS = ' data-guess';
  const newToken = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
  const stampLinks = (ev) => {
    const l = ev.target.closest && ev.target.closest('a[href*="/thanks/?"]');
    if (!l) return;
    const u = new URL(l.getAttribute('href'), location.href);
    u.searchParams.set('t', newToken());
    if (l.hasAttribute('data-guess')) { if (macKnown) u.searchParams.delete('g'); else u.searchParams.set('g', '1'); }
    l.setAttribute('href', u.pathname + u.search);
  };
  // Press, key activation (fires click), middle-click and the context menu ("open in new tab", "copy link address").
  ['pointerdown', 'click', 'auxclick', 'contextmenu'].forEach((ev) => document.addEventListener(ev, stampLinks, true));

  // Phones, tablets, unknown devices: the visitor picks a system; both download controls share the choice.
  let picked = null;
  // Computers: the closing block's "other system" choice belongs to that control only (el._pk).
  // Linux package (deb|rpm|appimage): one value for the hero and the closing block.
  let pkg = 'deb';
  const PKGN = { deb: '.deb', rpm: '.rpm', appimage: 'AppImage' };
  /* ---- copy the clean landing address (hero, closing block and the thank-you page share this) ---- */
  const cleanUrl = () => location.origin + t.page_desktop;
  const shortAddr = () => location.host + t.page_desktop.replace(/\/$/, '');
  const svg = (d) => `<svg class="i" viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`;
  const ICON_COPY = svg('<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>');
  const ICON_OK = svg('<path d="m5 12 4 4L19 6"/>');
  // Both labels sit in one grid cell, so the button is as wide as the longer one and never jumps.
  const copyInner = () => `<span class="cp"><span class="cp-a">${ICON_COPY}<span class="cp-t">${t.copy_link}</span></span><span class="cp-b">${ICON_OK}<span class="cp-t">${t.link_copied}</span></span></span>`;
  const sendHtml = () => `<p class="get-send"><button type="button" class="link-btn" data-share>${copyInner()}</button><button type="button" class="link-btn" data-share-native hidden><span class="cp-t">${t.share_more}</span></button><span class="vh" role="status" aria-live="polite" data-live></span></p>`;
  // iOS-safe fallback: a read-only 16px textarea (no zoom, no keyboard) fixed off-screen, focused without scrolling;
  // focus goes back to the button afterwards.
  function legacyCopy(text, btn) {
    let ok = false;
    const prev = btn || document.activeElement;
    try {
      const ta = document.createElement('textarea');
      ta.value = text; ta.setAttribute('readonly', ''); ta.setAttribute('aria-hidden', 'true'); ta.tabIndex = -1;
      ta.style.cssText = 'position:fixed;top:0;left:-9999px;inline-size:1px;block-size:1px;padding:0;border:0;opacity:0;font-size:16px';
      document.body.append(ta);
      try { ta.focus({ preventScroll: true }); } catch (e) {}
      ta.select(); ta.setSelectionRange(0, text.length);
      try { ok = !!document.execCommand('copy'); } catch (e) {}
      ta.remove();
    } catch (e) {}
    try { if (prev && prev.focus) prev.focus({ preventScroll: true }); } catch (e) {}
    return ok;
  }
  function copyText(text, btn) {
    return new Promise((res) => {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(() => res(true), () => res(legacyCopy(text, btn)));
          return;
        }
      } catch (e) {}
      res(legacyCopy(text, btn));
    });
  }
  function copyLink(btn) {
    const scope = btn.closest('[data-get],[data-th-phone]') || document;
    const live = scope.querySelector('[data-live]');
    const hint = scope.querySelector('[data-hint]');
    copyText(cleanUrl(), btn).then((ok) => {
      clearTimeout(btn._t);
      if (ok) {
        if (hint) hint.hidden = true;
        btn.dataset.state = 'done';
        if (live) live.textContent = t.link_copied;
        btn._t = setTimeout(() => { delete btn.dataset.state; if (live) live.textContent = ''; }, 2000);
        return;
      }
      // Nothing worked: show the short address, selected, and tell the visitor how to copy it by hand.
      if (!hint) return;
      let addr = scope.querySelector('[data-addr]');
      if (!addr) { hint.innerHTML = `<bdi dir="ltr" class="th-addr-fb" data-addr></bdi><br><span></span>`; addr = hint.querySelector('[data-addr]'); addr.textContent = shortAddr(); }
      const note = hint.querySelector('span'); (note || hint).textContent = t.hold_copy;
      hint.hidden = false;
      try { const r = document.createRange(); r.selectNodeContents(addr); const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r); } catch (e) {}
      if (live) live.textContent = t.hold_copy;
    });
  }
  // Share only where the browser can; never a fallback that prints a link.
  const syncNative = () => document.querySelectorAll('[data-share-native]').forEach((b) => { b.hidden = !navigator.share; });
  document.querySelectorAll('[data-copy-btn]').forEach((b) => { b.innerHTML = copyInner(); });

  function blockBase(d, ctx, back) {
    const place = ctx || 'get';
    // A phone's own CPU says nothing about the computer it is choosing for: fixed x64 plus a visible ARM link.
    const la = back ? 'x64' : linuxArch;
    const primary = ctx === 'footer' ? 'btn btn-close' : 'btn';
    const extra = back ? `<button type="button" class="link-btn" data-unpick>${t.other_systems}</button>` : ctx === 'hero' ? `<a href="#get">${t.other_systems}</a>` : ctx === 'footer' ? ['mac', 'windows', 'linux'].filter(x => x !== base(d)).map(x => `<button type="button" class="link-btn" data-pick="${x}" data-place="${place}">${t[x]}</button>`).join('') : '';
    const meta = value => ctx === 'footer' ? '' : `<p class="get-meta">${value}</p>`;
    const content = (label, chip = '', icon = 'download') => ctx === 'footer'
      ? `<span>${t.closing_label}</span><svg class="i" aria-hidden="true"><use href="#footer-arr"/></svg>`
      : `${ico(icon)}<span class="btn-label">${label}</span>${chip}`;
    switch (d) {
      case 'mac':
      case 'mac-intel': {
        const intel = d === 'mac-intel';
        return `<a class="${primary} dl-link" data-place="${place}"${intel ? '' : GUESS} href="${a(intel ? 'mac-intel' : 'mac-arm', place)}">${content(intel ? t.mac_intel : t.mac, intel ? '' : '<span class="btn-chip" dir="ltr">Apple Silicon</span>')}</a>
          ${meta(t.desk_meta + ' · ' + t.mac_note)}
          <p class="get-links"><a href="${a(intel ? 'mac-arm' : 'mac-intel', place)}">${intel ? t.other_arch_mac_arm : t.other_arch_mac}</a><a href="${t.install}#mac">${t.first_open}</a>${extra}</p>`;
      }
      case 'windows':
      case 'windows-arm': {
        const arm = d === 'windows-arm';
        return `<a class="${primary} dl-link" data-place="${place}" href="${a(arm ? 'win-arm' : 'win-x64', place)}">${content(arm ? t.windows_arm : t.windows)}</a>
          ${meta(t.desk_meta)}
          <p class="get-links"><a href="${a(arm ? 'win-x64' : 'win-arm', place)}">${arm ? t.other_arch_win_x64 : t.other_arch_win}</a><a href="${t.install}#windows">${t.first_open}</a>${extra}</p>`;
      }
      case 'linux':
        return `<div class="get-choice" role="radiogroup" aria-label="${t.pkg}">
            ${[['deb', '.deb', t.deb], ['rpm', '.rpm', t.rpm], ['appimage', 'AppImage', t.appimage]].map(([k, n, s], i) =>
              `<button type="button" role="radio" aria-checked="${k === pkg}" tabindex="${k === pkg ? 0 : -1}" data-pkg="${k}" data-name="${n}"><b dir="ltr">${n}</b><small>${s}</small></button>`).join('')}
          </div>
          <a class="${primary} dl-link" data-place="${place}" href="${a(pkg, place, la)}" data-pkg-link data-arch="${la}">${content(t.get_pkg + ` <bdi dir="ltr">${PKGN[pkg]}</bdi>`)}</a>
          ${meta(t.desk_meta)}
          <p class="get-links">${back || ctx === 'footer' ? `<a href="${a(pkg, place, la === 'arm64' ? 'x64' : 'arm64')}" data-alt-arch-link data-arch="${la === 'arm64' ? 'x64' : 'arm64'}">${la === 'arm64' ? t.other_arch_linux_x64 : t.other_arch_linux}</a>` : ''}<a href="${t.install}#linux">${t.first_open}</a>${extra}</p>`;
      default:
        // Phone, tablet or unknown device: no default system, three equal choices.
        return `<p class="get-for">${t.for_computers}</p>
          <div class="get-pick" role="group" aria-label="${t.pick}">${['mac', 'windows', 'linux'].map((x) => `<button type="button" class="${ctx === 'footer' ? 'btn btn-close' : 'btn btn-outline'}" data-pick="${x}" data-place="${place}" aria-label="${t[x]}">${t.plat[x]}</button>`).join('')}</div>
          ${d === 'android' ? `<p class="get-links"><a href="${t.page_android}">${t.android_link}</a></p>` : ''}
          ${d === 'unknown' ? '' : sendHtml()}`;
    }
  }

  // Phone-chosen views keep the quiet "send the link" action under the options.
  function block(d, ctx, back, from) {
    return blockBase(d, ctx, back) + (back && family(from) === 'phone' ? sendHtml() : '') + '<p class="get-status" data-hint hidden></p>';
  }

  function updateClosingMeta(el, d, back) {
    const meta = el.closest('.end')?.querySelector('[data-closing-meta]');
    if (!meta) return;
    if (family(d) !== 'computer') { meta.innerHTML = t.desk_meta; return; }
    const architecture = { mac: 'Apple Silicon', 'mac-intel': 'Intel', windows: 'x64', 'windows-arm': 'ARM', linux: back ? 'x64' : linuxArch }[d];
    const parts = [t.plat[base(d)], architecture].filter(Boolean).map(part => `<bdi>${part}</bdi>`);
    if (d === 'linux') parts.push(`<bdi data-closing-package>${PKGN[pkg]}</bdi>`);
    parts.push(t.desk_meta);
    meta.innerHTML = parts.join(' · ');
  }

  function render(el, d, animate) {
    const pk = family(d) === 'computer' ? el._pk : picked;
    const back = !!pk;
    const next = back ? pk : d;
    const signature = (next === 'linux' ? next + ':' + linuxArch : next) + (back ? ':back' : '');
    if (el.dataset.for === signature) return;
    const swap = () => {
      el.dataset.for = signature;
      // Choosing re-renders the control and moves focus itself; announcing the whole block on top would double up.
      el.setAttribute('aria-live', family(d) === 'computer' && !back ? 'polite' : 'off');
      el.innerHTML = block(next, el.hasAttribute('data-closing') ? 'footer' : el.closest('.get-hero') ? 'hero' : '', back, d);
      if (el.hasAttribute('data-closing')) updateClosingMeta(el, next, back);
      syncNative();
    };
    if (!animate || reduce.matches || !el.animate) { swap(); return; }
    // Device switch: old content leaves fast, new content arrives softly (one object, blur bridges the swap).
    const out = el.animate([{ opacity: 1, filter: 'blur(0)', transform: 'none' }, { opacity: 0, filter: 'blur(2px)', transform: 'translateY(-4px)' }],
      { duration: 120, easing: 'cubic-bezier(.23,1,.32,1)' });
    out.onfinish = () => {
      swap();
      el.animate([{ opacity: 0, filter: 'blur(2px)', transform: 'translateY(6px)' }, { opacity: 1, filter: 'blur(0)', transform: 'none' }],
        { duration: 240, easing: 'cubic-bezier(.23,1,.32,1)' });
    };
  }

  const gets = [...document.querySelectorAll('[data-get]')];
  function setDevice(d, animate) {
    device = d;
    root.dataset.device = d;
    root.dataset.family = family(d);
    gets.forEach((el) => render(el, d, animate));
    compact();
  }

  function compact() {
    document.querySelectorAll('[data-get-compact]').forEach((el) => {
      // Phones, tablets and unknown devices: no system in the label, the button goes to the choices.
      if (family(device) !== 'computer') { el.href = '#get'; return; }
      const d = device;
      const span = el.querySelector('span');
      const label = { mac: t.mac, 'mac-intel': t.mac_intel, windows: t.windows, 'windows-arm': t.windows_arm, linux: t.linux }[d];
      el.href = d === 'linux' ? '#get' : a({ mac: 'mac-arm', 'mac-intel': 'mac-intel', windows: 'win-x64', 'windows-arm': 'win-arm' }[d], 'nav');
      if (d === 'mac') el.setAttribute('data-guess', ''); else el.removeAttribute('data-guess');
      span.innerHTML = `<span data-compact-label="long">${label}</span><span data-compact-label="short">${t.plat[base(d)]}</span>`;
      el.setAttribute('aria-label', label);
    });
  }

  setDevice(device, false);
  syncNative();

  document.addEventListener('click', (ev) => {
    const pkg = ev.target.closest('[data-pkg]');
    if (pkg) { choosePkg(pkg); return; }
    const pick = ev.target.closest('[data-pick]');
    const unpick = ev.target.closest('[data-unpick]');
    if (pick || unpick) {
      const host = (pick || unpick).closest('[data-get]');
      const val = pick ? pick.dataset.pick : null;
      if (family(device) === 'computer') { if (host) { host._pk = val; render(host, device, false); } }
      else { picked = val; gets.forEach((el) => render(el, device, false)); }
      const next = host && host.querySelector(pick ? '.dl-link' : '[data-pick]');
      if (next) next.focus();
      return;
    }
    const share = ev.target.closest('[data-share]');
    if (share) { copyLink(share); return; }
    const native = ev.target.closest('[data-share-native]');
    if (native) { try { Promise.resolve(navigator.share({ title: 'Nezzel', url: cleanUrl() })).catch(() => {}); } catch (e) {} }
  });

  function choosePkg(b) {
    pkg = b.dataset.pkg;
    gets.forEach((el) => {
      el.querySelectorAll('[role=radio]').forEach((r) => { const on = r.dataset.pkg === pkg; r.setAttribute('aria-checked', String(on)); r.tabIndex = on ? 0 : -1; });
      const link = el.querySelector('[data-pkg-link]');
      if (link) {
        link.href = a(pkg, link.dataset.place || 'get', link.dataset.arch);
        const label = link.querySelector('bdi'); if (label) label.textContent = PKGN[pkg];
      }
      const alt = el.querySelector('[data-alt-arch-link]');
      if (alt) alt.href = a(pkg, link ? link.dataset.place || 'get' : 'get', alt.dataset.arch);
      const cl = el.closest('.end')?.querySelector('[data-closing-package]'); if (cl) cl.textContent = PKGN[pkg];
    });
  }
  // Radio group keys: arrows move and select (inline direction aware).
  document.addEventListener('keydown', (ev) => {
    const r = ev.target.closest && ev.target.closest('[role=radio]');
    if (!r || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(ev.key)) return;
    const all = [...r.parentElement.querySelectorAll('[role=radio]')];
    const rtl = getComputedStyle(r).direction === 'rtl';
    const fwd = ev.key === 'ArrowDown' || ev.key === (rtl ? 'ArrowLeft' : 'ArrowRight');
    const n = all[(all.indexOf(r) + (fwd ? 1 : all.length - 1)) % all.length];
    ev.preventDefault(); n.focus(); choosePkg(n);
  });

  // Footer download link: point it at the visitor's OS.
  const OS = { mac: 'mac', windows: 'win', linux: 'linux' }[base(device)];
  if (OS) document.querySelectorAll('a[data-dl-auto]').forEach((el) => { el.href = el.getAttribute('href').replace(/os=\w+/, 'os=' + OS) + (OS === 'linux' ? '&arch=' + linuxArch : ''); });

  /* ---------------- copy buttons on command blocks ---------------- */
  const COPY = { ar: ['نسخ', 'نُسخ'], en: ['Copy', 'Copied'], fr: ['Copier', 'Copié'] }[root.lang] || ['Copy', 'Copied'];
  document.querySelectorAll('pre > code').forEach((code) => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'btn btn-sm copy-btn'; b.textContent = COPY[0];
    b.addEventListener('click', () => {
      const text = code.innerText.replace(/^\$ /gm, '').trim();
      const ok = () => { b.textContent = COPY[1]; b.classList.add('is-done'); setTimeout(() => { b.textContent = COPY[0]; b.classList.remove('is-done'); }, 1600); };
      (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(ok, ok);
    });
    code.parentElement.append(b);
  });

  /* ---------------- install: highlight the visitor's OS ---------------- */
  const tab = document.querySelector(`.os-tabs [data-os="${base(device)}"]`);
  if (tab) tab.setAttribute('aria-current', 'true');

  /* ---------------- entrance (enhancement): only blocks below the first screen, never the hero ---------------- */
  if (!reduce.matches && 'IntersectionObserver' in window) {
    root.classList.add('js-io');
    const items = [...document.querySelectorAll('.rv')].filter((el) => el.getBoundingClientRect().top > innerHeight * 0.9);
    const io = new IntersectionObserver((es) => es.forEach((en) => { if (en.isIntersecting) { en.target.classList.remove('pre'); io.unobserve(en.target); } }), { rootMargin: '0px 0px -8% 0px' });
    items.forEach((el) => { el.classList.add('pre'); io.observe(el); });
    addEventListener('beforeprint', () => document.querySelectorAll('.rv.pre').forEach((el) => el.classList.remove('pre')));
    // Keyboard focus must never land on something still hidden.
    document.addEventListener('focusin', (e) => { const el = e.target.closest && e.target.closest('.rv.pre'); if (el) { el.classList.remove('pre'); io.unobserve(el); } });
  }
})();
