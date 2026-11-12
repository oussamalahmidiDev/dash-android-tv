# DASH 2.0.7 — Live TV Seeking, 400 MB Cache & Performance Overhaul

DASH 2.0.7 adds an interactive Live TV timeline, expands the persistent torrent cache, and improves search, artwork loading, and playback.

## 📺 Live TV Rewind & Progress

- Live streams with a seekable window now have an interactive timeline.
- The player shows how far behind the live edge you are, for example `-0:25`, and displays `● LIVE` when you’re caught up.
- D-pad scrubbing stays within the available window and stops at the live edge.
- Select **Ahead to Live** to return to the live edge in one click.

## ⚡ Performance & Streaming Improvements

- Increased the persistent torrent cache from 256 MB to 400 MB.
- Added a 64 MB disk cache for posters and backdrops, so previously loaded artwork can appear faster while browsing.
- Debounced search by 180 ms and kept results visible while the next query loads to reduce flicker.
- Moved torrent cache eviction tracking into memory to reduce disk work during playback.
- Enabled decoder fallback and requested a larger Android heap to improve resilience on demanding streams, including 4K video.
- Streamlined electronic program guide parsing and ID hashing for M3U imports.

## 🐛 Bug Fixes from 2.0.6

- Fixed the 30-second rewind button on seekable HLS live streams.
- Kept D-pad focus in Continue Watching after removing a card.
- Hid the All, Movies, Series, and Channels search filters when the query is empty.
- Fixed the torrent source details dialog closing immediately after a long press.
