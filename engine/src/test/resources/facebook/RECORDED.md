# Recorded Facebook page fixtures (sanitized)

Derived on 2026-10-18 from logged-out desktop-Chrome page fetches saved during the Facebook research
(no cookies, no login, metadata only; no media was downloaded for these files). These are reduced
recordings, not Nezzel device/site acceptance evidence.

| Fixture | Recorded source shape |
|---|---|
| `photo-post.html` | `/share/p/…` → 302 → `story.php` single-photo post (`data.node_v2` Story, `photo_image` only, `viewer_image` without uri) |
| `album-post.html` | multi-photo post: `all_subattachments.count` 18, the 5 nodes the page carries |
| `photo-page.html` | `/photo/?fbid=` page: `data.currMedia` plus neighbour ids and comment link-preview images |
| `reel-page.html` | `/reel/{id}` page with decoy reels under `viewer.lasso_blue_feed` (first 3 kept) |
| `video-story.html` | `/{page}/posts/{id}` video post (2026-10-19): `data.node_v2` Story whose attachment media is a `Video` (`StoryAttachmentVideoStyleRenderer`); `og:type video.other` and the og thumbnail (`3.jpg`) re-added by hand |
| `video-post.html` | `/{page}/videos/{id}` page with related-video thumbnails (2 per list kept) |
| `unavailable.html` | "This content isn't available" page (`CometErrorRoot.react` route) |
| `login-page.html` | `m.facebook.com` login interstitial served to a non-browser UA (head kept, body reduced to the login form) |
| `http400-shell.html` | HTTP 400 "Sorry, something went wrong" shell served without `Sec-Fetch-Mode` |

## Sanitization method

- Only `<script type="application/json" data-sjs>` blocks containing `RelayPrefetchedStreamCache` are kept,
  re-wrapped in the recorded `ScheduledServerJS` → `__bbox.result.data` shape, plus the page route marker
  (`initialRouteInfo.route.rootView.resource.__dr`). Everything else in the page is dropped.
- Inside `data`, a subtree is kept only if it leads to a `Photo`/`Video`/`Story` node or a media key; scalar keys
  other than `__typename`, `id`, `post_id`, `count`, `uri`, `width`, `height` and `browser_native_*` are removed.
- Every URL (scontent, video and external CDN alike) is replaced with `https://scontent.example.fbcdn.net/{n}.jpg`
  (`.mp4` for video keys), numbered by first appearance; no signed parameters remain.
- Numeric object ids are remapped to `900000000000001…` in order of appearance; other ids become `placeholder-id-{n}`;
  every other string (names, captions, comments, tokens) becomes `placeholder`.
- Lists keep at most the first 6 (album), 3 (reel feed) or 2 (related videos) media-bearing entries.
- `http400-shell.html` and `login-page.html` have every href/src/action replaced with `#` and tokens replaced.

Check before committing: `grep -rnE 'fbcdn\.net/|facebook\.com/' engine/src/test/resources/facebook | grep -v scontent.example.fbcdn.net/`
must print nothing.

## Synthetic decoys

`video-og-post.html` is hand-written: a post page with no relay data whose `og:type` is `video.other` and whose only
`og:image` (`1.jpg`) is the video's thumbnail. It is not recorded data.

`decoys-post.html` is `photo-post.html` plus hand-written decoys on the allowed scontent host (all with
valid `viewer_image` URIs): a comment photo (`84.jpg`, in the comment path), a second complete Story root for
another post (`81.jpg`), an unbound `currMedia` (`82.jpg`), a top-level `Photo` node standing for a profile picture
(`83.jpg`) and a recommendation-feed story (`85.jpg`). None of them is recorded data.

`video-decoys-post.html` is `video-story.html` plus hand-written decoys: a second complete Story root for another post
with its own Video (`900000000000092`) and a recommendation-feed story under `viewer.lasso_blue_feed` (`900000000000094`).
Neither is recorded data.

`video-conflict-post.html` is `video-story.html` with a hand-written Story root placed first that repeats the same
`post_id` but names a different Video (`900000000000095`). Not recorded data.
