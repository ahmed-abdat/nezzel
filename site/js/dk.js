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
  const ico = (id) => `<svg class="dk-i" aria-hidden="true"><use href="#i-${id}"/></svg>`;
  const a = (key, p) => {
    const [os, k] = KEYS[key];
    const arch = os === 'linux' ? linuxArch : k;
    return `/download/desktop?src=desk-${p}-${LANG}&os=${os}` + (arch ? `&arch=${arch}` : '') + (os === 'linux' ? `&pkg=${key}` : '');
  };
  const linkTo = (d, p) => (d === 'android' ? apk(p) : a({ mac: 'mac-arm', windows: 'win-x64', linux: 'deb' }[d], p));

  function block(d, ctx) {
    const place = ctx || 'get';
    switch (d) {
      case 'android':
        return `<a class="dk-btn dk-btn-primary dk-btn-lg dl-link" data-place="${place}" href="${apk(place)}">${ico('download')}<span>${t.android}</span></a>
          <p class="get-meta">${t.android_meta}</p>
          ${ctx === 'hero' ? `<p class="get-links"><a href="${t.page_desktop}">${t.also_desk}</a></p>` : ''}`;
      case 'mac':
      case 'mac-intel': {
        const intel = d === 'mac-intel';
        return `<a class="dk-btn dk-btn-primary dk-btn-lg dl-link" data-place="${place}" href="${a(intel ? 'mac-intel' : 'mac-arm', place)}">${ico('download')}<span>${intel ? t.mac_intel : t.mac}</span>${intel ? '' : '<span class="dk-btn-chip" dir="ltr">Apple Silicon</span>'}</a>
          <p class="get-meta">${t.desk_meta} · ${t.mac_note}</p>
          <p class="get-links"><a href="${a(intel ? 'mac-arm' : 'mac-intel', place)}">${intel ? t.other_arch_mac_arm : t.other_arch_mac}</a><a href="${t.install}#mac">${t.first_open}</a></p>`;
      }
      case 'windows':
      case 'windows-arm': {
        const arm = d === 'windows-arm';
        return `<a class="dk-btn dk-btn-primary dk-btn-lg dl-link" data-place="${place}" href="${a(arm ? 'win-arm' : 'win-x64', place)}">${ico('download')}<span>${arm ? t.windows_arm : t.windows}</span></a>
          <p class="get-meta">${t.desk_meta}</p>
          <p class="get-links"><a href="${a(arm ? 'win-x64' : 'win-arm', place)}">${arm ? t.other_arch_win_x64 : t.other_arch_win}</a><a href="${t.install}#windows">${t.first_open}</a></p>`;
      }
      case 'linux':
        return `<div class="get-choice" role="radiogroup" aria-label="${t.pkg}">
            ${[['deb', '.deb', t.deb], ['rpm', '.rpm', t.rpm], ['appimage', 'AppImage', t.appimage]].map(([k, n, s], i) =>
              `<button type="button" role="radio" aria-checked="${i === 0}" tabindex="${i === 0 ? 0 : -1}" data-pkg="${k}" data-name="${n}"><b dir="ltr">${n}</b><small>${s}</small></button>`).join('')}
          </div>
          <a class="dk-btn dk-btn-primary dk-btn-lg dl-link" data-place="${place}" href="${a('deb', place)}" data-pkg-link>${ico('download')}<span>${t.get_pkg} <bdi dir="ltr">.deb</bdi></span></a>
          <p class="get-meta">${t.desk_meta}</p>
          <p class="get-links"><a href="${t.install}#linux">${t.first_open}</a></p>`;
      case 'iphone':
      case 'ipad':
        return `<p class="get-note"><strong>${t.ios_title}</strong> ${t.ios_body}</p>
          <button type="button" class="dk-btn dk-btn-primary dk-btn-lg" data-share>${ico('share')}<span>${t.send}</span></button>
          <p class="get-status" role="status"></p>`;
      default:
        return `<p class="get-note"><strong>${t.pick}</strong></p>
          <div class="get-row">${['android', 'mac', 'windows', 'linux'].map((x) => `<a class="dk-btn dk-btn-outline dl-link" data-place="${place}" href="${place === 'hero' && x === 'android' ? t.page_android : linkTo(x, place)}">${t.plat[x]}</a>`).join('')}</div>`;
    }
  }

  function targetFor(el, d) {
    const fixed = el.dataset.get;
    if (fixed === 'android') return 'android';
    if (fixed === 'desktop') return family(d) === 'computer' ? d : 'mac';
    return d;
  }

  function render(el, d, animate) {
    const next = targetFor(el, d);
    if (el.dataset.for === next) return;
    const swap = () => {
      el.dataset.for = next;
      el.innerHTML = block(next, el.closest('.get-hero') ? 'hero' : '');
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
    document.querySelectorAll('[data-switch]').forEach((b) => b.setAttribute('aria-pressed', String(base(d) === b.dataset.switch)));
    compact();
  }

  function compact() {
    document.querySelectorAll('[data-get-compact]').forEach((el) => {
      const fixed = el.dataset.getCompact;
      let d = fixed === 'android' ? 'android' : fixed === 'desktop-auto' ? (family(device) === 'computer' ? device : 'mac') : device;
      const span = el.querySelector('span');
      el.removeAttribute('data-share');
      const use = el.querySelector('use'); if (use) use.setAttribute('href', d === 'iphone' || d === 'ipad' ? '#i-share' : '#i-download');
      if (d === 'iphone' || d === 'ipad') { el.href = '#get'; el.setAttribute('data-share', ''); span.innerHTML = `<span data-compact-label="long">${t.send}</span><span data-compact-label="short">${t.send_short}</span>`; el.setAttribute('aria-label', t.send); return; }
      if (d === 'unknown') { el.href = '#get'; return; }
      const label = { android: t.android, mac: t.mac, 'mac-intel': t.mac_intel, windows: t.windows, 'windows-arm': t.windows_arm, linux: t.linux }[d];
      el.href = d === 'android' ? apk('nav') : d === 'linux' ? '#get' : a({ mac: 'mac-arm', 'mac-intel': 'mac-intel', windows: 'win-x64', 'windows-arm': 'win-arm' }[d], 'nav');
      span.innerHTML = `<span data-compact-label="long">${label}</span><span data-compact-label="short">${t.plat[base(d)]}</span>`;
      el.setAttribute('aria-label', label);
    });
  }

  setDevice(device, false);

  document.addEventListener('click', (ev) => {
    const sw = ev.target.closest('[data-switch]');
    if (sw) {
      const k = sw.dataset.switch;
      setDevice(k === 'mac' && device === 'mac-intel' ? 'mac-intel' : k === 'windows' && device === 'windows-arm' ? 'windows-arm' : k, true);
      return;
    }
    const pkg = ev.target.closest('[data-pkg]');
    if (pkg) { choosePkg(pkg); return; }
    const share = ev.target.closest('[data-share]');
    if (share) {
      ev.preventDefault();
      const url = location.origin + location.pathname.replace(/[^/]*$/, '');
      const done = () => { const st = share.parentElement.querySelector('.get-status'); if (st) st.textContent = t.copied; };
      if (navigator.share) navigator.share({ title: 'Nezzel', url }).catch(() => {});
      else if (navigator.clipboard) navigator.clipboard.writeText(url).then(done, done);
      else done();
    }
  });

  function choosePkg(b) {
    const g = b.parentElement;
    g.querySelectorAll('[role=radio]').forEach((r) => { const on = r === b; r.setAttribute('aria-checked', String(on)); r.tabIndex = on ? 0 : -1; });
    const link = g.parentElement.querySelector('[data-pkg-link]');
        link.href = a(b.dataset.pkg, link.dataset.place || 'get');
    link.querySelector('bdi').textContent = b.dataset.name;
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
    b.type = 'button'; b.className = 'copy'; b.textContent = COPY[0];
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
