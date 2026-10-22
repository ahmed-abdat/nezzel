# Privacy

Nezzel has no analytics, no telemetry, no ads and no account. It has no server either: all processing happens on your phone.

## What goes over the network

- **Saving media:** requests go straight from your phone to the site you're saving from and its content servers. Nezzel is not an offline app.
- **Automatic options:** when **Show options automatically** is on, Nezzel loads a link's details and preview once you open the app with a copied link, or share or paste one. Turn it off to load options only when you ask. Nothing is ever saved without you tapping Download or Save.
- **Clipboard:** Nezzel reads the clipboard only while it's open on screen. It never monitors the clipboard in the background.
- **Site support updates:** once a week at startup, Nezzel checks the official yt-dlp releases for a newer stable version (at most one automatic attempt a day, never while downloads are running). Settings also has a manual update. Nezzel doesn't pin the hash of the downloaded update itself; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **App updates:** while **Check for app updates** is on (the default), Nezzel checks GitHub at most once a day. It downloads `nezzel-update.json` from the latest release on `github.com`. If that fails, it tries `api.github.com` once. GitHub sees your IP address and the Nezzel version (in the User-Agent). No cookies, account or device identifiers are sent. The APK is downloaded only after you tap Download update, and Android asks you to confirm the install. Before installing, Nezzel checks the APK's SHA-256, package name, signing certificate and version.
- **Report this link:** this button opens WhatsApp with the link and the app and engine versions filled in. Nothing is sent unless you press Send in WhatsApp.

## What stays on your phone

- Download records are kept so you can see progress and try again. Source links for jobs you can retry are kept for a limited time.
- Nezzel never logs links or clipboard contents.
- Saved files go to `Download/Nezzel` or a folder you pick through Android's system picker. Android's MediaStore manages them.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Loading options and previews, downloading, and updates |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Downloads you started keep running when you leave the app |
| `POST_NOTIFICATIONS` | Download progress, results and app updates (Android 13+). Downloads still work if you say no |
| `REQUEST_INSTALL_PACKAGES` | Installing an app update you confirmed, through Android's installer |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | Added by WorkManager: running queued work, waiting for a connection, rescheduling after a restart |
| App-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX internal protection; signature-level, no prompt |

Nezzel doesn't ask for storage, contacts, microphone, camera or location permission.
