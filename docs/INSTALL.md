# Installing WebStreamer (for players)

*[Version française](INSTALL.fr.md)*

WebStreamer is a **client-side only** mod. You need it to see the video screens on
TheGentleSuits' viewing-party server — without it you'll just see nothing where a screen
should be. It doesn't add any blocks, items or menus, and it's safe to keep installed even
when playing on other servers or singleplayer (it stays completely inactive unless a server
asks for it).

## What you need

- **Minecraft version**: 26.1.2
- **Mod loader**: Fabric
- **Also required**: Fabric API (a separate, very common library mod)
- **Java**: 25 or newer

If any of these don't match, the mod won't load — Fabric will tell you which requirement
failed when the game starts.

> The Java 25 requirement is normally handled automatically by the official Minecraft
> Launcher (it ships the right Java version with the game). It's mostly relevant if you use
> a third-party launcher — see the Lunar Client notes below.

## Option A — Official Fabric launcher (recommended, always works)

This is the most reliable method and the one the mod is built/tested against.

1. **Install Fabric Loader**: go to [fabricmc.net/use](https://fabricmc.net/use), download the
   installer, run it, select Minecraft version **26.1.2**, and click *Install*. This creates a
   "fabric-loader-26.1.2" profile in your official Minecraft Launcher.
2. **Install Fabric API**: download the Fabric API `.jar` for **26.1.2** from
   [Modrinth](https://modrinth.com/mod/fabric-api) or
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) (pick the file whose
   game version says 26.1.2 and loader says Fabric).
3. **Download WebStreamer**: grab the `webstreamer-<version>.jar` from the
   [GitHub Releases page](https://github.com/thegreensuits/fabric-webstreamer/releases/latest).
4. **Find your `mods` folder**:
   - Windows: `%appdata%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`

   (Create the `mods` folder if it doesn't exist yet.)
5. **Copy both `.jar` files** (Fabric API + WebStreamer) into that `mods` folder.
6. Open the official Minecraft Launcher, select the **fabric-loader-26.1.2** profile, and
   click *Play*.
7. Join the viewing-party server. If it worked, the server console (staff-side) shows
   "WebStreamer mod detected" for your name — you don't need to do anything else in-game.

## Option B — CurseForge App

The CurseForge App manages Fabric + mods for you inside its own instance, so you don't touch
the official launcher at all.

1. Install the [CurseForge App](https://www.curseforge.com/download/app) and sign in.
2. Go to **Minecraft → My Modpacks/Instances → Create Custom Profile**.
3. Choose **Minecraft 26.1.2**, loader **Fabric**, and create the instance.
4. Download the Fabric API `.jar` for 26.1.2 (see link in Option A, step 2) and the
   WebStreamer `.jar` from the
   [GitHub Releases page](https://github.com/thegreensuits/fabric-webstreamer/releases/latest).
5. On the instance page, open the **Mods** tab → **Add File** (or drag-and-drop) and add both
   downloaded `.jar` files.
6. Click **Play** on that instance, then join the server as usual.

> WebStreamer isn't published as a searchable CurseForge project yet (it's pending review), so
> for now you always add it via "Add File" with the `.jar` from GitHub rather than searching
> for it by name in the CurseForge browser.

## Option C — Lunar Client

Lunar Client supports adding your own Fabric mods on some profiles via **Settings → Mods →
Add Mod**, but Lunar typically takes time to support brand-new Minecraft versions and loader
releases.

1. Open Lunar Client and check whether a **26.1.2 Fabric** profile is available under
   **Version**. If 26.1.2 isn't listed yet, Lunar hasn't caught up — use Option A or B instead.
2. If it is available, select that profile, go to **Mods**, and use **Add Mod** (or the folder
   icon) to import the Fabric API `.jar` and the WebStreamer `.jar` (same two files as above).
3. Check **Settings → Java Runtime** for that profile and make sure it's set to Java 25+ — if
   Lunar only offers an older bundled runtime for that version, the mod won't load there and
   you'll need Option A instead.
4. Launch and join the server.

## Troubleshooting

- **Game won't start / crash on launch**: almost always a version mismatch — double check
  Minecraft is exactly 26.1.2, Fabric Loader is ≥ 0.19, and Fabric API is installed (WebStreamer
  depends on it).
- **Joined the server but see no screens**: ask staff to confirm the server sees you as
  "modded" — if not, the mod likely isn't actually loaded (check the `mods` folder / Lunar
  profile again).
- **Still stuck**: ask in the community's support channel with a screenshot of your `mods`
  folder (or Lunar's Mods tab) and the Minecraft crash report if there is one.
