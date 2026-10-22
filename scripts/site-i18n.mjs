#!/usr/bin/env node
// Node >=18, no dependencies. Run after editing site/index.html; --check never writes.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';

const root = new URL('../', import.meta.url);
const base = 'https://nezzel.hululsoft.mr';
const locales = ['ar', 'en', 'fr'];
const paths = { ar: '/', en: '/en/', fr: '/fr/' };
const ogLocales = { ar: 'ar_AR', en: 'en_US', fr: 'fr_FR' };
const check = process.argv.includes('--check');
const banner = "<!-- Generated from site/index.html by scripts/site-i18n.mjs, don't edit. -->\n";
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

// Track element boundaries, including nested markup. Ignore raw script/style text,
// comments, and quoted > characters rather than treating HTML as flat text.
function elements(html) {
  const token = /<!--[\s\S]*?-->|<![^>]*>|<\/?[a-z](?:[^>"']|"[^"]*"|'[^']*')*>/gi;
  const voids = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
  const stack = [], nodes = [];
  let match;
  while ((match = token.exec(html))) {
    const tag = match[0];
    if (tag.startsWith('<!')) continue;
    const name = /^<\/?([\w:-]+)/.exec(tag)[1].toLowerCase();
    if (tag.startsWith('</')) {
      const node = stack.pop();
      if (!node || node.name !== name) throw new Error(`Unbalanced HTML near ${tag}`);
      node.close = match.index;
      continue;
    }
    const attrs = Object.fromEntries([...tag.matchAll(/\s([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)]
      .map(([, key, double, single, bare]) => [key, double ?? single ?? bare ?? '']));
    const node = { name, attrs, start: match.index, openEnd: token.lastIndex, close: token.lastIndex };
    nodes.push(node);
    if (name === 'script' || name === 'style') {
      const end = new RegExp(`</${name}\\s*>`, 'gi');
      end.lastIndex = token.lastIndex;
      const close = end.exec(html);
      if (!close) throw new Error(`Unclosed ${name}`);
      node.close = close.index;
      token.lastIndex = end.lastIndex;
    } else if (!voids.has(name) && !tag.endsWith('/>')) stack.push(node);
  }
  if (stack.length) throw new Error(`Unclosed ${stack.at(-1).name}`);
  return nodes;
}

function dictionary(source, name) {
  // Dictionaries are literal objects, deliberately independent of the DOM.
  const literal = source.match(new RegExp(`  const ${name} = (\\{[\\s\\S]*?\\n  \\});`))?.[1];
  if (!literal) throw new Error(`Missing literal dictionary: ${name}`);
  return runInNewContext(`(${literal})`, Object.create(null), { timeout: 1000 });
}

function attr(tag, name, value) {
  const re = new RegExp(`\\s${name}=(?:"[^"]*"|'[^']*')`);
  const clean = tag.replace(re, '');
  return value === null ? clean : clean.replace(/\s*\/?>$/, end => ` ${name}="${escape(value)}"${end}`);
}

function translate(source, nodes, dict, meta, locale) {
  const edits = [];
  for (const node of nodes) {
    const a = node.attrs;
    let tag = source.slice(node.start, node.openEnd);
    const set = (key, value) => { tag = attr(tag, key, value); };
    if (a['data-i']) edits.push([node.openEnd, node.close, dict[a['data-i']]]);
    if (a['data-i-aria']) set('aria-label', dict[a['data-i-aria']]);
    if (node.name === 'html') { set('lang', locale); set('dir', locale === 'ar' ? 'rtl' : 'ltr'); }
    if (node.name === 'title') edits.push([node.openEnd, node.close, escape(meta[0])]);
    if (node.name === 'meta') {
      if (a.name === 'description' || a.property === 'og:description') set('content', meta[1]);
      if (a.property === 'og:title') set('content', meta[0]);
      if (a.property === 'og:image:alt') set('content', dict['og-alt']);
      if (a.property === 'og:url') set('content', base + paths[locale]);
      if (a.property === 'og:locale') set('content', ogLocales[locale]);
      if (a.property === 'og:locale:alternate') {
        edits.push([node.start, node.openEnd, '']);
        continue;
      }
    }
    if (a.rel === 'canonical') set('href', base + paths[locale]);
    if (a.id === 'lang-code') edits.push([node.openEnd, node.close, locale.toUpperCase()]);
    if (a['data-locale']) set('aria-current', a['data-locale'] === locale ? 'page' : null);
    if (a['data-mb']) {
      const number = new Intl.NumberFormat(locale + '-u-nu-latn', { maximumFractionDigits: 1 }).format(+a['data-mb']);
      edits.push([node.openEnd, node.close, (Object.hasOwn(a, 'data-est') ? '≈ ' : '') + number + (locale === 'fr' ? '\u00a0Mo' : ' MB')]);
    }
    if (a.type === 'application/ld+json') {
      const schema = JSON.parse(source.slice(node.openEnd, node.close));
      schema.description = meta[1]; schema.url = base + paths[locale]; schema.inLanguage = locale;
      edits.push([node.openEnd, node.close, JSON.stringify(schema).replaceAll('<', '\\u003c')]);
    }
    if (tag !== source.slice(node.start, node.openEnd)) edits.push([node.start, node.openEnd, tag]);
  }
  edits.sort((a, b) => b[0] - a[0]);
  let previous = source.length;
  for (const [start, end, value] of edits) {
    if (end > previous) throw new Error('Overlapping translations: avoid nested data-i elements');
    source = source.slice(0, start) + value + source.slice(end);
    previous = start;
  }
  const alternates = locales.filter(l => l !== locale).map(l => `<meta property="og:locale:alternate" content="${ogLocales[l]}">`).join('\n');
  return source.replace(/<meta property="og:locale"[^>]*>/, tag => tag + '\n' + alternates).replace(/\n{3,}/g, '\n\n');
}

try {
  if (process.argv.slice(2).some(arg => arg !== '--check')) throw new Error('Usage: node scripts/site-i18n.mjs [--check]');
  const source = await readFile(new URL('site/index.html', root), 'utf8');
  const nodes = elements(source);
  const dictionaries = Object.fromEntries(locales.map(l => [l, dictionary(source, l.toUpperCase())]));
  const meta = dictionary(source, 'META');
  const keys = new Set([...Object.values(dictionaries).flatMap(Object.keys), ...nodes.flatMap(n => [n.attrs['data-i'], n.attrs['data-i-aria']]).filter(Boolean)]);
  const missing = [];
  for (const locale of locales) {
    for (const key of keys) if (typeof dictionaries[locale][key] !== 'string' || !dictionaries[locale][key].trim()) missing.push(`${locale}: ${key}`);
    if (!meta[locale]?.[0] || !meta[locale]?.[1]) missing.push(`META: ${locale}`);
  }
  if (missing.length) throw new Error('Missing translation keys:\n' + missing.join('\n'));
  const alternates = [...locales, 'x-default'].map(l => `<link rel="alternate" hreflang="${l}" href="${base}${paths[l] || '/'}">`).join('\n');
  if (!source.includes(alternates)) throw new Error('site/index.html must contain all four hreflang links');
  const outputs = new Map(locales.map(l => [`site${paths[l]}index.html`, (l === 'ar' ? '' : banner) + translate(source, nodes, dictionaries[l], meta[l], l)]));
  const sitemapLinks = [...locales, 'x-default'].map(l => `    <xhtml:link rel="alternate" hreflang="${l}" href="${base}${paths[l] || '/'}"/>`).join('\n');
  outputs.set('site/sitemap.xml', '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n' + locales.map(l => `  <url>\n    <loc>${base}${paths[l]}</loc>\n${sitemapLinks}\n  </url>`).join('\n') + '\n</urlset>\n');
  const stale = [];
  for (const [path, expected] of outputs) {
    const url = new URL(path, root);
    const current = await readFile(url, 'utf8').catch(e => { if (e.code === 'ENOENT') return null; throw e; });
    if (current === expected) continue;
    if (check) stale.push(path);
    else { await mkdir(new URL('./', url), { recursive: true }); await writeFile(url, expected); console.log(`Wrote ${path}`); }
  }
  if (stale.length) throw new Error('Stale files:\n' + stale.join('\n') + '\nRun node scripts/site-i18n.mjs');
  console.log(check ? `site-i18n: OK (ar/en/fr; ${keys.size} keys; generated pages and sitemap current)` : 'site-i18n: generated ar/en/fr and sitemap');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
