# Historical response fixtures

These are sanitized projections of fixed upstream responses, not newly captured live responses.
Source revision: yuliskov/MediaServiceCore@7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5.
Each JSON names its original repository path. The first two matching renderer objects are retained in order.
Tracking/visitor fields are removed; opaque tokens, params and action strings are replaced with fixture values.

- tv-home.json: browse/tv/2025.04.06_home.json, tileRenderer.
- web-search.json: browse/chrome/2023.12.06_channel_search.json, videoRenderer.
- comments.json: chat_comments/2024.03.26_comments_new.json, commentThreadRenderer.

The older search/searchV2.json is malformed in the source repository and is intentionally not repaired or used here.