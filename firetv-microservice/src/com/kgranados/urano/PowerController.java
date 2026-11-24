package com.kgranados.urano;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nucleo del servicio: maquina de estados de energia de Urano.
 *
 * Estados: ABAJO, ENCENDIENDO, ARRIBA, APAGANDO.
 *  - ABAJO/ARRIBA  : estables, derivados de ping en vivo.
 *  - ENCENDIENDO/APAGANDO : transitorios, mientras corre una accion.
 *
 * Garantias:
 *  - Una sola accion a la vez (ExecutorService de 1 hilo + decision sincronizada).
 *  - Idempotencia: pedir la accion ya satisfecha / en curso no hace nada.
 *  - Bloqueo: pedir la accion opuesta durante una transicion -> rechazada (BLOQUEADO).
 */
public class PowerController {

    private static final String TAG = "UranoCtl";

    public enum Estado { ABAJO, ENCENDIENDO, ARRIBA, APAGANDO }

    /** Resultado de un POST a /energy. */
    public enum Resultado { OK, NOOP, BLOQUEADO, SIN_CORRIENTE, SIN_110V, ERROR }

    public static final String ORIGEN_MANUAL = "manual";
    public static final String ORIGEN_AUTO = "autom\u00e1tico por corte";

    private final Config cfg;
    private final Notificador notif;   // puede ser null (sin alertas)
    private final AtomicReference<Estado> estado = new AtomicReference<>(Estado.ABAJO);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // cache corta del ping para no lanzar un subproceso en cada poll
    private volatile long lastPingMs = 0;
    private volatile boolean lastPingUp = false;
    private static final long PING_CACHE_MS = 1500;

    // cache del chequeo de servicio electrico
    private volatile long lastElecMs = 0;
    private volatile boolean lastElecUp = false;
    private static final long ELEC_CACHE_MS = 2000;

    public PowerController(Config cfg, Notificador notif) {
        this.cfg = cfg;
        this.notif = notif;
        // estado inicial segun realidad
        estado.set(pingNow() ? Estado.ARRIBA : Estado.ABAJO);
    }

    /** Estado actual para GET /status. Si es estable, lo refresca con ping. */
    public synchronized Estado status() {
        Estado s = estado.get();
        if (s == Estado.ENCENDIENDO || s == Estado.APAGANDO) {
            return s; // transicion en curso: manda el estado transitorio
        }
        Estado real = pingCached() ? Estado.ARRIBA : Estado.ABAJO;
        estado.set(real);
        return real;
    }

    /**
     * Maneja POST /energy. Decide de forma atomica y encola la transicion.
     * accion: "encender" | "apagar"; origen: ORIGEN_MANUAL | ORIGEN_AUTO (para las alertas).
     */
    public synchronized Resultado energy(String accion, String origen) {
        Estado s = estado.get();
        boolean encender = "encender".equalsIgnoreCase(accion);
        boolean apagar = "apagar".equalsIgnoreCase(accion);
        if (!encender && !apagar) return Resultado.ERROR;

        switch (s) {
            case ENCENDIENDO:
                return encender ? Resultado.NOOP : Resultado.BLOQUEADO;
            case APAGANDO:
                return apagar ? Resultado.NOOP : Resultado.BLOQUEADO;
            case ARRIBA:
            case ABAJO:
            default:
                // refrescar con ping real antes de decidir desde estado estable
                Estado real = pingCached() ? Estado.ARRIBA : Estado.ABAJO;
                estado.set(real);
                if (encender) {
                    if (real == Estado.ARRIBA) return Resultado.NOOP;
                    if (!hayServicioElectrico()) return Resultado.SIN_CORRIENTE;
                    if (!hay110v()) {
                        Log.w(TAG, "Sin 110 V (" + cfg.ip110v + " no responde): no se enciende Urano");
                        return Resultado.SIN_110V;
                    }
                    estado.set(Estado.ENCENDIENDO);
                    avisar("\u23F3 Encendiendo servidor\u2026 (" + origen + ")");
                    worker.submit(new Runnable() { public void run() { tareaEncender(); } });
                    return Resultado.OK;
                } else { // apagar
                    if (real == Estado.ABAJO) return Resultado.NOOP;
                    estado.set(Estado.APAGANDO);
                    avisar("\u23F3 Apagando servidor\u2026 (" + origen + ")");
                    worker.submit(new Runnable() { public void run() { tareaApagar(); } });
                    return Resultado.OK;
                }
        }
    }

    // ---- tareas en background (hilo unico) ----

    private void tareaEncender() {
        String fallo;
        try {
            Log.i(TAG, "ENCENDIENDO: enviando WoL a " + cfg.mac);
            WolSender.wake(cfg.mac, cfg.broadcast, cfg.wolPort);
            long deadline = System.currentTimeMillis() + cfg.encenderTimeoutSec * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (pingNow()) {
                    estado.set(Estado.ARRIBA);
                    Log.i(TAG, "Urano ARRIBA");
                    avisar("\u2705 \u00a1Servidor encendido!");
                    return;
                }
                Thread.sleep(cfg.pollStepSec * 1000L);
            }
            fallo = "no respondi\u00f3 en " + cfg.encenderTimeoutSec + " s tras el Wake-on-LAN";
        } catch (Exception e) {
            Log.e(TAG, "Error al encender", e);
            fallo = motivo(e);
        }
        // el resultado real lo decide el ping
        if (pingNow()) {
            estado.set(Estado.ARRIBA);
            avisar("\u2705 \u00a1Servidor encendido!");
        } else {
            Log.w(TAG, "No se pudo encender Urano: " + fallo);
            estado.set(Estado.ABAJO);
            avisar("\u274C No se pudo encender el servidor: " + fallo + ".");
        }
    }

    private void tareaApagar() {
        String fallo;
        try {
            Log.i(TAG, "APAGANDO: ssh shutdown a " + cfg.ip);
            SshClient.shutdown(cfg);
            long deadline = System.currentTimeMillis() + cfg.apagarTimeoutSec * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (!pingNow()) {
                    estado.set(Estado.ABAJO);
                    Log.i(TAG, "Urano ABAJO");
                    avisar("\u2B58 Servidor apagado.");
                    return;
                }
                Thread.sleep(cfg.pollStepSec * 1000L);
            }
            fallo = "sigue respondiendo " + cfg.apagarTimeoutSec + " s despu\u00e9s de la orden de apagado";
        } catch (Exception e) {
            Log.e(TAG, "Error al apagar", e);
            fallo = motivo(e);
        }
        // el resultado real lo decide el ping
        if (pingNow()) {
            Log.w(TAG, "No se pudo apagar Urano: " + fallo);
            estado.set(Estado.ARRIBA);
            avisar("\u274C No se pudo apagar el servidor: " + fallo + ".");
        } else {
            estado.set(Estado.ABAJO);
            avisar("\u2B58 Servidor apagado.");
        }
    }

    private void avisar(String texto) {
        Log.i(TAG, "alerta: " + texto.replace('\n', ' '));
        if (notif != null) notif.enviar(texto);
    }

    /** Motivo breve y legible para la alerta de fallo. */
    static String motivo(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (m.contains("Auth fail")) return "SSH rechaz\u00f3 la autenticaci\u00f3n";
        if (m.contains("Connection refused")) return "SSH no acepta conexiones";
        if (m.contains("timed out") || m.contains("timeout")) return "no responde por SSH (tiempo agotado)";
        if (m.contains("No route to host") || m.contains("unreachable")) return "no hay ruta de red hacia el servidor";
        return m.length() > 120 ? m.substring(0, 120) + "\u2026" : m;
    }

    // ---- ping helpers ----

    private boolean pingNow() {
        boolean up = Pinger.isUp(cfg.ip);
        lastPingMs = System.currentTimeMillis();
        lastPingUp = up;
        return up;
    }

    private boolean pingCached() {
        if (System.currentTimeMillis() - lastPingMs < PING_CACHE_MS) return lastPingUp;
        return pingNow();
    }

    /** true si alguna de las IPs de servicio electrico responde ping (hay luz). */
    public boolean hayServicioElectrico() {
        if (System.currentTimeMillis() - lastElecMs < ELEC_CACHE_MS) return lastElecUp;
        boolean up = false;
        for (String ip : cfg.electricIps) {
            if (ip != null && !ip.isEmpty() && Pinger.isUp(ip)) { up = true; break; }
        }
        lastElecUp = up;
        lastElecMs = System.currentTimeMillis();
        return up;
    }

    /**
     * true si responde la IP en 110 V (o no hay ninguna configurada). Solo condiciona el
     * encendido de Urano; no participa en la deteccion de luz. Dos rondas separadas 2 s
     * para que un ping perdido no bloquee el encendido.
     */
    public boolean hay110v() {
        if (cfg.ip110v.isEmpty()) return true;
        for (int ronda = 0; ronda < 2; ronda++) {
            if (ronda > 0) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            if (Pinger.isUp(cfg.ip110v)) return true;
        }
        return false;
    }

    /**
     * Muestra para la automatizacion de cortes: ignora la cache y solo declara
     * "sin luz" si ninguna IP responde en dos rondas separadas 2 s (evita que un
     * ping perdido cuente como corte). Actualiza la cache que usa la API.
     */
    public boolean hayServicioElectricoFresco() {
        boolean up = false;
        for (int ronda = 0; ronda < 2 && !up; ronda++) {
            if (ronda > 0) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            for (String ip : cfg.electricIps) {
                if (ip != null && !ip.isEmpty() && Pinger.isUp(ip)) { up = true; break; }
            }
        }
        lastElecUp = up;
        lastElecMs = System.currentTimeMillis();
        return up;
    }
}
