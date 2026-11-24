package com.kgranados.urano;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Alertas al grupo de Telegram. enviar() encola (persistente) y no bloquea; un hilo
 * propio entrega en orden con reintentos (ver ColaAlertas / TelegramTransporte).
 */
public class Notificador {

    private static final String TAG = "UranoAlertas";

    private final boolean habilitado;
    private final String prefijo;
    private final ColaAlertas cola;
    private final ColaAlertas.Transporte transporte;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> futuro;
    private long proximoIntento = Long.MAX_VALUE;

    public Notificador(Context ctx, Config cfg) {
        ColaAlertas.Registro reg = new ColaAlertas.Registro() {
            public void log(String msg) { Log.w(TAG, msg); }
        };
        habilitado = cfg.alertas && !cfg.telegramBotToken.isEmpty() && !cfg.telegramChatId.isEmpty();
        prefijo = cfg.autoDryRun ? "🧪 [PRUEBA] " : "";
        cola = new ColaAlertas(new File(ctx.getFilesDir(), "alertas.cola"), 200, reg);
        transporte = new TelegramTransporte(cfg.telegramBotToken, cfg.telegramChatId, reg);

        if (!habilitado) {
            Log.i(TAG, "Alertas desactivadas" + (cfg.alertas ? " (falta telegramBotToken o telegramChatId)" : ""));
        } else {
            Log.i(TAG, "Alertas por Telegram activas" + (cfg.autoDryRun ? " [PRUEBA]" : "")
                    + (cola.tamano() > 0 ? "; " + cola.tamano() + " pendientes de antes" : ""));
            if (cola.tamano() > 0) programar(5000);
        }
    }

    /** Encola una alerta (con la hora actual) y dispara un intento de entrega. No bloquea. */
    public void enviar(String texto) {
        if (!habilitado) return;
        cola.agregar(System.currentTimeMillis(), prefijo + texto);
        programar(0);
    }

    public void detener() {
        exec.shutdownNow();
    }

    /** Un solo intento programado: si ya hay uno antes, no se agrega otro. */
    private synchronized void programar(long esperaMs) {
        long cuando = System.currentTimeMillis() + esperaMs;
        if (futuro != null && !futuro.isDone() && proximoIntento <= cuando) return;
        if (futuro != null) futuro.cancel(false);
        proximoIntento = cuando;
        futuro = exec.schedule(new Runnable() {
            public void run() { drenar(); }
        }, esperaMs, TimeUnit.MILLISECONDS);
    }

    private void drenar() {
        long espera;
        try {
            espera = cola.drenar(transporte, System.currentTimeMillis(), TimeZone.getDefault());
        } catch (Throwable t) {
            Log.e(TAG, "Fallo entregando alertas", t);
            espera = 60 * 1000L;
        }
        synchronized (this) {
            proximoIntento = Long.MAX_VALUE;
            futuro = null;
        }
        // si llego una alerta mientras este drenado terminaba, programar() la vio "en curso"
        // y no agendo nada: revisar la cola para no dejarla esperando
        if (espera == 0 && cola.tamano() > 0) espera = 1;
        if (espera > 0) programar(espera);
    }
}
