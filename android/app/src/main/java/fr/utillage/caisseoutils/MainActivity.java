package fr.utillage.caisseoutils;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognizerIntent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Coque Android de la Caisse à Outils : une WebView plein écran qui charge l'application
 * embarquée dans l'APK (assets/www/index.html). Aucune donnée ne sort de l'appareil.
 */
public class MainActivity extends Activity {
    private static final String ACCUEIL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int DEMANDE_ECOUTE = 41;
    private static final int DEMANDE_FICHIERS = 42;
    private static final int DEMANDE_STOCKAGE = 43;
    private WebView vue;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        vue = new WebView(this);
        setContentView(vue);

        // Les fichiers de l'APK sont servis en https local : le presse-papiers et le stockage fonctionnent comme sur un site
        final WebViewAssetLoader fichiers = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings r = vue.getSettings();
        r.setJavaScriptEnabled(true);
        r.setDomStorageEnabled(true);
        r.setDatabaseEnabled(true);
        r.setTextZoom(100);
        r.setMediaPlaybackRequiresUserGesture(false);
        r.setSupportZoom(false);

        vue.addJavascriptInterface(new Passerelle(), "NB");
        vue.setWebChromeClient(new WebChromeClient());
        vue.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest demande) {
                Uri u = demande.getUrl();
                // three.js (vues 3D) est embarqué dans l'APK : la 3D marche hors connexion
                if (u.toString().endsWith("/three.min.js")) {
                    try {
                        return new WebResourceResponse("application/javascript", "utf-8", getAssets().open("www/three.min.js"));
                    } catch (IOException e) { /* pas embarqué : on laisse le réseau répondre */ }
                }
                return fichiers.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest demande) {
                Uri u = demande.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                // Les liens de sources (normes, fabricants…) s'ouvrent dans le navigateur du téléphone
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* aucun navigateur */ }
                return true;
            }
        });

        if (etat != null) vue.restoreState(etat); else vue.loadUrl(ACCUEIL);
    }

    /** L'appli passe en arrière-plan : la page note l'heure, pour savoir au retour combien de temps on est parti. */
    @Override
    protected void onPause() {
        super.onPause();
        vue.evaluateJavascript("window.CaisseOutils&&CaisseOutils.pause&&CaisseOutils.pause()", null);
    }

    /** Retour au premier plan : après une longue absence, la page revient sur l'outil de base. */
    @Override
    protected void onResume() {
        super.onResume();
        vue.evaluateJavascript("window.CaisseOutils&&CaisseOutils.reprise&&CaisseOutils.reprise()", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle etat) {
        super.onSaveInstanceState(etat);
        vue.saveState(etat);
    }

    /** Retour : ferme d'abord ce qui est ouvert dans l'appli (fiche, menu, clavier), sinon quitte. */
    @Override
    public void onBackPressed() {
        vue.evaluateJavascript("(function(){try{return window.CaisseOutils&&CaisseOutils.retour?CaisseOutils.retour():false}catch(e){return false}})()", res -> {
            if (!"true".equals(res)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onActivityResult(int demande, int resultat, Intent donnees) {
        super.onActivityResult(demande, resultat, donnees);
        if (demande == DEMANDE_FICHIERS) {
            int copies = 0;
            if (resultat == RESULT_OK && donnees != null) {
                List<Uri> uris = new ArrayList<>();
                ClipData c = donnees.getClipData();
                if (c != null) for (int i = 0; i < c.getItemCount(); i++) uris.add(c.getItemAt(i).getUri());
                else if (donnees.getData() != null) uris.add(donnees.getData());
                for (Uri u : uris) if (copierDansPartage(u)) copies++;
            }
            vue.evaluateJavascript("window.__ftpAjout&&window.__ftpAjout(" + copies + ")", null);
            return;
        }
        if (demande != DEMANDE_ECOUTE) return;
        String texte = "";
        if (resultat == RESULT_OK && donnees != null) {
            ArrayList<String> phrases = donnees.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (phrases != null && !phrases.isEmpty()) texte = phrases.get(0);
        }
        vue.evaluateJavascript("window.__nbEcoute&&window.__nbEcoute(" + JSONObject.quote(texte) + ")", null);
    }

    /** Dossier partagé : les Téléchargements quand on y a droit, sinon le dossier privé de l'appli. */
    private File dossierPartage() {
        File d = null;
        try {
            if (Build.VERSION.SDK_INT <= 28) {
                File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Caisse a Outils");
                if ((pub.exists() || pub.mkdirs()) && pub.canWrite()) d = pub;
            }
        } catch (Exception ignore) { }
        if (d == null) d = new File(getExternalFilesDir(null), "Transfert");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Sur Android 6 à 9, écrire dans les Téléchargements demande une autorisation. */
    private void demanderStockage() {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 28) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, DEMANDE_STOCKAGE);
        } catch (Exception ignore) { }
    }

    /** Recopie un fichier choisi par l'utilisateur dans le dossier partagé. */
    private boolean copierDansPartage(Uri u) {
        InputStream i = null;
        OutputStream o = null;
        try {
            String nom = nomDeUri(u);
            File cible = new File(dossierPartage(), nom);
            for (int k = 1; cible.exists() && k < 500; k++) {
                int pt = nom.lastIndexOf('.');
                cible = new File(dossierPartage(), pt > 0 ? nom.substring(0, pt) + " (" + k + ")" + nom.substring(pt) : nom + " (" + k + ")");
            }
            i = getContentResolver().openInputStream(u);
            if (i == null) return false;
            o = new FileOutputStream(cible);
            byte[] buf = new byte[32768];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (i != null) i.close(); } catch (Exception ignore) { }
            try { if (o != null) o.close(); } catch (Exception ignore) { }
        }
    }

    private String nomDeUri(Uri u) {
        String nom = null;
        try {
            android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                int k = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (k >= 0 && c.moveToFirst()) nom = c.getString(k);
                c.close();
            }
        } catch (Exception ignore) { }
        if (nom == null || nom.trim().isEmpty()) {
            nom = u.getLastPathSegment();
            if (nom != null) { int b = nom.lastIndexOf('/'); if (b >= 0) nom = nom.substring(b + 1); }
        }
        if (nom == null || nom.trim().isEmpty()) nom = "fichier-" + System.currentTimeMillis();
        return nom.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Renvoie un résultat au mode « Envoyer » de la page. */
    private void versPageFtp(JSONObject o) {
        final String js = "window.__ftpc&&window.__ftpc(" + o.toString() + ")";
        runOnUiThread(new Runnable() { public void run() { vue.evaluateJavascript(js, null); } });
    }

    private static JSONObject jsonErreur(String action, Exception e) {
        JSONObject o = new JSONObject();
        try {
            o.put("action", action);
            o.put("ok", false);
            String m = e.getMessage();
            o.put("erreur", m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m);
        } catch (Exception ignore) { }
        return o;
    }

    /** Fonctions du téléphone offertes à la page (window.NB). */
    private class Passerelle {
        /** Partage un texte (notes de débogage…) vers une autre appli : mail, messagerie, Drive… */
        @JavascriptInterface
        public void share(String nom, String texte) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, nom);
            i.putExtra(Intent.EXTRA_TEXT, texte);
            startActivity(Intent.createChooser(i, "Envoyer"));
        }

        /** Dictée avec la reconnaissance vocale du téléphone ; la réponse revient par window.__nbEcoute(texte). */
        @JavascriptInterface
        public void listen() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
                try {
                    startActivityForResult(i, DEMANDE_ECOUTE);
                } catch (Exception e) {
                    vue.evaluateJavascript("window.__nbEcoute&&window.__nbEcoute('')", null);
                }
            });
        }

        /**
         * Garde l'écran allumé tant que l'appli est au premier plan. Dès qu'on la quitte, Android reprend
         * sa mise en veille et son verrouillage habituels : le code est redemandé comme d'ordinaire.
         */
        @JavascriptInterface
        public void keepAwake(boolean actif) {
            runOnUiThread(() -> {
                if (actif) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /**
         * Retour tactile. La page passe par ici plutôt que par navigator.vibrate.
         * On commande le vibreur directement, avec un usage « média » : le « tic » du système
         * (performHapticFeedback) reste muet dès que la vibration au toucher est coupée dans les
         * réglages du téléphone, alors que l'utilisateur a choisi ce retour dans l'appli.
         * Les durées arrivent en millisecondes, séparées par des virgules : « 7 » ou « 14,40,14 »
         * (impulsion, pause, impulsion…), comme navigator.vibrate.
         */
        @JavascriptInterface
        public void vibrer(String suite) {
            final long[] motif = enMotif(suite);
            if (motif == null) return;
            runOnUiThread(() -> {
                Vibrator v = vibreur();
                if (v == null || !v.hasVibrator()) { ticSysteme(); return; }
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        v.vibrate(effet(v, motif), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA));
                    } else if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(effet(v, motif), usageAudio());
                    } else if (motif.length == 1) {
                        v.vibrate(duree(motif[0], false), usageAudio());
                    } else {
                        long[] t = avecAttente(motif);
                        for (int i = 1; i < t.length; i += 2) t[i] = duree(t[i], false);
                        v.vibrate(t, -1, usageAudio());
                    }
                } catch (Exception e) {
                    ticSysteme();
                }
            });
        }

        /** Dernier recours : le tic du système, en ignorant le réglage global quand Android le permet encore. */
        @SuppressWarnings("deprecation")
        private void ticSysteme() {
            try {
                vue.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception e) { /* rien à faire de plus */ }
        }

        private AudioAttributes usageAudio() {
            return new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
        }

        /**
         * Durée réellement envoyée au moteur : en dessous d'une dizaine de millisecondes,
         * la plupart des vibreurs n'ont pas le temps de démarrer et on ne sent rien.
         */
        private long duree(long ms, boolean amplitudeReglable) {
            if (!amplitudeReglable) return ms >= 40 ? ms : ms + 10;
            if (ms <= 5) return 10;
            if (ms <= 9) return 14;
            if (ms <= 14) return 18;
            return ms;
        }

        @TargetApi(26)
        private VibrationEffect effet(Vibrator v, long[] motif) {
            boolean amp = v.hasAmplitudeControl();
            if (motif.length == 1) {
                long ms = motif[0];
                // effets prédéfinis, réglés par le fabricant pour son moteur : seulement là où
                // l'usage peut être déclaré, sinon Android les rattache au réglage « toucher »
                if (Build.VERSION.SDK_INT >= 33 && ms <= 14) {
                    return VibrationEffect.createPredefined(ms <= 5 ? VibrationEffect.EFFECT_TICK
                            : ms <= 9 ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_HEAVY_CLICK);
                }
                int force = !amp ? VibrationEffect.DEFAULT_AMPLITUDE : ms <= 5 ? 110 : ms <= 9 ? 170 : 255;
                return VibrationEffect.createOneShot(duree(ms, amp), force);
            }
            long[] t = new long[motif.length + 1];
            int[] a = new int[motif.length + 1];
            for (int i = 0; i < motif.length; i++) {
                boolean impulsion = i % 2 == 0;
                t[i + 1] = impulsion ? duree(motif[i], amp) : motif[i];
                a[i + 1] = impulsion ? 255 : 0;
            }
            return VibrationEffect.createWaveform(t, a, -1);
        }

        /** La page demande si le retour tactile est possible, pour afficher ou non le réglage. */
        @JavascriptInterface
        public boolean peutVibrer() {
            Vibrator v = vibreur();
            return v != null && v.hasVibrator();
        }

        /** Ce que l'appareil déclare, pour l'afficher à côté du réglage et comprendre un vibreur muet. */
        @JavascriptInterface
        public String infoVibreur() {
            JSONObject o = new JSONObject();
            try {
                Vibrator v = vibreur();
                boolean present = v != null && v.hasVibrator();
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("android", Build.VERSION.RELEASE);
                o.put("modele", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("vibreur", present);
                o.put("amplitude", present && Build.VERSION.SDK_INT >= 26 && v.hasAmplitudeControl());
            } catch (Exception e) { /* on renvoie ce qu'on a */ }
            return o.toString();
        }

        // ---------- couleur du bandeau haut ----------

        /**
         * La barre d'état d'Android ne suit pas la balise theme-color d'une page web :
         * il faut la peindre depuis Java à chaque changement d'accent.
         */
        @JavascriptInterface
        public void couleurBandeau(final String hex) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        int c = Color.parseColor(hex.trim().startsWith("#") ? hex.trim() : "#" + hex.trim());
                        Window f = getWindow();
                        if (Build.VERSION.SDK_INT >= 21) {
                            f.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                            f.setStatusBarColor(c);
                        }
                        // icônes de la barre en sombre quand le fond est clair, sinon illisibles
                        if (Build.VERSION.SDK_INT >= 23) {
                            double lum = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                            View d = f.getDecorView();
                            int v = d.getSystemUiVisibility();
                            d.setSystemUiVisibility(lum > 0.6 ? (v | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                                                              : (v & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
                        }
                    } catch (Exception ignore) { }
                }
            });
        }

        // ---------- transfert de fichiers ----------

        @JavascriptInterface
        public boolean ftpDispo() { return true; }

        /** Ouvre le partage. Reçoit {port, utilisateur, motDePasse, ecriture}. */
        @JavascriptInterface
        public String ftpDemarrer(String config) {
            JSONObject o = new JSONObject();
            try {
                JSONObject c = new JSONObject(config == null || config.isEmpty() ? "{}" : config);
                int port = c.optInt("port", 2121);
                if (port < 1024 || port > 65535) port = 2121;
                demanderStockage();
                ServiceFtp.ouvrir(MainActivity.this, dossierPartage(), port,
                        c.optString("utilisateur", ""), c.optString("motDePasse", ""), c.optBoolean("ecriture", true));
                o.put("ok", true);
            } catch (Exception e) {
                try {
                    o.put("ok", false);
                    String m = e.getMessage();
                    o.put("erreur", m != null && m.toLowerCase().contains("use") ? "Ce port est déjà pris : essayez-en un autre." : (m == null ? "Démarrage impossible" : m));
                } catch (Exception ignore) { }
            }
            return fusion(o);
        }

        @JavascriptInterface
        public String ftpArreter() {
            ServiceFtp.fermer(MainActivity.this);
            return ftpEtat();
        }

        /** État complet du partage, pour rafraîchir l'écran. */
        @JavascriptInterface
        public String ftpEtat() { return fusion(new JSONObject()); }

        private String fusion(JSONObject o) {
            try {
                ServeurFtp s = ServiceFtp.serveur();
                boolean actif = ServiceFtp.actif();
                o.put("actif", actif);
                o.put("port", actif ? s.port() : 0);
                o.put("clients", actif ? s.clients() : 0);
                o.put("recus", actif ? s.recus() : 0);
                o.put("envoyes", actif ? s.envoyes() : 0);
                File d = dossierPartage();
                o.put("dossier", d.getAbsolutePath());
                o.put("prive", d.getAbsolutePath().contains("/Android/data/"));
                JSONArray ip = new JSONArray();
                for (String a : ServiceFtp.adresses()) ip.put(a);
                o.put("adresses", ip);
                JSONArray j = new JSONArray();
                for (String l : ServiceFtp.journal()) j.put(l);
                o.put("journal", j);
                o.put("fichiers", listeFichiers(d));
            } catch (Exception ignore) { }
            return o.toString();
        }

        private JSONArray listeFichiers(File d) {
            JSONArray a = new JSONArray();
            File[] l = d.listFiles();
            if (l == null) return a;
            java.util.Arrays.sort(l, new java.util.Comparator<File>() {
                public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
            });
            for (File f : l) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("nom", f.getName());
                    o.put("taille", f.isDirectory() ? -1 : f.length());
                    o.put("date", f.lastModified());
                    a.put(o);
                } catch (Exception ignore) { }
            }
            return a;
        }

        /** Ouvre un fichier reçu avec l'application du téléphone qui sait le lire. */
        @JavascriptInterface
        public boolean ftpOuvrir(String nom) {
            try {
                File f = new File(dossierPartage(), nom);
                if (!f.isFile()) return false;
                Uri u = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(u, typeDe(nom));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(i, "Ouvrir " + nom).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) { return false; }
        }

        /** Envoie un fichier reçu vers une autre application (mail, messagerie, Drive…). */
        @JavascriptInterface
        public boolean ftpPartager(String nom) {
            try {
                File f = new File(dossierPartage(), nom);
                if (!f.isFile()) return false;
                Uri u = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(typeDe(nom));
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, nom).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean ftpSupprimer(String nom) {
            try {
                File f = new File(dossierPartage(), nom);
                return f.isFile() && f.delete();
            } catch (Exception e) { return false; }
        }

        /** Ouvre le sélecteur du téléphone pour déposer des fichiers dans le dossier partagé. */
        @JavascriptInterface
        public void ftpAjouter() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
                        i.setType("*/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(Intent.createChooser(i, "Choisir des fichiers"), DEMANDE_FICHIERS);
                    } catch (Exception e) {
                        vue.evaluateJavascript("window.__ftpAjout&&window.__ftpAjout(-1)", null);
                    }
                }
            });
        }

        // --- mode « Envoyer » : la Caisse à Outils dépose sur un serveur FTP ---

        /** Se connecte et liste le dossier distant. Réponse par window.__ftpc. */
        @JavascriptInterface
        public void ftpcLister(final String config) {
            new Thread(new Runnable() {
                public void run() {
                    ClientFtp c = new ClientFtp();
                    try {
                        JSONObject q = new JSONObject(config);
                        c.connecter(q.optString("hote"), q.optInt("port", 21), q.optString("utilisateur"), q.optString("motDePasse"));
                        String chemin = q.optString("chemin", "");
                        if (!chemin.isEmpty()) c.aller(chemin);
                        JSONArray a = new JSONArray();
                        for (ClientFtp.Entree e : c.lister()) {
                            JSONObject o = new JSONObject();
                            o.put("nom", e.nom);
                            o.put("dossier", e.dossier);
                            o.put("taille", e.taille);
                            a.put(o);
                        }
                        JSONObject r = new JSONObject();
                        r.put("action", "liste");
                        r.put("ok", true);
                        r.put("dossier", c.dossier());
                        r.put("entrees", a);
                        versPageFtp(r);
                    } catch (Exception e) {
                        versPageFtp(jsonErreur("liste", e));
                    } finally {
                        c.fermer();
                    }
                }
            }).start();
        }

        /** Envoie les fichiers nommés (pris dans le dossier partagé) vers le serveur distant. */
        @JavascriptInterface
        public void ftpcEnvoyer(final String config) {
            new Thread(new Runnable() {
                public void run() {
                    ClientFtp c = new ClientFtp();
                    try {
                        JSONObject q = new JSONObject(config);
                        c.connecter(q.optString("hote"), q.optInt("port", 21), q.optString("utilisateur"), q.optString("motDePasse"));
                        String chemin = q.optString("chemin", "");
                        if (!chemin.isEmpty()) c.aller(chemin);
                        JSONArray noms = q.optJSONArray("fichiers");
                        int total = noms == null ? 0 : noms.length(), faits = 0;
                        for (int i = 0; i < total; i++) {
                            String nom = noms.getString(i);
                            File f = new File(dossierPartage(), nom);
                            if (!f.isFile()) continue;
                            JSONObject p = new JSONObject();
                            p.put("action", "progres");
                            p.put("nom", nom);
                            p.put("fait", faits);
                            p.put("total", total);
                            versPageFtp(p);
                            c.envoyerFichier(f, nom);
                            faits++;
                        }
                        JSONObject r = new JSONObject();
                        r.put("action", "fini");
                        r.put("ok", true);
                        r.put("fait", faits);
                        r.put("total", total);
                        versPageFtp(r);
                    } catch (Exception e) {
                        versPageFtp(jsonErreur("fini", e));
                    } finally {
                        c.fermer();
                    }
                }
            }).start();
        }

        private String typeDe(String nom) {
            String n = nom.toLowerCase(java.util.Locale.US);
            int p = n.lastIndexOf('.');
            String ext = p < 0 ? "" : n.substring(p + 1);
            String t = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return t == null ? "*/*" : t;
        }

        @JavascriptInterface
        public String version() { return "android"; }

        private Vibrator vibreur() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibratorManager m = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                    return m == null ? null : m.getDefaultVibrator();
                }
                return (Vibrator) getSystemService(VIBRATOR_SERVICE);
            } catch (Exception e) {
                return null;
            }
        }

        /** « 14,40,14 » devient {14, 40, 14}. Renvoie null si rien d'exploitable. */
        private long[] enMotif(String suite) {
            if (suite == null) return null;
            String[] bouts = suite.split(",");
            ArrayList<Long> out = new ArrayList<>();
            for (String b : bouts) {
                try {
                    long ms = Long.parseLong(b.trim());
                    if (ms >= 0 && ms <= 2000) out.add(ms);
                } catch (NumberFormatException e) { /* on ignore ce bout */ }
            }
            if (out.isEmpty() || out.get(0) == 0) return null;
            long[] t = new long[out.size()];
            for (int i = 0; i < t.length; i++) t[i] = out.get(i);
            return t;
        }

        /** vibrate() attend un délai d'attente en tête : on ajoute un 0. */
        private long[] avecAttente(long[] motif) {
            long[] t = new long[motif.length + 1];
            t[0] = 0;
            System.arraycopy(motif, 0, t, 1, motif.length);
            return t;
        }
    }
}
