package com.kgranados.urano;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * KPIs del historial de cortes. Logica pura (sin Android) para probarla en PC;
 * ver test/TestPowerHistoryQuery.java.
 *
 * Recibe los eventos en orden cronologico y empareja cada OUTAGE con el siguiente
 * BACK_ON. Un OUTAGE repetido conserva el primero (el corte empezo ahi). Los cortes que
 * cruzan los bordes del rango se completan con eventos de fuera de el: el OUTAGE de un
 * corte que empezo antes de "Desde" (marcado antesDelRango: no cuenta en los KPIs) y el
 * BACK_ON posterior a "Hasta" del corte que sigue abierto al final. El estado dice solo si
 * se restablecio: cerrado si tiene BACK_ON, "en curso" si no hay ningun BACK_ON posterior
 * (no entra en las duraciones).
 */
public class ResumenCortes {

    public static final String CERRADO = "closed";
    public static final String EN_CURSO = "ongoing";
    public static final String ANTES_DEL_RANGO = "started_before_range";

    public static final class Evento {
        public final long id;
        public final long ms;
        public final int tipo;

        public Evento(long ms, int tipo) {
            this(0, ms, tipo);
        }

        public Evento(long id, long ms, int tipo) {
            this.id = id;
            this.ms = ms;
            this.tipo = tipo;
        }
    }

    /** Un corte agrupado: OUTAGE + su BACK_ON. Ids y ms en -1 cuando no existen. */
    public static final class Corte {
        public long outageId = -1, backOnId = -1;
        public long inicioMs = -1, finMs = -1;
        public long durSeg = -1;         // -1 si no esta cerrado o no tiene inicio
        public String estado;            // CERRADO o EN_CURSO
        public boolean antesDelRango;    // empezo antes de "Desde"

        /** Clave de orden: inicio del corte, o su fin si empezo antes del rango. */
        public long claveMs() { return inicioMs >= 0 ? inicioMs : finMs; }
        public long claveId() { return outageId >= 0 ? outageId : backOnId; }

        /** Duracion en s; un corte abierto cuenta hasta ahoraMs. -1 si no tiene inicio. */
        public long duracionSeg(long ahoraMs) {
            if (durSeg >= 0) return durSeg;
            return inicioMs >= 0 ? Math.max(0, (ahoraMs - inicioMs) / 1000) : -1;
        }
    }

    public int totalCortes;          // cortes que empiezan en el rango (cerrados + en curso)
    public int cortesCerrados;       // con BACK_ON: entran en las duraciones
    public long totalSeg;
    public long maxSeg;
    public long promedioSeg;
    public long enCursoDesdeMs = -1; // -1 = el ultimo corte del rango esta cerrado

    /**
     * Al arrancar: desde cuando dura el estado actual (epoch ms). tipo es el evento que lo
     * inicia (OUTAGE sin luz, BACK_ON con luz); si el ultimo guardado es de ese tipo, el
     * estado empezo ahi. Sin historial, con otro tipo o con una hora futura (reloj
     * incoherente), se toma la hora actual.
     */
    public static long inicioAlArrancar(Evento ultimo, int tipo, long ahoraMs) {
        if (ultimo != null && ultimo.tipo == tipo && ultimo.ms <= ahoraMs) {
            return ultimo.ms;
        }
        return ahoraMs;
    }

    /** Agrupa los eventos (orden cronologico) en cortes, en el mismo orden. */
    public static List<Corte> emparejar(List<Evento> eventos) {
        return emparejar(eventos, null, null);
    }

    /**
     * Como emparejar(eventos), con los eventos de fuera del rango que completan los cortes
     * de los bordes. antes es el estado al empezar el rango: el OUTAGE que inicio el corte
     * que seguia abierto, o el ultimo BACK_ON si habia luz (null si no hay nada antes).
     * backOnDespues es el primer BACK_ON tras el rango (null si no hay).
     */
    public static List<Corte> emparejar(List<Evento> eventos, Evento antes, Evento backOnDespues) {
        List<Corte> cortes = new ArrayList<Corte>();
        Corte abierto = null;
        // con luz antes del rango, un BACK_ON inicial es un duplicado del anterior
        boolean huboLuz = antes != null && antes.tipo == PowerHistoryQuery.TIPO_BACK_ON;
        if (antes != null && antes.tipo == PowerHistoryQuery.TIPO_OUTAGE) {
            abierto = new Corte();
            abierto.outageId = antes.id;
            abierto.inicioMs = antes.ms;
            abierto.estado = EN_CURSO;
            abierto.antesDelRango = true;
            cortes.add(abierto);
        }
        for (Evento e : eventos) {
            if (e.tipo == PowerHistoryQuery.TIPO_OUTAGE) {
                if (abierto == null) {
                    abierto = new Corte();
                    abierto.outageId = e.id;
                    abierto.inicioMs = e.ms;
                    abierto.estado = EN_CURSO;
                    cortes.add(abierto);
                }
            } else if (e.tipo == PowerHistoryQuery.TIPO_BACK_ON) {
                if (abierto != null) {
                    cerrar(abierto, e);
                    abierto = null;
                } else if (cortes.isEmpty() && !huboLuz) {
                    // el historial empieza con un BACK_ON (su OUTAGE no quedo registrado); mas
                    // adelante, un BACK_ON sin corte abierto es un duplicado y se ignora
                    Corte h = new Corte();
                    h.backOnId = e.id;
                    h.finMs = e.ms;
                    h.estado = CERRADO;
                    h.antesDelRango = true;
                    cortes.add(h);
                }
            }
        }
        // el corte abierto al final del rango termino despues de el
        if (abierto != null && backOnDespues != null) cerrar(abierto, backOnDespues);
        return cortes;
    }

    private static void cerrar(Corte c, Evento backOn) {
        c.backOnId = backOn.id;
        c.finMs = backOn.ms;
        c.durSeg = Math.max(0, (backOn.ms - c.inicioMs) / 1000);
        c.estado = CERRADO;
    }

    /** Filtro por status y orden de q (sort id o date_time) para /api/power_history/outages. */
    public static List<Corte> filtrarYOrdenar(List<Corte> cortes, final PowerHistoryQuery q) {
        return filtrarYOrdenar(cortes, q, System.currentTimeMillis());
    }

    /**
     * Filtro por status y por duracion minima (los cortes abiertos cuentan hasta ahoraMs;
     * uno sin inicio conocido no pasa el filtro) y orden de q (sort id o date_time).
     */
    public static List<Corte> filtrarYOrdenar(List<Corte> cortes, final PowerHistoryQuery q, long ahoraMs) {
        List<Corte> r = new ArrayList<Corte>();
        for (Corte c : cortes) {
            if (q.status != null && !(q.status.equals(ANTES_DEL_RANGO) ? c.antesDelRango : q.status.equals(c.estado))) continue;
            if (q.minDuracionSeg >= 0 && c.duracionSeg(ahoraMs) < q.minDuracionSeg) continue;
            r.add(c);
        }
        final boolean porId = q.sortField.equals("id");
        Collections.sort(r, new Comparator<Corte>() {
            @Override
            public int compare(Corte a, Corte b) {
                int k = porId ? 0 : Long.compare(a.claveMs(), b.claveMs());
                if (k == 0) k = Long.compare(a.claveId(), b.claveId());
                return q.sortAsc ? k : -k;
            }
        });
        return r;
    }

    public static ResumenCortes calcular(List<Evento> eventos) {
        return calcular(eventos, null, null);
    }

    /** KPIs de los cortes que empiezan en el rango (ver emparejar con bordes). */
    public static ResumenCortes calcular(List<Evento> eventos, Evento antes, Evento backOnDespues) {
        ResumenCortes r = new ResumenCortes();
        for (Corte c : emparejar(eventos, antes, backOnDespues)) {
            if (c.antesDelRango) continue;   // ya cuenta en el periodo en que empezo
            if (c.estado.equals(CERRADO)) {
                r.totalCortes++;
                r.cortesCerrados++;
                r.totalSeg += c.durSeg;
                if (c.durSeg > r.maxSeg) r.maxSeg = c.durSeg;
            } else if (c.estado.equals(EN_CURSO)) {
                r.totalCortes++;
                r.enCursoDesdeMs = c.inicioMs;
            }
        }
        r.promedioSeg = r.cortesCerrados > 0 ? Math.round((double) r.totalSeg / r.cortesCerrados) : 0;
        return r;
    }
}
