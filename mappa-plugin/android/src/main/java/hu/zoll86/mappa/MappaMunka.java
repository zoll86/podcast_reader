package hu.zoll86.mappa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * v90: ELŐTÉR-SZOLGÁLTATÁS + ÉBRENTARTÁS.
 *
 * Miért kell: a sorozat-előfelismerés hosszan fut, és a felhasználó közben
 * lezárja a képernyőt. Hang nélkül (a lejátszó áll) az Android a folyamatot
 * elaltatja — a WebView időzítői és a hálózat megállnak, a felismerés
 * félbeszakad. Az előtér-szolgáltatás egy állandó értesítéssel életben
 * tartja a folyamatot, a részleges ébrenlét-zár (PARTIAL_WAKE_LOCK) pedig a
 * CPU-t — a képernyő nyugodtan alhat.
 *
 * A zár legfeljebb 6 órára szól (egy éjszakai sorozat-menet belefér), és a
 * szolgáltatás leállításakor (fgOff) elenged. Az értesítés a rendszer
 * letöltés-ikonját használja, hogy ne kelljen saját erőforrás.
 */
public class MappaMunka extends Service {
    private PowerManager.WakeLock wl;

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        String cim = (it != null) ? it.getStringExtra("cim") : null;
        if (cim == null || cim.isEmpty()) cim = "feldolgozás fut";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    "mappa_munka", "Háttérmunka", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Előfelismerés és hosszú feldolgozás a háttérben");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, "mappa_munka")
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("Kétnyelvű hallgató")
                .setContentText(cim)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else
                startForeground(9, n);
        } catch (Exception e) {
            /* értesítési jog híján is fusson tovább sima szolgáltatásként */
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (wl == null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mappa:munka");
                wl.setReferenceCounted(false);
            }
            if (!wl.isHeld()) wl.acquire(6L * 60 * 60 * 1000);
        } catch (Exception ignored) { }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
