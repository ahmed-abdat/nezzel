/* Nezzel site (option A). Device-aware downloads, copy buttons, entrance reveals.
   Preview overrides: ?device=android|mac|mac-intel|windows|windows-arm|linux|iphone|ipad|unknown */
(() => {
  const root = document.documentElement;
  const q = new URLSearchParams(location.search);
  const t = JSON.parse(document.getElementById('nz-i18n').textContent);
  const LANG = root.lang;
  // Every download goes through the site's own routes (never straight to GitHub).
  const KEYS = { 'mac-arm': ['mac', 'arm64'], 'mac-intel': ['mac', 'x64'], 'win-x64': ['win', 'x64'], 'win-arm': ['win', 'arm64'], deb: ['linux'], rpm: ['linux'], appimage: ['linux'] };
  const apk = (p) => `/download?src=desk-${p}-${LANG}`;
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
  if (!q.get('device') && navigator.userAgentData && navigator.userAgentData.getHighEntropyValues && (device === 'mac' || device === 'windows' || device === 'linux')) {
    navigator.userAgentData.getHighEntropyValues(['architecture']).then((v) => {
      if (device === 'mac' && v.architecture === 'x86') setDevice('mac-intel', false);
      if (device === 'windows' && v.architecture === 'arm') setDevice('windows-arm', false);
      if (device === 'linux' && v.architecture === 'arm') { linuxArch = 'arm64'; setDevice('linux', false); }
    }).catch(() => {});
  }

  /* ---------------- download block ---------------- */
  const ico = (id) => `<svg class="i" aria-hidden="true"><use href="#i-${id}"/></svg>`;
  const a = (key, p) => {
    const [os, k] = KEYS[key];
    const arch = os === 'linux' ? linuxArch : k;
    return `/download/desktop?src=desk-${p}-${LANG}&os=${os}` + (arch ? `&arch=${arch}` : '') + (os === 'linux' ? `&pkg=${key}` : '');
  };
  const OSQ = { mac: 'mac', windows: 'win', linux: 'linux' };
  // Plain per-system route (no arch): the visitor picks the exact file there.
  const osHref = (x, p) => `/download/desktop?src=desk-${p}-${LANG}&os=${OSQ[x]}`;

  // Phones, tablets, unknown devices: the visitor picks a system; both download controls share the choice.
  let picked = null;

  function block(d, ctx, back) {
    const place = ctx || 'get';
    const primary = ctx === 'footer' ? 'btn btn-close' : 'btn';
    const extra = back ? `<button type="button" class="link-btn" data-unpick>${t.other_systems}</button>` : ctx === 'hero' ? `<a href="#get">${t.other_systems}</a>` : ctx === 'footer' ? ['mac', 'windows', 'linux'].filter(x => x !== base(d)).map(x => `<a href="${osHref(x, place)}">${t[x]}</a>`).join('') : '';
    const meta = value => ctx === 'footer' ? '' : `<p class="get-meta">${value}</p>`;
    const content = (label, chip = '', icon = 'download') => ctx === 'footer'
      ? `<span>${t.closing_label}</span><svg class="i" aria-hidden="true"><use href="#footer-arr"/></svg>`
      : `${ico(icon)}<span class="btn-label">${label}</span>${chip}`;
    switch (d) {
      case 'mac':
      case 'mac-intel': {
        const intel = d === 'mac-intel';
        return `<a class="${primary} dl-link" data-place="${place}" href="${a(intel ? 'mac-intel' : 'mac-arm', place)}">${content(intel ? t.mac_intel : t.mac, intel ? '' : '<span class="btn-chip" dir="ltr">Apple Silicon</span>')}</a>
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
              `<button type="button" role="radio" aria-checked="${i === 0}" tabindex="${i === 0 ? 0 : -1}" data-pkg="${k}" data-name="${n}"><b dir="ltr">${n}</b><small>${s}</small></button>`).join('')}
          </div>
          <a class="${primary} dl-link" data-place="${place}" href="${a('deb', place)}" data-pkg-link>${content(t.get_pkg + ' <bdi dir="ltr">.deb</bdi>')}</a>
          ${meta(t.desk_meta)}
          <p class="get-links"><a href="${t.install}#linux">${t.first_open}</a>${extra}</p>`;
      default:
        // Phone, tablet or unknown device: no default system, three equal choices.
        return `<p class="get-for">${t.for_computers}</p>
          <div class="get-pick" role="group" aria-label="${t.pick}">${['mac', 'windows', 'linux'].map((x) => `<button type="button" class="${ctx === 'footer' ? 'btn btn-close' : 'btn btn-outline'}" data-pick="${x}" data-place="${place}" aria-label="${t[x]}">${t.plat[x]}</button>`).join('')}</div>
          ${d === 'android' ? `<p class="get-links"><a href="${t.page_android}">${t.android_link}</a></p>` : ''}
          ${d === 'unknown' ? '' : `<p class="get-send"><button type="button" class="link-btn" data-share>${ico('share')}<span>${t.send}</span></button></p><p class="get-status" role="status"></p>`}`;
    }
  }

  function updateClosingMeta(el, d) {
    const meta = el.closest('.end')?.querySelector('[data-closing-meta]');
    if (!meta) return;
    if (family(d) !== 'computer') { meta.innerHTML = t.desk_meta; return; }
    const architecture = { mac: 'Apple Silicon', 'mac-intel': 'Intel', windows: 'x64', 'windows-arm': 'ARM', linux: linuxArch }[d];
    const parts = [t.plat[base(d)], architecture].filter(Boolean).map(part => `<bdi>${part}</bdi>`);
    if (d === 'linux') parts.push('<bdi data-closing-package>.deb</bdi>');
    parts.push(t.desk_meta);
    meta.innerHTML = parts.join(' · ');
  }

  function render(el, d, animate) {
    const back = family(d) !== 'computer' && !!picked;
    const next = back ? picked : d;
    const signature = (next === 'linux' ? next + ':' + linuxArch : next) + (back ? ':back' : '');
    if (el.dataset.for === signature) return;
    const swap = () => {
      el.dataset.for = signature;
      // Choosing re-renders the control and moves focus itself; announcing the whole block on top would double up.
      el.setAttribute('aria-live', family(d) === 'computer' ? 'polite' : 'off');
      el.innerHTML = block(next, el.hasAttribute('data-closing') ? 'footer' : el.closest('.get-hero') ? 'hero' : '', back);
      if (el.hasAttribute('data-closing')) updateClosingMeta(el, next);
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
      span.innerHTML = `<span data-compact-label="long">${label}</span><span data-compact-label="short">${t.plat[base(d)]}</span>`;
      el.setAttribute('aria-label', label);
    });
  }

  setDevice(device, false);

  document.addEventListener('click', (ev) => {
    const pkg = ev.target.closest('[data-pkg]');
    if (pkg) { choosePkg(pkg); return; }
    const pick = ev.target.closest('[data-pick]');
    const unpick = ev.target.closest('[data-unpick]');
    if (pick || unpick) {
      const host = (pick || unpick).closest('[data-get]');
      picked = pick ? pick.dataset.pick : null;
      gets.forEach((el) => render(el, device, false));
      const next = host && host.querySelector(pick ? '.dl-link' : '[data-pick]');
      if (next) next.focus();
      return;
    }
    const share = ev.target.closest('[data-share]');
    if (share) {
      const url = location.origin + location.pathname;
      const st = share.closest('[data-get]').querySelector('.get-status');
      const say = (msg) => { if (st) st.textContent = msg; };
      const copy = () => (navigator.clipboard ? navigator.clipboard.writeText(url) : Promise.reject()).then(() => say(t.copied), () => say(url));
      if (navigator.share) navigator.share({ title: 'Nezzel', url }).catch((e) => { if (!e || e.name !== 'AbortError') copy(); });
      else copy();
    }
  });

  function choosePkg(b) {
    const g = b.parentElement;
    g.querySelectorAll('[role=radio]').forEach((r) => { const on = r === b; r.setAttribute('aria-checked', String(on)); r.tabIndex = on ? 0 : -1; });
    const link = g.parentElement.querySelector('[data-pkg-link]');
        link.href = a(b.dataset.pkg, link.dataset.place || 'get');
    const label = link.querySelector('bdi') || g.closest('.end')?.querySelector('[data-closing-package]');
    if (label) label.textContent = b.dataset.name;
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
