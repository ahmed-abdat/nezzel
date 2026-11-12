# Website contributor rules

- Static HTML/CSS/JS only. Arabic lives at `/`, English at `/en/`, French at `/fr/`;
  desktop landing, install and thank-you pages live below each locale's `desktop/`.
- Keep all three languages equivalent. Use logical CSS properties and preserve RTL.
  Isolate URLs, filenames and architecture labels with `dir="ltr"` or `<bdi>`.
- `site/index.html` owns the homepage and the AR/EN/FR dictionaries. Shared header and
  closing markup come from `scripts/site-header.mjs` and `scripts/site-footer.mjs`.
  Run `node scripts/site-i18n.mjs` after source edits, then
  `node scripts/site-i18n.mjs --check`. Do not hand-edit generated locale pages.
- Shared tokens, header and closing styles live in `css/site.css`; desktop styles in
  `css/dk.css`. Preserve light/dark, scrolled/on-dark header states, keyboard access
  and usable navigation when JavaScript is disabled.
- Use existing download routes and architecture/package parameters. Do not invent
  release assets, versions or product claims. Prove dictionary keys unused with
  `rg` before deleting them, including dynamic consumers in `js/` and generators.
- Keep changes surgical. Verify with `python3 -m http.server` from `site/` and a
  headless browser at 390px and 1440px in Arabic and English; check French parity,
  both themes, overflow and console errors. Save screenshots outside the repo.
- Pushing main deploys to Cloudflare Pages. Never commit, push or deploy without
  the owner's explicit instruction. Keep this file tracked; `.gitignore` has an
  exception for it.
