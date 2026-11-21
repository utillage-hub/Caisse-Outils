package fr.utillage.caisseoutils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client FTP minimal, en mode passif, sans dépendance Android : il sert au mode « Envoyer »,
 * quand c'est la Caisse à Outils qui dépose des fichiers sur l'ordinateur d'en face.
 *
 * Comme le serveur, il est écrit en Java pur pour pouvoir être essayé sur un poste de travail.
 */
public class ClientFtp {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Une entrée de dossier distant. */
    public static class Entree {
        public final String nom;
        public final boolean dossier;
        public final long taille;
        Entree(String nom, boolean dossier, long taille) { this.nom = nom; this.dossier = dossier; this.taille = taille; }
    }

    private Socket ctrl;
    private BufferedReader lit;
    private Writer ecrit;
    private boolean mlsd;

    // ---------- connexion ----------

    public void connecter(String hote, int port, String utilisateur, String motDePasse) throws IOException {
        ctrl = new Socket();
        ctrl.connect(new InetSocketAddress(hote, port), 12000);
        ctrl.setSoTimeout(60000);
        lit = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), UTF8));
        ecrit = new OutputStreamWriter(ctrl.getOutputStream(), UTF8);
        attendre(220);
        String u = utilisateur == null || utilisateur.trim().isEmpty() ? "anonymous" : utilisateur.trim();
        String r = envoyer("USER " + u);
        if (code(r) == 331) {
            r = envoyer("PASS " + (motDePasse == null || motDePasse.isEmpty() ? "caisse@outils" : motDePasse));
        }
        if (code(r) != 230 && code(r) != 202) throw new IOException("Connexion refusée : " + r);
        try { envoyer("OPTS UTF8 ON"); } catch (IOException ignore) { }
        String f;
        try { f = envoyer("FEAT"); } catch (IOException e) { f = ""; }
        mlsd = f.toUpperCase(Locale.US).contains("MLSD");
        verifier(envoyer("TYPE I"), 200);
    }

    public void fermer() {
        try { if (ctrl != null && !ctrl.isClosed()) envoyer("QUIT"); } catch (Exception ignore) { }
        try { if (ctrl != null) ctrl.close(); } catch (Exception ignore) { }
        ctrl = null;
    }

    public boolean connecte() { return ctrl != null && !ctrl.isClosed(); }

    // ---------- dialogue ----------

    private String envoyer(String commande) throws IOException {
        ecrit.write(commande + "\r\n");
        ecrit.flush();
        return lireReponse();
    }

    /** Lit une réponse, y compris les réponses sur plusieurs lignes (« 211-… » puis « 211 fin »). */
    private String lireReponse() throws IOException {
        String ligne = lit.readLine();
        if (ligne == null) throw new IOException("Le serveur a coupé la connexion");
        StringBuilder tout = new StringBuilder(ligne);
        if (ligne.length() >= 4 && ligne.charAt(3) == '-') {
            String fin = ligne.substring(0, 3) + " ";
            String suite;
            while ((suite = lit.readLine()) != null) {
                tout.append('\n').append(suite);
                if (suite.startsWith(fin)) break;
            }
        }
        return tout.toString();
    }

    private String attendre(int attendu) throws IOException {
        String r = lireReponse();
        verifier(r, attendu);
        return r;
    }

    private static int code(String reponse) {
        try { return Integer.parseInt(reponse.substring(0, 3)); } catch (Exception e) { return -1; }
    }

    private static void verifier(String reponse, int attendu) throws IOException {
        int c = code(reponse);
        if (c / 100 != attendu / 100) throw new IOException(reponse.split("\n")[0]);
    }

    /** Ouvre la connexion de données en mode passif. */
    private Socket passif() throws IOException {
        String r = envoyer("PASV");
        if (code(r) != 227) {
            r = envoyer("EPSV");
            if (code(r) != 229) throw new IOException("Mode passif refusé : " + r);
            int a = r.indexOf("(|||"), b = r.indexOf('|', a + 4);
            int p = Integer.parseInt(r.substring(a + 4, b).trim());
            return ouvrir(ctrl.getInetAddress().getHostAddress(), p);
        }
        int a = r.indexOf('('), b = r.indexOf(')', a);
        String[] n = r.substring(a + 1, b).split(",");
        if (n.length < 6) throw new IOException("Réponse passive incomprise : " + r);
        String ip = n[0].trim() + "." + n[1].trim() + "." + n[2].trim() + "." + n[3].trim();
        int p = (Integer.parseInt(n[4].trim()) << 8) + Integer.parseInt(n[5].trim());
        // certains serveurs derrière un routeur annoncent une adresse privée inutilisable :
        // on garde celle qui a servi à se connecter
        if (ip.startsWith("0.") || ip.startsWith("127.")) ip = ctrl.getInetAddress().getHostAddress();
        return ouvrir(ip, p);
    }

    private Socket ouvrir(String ip, int port) throws IOException {
        Socket d = new Socket();
        d.connect(new InetSocketAddress(ip, port), 12000);
        d.setSoTimeout(120000);
        return d;
    }

    // ---------- opérations ----------

    public String dossier() throws IOException {
        String r = envoyer("PWD");
        int a = r.indexOf('"'), b = r.lastIndexOf('"');
        return a >= 0 && b > a ? r.substring(a + 1, b) : "/";
    }

    public void aller(String chemin) throws IOException {
        verifier(envoyer("CWD " + chemin), 250);
    }

    public List<Entree> lister() throws IOException {
        Socket d = passif();
        String r = envoyer(mlsd ? "MLSD" : "LIST");
        if (code(r) / 100 != 1) { try { d.close(); } catch (Exception ignore) { } throw new IOException(r.split("\n")[0]); }
        StringBuilder b = new StringBuilder();
        try {
            InputStream i = d.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.append(new String(buf, 0, n, UTF8));
        } finally {
            try { d.close(); } catch (Exception ignore) { }
        }
        verifier(lireReponse(), 226);
        List<Entree> liste = new ArrayList<Entree>();
        for (String ligne : b.toString().split("\r?\n")) {
            Entree e = mlsd ? lireMlsd(ligne) : lireList(ligne);
            if (e != null && !".".equals(e.nom) && !"..".equals(e.nom)) liste.add(e);
        }
        return liste;
    }

    private static Entree lireMlsd(String l) {
        int esp = l.indexOf("; ");
        if (esp < 0) return null;
        String faits = l.substring(0, esp).toLowerCase(Locale.US), nom = l.substring(esp + 2);
        if (nom.isEmpty()) return null;
        boolean dir = faits.contains("type=dir") || faits.contains("type=cdir") || faits.contains("type=pdir");
        long t = 0;
        int a = faits.indexOf("size=");
        if (a >= 0) {
            int f = faits.indexOf(';', a);
            try { t = Long.parseLong(faits.substring(a + 5, f < 0 ? faits.length() : f).trim()); } catch (Exception ignore) { }
        }
        return new Entree(nom, dir, t);
    }

    /** Lit une ligne de LIST façon « ls -l » (format de loin le plus répandu). */
    private static Entree lireList(String l) {
        String ligne = l.trim();
        if (ligne.isEmpty() || ligne.startsWith("total ")) return null;   // en-tête de « ls -l »
        String[] m = ligne.split("\\s+", 9);
        if (m.length >= 9 && (m[0].length() == 10 || m[0].length() == 11)
                && (m[0].charAt(0) == 'd' || m[0].charAt(0) == '-' || m[0].charAt(0) == 'l')) {
            long t = 0;
            try { t = Long.parseLong(m[4]); } catch (Exception ignore) { }
            String nom = m[8];
            int fleche = nom.indexOf(" -> ");               // lien symbolique : on garde la cible de côté
            if (m[0].charAt(0) == 'l' && fleche > 0) nom = nom.substring(0, fleche);
            if (nom.isEmpty() || ".".equals(nom) || "..".equals(nom)) return null;
            return new Entree(nom, m[0].charAt(0) == 'd', t);
        }
        // format Windows : « 11-20-26  10:14PM       <DIR>          Dossier avec espaces »
        String[] w = ligne.split("\\s+", 4);
        if (w.length >= 4 && w[0].indexOf('-') > 0) {
            boolean dir = "<DIR>".equalsIgnoreCase(w[2]);
            long t = 0;
            if (!dir) try { t = Long.parseLong(w[2]); } catch (Exception e) { return null; }
            return new Entree(w[3], dir, t);
        }
        return null;
    }

    /** Dépose un fichier sous le nom donné. Renvoie le nombre d'octets envoyés. */
    public long envoyerFichier(File local, String nomDistant) throws IOException {
        Socket d = passif();
        String r = envoyer("STOR " + nomDistant);
        if (code(r) / 100 != 1) { try { d.close(); } catch (Exception ignore) { } throw new IOException(r.split("\n")[0]); }
        long total = 0;
        InputStream i = null;
        try {
            i = new FileInputStream(local);
            OutputStream o = new BufferedOutputStream(d.getOutputStream(), 32768);
            byte[] buf = new byte[32768];
            int n;
            while ((n = i.read(buf)) > 0) { o.write(buf, 0, n); total += n; }
            o.flush();
        } finally {
            if (i != null) try { i.close(); } catch (Exception ignore) { }
            try { d.close(); } catch (Exception ignore) { }
        }
        verifier(lireReponse(), 226);
        return total;
    }

    /** Récupère un fichier distant. Renvoie le nombre d'octets reçus. */
    public long recevoirFichier(String nomDistant, File local) throws IOException {
        Socket d = passif();
        String r = envoyer("RETR " + nomDistant);
        if (code(r) / 100 != 1) { try { d.close(); } catch (Exception ignore) { } throw new IOException(r.split("\n")[0]); }
        long total = 0;
        OutputStream o = null;
        try {
            File parent = local.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            o = new BufferedOutputStream(new FileOutputStream(local), 32768);
            InputStream i = d.getInputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = i.read(buf)) > 0) { o.write(buf, 0, n); total += n; }
            o.flush();
        } finally {
            if (o != null) try { o.close(); } catch (Exception ignore) { }
            try { d.close(); } catch (Exception ignore) { }
        }
        verifier(lireReponse(), 226);
        return total;
    }

    public void creerDossier(String nom) throws IOException {
        verifier(envoyer("MKD " + nom), 257);
    }
}
