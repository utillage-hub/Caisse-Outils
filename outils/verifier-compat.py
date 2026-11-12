#!/usr/bin/env python3
"""Vérifie que index.html n'utilise rien de plus récent que Chrome 78.

Certaines tablettes d'atelier (Android 7, sans Play Store) ont un navigateur interne
figé en Chrome 78 : une seule syntaxe plus récente et toute l'application reste blanche.
Lancer : python3 outils/verifier-compat.py [fichier]
"""
import re, sys

# (motif, nom, version de Chrome qui l'a introduit)
INTERDITS = [
    (r'\?\.(?![\d\s])',    "chaînage optionnel ?.",            80),   # pas le ternaire « a ? .5 : 1 »
    (r'\?\?',              "opérateur ??",                     80),
    (r'(?:\|\||&&|\?\?)=', "affectation logique ||= &&= ??=",  85),
    (r'\.replaceAll\s*\(', "String.replaceAll",                85),
    (r'\.at\s*\(\s*-?\d',  "Array.at",                         92),
    (r'Object\.hasOwn',    "Object.hasOwn",                    93),
    (r'structuredClone',   "structuredClone",                  98),
    (r'randomUUID',        "crypto.randomUUID",                92),
    (r'\.findLast',        "findLast",                         97),
    (r'Promise\.any',      "Promise.any",                      85),
    (r'\.replaceChildren', "replaceChildren",                  86),
    (r'\.to(?:Sorted|Reversed|Spliced)\s*\(', "toSorted / toReversed", 110),
    (r'\bWeakRef\b',       "WeakRef",                          84),
]

def code_seul(src):
    """Remplace commentaires, chaînes et expressions régulières par des espaces, en gardant
    les retours à la ligne. Le contenu des gabarits `...${ici}...` reste du code : c'est là
    que l'application construit presque toute son interface."""
    out, i, n = [], 0, len(src)
    avant = ''          # dernier caractère significatif, pour reconnaître une expression régulière
    pile = []           # ('tpl',) dans un gabarit, ['sub', profondeur] dans un ${...}
    def blanc(t): return ''.join(ch if ch == '\n' else ' ' for ch in t)
    while i < n:
        c, d = src[i], src[i:i+2]
        if pile and pile[-1][0] == 'tpl':                 # texte d'un gabarit
            if c == '\\': out.append('  '); i += 2; continue
            if c == '`':   out.append(' '); pile.pop(); avant = '`'; i += 1; continue
            if d == '${':  out.append('  '); pile.append(['sub', 0]); avant = '{'; i += 2; continue
            out.append('\n' if c == '\n' else ' '); i += 1; continue
        if d == '//':
            j = src.find('\n', i); j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j; continue
        if d == '/*':
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            out.append(blanc(src[i:j])); i = j; continue
        if c in '"\'':
            j = i + 1
            while j < n:
                if src[j] == '\\': j += 2; continue
                if src[j] == c: j += 1; break
                j += 1
            out.append(blanc(src[i:j])); avant = c; i = j; continue
        if c == '`':
            out.append(' '); pile.append(['tpl']); i += 1; continue
        if c == '/' and (avant == '' or avant in '(,=:[!&|?{};+-*%~^<>'):
            j, classe = i + 1, False
            while j < n:
                if src[j] == '\\': j += 2; continue
                if src[j] == '[': classe = True
                elif src[j] == ']': classe = False
                elif src[j] == '/' and not classe: j += 1; break
                elif src[j] == '\n': break
                j += 1
            out.append(' ' * (j - i)); avant = '/'; i = j; continue
        if pile and pile[-1][0] == 'sub':
            if c == '{': pile[-1][1] += 1
            elif c == '}':
                if pile[-1][1] == 0: out.append(' '); pile.pop(); avant = '}'; i += 1; continue
                pile[-1][1] -= 1
        out.append(c)
        if not c.isspace(): avant = c
        i += 1
    return ''.join(out)

def main():
    chemin = sys.argv[1] if len(sys.argv) > 1 else 'index.html'
    src = open(chemin, encoding='utf-8').read()
    soucis = 0
    for bloc in re.finditer(r'<script>(.*?)</script>', src, re.S):
        depart = src[:bloc.start(1)].count('\n') + 1
        lignes = code_seul(bloc.group(1)).split('\n')
        for num, ligne in enumerate(lignes, depart):
            for motif, nom, chrome in INTERDITS:
                if re.search(motif, ligne):
                    soucis += 1
                    print(f"{chemin}:{num}  {nom} (Chrome {chrome}) : {ligne.strip()[:120]}")
    if soucis:
        print(f"\n{soucis} point(s) incompatible(s) avec Chrome 78.")
        return 1
    print(f"{chemin} : compatible Chrome 78.")
    return 0

if __name__ == '__main__':
    sys.exit(main())
