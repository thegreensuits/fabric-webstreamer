# CurseForge listing texts

Copy-paste material for the CurseForge project page.

## Summary (short description, ≤ 250 chars)

> Client-side Fabric mod that renders Twitch / HLS streams on virtual screens in the
> world — no blocks, no GUIs, entirely driven by the server. Built for the
> TheGentleSuits viewing-party server. Remake of fabric-webstreamer by Theo Rozier.

## Full description

**WebStreamer** turns Minecraft into a viewing party: live Twitch or raw HLS streams
are rendered as virtual screens floating in the world, with positional audio that
follows you as you walk around.

This is a **client-side companion mod** — it registers no blocks, items, commands or
GUIs, and is completely inert on servers that don't drive it. Displays (position,
size, orientation, stream source, audio settings) are managed by server staff and
pushed to players over a plugin channel. If you've been asked to install it for a
viewing-party event, just drop it in your mods folder and join.

### Features

- 🎬 Twitch channels and raw HLS playlists rendered as in-world screens
- 🔊 Positional OpenAL audio with distance attenuation, configured per display
- 🖥️ Fully server-driven: nothing to configure or place on the client
- ⚡ Displays sharing the same source share a single decode pipeline
- 📦 FFmpeg natives bundled for Windows, Linux and macOS (x86_64 + ARM64) — one jar,
  no extra installs

### Requirements

- Minecraft 26.1.x (client only)
- Fabric Loader ≥ 0.19 and Fabric API
- Java 25
- A server running the matching WebStreamer bridge plugin (the mod does nothing on
  its own)

### Credits & license

Blockless remake of
[fabric-webstreamer](https://github.com/mindstorm38/fabric-webstreamer) by
**Theo Rozier (Mindstorm38)** — the video/audio pipeline is adapted from it.
Licensed under **LGPL-3.0**. Source:
[github.com/thegreensuits/fabric-webstreamer](https://github.com/thegreensuits/fabric-webstreamer).
