# Intégrer la Caisse à Outils

La page s'adapte à la charte de l'application qui l'accueille. Trois façons de la régler, cumulables.

## 1. Paramètres d'URL

```
index.html?accent=1f6fd6&header=neutral&mode=dark&font=inter&radius=12&title=Outils%20Atelier&tools=calc,pct,mat&embed=1
```

| Paramètre | Valeurs | Effet |
|---|---|---|
| `accent` | couleur hex sans `#` | couleur principale (en-tête, boutons, résultats). Le texte dessus passe en blanc ou en noir selon le contraste |
| `header` | `accent` (défaut) ou `neutral` | en-tête coloré ou fond clair avec titre en couleur |
| `mode` | `auto` (défaut), `light`, `dark` | clair, sombre ou selon le système |
| `font` | `atelier`, `systeme`, `inter`, `manrope`, `roboto` | famille de polices |
| `radius` | 0 à 24 | arrondi des cartes et boutons en px |
| `title` | texte | nom affiché dans l'en-tête et l'onglet |
| `tools` | liste d'identifiants séparés par des virgules | n'affiche que ces outils |
| `embed` | `1` | masque le titre (la page est dans une appli qui a déjà le sien) |

Identifiants d'outils : `calc`, `pct`, `tap`, `drill`, `mat`, `per`, `prof`, `cont`, `trigo`.

Le menu › Apparence propose « Copier le lien » qui génère cette URL à partir des réglages en cours.

## 2. Dans une iframe, par message

```html
<iframe id="co" src="index.html?embed=1" style="width:100%;height:100vh;border:0"></iframe>
<script>
const f=document.getElementById('co');
addEventListener('message',e=>{
  if(e.data&&e.data.type==='caisse-outils:ready'){
    f.contentWindow.postMessage({type:'caisse-outils:theme',theme:{accent:'#1f6fd6',header:'neutral',font:'inter',radius:12}},'*');
    f.contentWindow.postMessage({type:'caisse-outils:show',tool:'mat'},'*');
  }
});
</script>
```

La page envoie `{type:'caisse-outils:ready', version, tools}` au chargement. Le menu › Apparence propose « Copier le JSON » : le message à envoyer pour reproduire les réglages en cours.

## 3. Dans la même page, en JavaScript

```js
CaisseOutils.setTheme({accent:'#2f7d4f',mode:'dark'});
CaisseOutils.getTheme();   // réglages en cours
CaisseOutils.show('cont'); // ouvre un outil
CaisseOutils.tools;        // identifiants disponibles
CaisseOutils.version;
```

## Jetons CSS

Pour aller plus loin, tout passe par des variables sur `:root` : `--red` (accent), `--on-red`, `--bg`, `--card`, `--fg`, `--muted`, `--line`, `--field`, `--chip`, `--ok`, `--bad`, `--calc`, `--calc-fg`, `--rc` (arrondi), `--display`, `--body`, `--num` (polices). Les surcharger dans une feuille de style chargée après la page suffit.

## Réglages conservés sur l'appareil

Ordre des onglets, dernier outil ouvert, clavier à l'écran (ancré ou flottant), retour tactile, charte choisie dans le menu, notes de débogage hors artefact : tout est dans `localStorage`, préfixe sans conflit avec une application hôte (clés courtes : `tab`, `theme`, `haptic`, …). Pour isoler totalement, charger la page dans une iframe sur son propre chemin.
