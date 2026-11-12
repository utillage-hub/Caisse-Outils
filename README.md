# Caisse à Outils

Boîte à outils d'atelier en une seule page web (`index.html`), sans dépendance à installer : calcul exact avec fractions, pourcentages et TVA, taraudage, forets (3D), base matières et vitesses de coupe, classification périodique, profilés, conteneurs ISO (3D et 2D), trigonométrie.

Fonctionne sur téléphone, tablette et ordinateur, en clair et en sombre, hors ligne une fois chargée (seuls three.js et les polices viennent d'un CDN).

## Lancer

Ouvrir `index.html` dans un navigateur, ou servir le dossier :

```sh
python3 -m http.server 8080
# puis http://localhost:8080/
```

## Application Android (APK)

Chaque envoi sur `main` compile un APK d'essai (GitHub Actions, `.github/workflows/apk.yml`) et le publie dans **Releases › Dernier APK**. Pour l'installer : ouvrir le fichier `.apk` depuis le téléphone ou la tablette et autoriser l'installation. Les versions suivantes s'installent par-dessus.

L'application a besoin d'un navigateur interne récent (Chrome 80 ou plus, sorti début 2020). Sur une tablette ancienne, mettre à jour **Android System WebView** et **Google Chrome** depuis le Play Store ; l'application affiche elle-même ce message si le moteur est trop ancien.

Le dossier `android/` contient la coque : une WebView qui charge `index.html` embarqué (three.js compris, donc la 3D marche hors connexion), le partage et la dictée du téléphone pour l'outil de débogage, le vibreur pour le retour tactile. La clé `android/app/essai.keystore` est une clé d'essai publique : à remplacer avant une diffusion sur le Play Store.

## Débogage

Le menu › Débogage ouvre l'outil commun [`utillage-hub/outil-debogage`](https://github.com/utillage-hub/outil-debogage), dont `dist/debogage.js` est copié à la fin de `index.html`. Pour le mettre à jour, remplacer ce bloc par la nouvelle version du fichier.

## Intégrer dans une application ou un site

Voir [docs/integration.md](docs/integration.md) : charte graphique (couleur, en-tête, police, coins, mode), paramètres d'URL, `postMessage`, API `window.CaisseOutils`, mode intégré et choix des outils affichés.

## Structure

| Fichier | Rôle |
|---|---|
| `index.html` | L'application complète (HTML, CSS et JavaScript dans un seul fichier) |
| `docs/integration.md` | Guide d'intégration et de personnalisation |
| `android/` | Coque Android (WebView) pour l'APK |
| `.github/workflows/apk.yml` | Compilation automatique de l'APK |
| `CHANGELOG.md` | Historique des versions |

Le fichier est organisé en sections commentées (`// ---------- NOM ----------`) : une par outil, puis clavier à l'écran, débogage, menu, charte, copier-coller.

## Sources des données

Les sources sont citées dans chaque onglet (CAPR, ArcelorMittal, Triton, HZ Containers, techno-science.net, Éduscol…). Les valeurs sont indicatives : la fiche du fabricant et le certificat matière font foi.

## Licence

À définir par le propriétaire du dépôt.
