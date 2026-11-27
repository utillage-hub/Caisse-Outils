package fr.utillage.caisseoutils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Petit serveur web de dépannage, à côté du serveur FTP : il sert une page unique où
 * l'on dépose et récupère des fichiers depuis n'importe quel navigateur. C'est ce qui
 * rend le code QR utile — en face, il n'y a rien à installer.
 *
 * Écrit sans dépendance Android pour pouvoir être essayé sur un poste de travail
 * (voir la méthode main en fin de fichier).
 */
public class ServeurWeb {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File racine;
    private final int port;
    private final boolean ecriture;
    private final ServeurFtp.Journal journal;

    private ServerSocket ecoute;
    private volatile boolean tourne;
    private volatile int recus, envoyes;

    public ServeurWeb(File racine, int port, boolean ecriture, ServeurFtp.Journal journal) {
        this.racine = racine;
        this.port = port;
        this.ecriture = ecriture;
        this.journal = journal;
    }

    public boolean tourne() { return tourne; }
    public int port() { return ecoute != null ? ecoute.getLocalPort() : port; }
    public int recus() { return recus; }
    public int envoyes() { return envoyes; }

    private void note(String t) {
        if (journal != null) { try { journal.ligne(t); } catch (Exception ignore) { } }
    }

    public synchronized void demarrer() throws IOException {
        if (tourne) return;
        if (!racine.exists() && !racine.mkdirs()) throw new IOException("Dossier inaccessible");
        ecoute = new ServerSocket();
        ecoute.setReuseAddress(true);
        ecoute.bind(new InetSocketAddress((InetAddress) null, port), 16);
        tourne = true;
        Thread t = new Thread(new Runnable() { public void run() { boucle(); } }, "web-ecoute");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void arreter() {
        tourne = false;
        try { if (ecoute != null) ecoute.close(); } catch (Exception ignore) { }
    }

    private void boucle() {
        while (tourne) {
            try {
                final Socket s = ecoute.accept();
                s.setSoTimeout(600000);
                Thread t = new Thread(new Runnable() { public void run() { servir(s); } }, "web-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (tourne) note("Web : connexion refusée.");
            }
        }
    }

    // ---------- une requête ----------

    private void servir(Socket s) {
        try {
            BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 32768);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32768);
            String ligne = lireLigne(in);
            if (ligne == null) return;
            String[] m = ligne.split(" ");
            if (m.length < 2) return;
            String methode = m[0], chemin = m[1];
            long taille = 0;
            String typeContenu = "";
            String h;
            while ((h = lireLigne(in)) != null && !h.isEmpty()) {
                String b = h.toLowerCase(Locale.US);
                if (b.startsWith("content-length:")) {
                    try { taille = Long.parseLong(h.substring(15).trim()); } catch (Exception ignore) { }
                } else if (b.startsWith("content-type:")) {
                    typeContenu = h.substring(13).trim();
                }
            }

            if ("GET".equals(methode) && (chemin.equals("/") || chemin.startsWith("/?"))) {
                byte[] page = page().getBytes(UTF8);
                entete(out, "200 OK", "text/html; charset=utf-8", page.length, null);
                out.write(page);
            } else if ("GET".equals(methode) && chemin.startsWith("/f/")) {
                File f = new File(racine, nomSur(decoder(chemin.substring(3))));
                if (!f.isFile()) { erreur(out, "404 Not Found", "Fichier introuvable."); }
                else {
                    entete(out, "200 OK", "application/octet-stream", f.length(),
                            "attachment; filename*=UTF-8''" + encoder(f.getName()));
                    InputStream fi = new FileInputStream(f);
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = fi.read(buf)) > 0) out.write(buf, 0, n);
                    fi.close();
                    envoyes++;
                    note("Web : " + f.getName() + " téléchargé.");
                }
            } else if ("POST".equals(methode) && chemin.startsWith("/envoi")) {
                if (!ecriture) { erreur(out, "403 Forbidden", "Le partage est en lecture seule."); }
                else {
                    int k = recevoir(in, typeContenu, taille);
                    byte[] page = ("<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='1;url=/'>"
                            + "<body style=\"font:16px system-ui;padding:24px\">" + k + " fichier(s) reçu(s). Retour…</body>").getBytes(UTF8);
                    entete(out, "200 OK", "text/html; charset=utf-8", page.length, null);
                    out.write(page);
                }
            } else {
                erreur(out, "404 Not Found", "Page inconnue.");
            }
            out.flush();
        } catch (Exception e) {
            // connexion coupée en cours de route : rien à faire
        } finally {
            try { s.close(); } catch (Exception ignore) { }
        }
    }

    private void entete(OutputStream o, String etat, String type, long taille, String disposition) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("HTTP/1.1 ").append(etat).append("\r\n");
        b.append("Content-Type: ").append(type).append("\r\n");
        if (taille >= 0) b.append("Content-Length: ").append(taille).append("\r\n");
        if (disposition != null) b.append("Content-Disposition: ").append(disposition).append("\r\n");
        b.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        o.write(b.toString().getBytes(UTF8));
    }

    private void erreur(OutputStream o, String etat, String texte) throws IOException {
        byte[] c = ("<!doctype html><meta charset=utf-8><body style=\"font:16px system-ui;padding:24px\">" + texte + "</body>").getBytes(UTF8);
        entete(o, etat, "text/html; charset=utf-8", c.length, null);
        o.write(c);
    }

    /** Lit une ligne terminée par CRLF, sans tampon de caractères (les octets suivants restent intacts). */
    private static String lireLigne(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), UTF8);
    }

    // ---------- réception multipart ----------

    private int recevoir(InputStream in, String typeContenu, long taille) throws IOException {
        int p = typeContenu.toLowerCase(Locale.US).indexOf("boundary=");
        if (p < 0) return 0;
        String sep = typeContenu.substring(p + 9).trim();
        if (sep.startsWith("\"")) sep = sep.substring(1, sep.length() - 1);
        byte[] marque = ("\r\n--" + sep).getBytes(UTF8);
        // la première frontière n'est pas précédée d'un saut de ligne : on la consomme à la main
        lireLigne(in);
        int k = 0;
        while (true) {
            String nom = null;
            String h;
            while ((h = lireLigne(in)) != null && !h.isEmpty()) {
                String b = h.toLowerCase(Locale.US);
                if (b.startsWith("content-disposition:")) {
                    int a = b.indexOf("filename=\"");
                    if (a >= 0) {
                        int f = h.indexOf('"', a + 10);
                        if (f > 0) nom = h.substring(a + 10, f);
                    }
                }
            }
            if (h == null) break;
            File cible = nom == null || nom.trim().isEmpty() ? null : libre(nomSur(nom));
            OutputStream o = cible == null ? null : new BufferedOutputStream(new FileOutputStream(cible), 32768);
            boolean fin = finDePartie(in, marque, o);
            if (o != null) { o.close(); k++; recus++; note("Web : " + cible.getName() + " reçu (" + cible.length() + " octets)."); }
            if (fin) break;
        }
        return k;
    }

    /**
     * Recopie le corps d'une partie jusqu'à la frontière suivante, en flux.
     * Renvoie true si c'était la dernière partie.
     */
    private boolean finDePartie(InputStream in, byte[] marque, OutputStream o) throws IOException {
        byte[] fenetre = new byte[marque.length];
        int rempli = 0;
        while (true) {
            int c = in.read();
            if (c == -1) { if (o != null) o.write(fenetre, 0, rempli); return true; }
            if (rempli < marque.length) {
                fenetre[rempli++] = (byte) c;
            } else {
                if (o != null) o.write(fenetre[0]);
                System.arraycopy(fenetre, 1, fenetre, 0, marque.length - 1);
                fenetre[marque.length - 1] = (byte) c;
            }
            if (rempli == marque.length && Arrays.equals(fenetre, marque)) {
                int a = in.read(), b = in.read();      // « -- » pour la dernière, CRLF sinon
                return a == '-' && b == '-';
            }
        }
    }

    private File libre(String nom) {
        File f = new File(racine, nom);
        int p = nom.lastIndexOf('.');
        for (int k = 1; f.exists() && k < 500; k++)
            f = new File(racine, p > 0 ? nom.substring(0, p) + " (" + k + ")" + nom.substring(p) : nom + " (" + k + ")");
        return f;
    }

    /** Ne garde que le nom du fichier, jamais un chemin : on ne sort pas du dossier partagé. */
    static String nomSur(String nom) {
        String n = nom.replace('\\', '/');
        int b = n.lastIndexOf('/');
        if (b >= 0) n = n.substring(b + 1);
        n = n.replaceAll("[\\x00-\\x1f:*?\"<>|]", "_").trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) n = "fichier-" + System.currentTimeMillis();
        return n;
    }

    static String decoder(String s) {
        StringBuilder b = new StringBuilder();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try { o.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; continue; }
                catch (Exception ignore) { }
            }
            if (c == '+') { o.write(' '); continue; }
            byte[] u = String.valueOf(c).getBytes(UTF8);
            o.write(u, 0, u.length);
        }
        b.append(new String(o.toByteArray(), UTF8));
        return b.toString();
    }

    static String encoder(String s) {
        StringBuilder b = new StringBuilder();
        byte[] u = s.getBytes(UTF8);
        for (byte x : u) {
            int v = x & 255;
            if ((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9') || v == '.' || v == '-' || v == '_') b.append((char) v);
            else b.append('%').append(String.format("%02X", v));
        }
        return b.toString();
    }

    private static String echapper(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String taille(long o) {
        if (o < 1024) return o + " o";
        if (o < 1048576) return String.format(Locale.FRANCE, "%.1f ko", o / 1024.0);
        if (o < 1073741824) return String.format(Locale.FRANCE, "%.1f Mo", o / 1048576.0);
        return String.format(Locale.FRANCE, "%.2f Go", o / 1073741824.0);
    }

    // ---------- la page ----------

    private String page() {
        File[] l = racine.listFiles();
        List<File> fichiers = new ArrayList<File>();
        if (l != null) for (File f : l) if (f.isFile()) fichiers.add(f);
        java.util.Collections.sort(fichiers, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        StringBuilder liste = new StringBuilder();
        for (File f : fichiers)
            liste.append("<li><a href=\"/f/").append(encoder(f.getName())).append("\" download>")
                 .append(echapper(f.getName())).append("</a><span>").append(taille(f.length())).append("</span></li>");
        if (fichiers.isEmpty()) liste.append("<li class=vide>Aucun fichier pour l'instant.</li>");

        return "<!doctype html><html lang=fr><head><meta charset=utf-8>"
            + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
            + "<title>Caisse à Outils — partage</title><style>"
            + ":root{color-scheme:light dark}"
            + "*{box-sizing:border-box}"
            + "body{margin:0;padding:20px;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
            + "background:#f1efee;color:#2b2726;max-width:680px;margin:0 auto}"
            + "@media (prefers-color-scheme:dark){body{background:#171413;color:#eeeae8}"
            + ".c{background:#221e1d!important;border-color:#3a3432!important}}"
            + "h1{font-size:1.4rem;margin:0 0 4px}p.s{margin:0 0 18px;color:#76706d}"
            + ".c{background:#fff;border:1px solid #d9d4d1;border-radius:10px;padding:16px;margin-bottom:14px}"
            + "h2{font-size:1.05rem;margin:0 0 10px}"
            + "input[type=file]{width:100%;margin-bottom:12px}"
            + "button{width:100%;min-height:50px;border:0;border-radius:8px;background:#d9423a;color:#fff;"
            + "font:600 1rem inherit;cursor:pointer}button:disabled{opacity:.5}"
            + "ul{list-style:none;margin:0;padding:0}"
            + "li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid #d9d4d1}"
            + "li:first-child{border-top:0}li.vide{color:#76706d;justify-content:flex-start}"
            + "a{color:#d9423a;word-break:break-word}span{color:#76706d;white-space:nowrap;font-size:.85rem}"
            + "</style></head><body>"
            + "<h1>Caisse à Outils</h1><p class=s>Partage de fichiers sur le réseau local.</p>"
            + (ecriture
                ? "<form class=c method=post action=/envoi enctype=multipart/form-data>"
                + "<h2>Envoyer vers le téléphone</h2>"
                + "<input type=file name=f multiple required>"
                + "<button type=submit>Envoyer</button></form>"
                : "<div class=c><h2>Envoi désactivé</h2><p>Le partage est en lecture seule.</p></div>")
            + "<div class=c><h2>Fichiers sur le téléphone</h2><ul>" + liste + "</ul></div>"
            + "</body></html>";
    }

    // ---------- essai depuis un poste de travail ----------

    /** java fr.utillage.caisseoutils.ServeurWeb &lt;dossier&gt; [port] */
    public static void main(String[] args) throws Exception {
        File d = new File(args.length > 0 ? args[0] : ".");
        int p = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        ServeurWeb s = new ServeurWeb(d, p, true, new ServeurFtp.Journal() {
            public void ligne(String t) { System.out.println("[web] " + t); }
        });
        s.demarrer();
        System.out.println("Pret sur " + s.port() + ", dossier " + d.getAbsolutePath());
        Thread.sleep(Long.MAX_VALUE);
    }
}
