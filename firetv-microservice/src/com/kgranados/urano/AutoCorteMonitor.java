package com.kgranados.urano;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitor de cortes de luz: muestrea el servicio electrico cada autoCheckSeg y,
 * aplica PoliticaCorte con dos interruptores independientes: apagado automatico
 * (tras un corte largo) y encendido automatico (al volver la luz tras ese corte).
 * Ambos desactivados = control manual: no hace nada y olvida cortes/esperas en curso.
 *
 * Los interruptores se cambian desde la UI (POST /api/auto) y se guardan en
 * SharedPreferences; urano.json (autoApagar/autoEncender) solo da el valor inicial.
 * Toda la mutacion de la politica ocurre en el hilo del monitor.
 */
public class AutoCorteMonitor {

    private static final String TAG = "UranoAuto";
    private static final String PREFS = "urano_auto";
    private static final String PREF_APAGAR = "apagar";
    private static final String PREF_ENCENDER = "encender";
    private static final String PREF_ACTIVO = "activo";    // version anterior: un solo interruptor
    private static final String PREF_ENCENDER_PENDIENTE = "encenderPendiente";
    private static final String PREF_OBSOLETA = "apagadoPorCorte"; // version anterior

    private final Config cfg;
    private final PowerController controller;
    private final Notificador notif;   // puede ser null (sin alertas)
    private final PowerHistoryDb historial;  // puede ser null (sin historial)
    private final SharedPreferences prefs;
    private final PoliticaCorte politica;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean apagar;   // lo que pide la UI
    private volatile boolean encender;
    private boolean apagarAplicado;    // lo que ya aplico el hilo del monitor
    private boolean encenderAplicado;

    // flancos de luz para las alertas (anti-rebote: el cambio debe verse en 2 muestras seguidas)
    private Boolean luzConfirmada = null;  // null = aun sin referencia
    private int cuentaCambio = 0;
    private long primerCambioMs = -1;      // 1a muestra del cambio en curso (elapsedRealtime)
    private long inicioCorteEpochMs = -1;  // inicio del corte confirmado (hora de pared)
    // la politica ya cuenta la espera para encender (hay 110 V), pero el aviso de luz
    // restablecida aun no salio: se avisa junto con el, o despues, nunca antes
    private boolean aviso110vPendiente = false;

    public AutoCorteMonitor(Context ctx, Config cfg, PowerController controller, Notificador notif,
                            PowerHistoryDb historial) {
        this.cfg = cfg;
        this.controller = controller;
        this.notif = notif;
        this.historial = historial;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean legado = prefs.contains(PREF_ACTIVO);
        this.apagar = prefs.getBoolean(PREF_APAGAR, legado ? prefs.getBoolean(PREF_ACTIVO, true) : cfg.autoApagar);
        this.encender = prefs.getBoolean(PREF_ENCENDER, legado ? prefs.getBoolean(PREF_ACTIVO, true) : cfg.autoEncender);
        this.apagarAplicado = apagar;
        this.encenderAplicado = encender;
        if (legado) {
            prefs.edit().putBoolean(PREF_APAGAR, apagar).putBoolean(PREF_ENCENDER, encender)
                    .remove(PREF_ACTIVO).apply();
        }
        prefs.edit().remove(PREF_OBSOLETA).apply();
        this.politica = new PoliticaCorte(
                cfg.corteApagarSeg * 1000L, cfg.restablecerEncenderSeg * 1000L, 60 * 1000L,
                cfg.encenderReintentoSeg * 1000L, cfg.encenderReintentos,
                prefs.getBoolean(PREF_ENCENDER_PENDIENTE, false),
                new PoliticaCorte.Registro() {
                    public void log(String msg) { Log.i(TAG, msg); }
                });
        politica.configurar(apagar, encender);
        guardarEncenderPendiente();
    }

    public void iniciar() {
        exec.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    // no dejar morir el monitor por un error puntual
                    Log.e(TAG, "Fallo en el monitor de cortes", t);
                }
            }
        }, 10, cfg.autoCheckSeg, TimeUnit.SECONDS);
        Log.i(TAG, "Monitor de cortes: apagado automatico " + (apagar ? "SI" : "NO")
                + " (tras " + cfg.corteApagarSeg + "s sin luz), encendido automatico " + (encender ? "SI" : "NO")
                + " (tras " + cfg.restablecerEncenderSeg + "s de luz estable), muestreo cada " + cfg.autoCheckSeg + "s"
                + (cfg.autoDryRun ? " [DRY-RUN: no ejecuta acciones]" : "")
                + (politica.isEncenderPendiente() ? " (pendiente: encender Urano al volver la luz)" : ""));
    }

    public void detener() {
        exec.shutdownNow();
    }

    public boolean isApagar() { return apagar; }

    public boolean isEncender() { return encender; }

    public int getCorteApagarSeg() { return cfg.corteApagarSeg; }

    public int getRestablecerEncenderSeg() { return cfg.restablecerEncenderSeg; }

    /**
     * Cambia los interruptores (idempotente; null = no tocar ese). Se aplica en el
     * proximo ciclo del monitor.
     */
    public synchronized void setModo(Boolean apagarNuevo, Boolean encenderNuevo) {
        boolean ap = apagarNuevo != null ? apagarNuevo : apagar;
        boolean en = encenderNuevo != null ? encenderNuevo : encender;
        if (ap == apagar && en == encender) return;
        if (ap != apagar) Log.i(TAG, "Apagado automatico " + (ap ? "ACTIVADO" : "DESACTIVADO") + " desde la UI");
        if (en != encender) Log.i(TAG, "Encendido automatico " + (en ? "ACTIVADO" : "DESACTIVADO") + " desde la UI");
        apagar = ap;
        encender = en;
        prefs.edit().putBoolean(PREF_APAGAR, ap).putBoolean(PREF_ENCENDER, en).apply();
    }

    private void tick() {
        boolean ap = apagar, en = encender;
        boolean act = ap || en;
        if (ap != apagarAplicado || en != encenderAplicado) {
            // pasar a (o salir de) control manual: empezar de cero (desactivar = el usuario toma
            // el control); cambiar solo uno de los dos conserva el corte en curso
            if (act != (apagarAplicado || encenderAplicado)) politica.reiniciar();
            politica.configurar(ap, en);
            if (!en) aviso110vPendiente = false;
            guardarEncenderPendiente();
            apagarAplicado = ap;
            encenderAplicado = en;
        }
        // la luz se muestrea SIEMPRE: las alertas de corte/restablecimiento son informativas
        // y salen aunque la automatizacion este desactivada
        boolean luz = controller.hayServicioElectricoFresco();
        alertasDeLuz(luz, SystemClock.elapsedRealtime(), ap, en);
        if (!act) return;

        PowerController.Estado e = controller.status();
        boolean antes = politica.isEncenderPendiente();
        // el 110 V solo importa para encender: se muestrea solo si hay luz y encendido pendiente
        boolean v110 = !luz || !antes || controller.hay110v();
        PoliticaCorte.Accion a = politica.evaluar(SystemClock.elapsedRealtime(), luz, v110,
                e == PowerController.Estado.ARRIBA, e == PowerController.Estado.ABAJO);
        if (politica.consumirEsperaIniciada() && !cfg.ip110v.isEmpty()) aviso110vPendiente = true;
        if (aviso110vPendiente && Boolean.TRUE.equals(luzConfirmada)) {
            aviso110vPendiente = false;
            avisar("\u2705 Hay 110 V: el servidor se encender\u00e1 tras " + fmtSeg(cfg.restablecerEncenderSeg)
                    + " de luz estable.");
        }

        if ((a == PoliticaCorte.Accion.APAGAR && !apagar) || (a == PoliticaCorte.Accion.ENCENDER && !encender)) {
            // lo desactivaron mientras se muestreaba: no actuar
            return;
        }
        if (a == PoliticaCorte.Accion.APAGAR) {
            if (cfg.autoDryRun) {
                Log.w(TAG, "DRY-RUN: aqui se apagaria Urano");
            } else {
                PowerController.Resultado r = controller.energy("apagar", PowerController.ORIGEN_AUTO);
                Log.i(TAG, "apagar -> " + r);
            }
        } else if (a == PoliticaCorte.Accion.ENCENDER) {
            if (cfg.autoDryRun) {
                Log.w(TAG, "DRY-RUN: aqui se encenderia Urano");
            } else {
                PowerController.Resultado r = controller.energy("encender", PowerController.ORIGEN_AUTO);
                Log.i(TAG, "encender -> " + r);
                if (r != PowerController.Resultado.OK) politica.encenderRechazado();
            }
        }

        if (politica.consumirRendido()) {
            avisar("\u274C El servidor no encendi\u00f3 tras " + politica.getMaxIntentosEncender()
                    + " intentos de encendido autom\u00e1tico; se deja de intentar.");
        }
        if (politica.isEncenderPendiente() != antes) guardarEncenderPendiente();
    }

    /** Alertas de corte / restablecimiento con anti-rebote de 2 muestras. */
    private void alertasDeLuz(boolean luz, long ahora, boolean ap, boolean en) {
        if (luzConfirmada == null) {          // primera muestra: referencia, sin alerta
            luzConfirmada = luz;
            ResumenCortes.Evento ultimo = reconciliarHistorial(luz);
            // al reiniciar se pierde la referencia en memoria: el corte (o la luz estable)
            // cuenta desde la fecha guardada en el historial, no desde el arranque
            long ahoraEpoch = System.currentTimeMillis();
            long desde = ResumenCortes.inicioAlArrancar(ultimo,
                    luz ? PowerHistoryQuery.TIPO_BACK_ON : PowerHistoryQuery.TIPO_OUTAGE, ahoraEpoch);
            if (!luz) inicioCorteEpochMs = desde;
            politica.restaurar(luz, ahora - (ahoraEpoch - desde));
            return;
        }
        if (luz == luzConfirmada) {
            cuentaCambio = 0;
            return;
        }
        if (cuentaCambio == 0) primerCambioMs = ahora;
        if (++cuentaCambio < 2) return;
        cuentaCambio = 0;
        luzConfirmada = luz;
        // la hora del evento es la de la 1a muestra del cambio, igual que en la alerta
        long epochCambio = System.currentTimeMillis() - (ahora - primerCambioMs);
        registrarHistorial(luz ? PowerHistoryQuery.TIPO_BACK_ON : PowerHistoryQuery.TIPO_OUTAGE, epochCambio);
        if (!luz) {
            inicioCorteEpochMs = epochCambio;
            aviso110vPendiente = false;
            avisar("\u26A1 Corte el\u00e9ctrico detectado." + (ap
                    ? " Si dura " + fmtSeg(cfg.corteApagarSeg) + ", el servidor se apagar\u00e1 autom\u00e1ticamente."
                    : " El apagado autom\u00e1tico est\u00e1 desactivado: el servidor no se apagar\u00e1 solo."));
        } else {
            long durMs = inicioCorteEpochMs >= 0 ? epochCambio - inicioCorteEpochMs : -1;
            String dur = durMs >= 0 ? " tras " + fmtSeg(durMs / 1000) + " de corte" : "";
            inicioCorteEpochMs = -1;
            avisar("\uD83D\uDD0C Servicio el\u00e9ctrico restablecido" + dur + "."
                    + (en && politica.isEncenderPendiente() ? avisoEncendido() : ""));
        }
    }

    /**
     * Al arrancar: si el servicio estuvo caido durante un cambio de luz, el historial
     * quedo desfasado; se inserta el evento que falta con la hora actual (aproximada).
     * Devuelve el ultimo evento leido antes de reconciliar (null si no hay o fallo la lectura).
     */
    private ResumenCortes.Evento reconciliarHistorial(boolean luz) {
        if (historial == null) return null;
        ResumenCortes.Evento ev = null;
        try {
            ev = historial.ultimoEvento();
            Integer ultimo = ev != null ? ev.tipo : null;
            if (luz && ultimo != null && ultimo == PowerHistoryQuery.TIPO_OUTAGE) {
                Log.i(TAG, "Historial: el ultimo evento era OUTAGE y hay luz; se registra BACK_ON");
                registrarHistorial(PowerHistoryQuery.TIPO_BACK_ON, System.currentTimeMillis());
            } else if (!luz && (ultimo == null || ultimo == PowerHistoryQuery.TIPO_BACK_ON)) {
                Log.i(TAG, "Historial: arranque sin luz; se registra OUTAGE");
                registrarHistorial(PowerHistoryQuery.TIPO_OUTAGE, System.currentTimeMillis());
            }
        } catch (Exception e) {
            Log.e(TAG, "No se pudo reconciliar el historial de cortes", e);
        }
        return ev;
    }

    /** Un fallo de SQLite no debe impedir la alerta ni detener el monitor. */
    private void registrarHistorial(int tipo, long epochMs) {
        if (historial == null) return;
        try {
            historial.registrar(tipo, epochMs);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo registrar el evento en el historial", e);
        }
    }

    /** Cola del aviso de luz restablecida cuando hay que volver a encender el servidor. */
    private String avisoEncendido() {
        String espera = fmtSeg(cfg.restablecerEncenderSeg);
        if (cfg.ip110v.isEmpty()) return " El servidor se encender\u00e1 tras " + espera + " de luz estable.";
        if (aviso110vPendiente) {
            // el 110 V ya respondio: la espera corre desde ahora
            aviso110vPendiente = false;
            return " Hay 110 V: el servidor se encender\u00e1 tras " + espera + " de luz estable.";
        }
        return " Se esperar\u00e1 a que haya 110 V para iniciar la espera de " + espera + " y encender el servidor.";
    }

    private void avisar(String texto) {
        Log.i(TAG, "alerta: " + texto.replace('\n', ' '));
        if (notif != null) notif.enviar(texto);
    }

    /** 45 -> "45 s", 125 -> "2 min 05 s", 600 -> "10 min", 3725 -> "1 h 02 min" */
    static String fmtSeg(long s) {
        if (s < 60) return s + " s";
        long min = s / 60, seg = s % 60;
        if (min < 10) return seg == 0 ? min + " min" : min + " min " + (seg < 10 ? "0" : "") + seg + " s";
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60 < 10 ? "0" : "") + (min % 60) + " min";
    }

    private void guardarEncenderPendiente() {
        prefs.edit().putBoolean(PREF_ENCENDER_PENDIENTE, politica.isEncenderPendiente()).apply();
    }
}
