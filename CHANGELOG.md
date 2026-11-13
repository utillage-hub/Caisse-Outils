# Historique

## v1.15
- Forets › **Conditions de coupe** remplace l'onglet « Vitesses de coupe », qui devient l'abaque dépliable du nouveau module.
- Quatre familles d'usinage, douze opérations : tournage (chariotage-dressage, rainurage-tronçonnage), perçage (foret 2 arêtes, foret à plaquettes, alésage), fraisage (surfaçage, contournage, rainurage), filetage (taraudage par coupe, par déformation, fraisage de filets).
- Calculs selon le modèle de Kienzle (kc = kc1.1 / h^mc) : débit de copeaux, épaisseur de copeau, effort de coupe, effort d'avance, couple, puissance machine, temps de coupe, rugosité théorique.
- Bibliothèque de 53 groupes de matières ISO P/M/K/N/S/H/O avec dureté, Rm, kc1.1 et mc ; recherche par nuance dans la base Matières de l'application, reliée au groupe de calcul le plus proche.
- Les grandeurs liées se déduisent l'une de l'autre dans les deux sens : vitesse de coupe et rotation, avance par dent, avance par tour et avance linéaire. Les cases déduites sont marquées « calculé ».
- Chaque opération garde ses propres valeurs, avec des points de départ réalistes.
- Bandeau de résultats collant au bloc de saisie, détail du calcul et formules employées dépliables.

## v1.14
- En-tête : le titre repartait sur le côté (une valeur de secours CSS avait écrasé la marge droite de la barre).
- La remise à zéro CSS du gabarit est désormais dans la source : sans elle, les versions autonomes (APK, site) héritaient des marges du navigateur et tout était décalé.
- Calculatrice : elle tient dans l'écran sans défilement ; en paysage, récapitulatif d'un côté et ligne de calcul en haut, côté réglable par le bouton ⇄ ou un appui long sur le clavier ; quand la place manque, les réglages a/b et deg n'affichent que le mode actif et basculent au toucher.
- Vues 3D : matériau simple et précision réduite sur les appareils anciens (elles sortaient toutes noires), repli 2D automatique si rien n'est dessiné, et construction différée pour que l'onglet s'ouvre tout de suite.
- Onglets : l'appui long pour les déplacer fonctionne enfin au doigt (événements tactiles dédiés).
- Vitesses de coupe : passées de Matières à Forets.
- Classification périodique : le tableau remplit l'écran dès l'ouverture.
- Outil de débogage : copie adaptée aux anciens navigateurs par `outils/compat-debogage.py` (son conteneur en `inset:0` n'avait ni position ni taille).

## v1.12
- Matières › Traitements : fiche « Nitruration et nitrocarburation » (couches, profondeurs, duretés par nuance, procédés, post-oxydation, points à préciser à la commande).

## v1.11
- Compatible avec le navigateur interne des tablettes Android 7 (Chrome 78) : page blanche corrigée. Syntaxe `??` et `?.` remplacée, et replis CSS pour `min()`/`max()`/`clamp()`, `inset`, les propriétés logiques et l'écart (`gap`) en flexbox.
- `outils/verifier-compat.py` vérifie cette compatibilité à chaque compilation ; `outils/simuler-chrome78.py` rend la page comme Chrome 78 la verrait.

## v1.10
- Démarrage sûr : si l'application ne peut pas se lancer (navigateur interne trop ancien, erreur JavaScript), un écran explique pourquoi et affiche le détail à transmettre, au lieu d'une page blanche.
- APK installable à partir d'Android 5 (au lieu d'Android 8), signature vérifiée à chaque compilation.

## v1.9
- Outil de débogage mis à jour sur la dernière version du dépôt `outil-debogage` (commit 271d5a9 : versions de l'appli et de l'outil visibles dans les réglages).

## v1.8
- Outil de débogage mis à jour vers la dernière version publiée (commit 678eaae du 12/11/2026) : onglet Interface (fermeture de la bulle, outils déplaçables et masquables, taille, couleurs reprises de l'appli), fenêtres d'outils déplaçables, notes réordonnables, « ‹ Précédente » en vérification. Entrée « Interface de l'outil » ajoutée dans Menu › Débogage.

## v1.7
- Écran toujours allumé tant que la caisse est affichée (réglage dans le menu). En quittant l'appli, l'appareil reprend sa veille et son verrouillage habituels.

## v1.6
- Débogage : l'ancien module est remplacé par l'outil commun `utillage-hub/outil-debogage` (captures annotées, élément, texte, mesure, dictée, export), accessible depuis le menu.
- Cases de saisie : elles ne dépassent plus de leur carte (recherche de la base matières, tableau périodique).
- APK Android : coque WebView dans `android/`, compilée automatiquement et publiée dans Releases.

## v1.5
- Conteneurs : vue 3D isométrique (rotation, zoom molette ou deux doigts, flèches, pièce nommée au toucher) et vue 2D face par face avec fermetures et repères numérotés.
- Double appui (ou double-clic) sur une case : ouvre le clavier, et le referme sur la même case.
- Charte graphique adaptable : menu Apparence, paramètres d'URL, `postMessage`, API `CaisseOutils`, mode intégré.
- Copier / Couper / Coller sur toutes les cases de chiffres, sur les résultats et dans la calculatrice (calcul ou résultat, historique par appui long).
- Retour tactile réglable (7 ms par défaut) sur la calculatrice et le clavier.
- Flèches discrètes de défilement des onglets sur ordinateur, fondu sur les bords en tactile.

## v1.4
- Affichage adapté aux grands écrans (cartes en colonnes).
- Tableau périodique zoomable : boutons, pincement, Ctrl + molette, ajuster, 100 %, plein écran.

## v1.3
- Classification périodique liée aux compositions des matières.
- Débogage complet (bulle déplaçable, vérification, dictée), menu allégé, TVA en tuiles, pièces forgées.

## v1.0 à v1.2
- Calcul, pourcentage et TVA, taraudage, forets 3D, matières, profilés, conteneurs, trigonométrie, recherche, onglets déplaçables.
