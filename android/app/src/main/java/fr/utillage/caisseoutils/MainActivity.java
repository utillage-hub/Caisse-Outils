package fr.utillage.caisseoutils;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognizerIntent;
import android.view.HapticFeedbackConstants;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Coque Android de la Caisse à Outils : une WebView plein écran qui charge l'application
 * embarquée dans l'APK (assets/www/index.html). Aucune donnée ne sort de l'appareil.
 */
public class MainActivity extends Activity {
    private static final String ACCUEIL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int DEMANDE_ECOUTE = 41;
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
        if (demande != DEMANDE_ECOUTE) return;
        String texte = "";
        if (resultat == RESULT_OK && donnees != null) {
            ArrayList<String> phrases = donnees.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (phrases != null && !phrases.isEmpty()) texte = phrases.get(0);
        }
        vue.evaluateJavascript("window.__nbEcoute&&window.__nbEcoute(" + JSONObject.quote(texte) + ")", null);
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
