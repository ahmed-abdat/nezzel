/* Nezzel Android thank-you page (/thanks/). Loaded in <head> (not deferred) so the state is set before first paint.
   ?src=tag&t=token. The same single-download guard as /js/thanks.js: the APK starts once, only with a per-click token `t`
   (set by the home page's button at click time). Reload, back/forward, a duplicated tab or a language switch never start it again.
   No token (bookmark, shared link): the page is settled and offers the file as a button. No JavaScript: the status line
   says "didn't start?" with a plain download link. */
(() => {
  const root = document.documentElement;
  const LANG = root.lang || 'ar';
  const q = new URLSearchParams(location.search);
  // Only a plain tag ever reaches the file URL (same rule as the home page's ?src=).
  const src = /^[a-z0-9-]{1,24}$/.test(q.get('src') || '') ? q.get('src') : 'thanks-' + LANG;
  const fileUrl = '/download?src=' + src;
  const tok = /^[a-z0-9]{1,12}$/.test(q.get('t') || '') ? q.get('t') : null;
  const key = 'nz-thanks:android:' + src + (tok ? '&t=' + tok : '');
  let started = false;
  // The install recording waits for the APK request on a fresh visit, so its 0.5 MB never competes with the download.
  let armFilm = () => {};
  const filmReady = fresh => fresh ? new Promise((r) => { armFilm = r; }) : Promise.resolve();

  if (!tok) root.dataset.thNt = '1';
  // The stored marker wins over the navigation type, so a duplicated tab (same sessionStorage, type 'navigate')
  // never downloads again. Reload and back/forward never start either. A fresh click carries a new token.
  const nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
  let seen = false;
  try { seen = sessionStorage.getItem(key) === '1'; } catch (e) {}
  const fresh = !!tok && !q.has('again') && !seen && !(nav && (nav.type === 'reload' || nav.type === 'back_forward'));
  root.dataset.thAn = fresh ? 'wait' : 'done';

  const $ = (s) => document.querySelector(s);
  const all = (s) => [...document.querySelectorAll(s)];
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
    s.querySelectorAll(':scope > [data-th-st]').forEach((el) => {
      el.setAttribute('aria-hidden', String(!(el.dataset.thSt === k && el.querySelector('a'))));
    });
  };

  // Called from an inline script right after the head block, so the state is right before first paint.
  let filled = false;
  function fill() {
    if (filled || !$('[data-th-live]')) return;
    filled = true;
    all('[data-th-direct]').forEach((a) => { a.href = fileUrl; });
    show(state0);
  }
  window.__thFill = fill;

  document.addEventListener('DOMContentLoaded', () => {
    fill();
    // Language switch keeps the campaign tag, and never starts a second download (no token).
    all('a[hreflang]').forEach((a) => { a.href = a.getAttribute('href').split('?')[0] + '?src=' + src; });
    say(state0);
    if (!fresh) return;

    // Count the second only while the page is on screen (not prerendered, not in a hidden tab).
    const ready = () => !document.prerendering && !document.hidden && document.visibilityState !== 'hidden';
    const go = () => setTimeout(() => {
      if (started) return;
      if (!ready()) { wait(); return; }
      started = true;
      try { sessionStorage.setItem(key, '1'); } catch (e) {}
      location.assign(fileUrl);
      armFilm();
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

  /* ---- install recording: the home page's film pattern (plays itself in view, a tap pauses; reduced motion = poster + play button).
         No JavaScript: the poster stays and the play button is hidden by CSS. ---- */
  const gate = filmReady(fresh);
  document.addEventListener('DOMContentLoaded', () => {
    const film = $('#film');
    if (!film) return;
    const v = $('video', film), pl = $('.fpl', film);
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    let held = still, inView = false, armed = false;
    const label = () => { pl.setAttribute('aria-label', film.classList.contains('paused') ? pl.dataset.play : pl.dataset.pause); };
    const run = () => {
      if (!armed) return;
      film.classList.remove('paused'); label();
      v.play().catch(() => { film.classList.add('paused'); label(); });
    };
    v.addEventListener('pause', () => { if (held) { film.classList.add('paused'); label(); } });
    pl.addEventListener('click', () => {
      if (v.paused) { held = false; armed = true; run(); film.classList.add('blip'); setTimeout(() => film.classList.remove('blip'), 500); }
      else { held = true; v.pause(); }
    });
    if (still) { film.classList.add('paused'); label(); }
    gate.then(() => { armed = true; if (inView && !held && !document.hidden) run(); });
    new IntersectionObserver(([e]) => {
      inView = e.isIntersecting;
      if (inView) { if (!held) run(); } else if (!v.paused) v.pause();
    }, { threshold: .35 }).observe(film);
    document.addEventListener('visibilitychange', () => { if (document.hidden) v.pause(); else if (inView && !held) run(); });
  });

  // Restored from the back/forward cache: the page is exactly as it was left; nothing starts again.
  addEventListener('pageshow', (e) => { if (e.persisted) started = true; });
})();
