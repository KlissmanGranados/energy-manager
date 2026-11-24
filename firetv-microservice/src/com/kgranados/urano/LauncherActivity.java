package com.kgranados.urano;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Activity minima: arranca el servicio y muestra la URL de la UI.
 * Su presencia (LEANBACK_LAUNCHER) tambien habilita la entrega de BOOT_COMPLETED
 * en Android 9 tras el primer lanzamiento.
 *
 * Se cierra sola tras CIERRE_SEG: el servicio no la necesita y, abierta, retiene
 * ~8 MB de memoria grafica en un stick con poca RAM.
 */
public class LauncherActivity extends Activity {

    private static final int CIERRE_SEG = 20;

    private final Handler handler = new Handler();
    private TextView cierreTv;
    private int restante = CIERRE_SEG;

    private final Runnable cuentaAtras = new Runnable() {
        public void run() {
            if (--restante <= 0) {
                finish();
                return;
            }
            mostrarRestante();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ControlService.startSelf(this);

        String ip = localIp();
        String url = "http://" + ip + ":" + ControlService.PORT + "/";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0b1020"));
        root.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("Urano — Controlador de energia");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("Servicio activo. Abre la interfaz en:");
        sub.setTextColor(Color.parseColor("#9fb0d0"));
        sub.setTextSize(18);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 40, 0, 10);

        TextView urlTv = new TextView(this);
        urlTv.setText(url);
        urlTv.setTextColor(Color.parseColor("#4ade80"));
        urlTv.setTextSize(24);
        urlTv.setGravity(Gravity.CENTER);

        cierreTv = new TextView(this);
        cierreTv.setTextColor(Color.parseColor("#9fb0d0"));
        cierreTv.setTextSize(14);
        cierreTv.setGravity(Gravity.CENTER);
        cierreTv.setPadding(0, 50, 0, 0);
        mostrarRestante();

        root.addView(title);
        root.addView(sub);
        root.addView(urlTv);
        root.addView(cierreTv);
        setContentView(root);

        handler.postDelayed(cuentaAtras, 1000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(cuentaAtras);
        super.onDestroy();
    }

    private void mostrarRestante() {
        cierreTv.setText("Esta pantalla se cerrara en " + restante
                + " s. El servicio sigue activo en segundo plano.");
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "<ip-del-stick>";
    }
}
