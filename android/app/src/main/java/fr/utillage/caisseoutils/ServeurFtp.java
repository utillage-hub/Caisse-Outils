package fr.utillage.caisseoutils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Serveur FTP minimal, écrit sans aucune dépendance Android : il tourne tel quel sur un
 * poste de travail, ce qui permet de l'essayer pour de bon avant de le livrer dans l'APK.
 *
 * Il couvre ce dont se servent l'Explorateur Windows, le Finder, FileZilla, WinSCP et les
 * clients FTP Android : connexion avec ou sans mot de passe, mode passif, listage simple
 * (LIST) et machine (MLSD), envoi, réception, reprise, renommage et suppression.
 *
 * Volontairement absents : le mode actif (PORT), qui ne traverse pas les box, et le
 * chiffrement, qui n'a pas de sens pour un partage de quelques minutes sur le Wi-Fi local.
 */
public class ServeurFtp {

    /** Pour afficher l'activité dans l'application. */
    public interface Journal {
        void ligne(String texte);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int port;
    private final String utilisateur;
    private final String motDePasse;
    private final boolean ecriture;
    private final File racine;
    private final Journal journal;

    private ServerSocket ecoute;
    private Thread fil;
    private volatile boolean tourne;
    private final List<Session> sessions = Collections.synchronizedList(new ArrayList<Session>());

    private volatile int recus, envoyes;

    public ServeurFtp(File racine, int port, String utilisateur, String motDePasse, boolean ecriture, Journal journal) {
        this.racine = racine;
        this.port = port;
        this.utilisateur = utilisateur == null ? "" : utilisateur.trim();
        this.motDePasse = motDePasse == null ? "" : motDePasse;
        this.ecriture = ecriture;
        this.journal = journal;
    }

    public boolean tourne() { return tourne; }
    public int port() { return ecoute != null ? ecoute.getLocalPort() : port; }
    public int recus() { return recus; }
    public int envoyes() { return envoyes; }
    public int clients() { return sessions.size(); }
    public File racine() { return racine; }

    private void note(String t) {
        if (journal != null) {
            try { journal.ligne(t); } catch (Exception ignore) { }
        }
    }

    /** Ouvre l'écoute. Lève une IOException si le port est déjà pris. */
    public synchronized void demarrer() throws IOException {
        if (tourne) return;
        if (!racine.exists() && !racine.mkdirs()) throw new IOException("Dossier inaccessible : " + racine);
        ecoute = new ServerSocket();
        ecoute.setReuseAddress(true);
        ecoute.bind(new InetSocketAddress((InetAddress) null, port), 8);
        tourne = true;
        fil = new Thread(new Runnable() {
            public void run() { boucle(); }
        }, "ftp-ecoute");
        fil.setDaemon(true);
        fil.start();
        note("Serveur ouvert sur le port " + port() + ".");
    }

    public synchronized void arreter() {
        if (!tourne) return;
        tourne = false;
        try { ecoute.close(); } catch (Exception ignore) { }
        synchronized (sessions) {
            for (Session s : new ArrayList<Session>(sessions)) s.fermer();
        }
        sessions.clear();
        note("Serveur arrêté.");
    }

    private void boucle() {
        while (tourne) {
            try {
                Socket c = ecoute.accept();
                c.setSoTimeout(300000);           // cinq minutes sans un mot : le client est parti
                Session s = new Session(c);
                sessions.add(s);
                Thread t = new Thread(s, "ftp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (tourne) note("Connexion refusée : " + e.getMessage());
            }
        }
    }

    // ---------- chemins ----------

    /** Transforme un chemin FTP en fichier réel, en refusant toute sortie du dossier partagé. */
    private File reel(String virtuel) throws IOException {
        String p = virtuel.replace('\\', '/');
        File f = new File(racine, p.startsWith("/") ? p.substring(1) : p);
        String bon = racine.getCanonicalPath(), vu = f.getCanonicalPath();
        if (!vu.equals(bon) && !vu.startsWith(bon + File.separator)) throw new IOException("Hors du dossier partagé");
        return f;
    }

    private static String joindre(String base, String ajout) {
        if (ajout == null || ajout.isEmpty()) return base;
        String p = ajout.replace('\\', '/');
        if (!p.startsWith("/")) p = (base.endsWith("/") ? base : base + "/") + p;
        List<String> pile = new ArrayList<String>();
        for (String bout : p.split("/")) {
            if (bout.isEmpty() || ".".equals(bout)) continue;
            if ("..".equals(bout)) { if (!pile.isEmpty()) pile.remove(pile.size() - 1); continue; }
            pile.add(bout);
        }
        StringBuilder b = new StringBuilder("/");
        for (int i = 0; i < pile.size(); i++) { if (i > 0) b.append('/'); b.append(pile.get(i)); }
        return b.toString();
    }

    // ---------- une connexion ----------

    private class Session implements Runnable {
        private final Socket ctrl;
        private Writer out;
        private InputStream in;
        private String dossier = "/";
        private boolean identifie;
        private String nomDonne = "";
        private ServerSocket passif;
        private boolean binaire = true;
        private long reprise;
        private String renommeDe;

        Session(Socket s) { this.ctrl = s; }

        void fermer() {
            try { if (passif != null) passif.close(); } catch (Exception ignore) { }
            try { ctrl.close(); } catch (Exception ignore) { }
        }

        private void dis(String ligne) throws IOException {
            out.write(ligne + "\r\n");
            out.flush();
        }

        public void run() {
            try {
                in = ctrl.getInputStream();
                out = new OutputStreamWriter(ctrl.getOutputStream(), UTF8);
                note("Connexion de " + ctrl.getInetAddress().getHostAddress() + ".");
                dis("220 Caisse a Outils FTP");
                StringBuilder tampon = new StringBuilder();
                int c;
                while (tourne && (c = in.read()) != -1) {
                    if (c == '\n') {
                        String ligne = tampon.toString();
                        tampon.setLength(0);
                        if (ligne.endsWith("\r")) ligne = ligne.substring(0, ligne.length() - 1);
                        boolean suite;
                        try {
                            suite = commande(ligne);
                        } catch (IOException erreurCommande) {
                            // chemin refusé, disque plein… : on répond et la connexion continue
                            if (ctrl.isClosed()) break;
                            dis("550 " + erreurCommande.getMessage());
                            suite = true;
                        }
                        if (!suite) break;
                    } else {
                        tampon.append((char) c);
                        if (tampon.length() > 4096) tampon.setLength(0);   // ligne absurde : on repart
                    }
                }
            } catch (Exception e) {
                // déconnexion : rien à signaler
            } finally {
                fermer();
                sessions.remove(this);
            }
        }

        /** Renvoie false pour fermer la connexion. */
        private boolean commande(String brut) throws IOException {
            // les noms de fichiers arrivent en UTF-8, octet par octet : on les recompose
            String ligne = new String(brut.getBytes("ISO-8859-1"), UTF8);
            int esp = ligne.indexOf(' ');
            String mot = (esp < 0 ? ligne : ligne.substring(0, esp)).toUpperCase(Locale.US).trim();
            String arg = esp < 0 ? "" : ligne.substring(esp + 1).trim();
            if (mot.isEmpty()) return true;

            if ("USER".equals(mot)) {
                nomDonne = arg;
                identifie = false;
                if (motDePasse.isEmpty() && (utilisateur.isEmpty() || utilisateur.equals(arg) || "anonymous".equalsIgnoreCase(arg))) {
                    identifie = true;
                    dis("230 Bienvenue");
                } else {
                    dis("331 Mot de passe demande");
                }
                return true;
            }
            if ("PASS".equals(mot)) {
                boolean nomOk = utilisateur.isEmpty() || utilisateur.equals(nomDonne) || "anonymous".equalsIgnoreCase(nomDonne);
                identifie = nomOk && (motDePasse.isEmpty() || motDePasse.equals(arg));
                if (identifie) { note("Client identifie."); dis("230 Bienvenue"); }
                else { note("Mot de passe refuse."); dis("530 Identifiants refuses"); }
                return true;
            }
            if ("QUIT".equals(mot)) { dis("221 Au revoir"); return false; }
            if ("NOOP".equals(mot)) { dis("200 Ok"); return true; }
            if ("SYST".equals(mot)) { dis("215 UNIX Type: L8"); return true; }
            if ("FEAT".equals(mot)) {
                dis("211-Extensions");
                dis(" UTF8");
                dis(" SIZE");
                dis(" MDTM");
                dis(" MLST type*;size*;modify*;");
                dis(" MLSD");
                dis(" REST STREAM");
                dis(" EPSV");
                dis("211 Fin");
                return true;
            }
            if ("OPTS".equals(mot)) { dis(arg.toUpperCase(Locale.US).startsWith("UTF8") ? "200 Ok" : "501 Option inconnue"); return true; }

            if (!identifie) { dis("530 Identifiez-vous d'abord"); return true; }

            if ("PWD".equals(mot) || "XPWD".equals(mot)) { dis("257 \"" + dossier + "\""); return true; }
            if ("TYPE".equals(mot)) { binaire = !arg.toUpperCase(Locale.US).startsWith("A"); dis("200 Type " + (binaire ? "I" : "A")); return true; }
            if ("MODE".equals(mot)) { dis(arg.toUpperCase(Locale.US).startsWith("S") ? "200 Ok" : "504 Mode non gere"); return true; }
            if ("STRU".equals(mot)) { dis(arg.toUpperCase(Locale.US).startsWith("F") ? "200 Ok" : "504 Structure non geree"); return true; }
            if ("ALLO".equals(mot)) { dis("200 Ok"); return true; }
            if ("ABOR".equals(mot)) { dis("226 Rien en cours"); return true; }
            if ("PORT".equals(mot) || "EPRT".equals(mot)) { dis("502 Utilisez le mode passif"); return true; }

            if ("CWD".equals(mot) || "XCWD".equals(mot)) {
                String cible = joindre(dossier, arg);
                File f = reel(cible);
                if (f.isDirectory()) { dossier = cible; dis("250 Dossier \"" + dossier + "\""); }
                else dis("550 Dossier introuvable");
                return true;
            }
            if ("CDUP".equals(mot) || "XCUP".equals(mot)) { dossier = joindre(dossier, ".."); dis("250 Dossier \"" + dossier + "\""); return true; }

            if ("PASV".equals(mot) || "EPSV".equals(mot)) {
                try { if (passif != null) passif.close(); } catch (Exception ignore) { }
                passif = new ServerSocket();
                passif.setReuseAddress(true);
                passif.bind(new InetSocketAddress(ctrl.getLocalAddress(), 0), 1);
                passif.setSoTimeout(60000);
                int p = passif.getLocalPort();
                if ("EPSV".equals(mot)) {
                    dis("229 Mode passif etendu (|||" + p + "|)");
                } else {
                    String ip = ctrl.getLocalAddress().getHostAddress();
                    if (ip.indexOf(':') >= 0) { dis("522 IPv6 : utilisez EPSV"); return true; }
                    dis("227 Mode passif (" + ip.replace('.', ',') + "," + (p >> 8) + "," + (p & 255) + ")");
                }
                return true;
            }

            if ("REST".equals(mot)) {
                try { reprise = Math.max(0L, Long.parseLong(arg.trim())); dis("350 Reprise a " + reprise); }
                catch (NumberFormatException e) { reprise = 0; dis("501 Position incorrecte"); }
                return true;
            }

            if ("SIZE".equals(mot)) {
                File f = reel(joindre(dossier, arg));
                if (f.isFile()) dis("213 " + f.length()); else dis("550 Fichier introuvable");
                return true;
            }
            if ("MDTM".equals(mot)) {
                File f = reel(joindre(dossier, arg));
                if (f.exists()) dis("213 " + horodate(f.lastModified())); else dis("550 Fichier introuvable");
                return true;
            }
            if ("MLST".equals(mot)) {
                File f = reel(joindre(dossier, arg));
                if (!f.exists()) { dis("550 Introuvable"); return true; }
                dis("250-Fiche");
                dis(" " + ligneMlsd(f));
                dis("250 Fin");
                return true;
            }

            if ("LIST".equals(mot) || "NLST".equals(mot) || "MLSD".equals(mot)) {
                String chemin = arg;
                while (chemin.startsWith("-")) {               // les clients envoient parfois « LIST -al »
                    int e = chemin.indexOf(' ');
                    chemin = e < 0 ? "" : chemin.substring(e + 1).trim();
                }
                File d = reel(joindre(dossier, chemin));
                if (!d.exists()) { dis("550 Dossier introuvable"); return true; }
                File[] liste = d.isDirectory() ? d.listFiles() : new File[]{d};
                if (liste == null) liste = new File[0];
                StringBuilder b = new StringBuilder();
                for (File f : liste) {
                    if ("NLST".equals(mot)) b.append(f.getName());
                    else if ("MLSD".equals(mot)) b.append(ligneMlsd(f));
                    else b.append(ligneList(f));
                    b.append("\r\n");
                }
                envoyerDonnees(b.toString().getBytes(UTF8), "Liste de " + d.getName());
                return true;
            }

            if ("RETR".equals(mot)) {
                File f = reel(joindre(dossier, arg));
                if (!f.isFile()) { dis("550 Fichier introuvable"); return true; }
                lireFichier(f);
                return true;
            }

            if ("STOR".equals(mot) || "APPE".equals(mot) || "STOU".equals(mot)) {
                if (!ecriture) { dis("550 Envoi interdit : le partage est en lecture seule"); return true; }
                String nom = arg;
                if ("STOU".equals(mot) || nom.isEmpty()) nom = "fichier-" + System.currentTimeMillis();
                File f = reel(joindre(dossier, nom));
                ecrireFichier(f, "APPE".equals(mot));
                return true;
            }

            if ("DELE".equals(mot)) {
                if (!ecriture) { dis("550 Suppression interdite"); return true; }
                File f = reel(joindre(dossier, arg));
                if (f.isFile() && f.delete()) { note("Supprime : " + f.getName()); dis("250 Supprime"); }
                else dis("550 Suppression impossible");
                return true;
            }
            if ("MKD".equals(mot) || "XMKD".equals(mot)) {
                if (!ecriture) { dis("550 Creation interdite"); return true; }
                String cible = joindre(dossier, arg);
                File f = reel(cible);
                if (f.mkdirs()) dis("257 \"" + cible + "\" cree"); else dis("550 Creation impossible");
                return true;
            }
            if ("RMD".equals(mot) || "XRMD".equals(mot)) {
                if (!ecriture) { dis("550 Suppression interdite"); return true; }
                File f = reel(joindre(dossier, arg));
                if (f.isDirectory() && f.delete()) dis("250 Supprime"); else dis("550 Suppression impossible");
                return true;
            }
            if ("RNFR".equals(mot)) {
                File f = reel(joindre(dossier, arg));
                if (!f.exists()) { dis("550 Introuvable"); return true; }
                renommeDe = joindre(dossier, arg);
                dis("350 Nouveau nom ?");
                return true;
            }
            if ("RNTO".equals(mot)) {
                if (renommeDe == null) { dis("503 RNFR d'abord"); return true; }
                File de = reel(renommeDe), vers = reel(joindre(dossier, arg));
                renommeDe = null;
                if (de.renameTo(vers)) dis("250 Renomme"); else dis("550 Renommage impossible");
                return true;
            }

            dis("502 Commande inconnue");
            return true;
        }

        /** Récupère la connexion de données ouverte par PASV. */
        private Socket donnees() throws IOException {
            if (passif == null) throw new IOException("pas de mode passif");
            Socket s = passif.accept();
            s.setSoTimeout(300000);
            try { passif.close(); } catch (Exception ignore) { }
            passif = null;
            return s;
        }

        private void envoyerDonnees(byte[] contenu, String quoi) throws IOException {
            if (passif == null) { dis("425 Ouvrez d'abord le mode passif"); return; }
            dis("150 " + quoi);
            Socket s = null;
            try {
                s = donnees();
                OutputStream o = s.getOutputStream();
                o.write(contenu);
                o.flush();
            } catch (IOException e) {
                dis("426 Transfert interrompu");
                return;
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignore) { }
            }
            dis("226 Termine");
        }

        private void lireFichier(File f) throws IOException {
            if (passif == null) { dis("425 Ouvrez d'abord le mode passif"); return; }
            long depart = reprise; reprise = 0;
            dis("150 Envoi de " + f.getName() + " (" + f.length() + " octets)");
            Socket s = null;
            RandomAccessFile r = null;
            try {
                s = donnees();
                r = new RandomAccessFile(f, "r");
                if (depart > 0 && depart < f.length()) r.seek(depart);
                OutputStream o = new BufferedOutputStream(s.getOutputStream(), 32768);
                byte[] buf = new byte[32768];
                int n;
                while ((n = r.read(buf)) > 0) o.write(buf, 0, n);
                o.flush();
            } catch (IOException e) {
                dis("426 Transfert interrompu");
                return;
            } finally {
                if (r != null) try { r.close(); } catch (Exception ignore) { }
                if (s != null) try { s.close(); } catch (Exception ignore) { }
            }
            envoyes++;
            note("Telecharge : " + f.getName());
            dis("226 Termine");
        }

        private void ecrireFichier(File f, boolean ajout) throws IOException {
            if (passif == null) { dis("425 Ouvrez d'abord le mode passif"); return; }
            long depart = reprise; reprise = 0;
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            dis("150 Reception de " + f.getName());
            Socket s = null;
            OutputStream o = null;
            boolean bon = false;
            try {
                s = donnees();
                if (ajout || depart > 0) {
                    RandomAccessFile r = new RandomAccessFile(f, "rw");
                    r.seek(ajout ? r.length() : Math.min(depart, r.length()));
                    InputStream i = s.getInputStream();
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = i.read(buf)) > 0) r.write(buf, 0, n);
                    r.close();
                } else {
                    o = new BufferedOutputStream(new FileOutputStream(f), 32768);
                    InputStream i = s.getInputStream();
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
                    o.flush();
                }
                bon = true;
            } catch (IOException e) {
                dis("426 Transfert interrompu");
            } finally {
                if (o != null) try { o.close(); } catch (Exception ignore) { }
                if (s != null) try { s.close(); } catch (Exception ignore) { }
            }
            if (!bon) return;
            recus++;
            note("Recu : " + f.getName() + " (" + f.length() + " octets)");
            dis("226 Termine");
        }
    }

    // ---------- lignes de listage ----------

    private static final String[] MOIS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static String ligneList(File f) {
        SimpleDateFormat recent = new SimpleDateFormat("dd HH:mm", Locale.US);
        SimpleDateFormat vieux = new SimpleDateFormat("dd  yyyy", Locale.US);
        Date d = new Date(f.lastModified());
        boolean moinsDeSixMois = System.currentTimeMillis() - f.lastModified() < 15552000000L;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(d);
        String date = MOIS[cal.get(java.util.Calendar.MONTH)] + " " + (moinsDeSixMois ? recent.format(d) : vieux.format(d));
        String droits = f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--";
        return String.format(Locale.US, "%s 1 ftp ftp %13d %s %s", droits, f.isDirectory() ? 0 : f.length(), date, f.getName());
    }

    private static String ligneMlsd(File f) {
        return "type=" + (f.isDirectory() ? "dir" : "file")
                + ";size=" + (f.isDirectory() ? 0 : f.length())
                + ";modify=" + horodate(f.lastModified())
                + "; " + f.getName();
    }

    private static String horodate(long ms) {
        SimpleDateFormat s = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        s.setTimeZone(TimeZone.getTimeZone("UTC"));
        return s.format(new Date(ms));
    }

    // ---------- essai depuis un poste de travail ----------

    /** java fr.utillage.caisseoutils.ServeurFtp &lt;dossier&gt; [port] [utilisateur] [motdepasse] */
    public static void main(String[] args) throws Exception {
        File d = new File(args.length > 0 ? args[0] : ".");
        int p = args.length > 1 ? Integer.parseInt(args[1]) : 2121;
        String u = args.length > 2 ? args[2] : "";
        String m = args.length > 3 ? args[3] : "";
        ServeurFtp s = new ServeurFtp(d, p, u, m, true, new Journal() {
            public void ligne(String t) { System.out.println("[ftp] " + t); }
        });
        s.demarrer();
        System.out.println("Pret sur " + s.port() + ", dossier " + d.getAbsolutePath());
        Thread.sleep(Long.MAX_VALUE);
    }
}
