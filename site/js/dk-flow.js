/* Nezzel desktop: install and thank-you pages (loaded after dk.js). Everything here is an enhancement:
   without it the steps are plain numbered steps and commands are still selectable. */
(() => {
  const root = document.documentElement;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const all = (s, el = document) => [...el.querySelectorAll(s)];

  /* ---- stepper: the line between steps fills with the scroll; a passed step becomes a check ---- */
  const lists = all('.ist-list');
  if (lists.length && !reduce) {
    lists.forEach((list) => {
      list.classList.add('stp');
      all('.step-n', list).forEach((n) => {
        n.innerHTML = `<span class="sn-n">${n.textContent}</span><svg class="sn-c" viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5"/></svg>`;
      });
    });
    let raf = 0;
    const update = () => {
      raf = 0;
      const line = innerHeight * 0.62;
      lists.forEach((list) => {
        if (!list.offsetParent) return;
        const box = list.getBoundingClientRect();
        if (box.bottom < -200 || box.top > innerHeight + 200) return;
        const items = [...list.children];
        const ys = items.map((li) => { const r = li.querySelector('.step-n').getBoundingClientRect(); return r.top + r.height / 2; });
        items.forEach((li, i) => {
          const next = i < items.length - 1 ? ys[i + 1] : box.bottom;
          const f = Math.max(0, Math.min(1, (line - ys[i]) / Math.max(1, next - ys[i])));
          li.style.setProperty('--f', f.toFixed(3));
          li.classList.toggle('is-on', line >= ys[i]);
          li.classList.toggle('is-done', f >= 1);
        });
      });
    };
    const refresh = () => { if (!raf) raf = requestAnimationFrame(update); };
    addEventListener('scroll', refresh, { passive: true });
    addEventListener('resize', refresh);
    update();
  }

  /* ---- copy buttons on commands: copy icon -> check for two seconds (-> cross when the browser refuses), spoken once ---- */
  const COPY = { ar: ['نسخ', 'نُسخ', 'تعذّر النسخ'], en: ['Copy', 'Copied', 'Couldn’t copy'], fr: ['Copier', 'Copié', 'Copie impossible'] }[root.lang] || ['Copy', 'Copied', 'Couldn’t copy'];
  const codes = all('pre > code');
  if (codes.length) {
    const live = document.createElement('span');
    live.className = 'vh'; live.setAttribute('role', 'status'); live.setAttribute('aria-live', 'polite');
    document.body.append(live);
    const legacy = (text) => {
      const ta = document.createElement('textarea');
      ta.value = text; ta.setAttribute('readonly', ''); ta.style.cssText = 'position:fixed;inset-block-start:0;opacity:0';
      document.body.append(ta); ta.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (e) {}
      ta.remove();
      return ok;
    };
    codes.forEach((code) => {
      const pre = code.parentElement;
      all('.copy-btn', pre).forEach((b) => b.remove()); // the text button dk.js adds on other pages
      const wrap = document.createElement('div');
      wrap.className = 'cpy-wrap';
      pre.before(wrap); wrap.append(pre);
      const b = document.createElement('button');
      b.type = 'button'; b.className = 'cpy'; b.setAttribute('aria-label', COPY[0]);
      b.innerHTML = '<svg class="cpy-a" viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="9" width="12" height="12" rx="2.5"/><path d="M5 15a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2"/></svg><svg class="cpy-b" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg><svg class="cpy-c" viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';
      let timer = 0;
      const settle = (ok) => {
        b.dataset.state = ok ? 'done' : 'error';
        live.textContent = ''; requestAnimationFrame(() => { live.textContent = COPY[ok ? 1 : 2]; });
        clearTimeout(timer);
        timer = setTimeout(() => { delete b.dataset.state; live.textContent = ''; }, 2000);
      };
      b.addEventListener('click', () => {
        const text = code.innerText.replace(/^\$ /gm, '').trim();
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(() => settle(true), () => settle(legacy(text)));
        else settle(legacy(text));
      });
      wrap.append(b);
    });
  }

  /* ---- thank-you: the folder's files rise into view once, when the drawing is on screen ---- */
  const fold = document.querySelector('.fold');
  if (fold) {
    if (reduce || !('IntersectionObserver' in window)) fold.classList.add('is-in');
    else {
      const io = new IntersectionObserver((es) => es.forEach((en) => { if (en.isIntersecting) { fold.classList.add('is-in'); io.disconnect(); } }), { threshold: 0.6 });
      io.observe(fold);
    }
  }
})();
