#!/usr/bin/env python3
"""Adapte la copie embarquée de l'outil de débogage au navigateur des tablettes Android 7.

L'outil est écrit pour les navigateurs récents : conteneur en `inset:0`, sélecteurs `:where()`,
largeurs en `min()`. Chrome 78 ignore tout cela et l'affichage part de travers. Ce script ajoute
les valeurs de secours, sans rien changer pour les navigateurs récents.
Lancer : python3 outils/compat-debogage.py <chemin de dist/debogage.js> > debogage-compat.js
"""
import re, sys

REMPLACEMENTS = [
    # conteneur de l'outil : sans inset, il n'a plus ni position ni taille
    ('HOTE.style.cssText = "position:fixed;inset:0;',
     'HOTE.style.cssText = "position:fixed;top:0;right:0;bottom:0;left:0;inset:0;'),
    ('inset: 0;', 'top: 0; right: 0; bottom: 0; left: 0; inset: 0;'),
    ('inset: 10px;', 'top: 10px; right: 10px; bottom: 10px; left: 10px; inset: 10px;'),
    ('max-height: min(78vh, 720px)', 'max-height: 78vh; max-height: min(78vh, 720px)'),
    ('max-width: min(92vw, 460px)', 'max-width: 92vw; max-width: min(92vw, 460px)'),
    ('width: min(92vw, 440px)', 'width: 92vw; max-width: 440px; width: min(92vw, 440px)'),
]

def police_de_base(src):
    """Duplique la règle de police écrite avec :where(), que Chrome 78 jette en entier."""
    m = re.search(r':where\(\.r\), :where\(\.r\) :where\(\*\) \{(.*?)\}', src, re.S)
    if not m:
        print('// règle :where(.r) introuvable : rien à dupliquer', file=sys.stderr)
        return src
    return src[:m.end()] + '.r, .r * {' + m.group(1) + '}' + src[m.end():]

def main():
    src = open(sys.argv[1], encoding='utf-8').read()
    for avant, apres in REMPLACEMENTS:
        n = src.count(avant)
        if not n:
            print(f'// introuvable, ignoré : {avant[:50]}', file=sys.stderr)
            continue
        src = src.replace(avant, apres)
        print(f'// {n}× {avant[:50]}', file=sys.stderr)
    src = police_de_base(src)
    sys.stdout.write(src)

main()
