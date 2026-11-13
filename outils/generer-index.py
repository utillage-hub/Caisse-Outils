#!/usr/bin/env python3
"""Fabrique index.html à partir de la source de l'artefact.

L'artefact est hébergé dans un gabarit qui fournit le doctype, l'encodage et la
balise viewport. La version autonome (site, APK) doit les porter elle-même.
    python3 outils/generer-index.py ../caisse-a-outils.html
"""
import io, sys, os

TETE = """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#d9423a">
"""
PIED = "\n</body>\n</html>\n"

def main():
    src = sys.argv[1] if len(sys.argv) > 1 else '../caisse-a-outils.html'
    dest = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), '..', 'index.html')
    corps = io.open(src, encoding='utf-8').read()
    if not corps.lstrip().startswith('<title>'):
        sys.exit('La source doit commencer par <title> : ' + src)
    out = TETE + corps.rstrip('\n') + PIED
    io.open(dest, 'w', encoding='utf-8').write(out)
    print('%s écrit (%d octets)' % (os.path.normpath(dest), len(out)))

main()
