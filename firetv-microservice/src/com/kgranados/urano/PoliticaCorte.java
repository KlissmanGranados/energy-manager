package com.kgranados.urano;

/**
 * Politica automatica ante cortes de luz. Logica pura (sin Android) para poder
 * probarla en PC con un reloj simulado; ver test/TestPoliticaCorte.java.
 *
 *  - Corte continuo >= umbralCorteMs: queda pendiente encender Urano al volver la luz
 *    (si el encendido automatico esta activo) y, si Urano esta ARRIBA y el apagado
 *    automatico esta activo, se pide APAGAR (reintento cada reintentoApagarMs si no baja).
 *  - Tras ese corte, con luz estable >= umbralRestabMs y Urano ABAJO -> ENCENDER, sin
 *    importar quien o que lo apago. Si no enciende, se reintenta cada reintentoEncenderMs
 *    hasta maxIntentosEncender veces.
 *  - Un corte corto (< umbralCorteMs) no dispara nada.
 *  - Si Urano ya esta ARRIBA al volver la luz (o alguien lo enciende), el ciclo termina.
 *  - Apagado y encendido automaticos se activan por separado (configurar).
 *
 * Los tiempos son de un reloj monotono en ms (SystemClock.elapsedRealtime en Android).
 */
public class PoliticaCorte {

    public enum Accion { NINGUNA, APAGAR, ENCENDER }

    public interface Registro { void log(String msg); }

    private final long umbralCorteMs;
    private final long umbralRestabMs;
    private final long reintentoApagarMs;
    private final long reintentoEncenderMs;
    private final int maxIntentosEncender;
    private final Registro reg;

    /** "Sin valor" para inicioCorte/inicioRestab: un inicio restaurado tras un reboot puede ser negativo. */
    static final long NADA = Long.MIN_VALUE;

    private long inicioCorte = NADA;    // desde cuando no hay luz (continuo); NADA = hay luz
    private long inicioRestab = NADA;   // desde cuando hay luz (continuo); NADA = no hay luz
    private long ultimoApagar = -1;
    private long ultimoEncender = -1;
    private int intentosEncender = 0;
    private boolean encenderPendiente;  // hubo un corte largo: encender al volver la luz (se persiste fuera)
    private boolean rendido;            // evento: se dejo de intentar encender (se consume una vez)
    private boolean esperaIniciada;     // evento: con 220 V y 110 V empezo la espera para encender (se consume una vez)
    private boolean apagar = true;      // apagado automatico tras un corte largo
    private boolean encender = true;    // encendido automatico al volver la luz tras un corte largo

    public PoliticaCorte(long umbralCorteMs, long umbralRestabMs, long reintentoApagarMs,
                         long reintentoEncenderMs, int maxIntentosEncender,
                         boolean encenderPendiente, Registro reg) {
        this.umbralCorteMs = umbralCorteMs;
        this.umbralRestabMs = umbralRestabMs;
        this.reintentoApagarMs = reintentoApagarMs;
        this.reintentoEncenderMs = reintentoEncenderMs;
        this.maxIntentosEncender = maxIntentosEncender;
        this.encenderPendiente = encenderPendiente;
        this.reg = reg;
    }

    public boolean isEncenderPendiente() { return encenderPendiente; }

    public int getMaxIntentosEncender() { return maxIntentosEncender; }

    /** true una sola vez tras rendirse (para avisar "no encendio tras N intentos"). */
    public boolean consumirRendido() {
        boolean r = rendido;
        rendido = false;
        return r;
    }

    /** true una sola vez cuando, con encendido pendiente, empieza la espera de luz estable (ya hay 110 V). */
    public boolean consumirEsperaIniciada() {
        boolean r = esperaIniciada;
        esperaIniciada = false;
        return r;
    }

    /**
     * Activa/desactiva por separado el apagado y el encendido automaticos. El corte en curso
     * se sigue contando; desactivar el encendido olvida el encendido pendiente.
     */
    public void configurar(boolean apagar, boolean encender) {
        this.apagar = apagar;
        this.encender = encender;
        if (!encender && encenderPendiente) {
            reg.log("Encendido automatico desactivado: se olvida el encendido pendiente");
            terminarCiclo();
        }
        if (!apagar) ultimoApagar = -1;
    }

    /**
     * Olvida cortes y esperas en curso y el encendido pendiente.
     * Se usa al activar/desactivar la automatizacion: desactivar = control manual.
     */
    public void reiniciar() {
        inicioCorte = NADA;
        inicioRestab = NADA;
        ultimoApagar = -1;
        rendido = false;
        esperaIniciada = false;
        terminarCiclo();
    }

    /**
     * Al arrancar el servicio: retoma el corte (sin luz) o la luz estable (con luz) desde
     * inicio, calculado con la fecha guardada en el historial. Llamar antes del primer evaluar().
     */
    public void restaurar(boolean hayLuz, long inicio) {
        if (hayLuz) inicioRestab = inicio;
        else inicioCorte = inicio;
    }

    /** El ENCENDER pedido no llego a iniciarse (bloqueado, sin luz...): no cuenta como intento. */
    public void encenderRechazado() {
        if (intentosEncender > 0) intentosEncender--;
        ultimoEncender = -1;
    }

    /**
     * Evalua una muestra. arriba/abajo describen el estado estable de Urano;
     * si ambos son false, Urano esta en transicion (ENCENDIENDO/APAGANDO).
     * hay110v solo condiciona el encendido: con encendido pendiente, la espera de luz
     * estable no empieza (y se reinicia si ya corria) mientras no haya 110 V.
     */
    public Accion evaluar(long ahora, boolean hayLuz, boolean hay110v, boolean arriba, boolean abajo) {
        if (!hayLuz) {
            if (inicioRestab != NADA && encenderPendiente) {
                reg.log("La luz se volvio a ir antes de " + dur(umbralRestabMs) + " estables; se reinicia la espera");
            }
            inicioRestab = NADA;
            intentosEncender = 0;
            ultimoEncender = -1;
            if (inicioCorte == NADA) {
                inicioCorte = ahora;
                reg.log("Corte de luz detectado" + (apagar ? "; Urano se apagara si dura " + dur(umbralCorteMs) : ""));
            }
            if (ahora - inicioCorte >= umbralCorteMs) {
                if (encender && !encenderPendiente) {
                    encenderPendiente = true;
                    reg.log("Corte de " + dur(ahora - inicioCorte) + ": al volver la luz, Urano se encendera tras "
                            + dur(umbralRestabMs) + " estables");
                }
                if (apagar && arriba && (ultimoApagar < 0 || ahora - ultimoApagar >= reintentoApagarMs)) {
                    ultimoApagar = ahora;
                    reg.log("Corte de " + dur(ahora - inicioCorte) + " -> APAGAR Urano");
                    return Accion.APAGAR;
                }
            }
            return Accion.NINGUNA;
        }

        // hay luz
        if (inicioCorte != NADA) {
            reg.log("Luz restablecida tras " + dur(ahora - inicioCorte) + " de corte");
            inicioCorte = NADA;
            ultimoApagar = -1;
        }
        if (!encenderPendiente) {
            if (inicioRestab == NADA) inicioRestab = ahora;
            return Accion.NINGUNA;
        }

        if (arriba) {
            reg.log("Urano ARRIBA: ciclo de corte terminado");
            terminarCiclo();
            return Accion.NINGUNA;
        }
        if (!hay110v) {
            if (inicioRestab != NADA) {
                reg.log("Sin 110 V: se espera a que vuelva para contar " + dur(umbralRestabMs) + " estables");
            }
            inicioRestab = NADA;
            return Accion.NINGUNA;
        }
        if (inicioRestab == NADA) {
            inicioRestab = ahora;
            esperaIniciada = true;
        }
        if (!abajo) return Accion.NINGUNA;                                   // en transicion
        if (ahora - inicioRestab < umbralRestabMs) return Accion.NINGUNA;    // luz aun no estable
        if (ultimoEncender >= 0 && ahora - ultimoEncender < reintentoEncenderMs) return Accion.NINGUNA;
        if (intentosEncender >= maxIntentosEncender) {
            reg.log("Urano no encendio tras " + maxIntentosEncender + " intentos; se deja de intentar");
            rendido = true;
            terminarCiclo();
            return Accion.NINGUNA;
        }
        intentosEncender++;
        ultimoEncender = ahora;
        reg.log("Luz estable " + dur(ahora - inicioRestab) + " -> ENCENDER Urano (intento "
                + intentosEncender + "/" + maxIntentosEncender + ")");
        return Accion.ENCENDER;
    }

    private void terminarCiclo() {
        encenderPendiente = false;
        intentosEncender = 0;
        ultimoEncender = -1;
    }

    static String dur(long ms) {
        long s = ms / 1000;
        return (s / 60) + "m" + (s % 60 < 10 ? "0" : "") + (s % 60) + "s";
    }
}
