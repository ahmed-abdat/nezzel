#!/usr/bin/env node
// Regenerates site/img/og-desktop{,-en,-fr}.jpg from scripts/og-desktop.html (headless Chrome + sips).
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const root = new URL('../', import.meta.url).pathname;
const chrome = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const tpl = readFileSync(root + 'scripts/og-desktop.html', 'utf8');
const symbols = [...readFileSync(root + 'site/img/os.svg', 'utf8').matchAll(/<symbol[\s\S]*?<\/symbol>/g)].map(m => m[0]).join('');
const text = {
  ar: ['', 'نزّل الفيديو والصوت والصور من مواقع كثيرة', 'مجاني ومفتوح المصدر، بلا إعلانات ولا حساب', 'rtl'],
  en: ['-en', 'Save videos, audio and photos from many sites', 'Free, open source, no ads, no account', 'ltr'],
  fr: ['-fr', 'Enregistrez vidéos, sons et photos depuis de nombreux sites', 'Gratuit, open source, sans pub ni compte', 'ltr'],
};
const dir = mkdtempSync(join(tmpdir(), 'og-desktop-'));
for (const [lang, [suffix, headline, sub, d]] of Object.entries(text)) {
  const html = tpl.replace(/\{\{(\w+)\}\}/g, (_, k) => ({
    lang, dir: d, headline, sub, symbols,
    fonts: 'file://' + root + 'site/fonts', icon: 'file://' + root + 'site/img/icon.svg',
    img: 'file://' + root + `site/img/desktop/hero-${lang}-light-1500.webp`,
  })[k]);
  const page = join(dir, `${lang}.html`), png = join(dir, `${lang}.png`), out = root + `site/img/og-desktop${suffix}.jpg`;
  writeFileSync(page, html);
  execFileSync(chrome, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files', '--virtual-time-budget=3000', `--screenshot=${png}`, '--window-size=1200,630', '--force-device-scale-factor=1', 'file://' + page], { stdio: 'ignore' });
  execFileSync('sips', ['-s', 'format', 'jpeg', '-s', 'formatOptions', '82', png, '--out', out], { stdio: 'ignore' });
  console.log('Wrote', out);
}
