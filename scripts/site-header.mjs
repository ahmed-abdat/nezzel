// Markup only. All translated strings come from the homepage dictionaries.
export function header(dict, locale, page, paths, escape) {
  const text = key => {
    const value = dict[key];
    if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing header translation: ${locale}: ${key}`);
    return escape(value);
  };
  const home = paths[locale];
  const computer = page !== '';
  const link = (href, key, current = false) => `<a href="${escape(href)}"${current ? ' aria-current="page"' : ''}>${text(key)}</a>`;
  const pages = page === ''
    ? [['#how', 'nl1'], ['#more', 'nl2'], ['#install', 'nl3'], ['#faq', 'nl4']]
    : page === 'desktop/' ? [[home + 'desktop/install/', 'nl3'], ['#faq', 'nl4']]
    : [[home + 'desktop/#faq', 'nl4']];
  pages.push(computer ? [home, 'app-android'] : [home + 'desktop/', 'app-desktop']);
  return `<!-- chrome:header -->
<a class="sr" href="#main">${text('skip')}</a>
<div class="wrap top">
  <nav class="bar" aria-label="Nezzel" data-product="${computer ? 'computer' : 'phone'}">
    <a class="brand" href="${computer ? home : '#top'}" aria-label="Nezzel"><img src="/img/icon.svg" alt="" width="34" height="34"><span class="bn" dir="ltr">Nezzel</span></a>
    <div class="nl">${pages.map(([href, key]) => link(href, key)).join('')}</div><span class="sp"></span>
    <button class="theme" type="button" id="theme" data-to-dark="${text('to-dark')}" data-to-light="${text('to-light')}" aria-label="${text('to-dark')}"><svg class="moon i" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10Z"/></svg><svg class="sun i" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/></svg></button>
    <details class="locale-switch" id="locale-switch">
      <summary class="lang" id="lang" aria-controls="lang-menu" aria-label="${text('language')}"><svg class="i" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/></svg><span id="lang-code" lang="en" dir="ltr">${locale.toUpperCase()}</span></summary>
      <div class="lang-menu" id="lang-menu">${['ar', 'en', 'fr'].map((language, row) => `
        <a href="${paths[language]}${page}" hreflang="${language}" lang="${language}" data-locale="${language}" style="--row:${row}"${language === locale ? ' aria-current="page"' : ''}><span dir="${language === 'ar' ? 'rtl' : 'ltr'}">${text('lang-' + language)}</span><svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg></a>`).join('')}
      </div>
    </details>
    <a class="mini${computer ? '' : ' dl-link'}" ${computer ? 'data-get-compact="desktop-auto" href="#get"' : `data-place="nav" href="/download?src=nav-${locale}"`} aria-label="${text('get')}"><svg class="i" aria-hidden="true"><use href="#${computer ? 'i-download' : 'dl'}"/></svg><span>${text('get')}</span></a>
  </nav>
</div>
<!-- chrome:/header -->`;
}

export function replaceHeader(source, markup) {
  if (source.split('<!-- chrome:header -->').length !== 2 || source.split('<!-- chrome:/header -->').length !== 2) throw new Error('Expected exactly one chrome:header marker pair');
  return source.replace(/<!-- chrome:header -->[\s\S]*?<!-- chrome:\/header -->/, () => markup);
}

// The early theme state and desktop no-JS fallback are generated and checked too.
export function themeHead(source, desktop = false) {
  const boot = '<script>document.documentElement.classList.add("js");try{const t=localStorage.getItem("nezzel-theme");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}document.documentElement.dataset.mode=document.documentElement.dataset.theme||"light"</script>';
  const early = /<script>document\.documentElement\.classList\.add\("js"\);[^<]*<\/script>(?:\n<noscript><style>[^<]*<\/style><\/noscript>)?/;
  if (!early.test(source)) throw new Error('Missing early theme snippet');
  return source.replace(early, () => boot + (desktop ? '\n<noscript><style>.theme{display:none}</style></noscript>' : ''))
    .replace(/(<meta name="theme-color" content=")[^"]*(">)/, '$1#F5FBF8$2');
}
