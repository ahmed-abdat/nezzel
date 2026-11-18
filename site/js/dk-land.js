/* Nezzel Desktop landing: motion and media. Loaded after dk.js; the page reads fine without it.
   Recordings: an element with data-rec="name" becomes a muted loop only when it also carries data-rec-on.
   Files: /img/desktop/rec/<name>-<lang>-<light|dark>.webm and .mp4 (see the clip list in the unit report). */
(() => {
  const root = document.documentElement;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');
  const theme = () => (root.dataset.theme === 'dark' ? 'dark' : 'light');
  const still = () => reduce.matches;

  /* ---- headline: word by word, once on load. Words stay whole, so Arabic letters stay joined ---- */
  const h1 = document.getElementById('h1');
  if (h1 && !still() && !h1.children.length) {
    const words = h1.textContent.trim().split(/\s+/);
    h1.setAttribute('aria-label', h1.textContent.trim());
    h1.textContent = '';
    words.forEach((w, i) => {
      const s = document.createElement('span');
      s.className = 'w'; s.style.setProperty('--i', i); s.textContent = w; s.setAttribute('aria-hidden', 'true');
      h1.append(s, i < words.length - 1 ? ' ' : '');
    });
    h1.classList.add('h1-split');
  }

  /* ---- download buttons (hero and header): press, "Starting download", then the thank-you page ---- */
  const hero = document.querySelector('.get-hero');
  document.addEventListener('click', (ev) => {
    const l = ev.target.closest && ev.target.closest('.get-hero a.dl-link, .bar a.mini[href*="/thanks/?"]');
    if (!l || ev.defaultPrevented || ev.button || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey || still() || l.classList.contains('is-starting')) return;
    ev.preventDefault();
    const big = l.classList.contains('dl-link');
    if (big && hero && hero.dataset.starting) {
      let st = l.querySelector('.dl-state');
      if (!st) { st = document.createElement('span'); st.className = 'dl-state'; st.setAttribute('role', 'status'); l.append(st); }
      st.textContent = hero.dataset.starting;
      l.style.minInlineSize = l.offsetWidth + 'px';
    }
    l.classList.add('is-starting');
    setTimeout(() => { location.href = l.href; }, big ? 620 : 320);
  });
  // Coming back from the thank-you page (history cache): the buttons are ready again.
  addEventListener('pageshow', () => document.querySelectorAll('.is-starting').forEach((l) => { l.classList.remove('is-starting'); l.style.minInlineSize = ''; }));

  /* ---- recordings: play in view, pause out of view; the still stays under them ---- */
  const recs = [...document.querySelectorAll('[data-rec][data-rec-on]')];
  if (recs.length && !still() && 'IntersectionObserver' in window) {
    const vio = new IntersectionObserver((es) => es.forEach((en) => { const v = en.target; if (en.isIntersecting) { const p = v.play(); if (p) p.catch(() => {}); } else v.pause(); }), { threshold: 0.35 });
    const build = (el) => {
      const host = el.querySelector('.lpf-win') || el;
      host.querySelectorAll('.rec-v').forEach((v) => { vio.unobserve(v); v.remove(); });
      const v = document.createElement('video');
      v.className = 'rec-v'; v.muted = true; v.loop = true; v.playsInline = true; v.preload = 'none';
      v.setAttribute('disablepictureinpicture', ''); v.setAttribute('aria-hidden', 'true');
      const base = `/img/desktop/rec/${el.dataset.rec}-${root.lang}-${theme()}`;
      [['webm', 'video/webm'], ['mp4', 'video/mp4']].forEach(([x, type]) => { const s = document.createElement('source'); s.src = `${base}.${x}`; s.type = type; v.append(s); });
      host.append(v); el.classList.add('rec-on'); vio.observe(v);
    };
    recs.forEach(build);
    new MutationObserver(() => recs.forEach(build)).observe(root, { attributes: true, attributeFilter: ['data-theme'] });
  }

  /* ---- tour ---- */
  const steps = [...document.querySelectorAll('.tour-step')];
  const wide = matchMedia('(min-width:1100px)');

  // Step three: the real list capture, cut on its own row lines (measured: 72 + 4 x 128 px in Arabic, 60 + 4 x 122 px otherwise).
  const listFig = steps[2] && steps[2].querySelector('.tour-fig');
  const HEAD = root.lang === 'ar' ? 72 : 60, ROW = root.lang === 'ar' ? 128 : 122, N = 4;
  let rows = null, played = false;
  const listSrc = () => {
    const pic = listFig.querySelector('picture.th-' + theme()); if (!pic) return null;
    const img = pic.querySelector('img'), src = pic.querySelector('source');
    const small = src && matchMedia(src.media).matches;
    return { url: small ? src.srcset : img.getAttribute('src'), w: +(small ? src.getAttribute('width') : img.getAttribute('width')), h: +img.getAttribute('height'), alt: img.alt };
  };
  function buildRows() {
    if (!listFig || still() || listFig.hasAttribute('data-rec-on')) return;
    const m = listSrc(); if (!m || !m.w) return;
    if (rows) rows.remove();
    rows = document.createElement('div'); rows.className = 'rows'; rows.setAttribute('role', 'img'); rows.setAttribute('aria-label', m.alt);
    rows.style.aspectRatio = m.w + ' / ' + m.h;
    const cut = (y, h, cls) => {
      const d = document.createElement('div'); d.className = cls;
      d.style.insetBlockStart = (y / m.h * 100) + '%'; d.style.blockSize = (h / m.h * 100) + '%';
      const im = new Image(); im.alt = ''; im.decoding = 'async'; im.src = m.url; im.style.marginBlockStart = (-y / m.w * 100) + '%';
      d.append(im); rows.append(d); return d;
    };
    cut(0, HEAD, 'head');
    for (let j = 0; j < N; j++) cut(HEAD + j * ROW, ROW, 'row');
    cut(HEAD + N * ROW, Math.max(0, m.h - HEAD - N * ROW), 'head');
    listFig.append(rows); listFig.classList.add('has-rows');
    show(played ? N : 0, false);
  }
  // n rows are in the list: the last n of the capture, so the final frame is the capture itself.
  function show(n, animate) {
    if (!rows) return;
    rows.querySelectorAll('.row').forEach((r, j) => {
      const on = j >= N - n;
      if (!animate) r.style.transition = 'none';
      r.classList.toggle('off', !on);
      r.style.transform = on ? `translateY(${(j - (N - n)) - j}00%)` : `translateY(${-j * 100 - 40}%) scale(.96)`;
      if (!animate) { void r.offsetWidth; r.style.transition = ''; }
    });
  }
  function playRows() {
    if (played || !rows) return; played = true;
    for (let n = 1; n <= N; n++) setTimeout(() => show(n, true), 250 + (n - 1) * 800);
  }

  if (steps.length && 'IntersectionObserver' in window) {
    buildRows();
    new MutationObserver(buildRows).observe(root, { attributes: true, attributeFilter: ['data-theme'] });
    matchMedia('(max-width:640px)').addEventListener('change', buildRows);
    const on = (step, exclusive) => {
      if (exclusive) steps.forEach((s) => s !== step && s.classList.remove('is-on'));
      step.classList.add('is-on');
      if (step === steps[2]) playRows();
    };
    let io = null;
    const mode = () => {
      if (io) io.disconnect();
      steps.forEach((s) => s.classList.remove('is-on'));
      const sticky = wide.matches;
      root.classList.toggle('js-tour', sticky);
      if (sticky) {
        // The step whose text crosses the middle band of the window is the current one.
        on(steps[0], true);
        io = new IntersectionObserver((es) => es.forEach((en) => { if (en.isIntersecting) on(en.target.closest('.tour-step'), true); }), { rootMargin: '-45% 0px -45% 0px' });
        steps.forEach((s) => io.observe(s.querySelector('.tour-tx')));
      } else {
        io = new IntersectionObserver((es) => es.forEach((en) => { if (en.isIntersecting) { on(en.target.closest('.tour-step'), false); io.unobserve(en.target); } }), { threshold: 0.4 });
        steps.forEach((s) => io.observe(s.querySelector('.tour-fig')));
      }
    };
    mode();
    wide.addEventListener('change', mode);
    // Keyboard focus inside a step makes it the current one, so a focused link is never dimmed.
    document.addEventListener('focusin', (e) => { const s = e.target.closest && e.target.closest('.tour-step'); if (s && wide.matches) on(s, true); });
  }

  /* ---- closing wordmark: the name starts behind the app icon ---- */
  const mark = document.querySelector('.end .mark');
  if (mark) mark.classList.add('mark-behind');
})();
