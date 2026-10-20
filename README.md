# Nimbus TV — application Android TV (version test)

Interface web (HTML/CSS/JS) dans une WebView, avec **lecteur natif ExoPlayer** (HLS, MPEG-TS, MP4, MKV),
chargement des playlists par le réseau natif (pas de problème CORS) et lecture du guide XMLTV.

## Obtenir l'APK (sans installer Android Studio)
1. Créez un dépôt GitHub (privé conseillé) et envoyez-y **tout le contenu de ce dossier**
   (le dossier `.github` doit être à la racine du dépôt).
2. Onglet **Actions** → workflow **Build APK** → il se lance seul au premier envoi
   (sinon : *Run workflow*). Durée : 3 à 6 minutes.
3. Ouvrez l'exécution terminée → section **Artifacts** → téléchargez `nimbus-tv-debug-apk`
   (zip contenant `app-debug.apk`).

## Installer sur l'Android TV
- **ADB** (le plus simple) : activez *Options pour les développeurs* + *Débogage réseau* sur la TV, puis
  `adb connect IP_DE_LA_TV` et `adb install -r app-debug.apk`.
- **Clé USB** : copiez l'APK, ouvrez-le avec un gestionnaire de fichiers sur la TV
  (autoriser les sources inconnues).
- **Downloader** (app TV) : saisissez l'URL de votre fichier APK hébergé.

## Premier lancement
L'app propose : **Lien M3U**, **Compte Xtream**, **Démo** — ou ouvrez l'adresse `http://IP_TV:8765`
affichée à l'écran depuis votre téléphone (même Wi-Fi) pour coller le lien confortablement.
Le guide XMLTV est détecté automatiquement (en-tête M3U `url-tvg` ou `xmltv.php` Xtream),
ou réglable dans *Réglages › Guide des programmes*.

## Commandes télécommande
Flèches : naviguer · OK : choisir · Retour : remonter.
Lecteur direct : ▲▼ changer de chaîne, OK liste des chaînes, ◀ pistes audio/sous-titres, 0-9 numéro, L dernière chaîne.
Lecteur film/série : ◀▶ ±10 s, OK pause, ▲▼ pistes.

## Dépannage
- Débogage de l'interface : `chrome://inspect` (WebView activée en debug).
- Flux qui ne démarre pas : essayez *Réglages › Format des chaînes Xtream › HLS*, puis actualisez la playlist.
- Rien ne s'affiche après un échec : *Réglages › Supprimer la playlist active*, puis ajoutez-la de nouveau.
