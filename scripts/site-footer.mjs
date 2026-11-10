// Shared closing component. Copy is validated from the homepage dictionaries.
const icons = `<symbol id="footer-arr" viewBox="0 0 24 24"><path d="M4 12h16m-6-6 6 6-6 6"/></symbol><symbol id="footer-share" viewBox="0 0 24 24"><path d="M15 5l4 4-4 4M19 9H9a5 5 0 0 0 0 10h1"/></symbol><symbol id="footer-mail" viewBox="0 0 24 24"><rect x="3.5" y="5.5" width="17" height="13" rx="2.5"/><path d="m4.5 7.5 7.5 6 7.5-6"/></symbol><symbol id="footer-ne" viewBox="0 0 24 24"><path d="M7 17 17 7M8 7h9v9"/></symbol><symbol id="footer-lg-facebook" viewBox="0 0 24 24"><path d="M9.101 23.691v-7.98H6.627v-3.667h2.474v-1.58c0-4.085 1.848-5.978 5.858-5.978.401 0 .955.042 1.468.103a8.68 8.68 0 0 1 1.141 .195 v3.325a8.623 8.623 0 0 0 -.653 -.036 26.805 26.805 0 0 0 -.733 -.009 c-.707 0-1.259.096-1.675.309a1.686 1.686 0 0 0 -.679 .622 c-.258.42-.374.995-.374 1.752v1.297h3.919l-.386 2.103-.287 1.564h-3.246v8.245C19.396 23.238 24 18.179 24 12.044c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.628 3.874 10.35 9.101 11.647Z "/></symbol><symbol id="footer-lg-tiktok" viewBox="0 0 24 24"><path d="M12.525.02c1.31-.02 2.61-.01 3.91-.02.08 1.53.63 3.09 1.75 4.17 1.12 1.11 2.7 1.62 4.24 1.79v4.03c-1.44-.05-2.89-.35-4.2-.97-.57-.26-1.1-.59-1.62-.93-.01 2.92.01 5.84-.02 8.75-.08 1.4-.54 2.79-1.35 3.94-1.31 1.92-3.58 3.17-5.91 3.21-1.43.08-2.86-.31-4.08-1.03-2.02-1.19-3.44-3.37-3.65-5.71-.02-.5-.03-1-.01-1.49.18-1.9 1.12-3.72 2.58-4.96 1.66-1.44 3.98-2.13 6.15-1.72.02 1.48-.04 2.96-.04 4.44-.99-.32-2.15-.23-3.02.37-.63.41-1.11 1.04-1.36 1.75-.21.51-.15 1.07-.14 1.61.24 1.64 1.82 3.02 3.5 2.87 1.12-.01 2.19-.66 2.77-1.61.19-.33.4-.67.41-1.06.1-1.79.06-3.57.07-5.36.01-4.03-.01-8.05.02-12.07z "/></symbol><symbol id="footer-lg-linkedin" viewBox="0 0 24 24"><path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433c-1.144 0-2.063-.926-2.063-2.065 0-1.138.92-2.063 2.063-2.063 1.14 0 2.064.925 2.064 2.063 0 1.139-.925 2.065-2.064 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z"/></symbol><symbol id="footer-lg-whatsapp" viewBox="0 0 24 24"><path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 0 1 -5.031 -1.378 l-.361-.214-3.741.982.998-3.648-.235-.374a9.86 9.86 0 0 1 -1.51 -5.26 c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03 6.988 2.898a9.825 9.825 0 0 1 2.893 6.994 c-.003 5.45-4.437 9.884-9.885 9.884m8.413-18.297A11.815 11.815 0 0 0 12.05 0 C5.495 0 .16 5.335.157 11.892c0 2.096.547 4.142 1.588 5.945L.057 24l6.305-1.654a11.882 11.882 0 0 0 5.683 1.448 h.005c6.554 0 11.89-5.335 11.893-11.893a11.821 11.821 0 0 0 -3.48 -8.413 Z "/></symbol>`;
const social = `    <nav class="soc fe" style="--f:3" aria-label="Hululsoft">
      <a href="https://web.facebook.com/hululsoft" target="_blank" rel="noopener" aria-label="Hululsoft · Facebook"><svg viewBox="0 0 24 24" aria-hidden="true"><use href="#footer-lg-facebook"/></svg></a>
      <a href="https://www.tiktok.com/@hululsofte" target="_blank" rel="noopener" aria-label="Hululsoft · TikTok"><svg viewBox="0 0 24 24" aria-hidden="true"><use href="#footer-lg-tiktok"/></svg></a>
      <a href="https://linkedin.com/company/hululsoft" target="_blank" rel="noopener" aria-label="Hululsoft · LinkedIn"><svg viewBox="0 0 24 24" aria-hidden="true"><use href="#footer-lg-linkedin"/></svg></a>
      <a href="https://wa.me/22237928327" target="_blank" rel="noopener" aria-label="Hululsoft · WhatsApp"><svg viewBox="0 0 24 24" aria-hidden="true"><use href="#footer-lg-whatsapp"/></svg></a>
      <a href="mailto:contact@hululsoft.mr" aria-label="contact@hululsoft.mr"><svg class="i" aria-hidden="true"><use href="#footer-mail"/></svg></a>
    </nav>`;
const wordmark = `    <div class="mark" aria-hidden="true"><img src="/img/icon.svg" alt=""><svg viewBox="0 0 600 150"><text x="0" y="128" textLength="600" lengthAdjust="spacingAndGlyphs">Nezzel</text></svg></div>`;

export function footer(dict, locale, page, paths, escape, release) {
  const raw = key => {
    const value = dict[key];
    if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing footer translation: ${locale}: ${key}`);
    return value;
  };
  const text = key => escape(raw(key));
  const home = paths[locale], desktop = page !== '';
  // Only destinations already present in this site's product pages are included.
  const repos = desktop ? {
    'footer-source': 'https://github.com/ahmed-abdat/nezzel-desktop',
    priv: 'https://github.com/ahmed-abdat/nezzel-desktop/blob/main/PRIVACY.md',
    'footer-desktop-license': 'https://github.com/ahmed-abdat/nezzel-desktop/blob/main/LICENSE',
    'footer-report': 'https://github.com/ahmed-abdat/nezzel-desktop/issues/new/choose',
  } : {
    'footer-source': 'https://github.com/ahmed-abdat/nezzel',
    rel: 'https://github.com/ahmed-abdat/nezzel/releases',
    priv: 'https://github.com/ahmed-abdat/nezzel/blob/main/PRIVACY.md',
    lic: 'https://github.com/ahmed-abdat/nezzel/blob/main/THIRD_PARTY_NOTICES.md',
    sec: 'https://github.com/ahmed-abdat/nezzel/blob/main/SECURITY.md',
  };
  const links = [[home, 'app-android', page === ''], [home + 'desktop/', 'app-desktop', page === 'desktop/'], [home + 'desktop/install/', 'nl3', page === 'desktop/install/'],
    ...Object.entries(repos).map(([key, url]) => [url, key, false])];
  const size = new Intl.NumberFormat(locale + '-u-nu-latn', { maximumFractionDigits: 1 }).format(release.mb) + (locale === 'fr' ? '\u00a0Mo' : ' MB');
  const controls = desktop
    ? `<div class="closing-download fe" data-get="desktop" data-closing aria-live="polite" style="--f:1"><a class="btn btn-close" data-place="footer" href="/download/desktop?src=desk-cta-${locale}&amp;os=mac"><span>${text('desktop-end-button')}</span><svg class="i" aria-hidden="true"><use href="#footer-arr"/></svg></a></div>`
    : `<a class="btn btn-close dl-link fe" style="--f:1" data-place="footer" href="/download?src=footer-${locale}"><span>${text('get-big')}</span><svg class="i" aria-hidden="true"><use href="#footer-arr"/></svg></a>
    <button class="btn btn-close iob fe" style="--f:1" type="button"><span>${text('ios-share')}</span><svg class="i" aria-hidden="true"><use href="#footer-share"/></svg></button>`;
  const meta = desktop ? `<bdi>${text('download-platform-mac')}</bdi> · <bdi>Apple Silicon</bdi> · ${raw('desktop-end-meta')}` : `<span>${text('meta')}</span> <span class="ltr num vtxt">${escape(release.version)}</span> · <span class="ltr num size" data-mb="${release.mb}">${size}</span>`;
  return `<!-- chrome:footer -->
<footer class="end" id="${desktop ? 'get' : 'final'}"${desktop ? '' : ' data-reach="end" data-entrance'} aria-labelledby="fin-h">
  <svg class="chrome-icons" aria-hidden="true">${icons}</svg>
  <div class="wrap">
    <h2 id="fin-h" class="fe">${text(desktop ? 'desktop-end-h' : 'fin-h')}</h2>${desktop ? `
    <p class="end-desc fe">${text('desktop-end-p')}</p>` : ''}
    ${controls}
    <p class="gm fe" style="--f:2"${desktop ? ' data-closing-meta' : ''}>${meta}</p>
    <hr class="sep">
    <div class="frow fe" style="--f:3">
      <nav class="fl" aria-label="Nezzel">${links.map(([url, key, current]) => `
        <a href="${escape(url)}"${current ? ' aria-current="page"' : ''}>${text(key)}${url.startsWith('https://github.com/') ? '<svg class="i ne" aria-hidden="true"><use href="#footer-ne"/></svg>' : ''}</a>`).join('')}
      </nav>
      <div class="fm"><span class="langs">${['ar', 'en', 'fr'].map(language => `<a href="${paths[language]}${page}" hreflang="${language}" lang="${language}" dir="${language === 'ar' ? 'rtl' : 'ltr'}"${locale === language ? ' aria-current="page"' : ''}>${text('lang-' + language)}</a>`).join('')}</span><span>${raw('foot')}</span></div>
    </div>
${social}
${desktop ? '' : `    <p class="cr fe" style="--f:3">${raw('credit')}</p>\n`}${wordmark}
  </div>
</footer>
<!-- chrome:/footer -->`;
}

export function replaceFooter(source, markup) {
  if (source.split('<!-- chrome:footer -->').length !== 2 || source.split('<!-- chrome:/footer -->').length !== 2) throw new Error('Expected exactly one chrome:footer marker pair');
  return source.replace(/<!-- chrome:footer -->[\s\S]*?<!-- chrome:\/footer -->/, () => markup);
}

// The existing desktop download script consumes a generated projection of the same dictionary.
export function downloadStrings(dict, locale, paths) {
  const value = key => {
    if (typeof dict[key] !== 'string' || !dict[key].trim()) throw new Error(`Missing footer translation: ${locale}: ${key}`);
    return dict[key];
  };
  const keys = ["android", "mac", "mac_intel", "windows", "windows_arm", "linux", "android_meta", "mac_note", "other_arch_mac", "other_arch_mac_arm", "other_arch_win", "other_arch_win_x64", "other_arch_linux", "first_open", "also_desk", "pkg", "deb", "rpm", "appimage", "get_pkg", "ios_title", "ios_body", "send", "send_short", "copied", "copy_link", "link_copied", "share_more", "hold_copy", "pick", "for_computers", "android_link", "other_systems", "thanks_what", "thanks_sys_mac_arm", "thanks_sys_mac_intel", "thanks_sys_win_arm", "thanks_sys_linux", "thanks_sys_linux_arm"];
  return {
    ...Object.fromEntries(keys.map(key => [key, value('download-' + key)])),
    desk_meta: value('desktop-end-meta'),
    closing_label: value('desktop-end-button'),
    plat: Object.fromEntries(['android', 'mac', 'windows', 'linux'].map(key => [key, value('download-platform-' + key)])),
    install: paths[locale] + 'desktop/install/', page_desktop: paths[locale] + 'desktop/', page_android: paths[locale],
  };
}
