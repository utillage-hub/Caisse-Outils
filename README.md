# Caisse à Outils

Boîte à outils d'atelier en une seule page web (`index.html`), sans dépendance à installer : calcul exact avec fractions, pourcentages et TVA, taraudage, forets (3D), base matières et vitesses de coupe, classification périodique, profilés, conteneurs ISO (3D et 2D), trigonométrie.

Fonctionne sur téléphone, tablette et ordinateur, en clair et en sombre, hors ligne une fois chargée (seuls three.js et les polices viennent d'un CDN).

## Lancer

Ouvrir `index.html` dans un navigateur, ou servir le dossier :

```sh
python3 -m http.server 8080
# puis http://localhost:8080/
```

## Intégrer dans une application ou un site

Voir [docs/integration.md](docs/integration.md) : charte graphique (couleur, en-tête, police, coins, mode), paramètres d'URL, `postMessage`, API `window.CaisseOutils`, mode intégré et choix des outils affichés.

## Structure

| Fichier | Rôle |
|---|---|
| `index.html` | L'application complète (HTML, CSS et JavaScript dans un seul fichier) |
| `docs/integration.md` | Guide d'intégration et de personnalisation |
| `CHANGELOG.md` | Historique des versions |

Le fichier est organisé en sections commentées (`// ---------- NOM ----------`) : une par outil, puis clavier à l'écran, débogage, menu, charte, copier-coller.

## Notes de débogage

Le menu › Débogage affiche une bulle pour noter les problèmes écran par écran (texte ou dictée). Dans l'artefact Claude, les notes vont dans la base partagée de l'application ; en dehors, elles restent dans le navigateur et se copient d'un bouton.

## Sources des données

Les sources sont citées dans chaque onglet (CAPR, ArcelorMittal, Triton, HZ Containers, techno-science.net, Éduscol…). Les valeurs sont indicatives : la fiche du fabricant et le certificat matière font foi.

## Licence

À définir par le propriétaire du dépôt.
