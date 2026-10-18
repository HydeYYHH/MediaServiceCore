# Historical response fixtures

These are sanitized projections of fixed upstream responses, not newly captured live responses.
Source revision: yuliskov/MediaServiceCore@7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5.
Sources are listed below; object fixtures also name their original path. The first two matching renderer objects or endpoints are retained in order.
Tracking/visitor fields are removed; opaque tokens, params and action strings are replaced with fixture values.

- tv-home.json: browse/tv/2025.04.06_home.json, tileRenderer.
- web-search.json: browse/chrome/2023.12.06_channel_search.json, videoRenderer.
- comments.json: chat_comments/2024.03.26_comments_new.json, commentThreadRenderer.
- shorts-start.json: shorts/reel_item_start_2023.04.28.json, replacementEndpoint plus sequenceContinuation.
- shorts-next.json / shorts-next2.json: shorts/reel_item_continuation_2023.04.28.json and reel_item_continuation2_2023.04.28.json, first two entries.
- groups-pockettube.json: channelgroups/pockettube.json, first two collections; titles/icons replaced.
- groups-newpipe.json: channelgroups/newpipe_subscriptions.json, first two subscriptions; titles replaced.
- groups-grayjay.json: channelgroups/grayjay_subscription_groups, first two encoded group objects and channel URLs; IDs/titles/icons replaced.

The older search/searchV2.json is malformed in the source repository and is intentionally not repaired or used here.
