package com.kgranados.urano;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Foreground service persistente que levanta el servidor HTTP.
 * START_STICKY + notificacion persistente + watchdog => "siempre levantado".
 */
public class ControlService extends Service {

    private static final String TAG = "UranoSvc";
    private static final String CHANNEL_ID = "urano_ctl";
    private static final int NOTIF_ID = 1;
    public static final int PORT = 8080;

    private TinyHttpServer httpServer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private AutoCorteMonitor autoMonitor;
    private Notificador notificador;
    private PowerHistoryDb historial;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Servicio Urano activo"));

        if (httpServer == null) {
            try {
                Config cfg = Config.load(this);
                byte[] html = readAsset("index.html");
                byte[] dashboard = readAsset("power_dashboard.html");
                notificador = new Notificador(this, cfg);
                historial = new PowerHistoryDb(this);
                PowerController controller = new PowerController(cfg, notificador);
                autoMonitor = new AutoCorteMonitor(this, cfg, controller, notificador, historial);
                httpServer = new TinyHttpServer(PORT, controller, autoMonitor, html, dashboard,
                        historial, cfg.token);
                httpServer.start();
                acquireWakeLock();
                Log.i(TAG, "Servidor HTTP iniciado en puerto " + PORT);
                autoMonitor.iniciar();
            } catch (Exception e) {
                Log.e(TAG, "No se pudo iniciar el servidor HTTP", e);
            }
        }

        // reprogramar watchdog para re-asegurar el servicio
        Watchdog.schedule(this);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (autoMonitor != null) { autoMonitor.detener(); autoMonitor = null; }
        if (notificador != null) { notificador.detener(); notificador = null; }
        if (httpServer != null) { httpServer.stop(); httpServer = null; }
        if (historial != null) { historial.close(); historial = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        // intentar re-arrancar: el watchdog seguira vigilando
        Watchdog.schedule(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- helpers ----

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "urano:httpserver");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "No se pudo tomar wakelock", e);
        }
        // WifiLock: mantiene la radio WiFi despierta (sin power-save) para que el
        // stick siga respondiendo ARP y sea SIEMPRE alcanzable por todo el LAN,
        // aunque lleve rato inactivo (ver diagnostico: ARP FAILED a clientes dormidos).
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "urano:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "No se pudo tomar wifilock", e);
        }
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Urano", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Controlador de energia de Urano");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Urano")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setOngoing(true)
                .build();
    }

    /** Arranca el servicio de forma compatible con O+. */
    public static void startSelf(Context ctx) {
        Intent i = new Intent(ctx, ControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
