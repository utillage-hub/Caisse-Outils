package fr.utillage.caisseoutils;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
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

        @JavascriptInterface
        public String version() { return "android"; }
    }
}
