# Releasing WebStreamer

CI/CD lives in `.github/workflows/`:

- **build.yml** — every push to `master` and every PR: builds the mod and uploads
  `build/libs/*.jar` as a workflow artifact (7-day retention, the jar is ~130 MB).
- **release.yml** — every tag `v*`: builds, then publishes the mod jar to
  **CurseForge** and creates a **GitHub Release** with the jar attached
  (via [mc-publish](https://github.com/Kir-Antipov/mc-publish)).

## One-time CurseForge setup

> ⚠️ **Not** console.curseforge.com — that is "CurseForge for Studios", a product for
> game developers hosting their own modding platform. A "game" created there is
> unrelated to mod publishing and can be deleted.

1. **Create the project**: sign in on [curseforge.com](https://www.curseforge.com),
   open the authors portal ([authors.curseforge.com](https://authors.curseforge.com))
   and start a new project under **Minecraft → Mods**. Fill in name, summary,
   description and license (**LGPL-3.0**, inherited from fabric-webstreamer).
   New projects go through moderator review before they appear publicly, but API
   uploads work as soon as the project exists.
2. **Project ID**: copy the numeric *Project ID* shown in the sidebar of the project
   page, then add it on GitHub under
   *Settings → Secrets and variables → Actions → Variables* as
   `CURSEFORGE_PROJECT_ID`.
3. **API token**: generate one on the authors portal under *API Tokens*
   ([authors.curseforge.com/#/api-tokens](https://authors.curseforge.com/#/api-tokens)),
   then add it on GitHub under
   *Settings → Secrets and variables → Actions → Secrets* as `CURSEFORGE_TOKEN`.

## Cutting a release

1. Bump `mod_version` in [`gradle.properties`](../gradle.properties).
2. Commit and push to `master`; wait for the Build workflow to go green.
3. Tag and push — the tag **must** match `mod_version` (the workflow enforces this):

   ```bash
   git tag -a v2.0.1 -m "Short changelog for this release"
   git push origin v2.0.1
   ```

4. The Release workflow builds the jar, uploads it to CurseForge (game version
   26.3, loader Fabric, Java 25 — from the workflow and `fabric.mod.json`) and
   creates the GitHub Release with an auto-generated changelog.

If the CurseForge upload fails (e.g. secrets not configured yet), the GitHub
Release half may still have been created; fix the secrets and re-run the failed
job from the Actions tab.
