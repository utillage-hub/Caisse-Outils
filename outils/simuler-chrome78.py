#!/usr/bin/env python3
"""Fabrique une copie de la page telle que Chrome 78 la verrait.

Chrome 78 ignore les déclarations qu'il ne comprend pas (min()/max()/clamp(), inset,
propriétés logiques, gap en flexbox, unités dvh/cqi) et jette les règles dont le
sélecteur lui est inconnu (:focus-visible, :where, :is, :has). On reproduit cela pour
vérifier la mise en page sans tablette sous la main.
Lancer : python3 outils/simuler-chrome78.py index.html sortie.html
"""
import re, sys

SELECTEURS_INCONNUS = (':focus-visible', ':where(', ':is(', ':has(')
PROPRIETES_INCONNUES = ('inset', 'padding-block', 'padding-inline', 'margin-block', 'margin-inline',
                        'container-type', 'accent-color', 'text-wrap', 'color-scheme', 'aspect-ratio')
VALEURS_INCONNUES = re.compile(r'(?<![-\w])(?:min|max|clamp)\(|\d(?:dvh|svh|lvh|cqi|cqw)|:\s*clip\b')

def regles(css):
    """Découpe la feuille en (prefixe, selecteur, declarations, suffixe)."""
    out, i, n, profondeur, debut = [], 0, len(css), 0, 0
    while i < n:
        c = css[i]
        if c == '{':
            if profondeur == 0: tete = css[debut:i]
            profondeur += 1
        elif c == '}':
            profondeur -= 1
            if profondeur == 0:
                out.append((tete, css[css.index('{', debut) + 1:i]))
                debut = i + 1
        i += 1
    return out

def demi(v):
    m = re.match(r'^(-?[\d.]+)([a-z%]*)$', v.strip())
    return f"{float(m.group(1))/2:g}{m.group(2) or 'px'}" if m else '0px'

def filtre_bloc(bloc, marges):
    """Garde les déclarations comprises par Chrome 78 ; note les gap de flexbox."""
    gardees, flex, gap = [], False, ''
    for d in bloc.split(';'):
        if ':' not in d: continue
        prop = d.split(':', 1)[0].strip().lower()
        if prop == 'display' and 'flex' in d: flex = True
        if prop in ('gap', 'row-gap', 'column-gap'): gap = d.split(':', 1)[1].strip()
        if prop in PROPRIETES_INCONNUES or VALEURS_INCONNUES.search(d): continue
        gardees.append(d.strip())
    return gardees, (flex and gap)

def convertir(src):
    tete, reste = src.split('<style>', 1)
    css, fin = reste.split('</style>', 1)
    sortie, marges = [], []
    def traite(texte, dans_media=''):
        res = []
        for selecteur, bloc in regles(texte):
            s = selecteur.strip()
            if s.startswith('@media') or s.startswith('@supports'):
                res.append(s + '{' + traite(bloc, s) + '}')
                continue
            if s.startswith('@'):
                res.append(s + '{' + bloc + '}')
                continue
            if any(x in s for x in SELECTEURS_INCONNUS):
                continue                       # règle entière jetée
            gardees, gap = filtre_bloc(bloc, marges)
            gardees = [g for g in gardees if not (gap and g.split(':')[0].strip() in ('gap', 'row-gap', 'column-gap'))]
            if gap:
                p = gap.split()
                regle = s + '>*{margin:' + demi(p[0]) + ' ' + demi(p[1] if len(p) > 1 else p[0]) + '}'
                marges.append((dans_media, regle))
            if gardees: res.append(s + '{' + ';'.join(gardees) + '}')
        return '\n'.join(res)
    css78 = traite(css)
    groupes = {}
    for media, regle in marges: groupes.setdefault(media, []).append(regle)
    for media, rs in groupes.items():
        css78 += '\n' + (media + '{' + '\n'.join(rs) + '}' if media else '\n'.join(rs))
    return tete + '<style>' + css78 + '</style>' + fin

if __name__ == '__main__':
    entree = sys.argv[1] if len(sys.argv) > 1 else 'index.html'
    sortie = sys.argv[2] if len(sys.argv) > 2 else 'chrome78.html'
    open(sortie, 'w', encoding='utf-8').write(convertir(open(entree, encoding='utf-8').read()))
    print(sortie, 'écrit')
