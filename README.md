# WebStreamer (TheGentleSuits remake)

Client-side Fabric mod for Minecraft **26.1.2**: renders Twitch / raw HLS streams as virtual
screens in the world, entirely driven by the server. This is a blockless remake of
[fabric-webstreamer](https://github.com/mindstorm38/fabric-webstreamer) by Theo Rozier
(video/audio pipeline adapted from it, LGPL-3.0) designed for the TheGentleSuits viewing-party
server.

## How it works

- The mod registers **no blocks, items or GUIs**. It is inert on servers that don't drive it.
- It registers a receiver for the `webstreamer:display_sync` plugin channel; that registration
  announces the channel to any server (vanilla `minecraft:register`), which is how the
  ViewingParty Paper plugin detects modded players.
- The server sends the display list (position, orientation, size, source, audio settings) as
  JSON; the wire format is specified in `minecraft/docs/webstreamer-bridge-protocol.md` of the
  TheGentleSuits monorepo (protocol v1). Staff manage displays with `/display` on the server.
- The client resolves Twitch channels via the GraphQL API, follows the HLS playlist, decodes
  segments with FFmpeg (JavaCV) and draws each display as a textured quad with positional
  audio (OpenAL).

## Building

```bash
./gradlew build        # produces build/libs/webstreamer-<version>.jar
./gradlew runClient    # dev client
```

The distributable jar bundles JavaCV/FFmpeg natives for linux-x86_64, linux-arm64,
windows-x86_64, macosx-x86_64 and macosx-arm64 (~130 MB). To slim a platform-specific build,
remove entries from `nativePlatforms` in `build.gradle`.

Toolchain: Java 25, Gradle 9.5, fabric-loom 1.17, Fabric Loader ≥ 0.19, Fabric API.

## Runtime verification checklist

Compile-verified against Minecraft 26.1.2 mojmap; the following need an in-game pass after any
significant change:

- [ ] Join the viewing-party server → server log shows "WebStreamer mod detected".
- [ ] `/display create 16 9` + `/display twitch <channel>` → quad renders, stream plays.
- [ ] Idle display (no source) renders as a black quad.
- [ ] Audio attenuates with distance and follows `audio` settings.
- [ ] Two displays with the same source share one decode pipeline (single log allocation).
- [ ] Dimension change / disconnect cleans up layers (no leaked sound or texture).
- [ ] `LevelRenderEvents.COLLECT_SUBMITS` runs on the render thread (no
      `assertOnRenderThread` failures from texture upload).

## License

LGPL-3.0 (inherited from fabric-webstreamer — see `LICENSE`). Original pipeline by
Theo Rozier (Mindstorm38).
