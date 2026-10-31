# Privacy

Nezzel has no ads and no account. All processing of what you save happens on your phone.

## What goes over the network

- **Saving media:** requests go straight from your phone to the site you're saving from and its content servers. Nezzel is not an offline app.
- **Automatic options:** when **Show options automatically** is on, Nezzel loads a link's details and preview once you open the app with a copied link, or share or paste one. Turn it off to load options only when you ask. Nothing is ever saved without you tapping Download or Save.
- **Clipboard:** Nezzel reads the clipboard only while it's open on screen. It never monitors the clipboard in the background.
- **Site support updates:** once a week at startup, Nezzel checks the official yt-dlp releases for a newer stable version (at most one automatic attempt a day, never while downloads are running). Settings also has a manual update. Nezzel doesn't pin the hash of the downloaded update itself; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **App updates:** while **Automatic updates** is on (the default), Nezzel checks automatically when the last successful check is at least six hours old. It asks `nezzel.hululsoft.mr/update/<version code>`, which redirects to `nezzel-update.json` in the latest release on `github.com`. If that fails, it tries `api.github.com` once. These servers see your IP address and the Nezzel version. No cookies, account or device identifiers are sent. On an unmetered connection, Nezzel can download an available APK in the background; you can also request the download yourself. On Android 12 and later, Nezzel installs a downloaded update by itself while you're not using it and nothing is downloading; Android may still ask you to confirm. Turn **Automatic updates** off to update only when you choose. Before installing, Nezzel checks the APK's SHA-256, package name, signing certificate and version.

## What stays on your phone

- Download records are kept so you can see progress and try again. Source links for jobs you can retry are kept for a limited time.
- Nezzel never logs links or clipboard contents.
- Saved files go to `Download/Nezzel` or a folder you pick through Android's system picker. Android's MediaStore manages them.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Loading options and previews, downloading, and updates |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Downloads you started keep running when you leave the app |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Saved audio keeps playing when you leave the app |
| `POST_NOTIFICATIONS` | Download progress, results and app updates (Android 13+). Downloads still work if you say no |
| `POST_PROMOTED_NOTIFICATIONS` | On Android 16, the running download's percent in the status bar and on the lock screen (Live Updates). No prompt; you can turn it off in system settings |
| `REQUEST_INSTALL_PACKAGES` | Installing an app update you confirmed, through Android's installer |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | On Android 12 and later, installing a downloaded update while you're not using the app |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | Added by WorkManager: running queued work, waiting for a connection, rescheduling after a restart |
| App-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX internal protection; signature-level, no prompt |

Nezzel doesn't ask for storage, contacts, microphone, camera or location permission.

## Usage counts and reports

While Settings → Advanced → Help improve Nezzel is on:

- Once a day, Nezzel sends anonymous totals: features used, how far links got (opened, options shown, downloaded, failed), rough quality, speed and wait-time buckets, your settings, and the sites you saved from (domain only). Each send also names the app version, Android version, phone brand (never the model), screen size class, app language and a rough install age. No ID, links, titles or file names. The server adds your country and keeps daily totals only.
- When a download or link read fails, Nezzel sends a report with the site, the error code, the app and engine versions and the exact link. If the app crashed, the crash details (no personal data) are sent too. Links in reports are deleted after 30 days.
- Your IP address is never stored.

Turn it off and nothing is sent; anything not yet sent is deleted.

The website (nezzel.hululsoft.mr) counts visits anonymously: which parts of the page were seen and which buttons were tapped, as daily totals, with no cookies and no IP address. It also uses Cloudflare Web Analytics, which counts page views without cookies.
