# Installer WebStreamer (pour les joueurs)

*[English version](INSTALL.md)*

WebStreamer est un mod **côté client uniquement**. Il est nécessaire pour voir les écrans
vidéo sur le serveur viewing-party de TheGentleSuits — sans lui, tu ne verras tout simplement
rien à l'endroit où un écran devrait s'afficher. Il n'ajoute aucun bloc, objet ou menu, et il
est sans danger de le garder installé même en jouant sur d'autres serveurs ou en solo (il reste
totalement inactif tant qu'un serveur ne lui demande rien).

## Ce dont tu as besoin

- **Version de Minecraft** : 26.1.2
- **Mod loader** : Fabric
- **Également requis** : Fabric API (un mod-librairie séparé, très courant)
- **Java** : 25 ou plus récent

Si l'un de ces éléments ne correspond pas, le mod ne se chargera pas — Fabric t'indiquera au
lancement du jeu quelle condition n'est pas remplie.

> La condition Java 25 est normalement gérée automatiquement par le Launcher Minecraft
> officiel (il installe la bonne version de Java avec le jeu). Elle concerne surtout les
> launchers tiers — voir les notes sur Lunar Client plus bas.

## Option A — Launcher Fabric officiel (recommandé, fonctionne toujours)

C'est la méthode la plus fiable, et celle sur laquelle le mod est construit/testé.

1. **Installer Fabric Loader** : va sur [fabricmc.net/use](https://fabricmc.net/use),
   télécharge l'installeur, lance-le, sélectionne la version Minecraft **26.1.2**, puis clique
   sur *Install*. Cela crée un profil "fabric-loader-26.1.2" dans ton Launcher Minecraft
   officiel.
2. **Installer Fabric API** : télécharge le `.jar` de Fabric API pour la **26.1.2** depuis
   [Modrinth](https://modrinth.com/mod/fabric-api) ou
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) (choisis le fichier
   dont la version de jeu indique 26.1.2 et le loader Fabric).
3. **Télécharger WebStreamer** : récupère le `webstreamer-<version>.jar` depuis la
   [page des Releases GitHub](https://github.com/thegreensuits/fabric-webstreamer/releases/latest).
4. **Trouver ton dossier `mods`** :
   - Windows : `%appdata%\.minecraft\mods`
   - macOS : `~/Library/Application Support/minecraft/mods`
   - Linux : `~/.minecraft/mods`

   (Crée le dossier `mods` s'il n'existe pas encore.)
5. **Copie les deux fichiers `.jar`** (Fabric API + WebStreamer) dans ce dossier `mods`.
6. Ouvre le Launcher Minecraft officiel, sélectionne le profil **fabric-loader-26.1.2**, puis
   clique sur *Jouer*.
7. Rejoins le serveur viewing-party. Si tout fonctionne, la console du serveur (côté staff)
   affichera "WebStreamer mod detected" pour ton nom — tu n'as rien d'autre à faire en jeu.

## Option B — CurseForge App

Le CurseForge App gère Fabric + les mods à ta place dans sa propre instance, tu n'as donc pas
besoin de toucher au launcher officiel.

1. Installe le [CurseForge App](https://www.curseforge.com/download/app) et connecte-toi.
2. Va dans **Minecraft → My Modpacks/Instances → Create Custom Profile**.
3. Choisis **Minecraft 26.1.2**, loader **Fabric**, puis crée l'instance.
4. Télécharge le `.jar` de Fabric API pour la 26.1.2 (lien à l'étape 2 de l'Option A) ainsi que
   le `.jar` de WebStreamer depuis la
   [page des Releases GitHub](https://github.com/thegreensuits/fabric-webstreamer/releases/latest).
5. Sur la page de l'instance, ouvre l'onglet **Mods** → **Add File** (ou glisse-dépose) et
   ajoute les deux fichiers `.jar` téléchargés.
6. Clique sur **Play** sur cette instance, puis rejoins le serveur normalement.

> WebStreamer n'est pas encore publié en tant que projet CurseForge consultable (en attente de
> modération), donc pour l'instant il faut toujours l'ajouter via "Add File" avec le `.jar` de
> GitHub plutôt que de le chercher par son nom dans le catalogue CurseForge.

## Option C — Lunar Client

Lunar Client permet d'ajouter tes propres mods Fabric sur certains profils via **Settings →
Mods → Add Mod**, mais Lunar met généralement du temps à prendre en charge une toute nouvelle
version de Minecraft ou du loader.

1. Ouvre Lunar Client et vérifie si un profil **26.1.2 Fabric** est disponible dans
   **Version**. Si la 26.1.2 n'apparaît pas encore, Lunar n'a pas encore rattrapé son retard —
   utilise l'Option A ou B à la place.
2. Si elle est disponible, sélectionne ce profil, va dans **Mods**, puis utilise **Add Mod**
   (ou l'icône de dossier) pour importer le `.jar` de Fabric API et celui de WebStreamer (les
   deux mêmes fichiers que ci-dessus).
3. Vérifie **Settings → Java Runtime** pour ce profil et assure-toi qu'il est réglé sur Java
   25+ — si Lunar ne propose qu'un runtime plus ancien pour cette version, le mod ne se
   chargera pas et il faudra utiliser l'Option A à la place.
4. Lance le jeu et rejoins le serveur.

## Dépannage

- **Le jeu ne démarre pas / crash au lancement** : presque toujours un problème de version —
  vérifie bien que Minecraft est exactement en 26.1.2, que Fabric Loader est ≥ 0.19, et que
  Fabric API est installé (WebStreamer en dépend).
- **Connecté au serveur mais aucun écran visible** : demande au staff de confirmer si le
  serveur te voit comme "modded" — si non, le mod n'est probablement pas réellement chargé
  (revérifie le dossier `mods` / l'onglet Mods de Lunar).
- **Toujours bloqué ?** : demande sur le salon d'entraide de la communauté en joignant une
  capture d'écran de ton dossier `mods` (ou de l'onglet Mods de Lunar) et le rapport de crash
  Minecraft s'il y en a un.
