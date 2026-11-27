package fr.utillage.caisseoutils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;

/**
 * Garde le serveur FTP en vie tant que le partage est ouvert, même écran éteint ou
 * application en arrière-plan. Android exige pour cela un service de premier plan,
 * c'est-à-dire une notification visible : c'est aussi ce qui permet à l'utilisateur
 * de voir d'un coup d'œil que son téléphone est en train de partager un dossier.
 */
public class ServiceFtp extends Service {

    private static final String CANAL = "transfert";
    private static final int NOTIF = 4201;

    private static ServeurFtp serveur;
    private static ServeurWeb web;          // page de dépannage : c'est elle que vise le code QR
    private static File dossier;
    private static final LinkedList<String> journal = new LinkedList<String>();

    private WifiManager.WifiLock verrouWifi;

    // ---------- accès depuis l'activité ----------

    public static boolean actif() { return serveur != null && serveur.tourne(); }
    public static ServeurFtp serveur() { return serveur; }
    public static ServeurWeb web() { return web; }
    public static int portWeb() { return web != null && web.tourne() ? web.port() : 0; }
    public static File dossier() { return dossier; }

    public static List<String> journal() {
        synchronized (journal) { return new ArrayList<String>(journal); }
    }

    private static void noter(String t) {
        synchronized (journal) {
            journal.addFirst(t);
            while (journal.size() > 60) journal.removeLast();
        }
    }

    /** Ouvre le partage. Lève une IOException si le port est pris ou le dossier inaccessible. */
    public static void ouvrir(Context c, File racine, int port, String utilisateur, String mdp, boolean ecriture) throws IOException {
        fermer(c);
        synchronized (journal) { journal.clear(); }
        ServeurFtp s = new ServeurFtp(racine, port, utilisateur, mdp, ecriture, new ServeurFtp.Journal() {
            public void ligne(String t) { noter(t); }
        });
        s.demarrer();                       // échoue ici si le port est déjà pris : rien n'est démarré
        serveur = s;
        dossier = racine;
        // le partage par navigateur vit sur le port suivant ; s'il échoue, le FTP marche quand même
        try {
            ServeurWeb w = new ServeurWeb(racine, port + 1, ecriture, new ServeurFtp.Journal() {
                public void ligne(String t) { noter(t); }
            });
            w.demarrer();
            web = w;
        } catch (IOException e) {
            web = null;
            noter("Page navigateur indisponible (port " + (port + 1) + " occupé).");
        }
        Intent i = new Intent(c, ServiceFtp.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void fermer(Context c) {
        if (web != null) { web.arreter(); web = null; }
        if (serveur != null) { serveur.arreter(); serveur = null; }
        try { c.stopService(new Intent(c, ServiceFtp.class)); } catch (Exception ignore) { }
    }

    /** Adresses IPv4 de l'appareil sur le réseau local, la plus probable en premier. */
    public static List<String> adresses() {
        List<String> wifi = new ArrayList<String>(), autres = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> cartes = NetworkInterface.getNetworkInterfaces();
            while (cartes != null && cartes.hasMoreElements()) {
                NetworkInterface n = cartes.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                String nom = n.getName() == null ? "" : n.getName();
                Enumeration<InetAddress> ips = n.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress a = ips.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (nom.startsWith("wlan") || nom.startsWith("ap")) wifi.add(a.getHostAddress());
                    else autres.add(a.getHostAddress());
                }
            }
        } catch (Exception ignore) { }
        wifi.addAll(autres);
        return wifi.isEmpty() ? Collections.<String>emptyList() : wifi;
    }

    // ---------- cycle de vie du service ----------

    @Override
    public int onStartCommand(Intent intent, int drapeaux, int id) {
        demarrerNotification();
        try {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (w != null) {
                verrouWifi = w.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "caisse-outils:ftp");
                verrouWifi.setReferenceCounted(false);
                verrouWifi.acquire();
            }
        } catch (Exception ignore) { }
        return START_NOT_STICKY;      // si Android nous arrête, on ne relance pas un partage à l'insu de l'utilisateur
    }

    private void demarrerNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Transfert de fichiers", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Affiché pendant qu'un dossier est partagé sur le réseau local.");
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
        List<String> ip = adresses();
        String texte = ip.isEmpty() ? "En attente du Wi-Fi"
                : (web != null && web.tourne() ? "http://" + ip.get(0) + ":" + web.port()
                                               : "ftp://" + ip.get(0) + ":" + (serveur != null ? serveur.port() : 2121));
        PendingIntent ouvrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        b.setContentTitle("Partage de fichiers ouvert");
        b.setContentText(texte);
        b.setSmallIcon(android.R.drawable.stat_sys_upload);
        b.setOngoing(true);
        b.setContentIntent(ouvrir);
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);

        Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF, n);
        }
    }

    @Override
    public void onDestroy() {
        try { if (verrouWifi != null && verrouWifi.isHeld()) verrouWifi.release(); } catch (Exception ignore) { }
        verrouWifi = null;
        if (web != null) { web.arreter(); web = null; }
        if (serveur != null) { serveur.arreter(); serveur = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
