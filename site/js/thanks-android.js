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
