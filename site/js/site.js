/* Shared chrome; page-specific theme work listens for nezzel:theme. */
(() => {
  const root = document.documentElement;
  const header = document.querySelector('.bar');
  const themeButton = document.getElementById('theme');
  const languages = document.getElementById('locale-switch');
  const summary = languages?.querySelector('summary');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const mode = () => root.dataset.theme === 'dark' ? 'dark' : 'light';

  function syncTheme() {
    const theme = mode();
    root.dataset.mode = theme;
    if (themeButton) {
      const key = theme === 'dark' ? 'toLight' : 'toDark';
      if (themeButton.dataset[key]) themeButton.setAttribute('aria-label', themeButton.dataset[key]);
    }
    document.querySelectorAll('meta[name="theme-color"]').forEach(meta => {
      meta.content = getComputedStyle(root).getPropertyValue('--app').trim();
      meta.removeAttribute('media');
    });
    document.dispatchEvent(new CustomEvent('nezzel:theme', { detail: { theme } }));
  }
  function applyTheme(theme) {
    root.dataset.theme = theme;
    try { localStorage.setItem('nezzel-theme', theme); } catch (e) {}
    syncTheme();
  }
  themeButton?.addEventListener('click', () => {
    const next = mode() === 'dark' ? 'light' : 'dark';
    const apply = () => applyTheme(next);
    if (reduced.matches || !document.startViewTransition) { apply(); return; }
    const b = themeButton.getBoundingClientRect(), w = innerWidth, h = innerHeight;
    const x = b.left + b.width / 2, y = b.top + b.height / 2;
    const r = Math.hypot(Math.max(x, w - x), Math.max(y, h - y)) / (Math.hypot(w, h) / Math.SQRT2) * 100;
    const at = ' at ' + (x / w * 100) + '% ' + (y / h * 100) + '%';
    root.dataset.vt = '';
    const transition = document.startViewTransition(apply);
    transition.ready.then(() => root.animate({ clipPath: ['circle(0%' + at + ')', 'circle(' + r + '%' + at + ')'] },
      { duration: 600, easing: 'cubic-bezier(.16,1,.3,1)', pseudoElement: '::view-transition-new(root)' })).catch(() => {});
    transition.finished.then(() => { delete root.dataset.vt; }, () => { delete root.dataset.vt; });
  });
  addEventListener('storage', event => {
    if (event.key === 'nezzel-theme') {
      if (event.newValue === 'light' || event.newValue === 'dark') root.dataset.theme = event.newValue;
      else delete root.dataset.theme;
      syncTheme();
    }
  });
  syncTheme();

  // Plain disclosure links retain native Tab/Shift+Tab behavior.
  const closeLanguages = (refocus = false) => {
    if (!languages) return;
    languages.open = false;
    if (refocus) summary?.focus();
  };
  if (languages) {
    let keyboardFocus = false;
    languages.addEventListener('toggle', () => {
      summary?.setAttribute('aria-expanded', String(languages.open));
      if (languages.open) document.body.classList.remove('tuck');
    });
    summary?.setAttribute('aria-expanded', String(languages.open));
    document.addEventListener('pointerdown', event => {
      keyboardFocus = false;
      if (!languages.contains(event.target)) closeLanguages();
    });
    document.addEventListener('keydown', event => {
      keyboardFocus = true;
      if (event.key === 'Escape' && languages.open) { event.preventDefault(); closeLanguages(true); }
    });
    languages.addEventListener('blur', event => {
      if (keyboardFocus && event.relatedTarget && !languages.contains(event.relatedTarget)) closeLanguages();
    }, true);
    const links = [...languages.querySelectorAll('[data-locale]')];
    const syncHashes = () => links.forEach(link => { link.hash = location.hash; });
    syncHashes(); addEventListener('hashchange', syncHashes);
    links.forEach(link => link.addEventListener('click', () => {
      try { localStorage.setItem('nezzel-lang', link.dataset.locale); } catch (e) {}
      closeLanguages();
    }));
  }
  // Narrow-screen menu: a disclosure button; closes on Esc, outside tap, link tap, focus leaving, and when the wide header takes over.
  const menuBtn = document.getElementById('menu-btn');
  const menuPanel = document.getElementById('menu-panel');
  const setMenu = (open, refocus = false) => {
    if (!menuBtn || !header) return;
    header.classList.toggle('menu-open', open);
    menuBtn.setAttribute('aria-expanded', String(open));
    if (open) { closeLanguages(); document.body.classList.remove('tuck'); }
    if (!open && refocus) menuBtn.focus();
  };
  if (menuBtn && menuPanel) {
    const wide = matchMedia('(min-width:1024px)');
    menuBtn.addEventListener('click', () => setMenu(menuBtn.getAttribute('aria-expanded') !== 'true'));
    menuPanel.addEventListener('click', event => { if (event.target.closest('a')) setMenu(false); });
    document.addEventListener('pointerdown', event => { if (!header.contains(event.target)) setMenu(false); });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && menuBtn.getAttribute('aria-expanded') === 'true') { event.preventDefault(); setMenu(false, true); }
    });
    header.addEventListener('focusout', event => {
      if (event.relatedTarget && !menuBtn.contains(event.relatedTarget) && !menuPanel.contains(event.relatedTarget)) setMenu(false);
    });
    wide.addEventListener('change', () => { if (wide.matches) setMenu(false); });
    languages?.addEventListener('toggle', () => { if (languages.open) setMenu(false); });
  }
  let lastY = scrollY, accumulated = 0;
  const onScroll = () => {
    const y = scrollY, delta = y - lastY; lastY = y;
    accumulated = Math.sign(delta) === Math.sign(accumulated) ? accumulated + delta : delta;
    document.body.classList.toggle('scrolled', y > 10);
    if (y < 160 || accumulated < -24 || languages?.open || header?.classList.contains('menu-open') || header?.contains(document.activeElement)) document.body.classList.remove('tuck');
    else if (accumulated > 48) document.body.classList.add('tuck');
  };
  addEventListener('scroll', onScroll, { passive: true }); onScroll();
  // Preserve the homepage CTA boundary; desktop landings use their hero download block.
  // Install pages have no hero download, so keep their compact download available.
  const heroDownload = document.querySelector('.hero .hero-actions, .get-hero');
  if (heroDownload) {
    new IntersectionObserver(([entry]) => {
      document.body.classList.toggle('past', !entry.isIntersecting && entry.boundingClientRect.top < 0);
    }).observe(heroDownload);
  } else if (header?.dataset.product === 'computer') document.body.classList.add('past');
  const mini = header?.querySelector('.mini');
  if (mini) {
    const syncDownloadFocus = () => { mini.tabIndex = getComputedStyle(mini).display !== 'none' && (matchMedia('(min-width:641px)').matches || document.body.classList.contains('past')) ? 0 : -1; };
    new MutationObserver(syncDownloadFocus).observe(document.body, { attributes: true, attributeFilter: ['class'] });
    addEventListener('resize', syncDownloadFocus); syncDownloadFocus();
  }
})();
