import com.kgranados.urano.PowerHistoryQuery;
import com.kgranados.urano.ResumenCortes;
import com.kgranados.urano.ResumenCortes.Evento;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pruebas de PowerHistoryQuery (validacion de parametros de GET /api/power_history,
 * normalizacion de fechas, SQL generado) y de ResumenCortes (KPIs).
 * Ejecutar con:  ./build.sh test
 */
public class TestPowerHistoryQuery {

    static int fallos = 0;
    static final int O = PowerHistoryQuery.TIPO_OUTAGE, B = PowerHistoryQuery.TIPO_BACK_ON;

    static void check(boolean ok, String detalle) {
        if (ok) System.out.println("  OK   " + detalle);
        else { fallos++; System.out.println("  FALLO " + detalle); }
    }

    static void escenario(String n) { System.out.println("\n== " + n); }

    static Map<String, String> p(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Debe fallar con IllegalArgumentException cuyo mensaje contenga 'campo'. */
    static void rechaza(String campo, String... kv) {
        try {
            PowerHistoryQuery.parse(p(kv));
            check(false, Arrays.toString(kv) + " deberia rechazarse");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains(campo), Arrays.toString(kv) + " -> 400: " + e.getMessage());
        }
    }

    static long ms(String iso) { return PowerHistoryQuery.parseIso(iso, "t"); }

    public static void main(String[] args) {
        escenario("1) Valores por defecto");
        PowerHistoryQuery q = PowerHistoryQuery.parse(p());
        check(q.pageSize == 50 && q.page == 1, "page_size=50, page=1");
        check(q.typeId == null && q.dateStart == null && q.dateEnd == null, "sin filtros");
        check(q.where(true) == null && q.args(true).length == 0, "sin WHERE ni argumentos");
        check(q.orderBy().equals("h.date_time DESC, h.id DESC"), "orden date_time:desc -> " + q.orderBy());
        check(q.offset() == 0, "offset 0");
        check(PowerHistoryQuery.parse(p("page_size", " ", "page", "")).pageSize == 50, "parametros vacios = default");

        escenario("2) page_size y page");
        check(PowerHistoryQuery.parse(p("page_size", "1")).pageSize == 1, "page_size=1 valido");
        check(PowerHistoryQuery.parse(p("page_size", "50")).pageSize == 50, "page_size=50 valido");
        rechaza("page_size", "page_size", "0");
        rechaza("page_size", "page_size", "51");
        rechaza("page_size", "page_size", "-3");
        rechaza("page_size", "page_size", "diez");
        rechaza("page", "page", "0");
        q = PowerHistoryQuery.parse(p("page_size", "20", "page", "3"));
        check(q.offset() == 40, "page=3, page_size=20 -> offset 40");

        escenario("3) type_id");
        q = PowerHistoryQuery.parse(p("type_id", "2"));
        check(q.typeId == 2 && "h.type_id = ?".equals(q.where(true)), "type_id=2 -> " + q.where(true));
        check(q.where(false) == null, "where(false) ignora el tipo (summary)");
        rechaza("type_id", "type_id", "9");
        rechaza("type_id", "type_id", "x");

        escenario("4) sort (lista blanca)");
        check(PowerHistoryQuery.parse(p("sort", "date_time:asc")).orderBy().equals("h.date_time ASC, h.id ASC"), "date_time:asc");
        check(PowerHistoryQuery.parse(p("sort", "id:desc")).orderBy().equals("h.id DESC"), "id:desc");
        check(PowerHistoryQuery.parse(p("sort", "id:DESC")).orderBy().equals("h.id DESC"), "direccion sin distinguir mayusculas");
        check(PowerHistoryQuery.parse(p("sort", "id")).orderBy().equals("h.id ASC"), "sin direccion = asc");
        rechaza("sort", "sort", "foo:asc");
        rechaza("sort", "sort", "date_time:up");
        rechaza("sort", "sort", "date_time:desc; DROP TABLE power_history");
        rechaza("sort", "sort", "id:asc:x");

        escenario("5) Fechas: normalizacion a UTC");
        check(PowerHistoryQuery.parse(p("date_start", "2026-10-16")).dateStart.equals("2026-10-16T00:00:00Z"), "solo fecha");
        check(PowerHistoryQuery.parse(p("date_start", "2026-10-16T18:00:00Z")).dateStart.equals("2026-10-16T18:00:00Z"), "con Z");
        check(PowerHistoryQuery.parse(p("date_start", "2026-10-16T14:00:00-04:00")).dateStart.equals("2026-10-16T18:00:00Z"), "offset -04:00");
        check(PowerHistoryQuery.parse(p("date_start", "2026-10-16T23:30:00+0530")).dateStart.equals("2026-10-16T18:00:00Z"), "offset +0530");
        check(PowerHistoryQuery.parse(p("date_start", "2026-10-16T18:00:00.999Z")).dateStart.equals("2026-10-16T18:00:00Z"), "milisegundos (toISOString)");
        check(PowerHistoryQuery.parse(p("date_start", "2026-10-16T18:00")).dateStart.equals("2026-10-16T18:00:00Z"), "sin segundos ni zona = UTC");
        rechaza("date_start", "date_start", "16/10/2026");
        rechaza("date_start", "date_start", "2026-13-01");
        rechaza("date_end", "date_end", "2026-02-30");
        rechaza("date_start", "date_start", "2026-10-17", "date_end", "2026-10-16T00:00:00Z");

        escenario("6) date_end: inclusivo con hora, dia completo si es solo fecha");
        q = PowerHistoryQuery.parse(p("date_end", "2026-10-16"));
        check(q.dateEnd.equals("2026-10-17T00:00:00Z") && q.where(true).equals("h.date_time < ?"), "solo fecha -> < dia siguiente");
        q = PowerHistoryQuery.parse(p("date_end", "2026-10-16T23:59:59.999Z"));
        check(q.dateEnd.equals("2026-10-16T23:59:59Z") && q.where(true).equals("h.date_time <= ?"), "con hora -> <= (inclusivo)");
        q = PowerHistoryQuery.parse(p("type_id", "1", "date_start", "2026-10-01", "date_end", "2026-10-16"));
        check(q.where(true).equals("h.type_id = ? AND h.date_time >= ? AND h.date_time < ?"), "WHERE combinado -> " + q.where(true));
        check(Arrays.equals(q.args(true), new String[]{"1", "2026-10-01T00:00:00Z", "2026-10-17T00:00:00Z"}), "args en orden -> " + Arrays.toString(q.args(true)));
        check(Arrays.equals(q.args(false), new String[]{"2026-10-01T00:00:00Z", "2026-10-17T00:00:00Z"}), "args sin tipo (summary)");

        escenario("7) iso() / parseIso() ida y vuelta");
        check(PowerHistoryQuery.iso(ms("2026-10-16T18:00:00Z")).equals("2026-10-16T18:00:00Z"), "2026-10-16T18:00:00Z");
        check(PowerHistoryQuery.iso(0).equals("1970-01-01T00:00:00Z"), "epoch 0");

        escenario("8) ResumenCortes: pares normales");
        List<Evento> ev = new ArrayList<>();
        ev.add(new Evento(ms("2026-10-16T10:00:00Z"), O));
        ev.add(new Evento(ms("2026-10-16T10:10:00Z"), B));   // 600 s
        ev.add(new Evento(ms("2026-10-16T12:00:00Z"), O));
        ev.add(new Evento(ms("2026-10-16T12:30:00Z"), B));   // 1800 s
        ResumenCortes r = ResumenCortes.calcular(ev);
        check(r.totalCortes == 2 && r.cortesCerrados == 2, "2 cortes, 2 cerrados");
        check(r.promedioSeg == 1200 && r.maxSeg == 1800 && r.totalSeg == 2400, "promedio 1200, max 1800, total 2400");
        check(r.enCursoDesdeMs == -1, "sin corte en curso");

        escenario("9) ResumenCortes: OUTAGE abierto al final y OUTAGE repetido");
        ev.add(new Evento(ms("2026-10-16T15:00:00Z"), O));
        ev.add(new Evento(ms("2026-10-16T15:05:00Z"), O));   // repetido (reconciliacion): se ignora
        r = ResumenCortes.calcular(ev);
        check(r.totalCortes == 3 && r.cortesCerrados == 2, "3 cortes, 2 cerrados");
        check(r.promedioSeg == 1200, "el abierto no entra en el promedio");
        check(r.enCursoDesdeMs == ms("2026-10-16T15:00:00Z"), "en curso desde el primer OUTAGE");

        escenario("10) ResumenCortes: BACK_ON huerfano al inicio del rango y vacio");
        ev = new ArrayList<>();
        ev.add(new Evento(ms("2026-10-16T09:00:00Z"), B));   // su OUTAGE quedo antes del rango
        ev.add(new Evento(ms("2026-10-16T10:00:00Z"), O));
        ev.add(new Evento(ms("2026-10-16T10:00:45Z"), B));
        r = ResumenCortes.calcular(ev);
        check(r.totalCortes == 1 && r.cortesCerrados == 1 && r.promedioSeg == 45, "huerfano ignorado, 1 corte de 45 s");
        r = ResumenCortes.calcular(new ArrayList<>());
        check(r.totalCortes == 0 && r.promedioSeg == 0 && r.enCursoDesdeMs == -1, "sin eventos -> ceros");

        escenario("11) emparejar: una fila por corte");
        ev = new ArrayList<>();
        ev.add(new Evento(1, ms("2026-10-16T09:00:00Z"), B));   // huerfano
        ev.add(new Evento(2, ms("2026-10-16T09:00:30Z"), B));   // duplicado: se ignora
        ev.add(new Evento(3, ms("2026-10-16T10:00:00Z"), O));
        ev.add(new Evento(4, ms("2026-10-16T10:10:00Z"), B));
        ev.add(new Evento(5, ms("2026-10-16T12:00:00Z"), O));
        ev.add(new Evento(6, ms("2026-10-16T12:05:00Z"), O));   // repetido: se ignora
        List<ResumenCortes.Corte> cs = ResumenCortes.emparejar(ev);
        check(cs.size() == 3, "3 filas: huerfano, cerrado, en curso -> " + cs.size());
        ResumenCortes.Corte k = cs.get(0);
        check(k.estado.equals("closed") && k.antesDelRango && k.backOnId == 1 && k.outageId == -1 && k.durSeg == -1, "huerfano: restablecido, antes del rango, sin inicio ni duracion");
        k = cs.get(1);
        check(k.estado.equals("closed") && k.outageId == 3 && k.backOnId == 4 && k.durSeg == 600, "cerrado 3->4, 600 s");
        k = cs.get(2);
        check(k.estado.equals("ongoing") && k.outageId == 5 && k.backOnId == -1 && k.durSeg == -1, "en curso desde el OUTAGE 5");
        check(ResumenCortes.emparejar(new ArrayList<>()).isEmpty(), "sin eventos -> sin filas");

        escenario("12) filtrarYOrdenar y status");
        List<ResumenCortes.Corte> f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p()));
        check(f.get(0).outageId == 5 && f.get(2).backOnId == 1, "default date_time:desc (huerfano ordena por su fin)");
        f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p("sort", "date_time:asc")));
        check(f.get(0).backOnId == 1 && f.get(2).outageId == 5, "date_time:asc");
        f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p("status", "closed")));
        check(f.size() == 2 && f.get(0).outageId == 3 && f.get(1).backOnId == 1, "status=closed (incluye el huerfano)");
        f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p("status", "started_before_range")));
        check(f.size() == 1 && f.get(0).backOnId == 1, "status=started_before_range filtra por la marca");
        check(PowerHistoryQuery.parse(p("status", "ongoing")).status.equals("ongoing"), "status=ongoing valido");
        rechaza("status", "status", "OUTAGE");

        escenario("13) inicioAlArrancar: el estado cuenta desde la fecha guardada tras reiniciar");
        long ahora = ms("2026-10-17T00:31:00Z");
        long corte = ms("2026-10-16T23:18:19Z");
        check(ResumenCortes.inicioAlArrancar(new Evento(corte, O), O, ahora) == corte, "sin luz, ultimo OUTAGE -> su hora");
        check(ResumenCortes.inicioAlArrancar(new Evento(corte, B), O, ahora) == ahora, "sin luz, ultimo BACK_ON -> ahora");
        check(ResumenCortes.inicioAlArrancar(new Evento(corte, B), B, ahora) == corte, "con luz, ultimo BACK_ON -> su hora");
        check(ResumenCortes.inicioAlArrancar(new Evento(corte, O), B, ahora) == ahora, "con luz, ultimo OUTAGE -> ahora");
        check(ResumenCortes.inicioAlArrancar(null, O, ahora) == ahora, "sin historial -> ahora");
        check(ResumenCortes.inicioAlArrancar(new Evento(ahora + 60000, O), O, ahora) == ahora, "evento en el futuro -> ahora");

        escenario("14) Corte que cruza la medianoche: cada corte con su restablecimiento");
        Evento out22 = new Evento(10, ms("2026-10-16T22:00:00Z"), O);
        Evento back03 = new Evento(11, ms("2026-10-17T03:00:00Z"), B);
        // busqueda del dia 16: el BACK_ON cae despues del rango
        ev = new ArrayList<>();
        ev.add(out22);
        cs = ResumenCortes.emparejar(ev, null, back03);
        k = cs.get(0);
        check(cs.size() == 1 && k.estado.equals("closed") && k.backOnId == 11 && k.durSeg == 5 * 3600,
                "dia 16: cerrado 10->11, 5 h (no en curso)");
        r = ResumenCortes.calcular(ev, null, back03);
        check(r.totalCortes == 1 && r.cortesCerrados == 1 && r.maxSeg == 5 * 3600 && r.enCursoDesdeMs == -1,
                "dia 16: KPIs con el corte cerrado, sin en curso");
        // busqueda del dia 17: el OUTAGE cae antes del rango
        ev = new ArrayList<>();
        ev.add(back03);
        ev.add(new Evento(12, ms("2026-10-17T08:00:00Z"), O));
        ev.add(new Evento(13, ms("2026-10-17T08:01:00Z"), B));
        cs = ResumenCortes.emparejar(ev, out22, null);
        k = cs.get(0);
        check(cs.size() == 2 && k.estado.equals("closed") && k.antesDelRango && k.outageId == 10 && k.backOnId == 11
                && k.durSeg == 5 * 3600, "dia 17: restablecido 10->11 con inicio y duracion, marcado antes del rango");
        r = ResumenCortes.calcular(ev, out22, null);
        check(r.totalCortes == 1 && r.maxSeg == 60, "dia 17: el corte del dia anterior no cuenta en los KPIs");
        // el corte abarca todo el rango (sin eventos dentro)
        cs = ResumenCortes.emparejar(new ArrayList<>(), out22, back03);
        check(cs.size() == 1 && cs.get(0).estado.equals("closed") && cs.get(0).antesDelRango && cs.get(0).durSeg == 5 * 3600,
                "rango dentro del corte: una fila completa");
        check(ResumenCortes.emparejar(new ArrayList<>(), out22, null).get(0).estado.equals("ongoing"),
                "empezo antes y sigue sin BACK_ON: en curso");
        // habia luz antes del rango: un BACK_ON duplicado al inicio no es un corte
        ev = new ArrayList<>();
        ev.add(new Evento(20, ms("2026-10-17T00:10:00Z"), B));
        ev.add(new Evento(21, ms("2026-10-17T08:00:00Z"), O));
        ev.add(new Evento(22, ms("2026-10-17T08:01:00Z"), B));
        cs = ResumenCortes.emparejar(ev, back03, null);
        check(cs.size() == 1 && cs.get(0).outageId == 21 && !cs.get(0).antesDelRango, "BACK_ON duplicado inicial ignorado");
        // sin BACK_ON posterior sigue en curso
        ev = new ArrayList<>();
        ev.add(out22);
        check(ResumenCortes.emparejar(ev, null, null).get(0).estado.equals("ongoing"), "sin BACK_ON posterior: en curso");

        escenario("15) min_duration: duracion minima en segundos");
        check(PowerHistoryQuery.parse(p()).minDuracionSeg == -1, "sin parametro -> sin filtro");
        check(PowerHistoryQuery.parse(p("min_duration", "600")).minDuracionSeg == 600, "600 -> 600 s");
        boolean err = false;
        try { PowerHistoryQuery.parse(p("min_duration", "-1")); } catch (IllegalArgumentException e) { err = true; }
        check(err, "negativo -> error");
        ev = new ArrayList<>();
        ev.add(new Evento(1, ms("2026-10-16T09:00:00Z"), B));   // huerfano: sin duracion
        ev.add(new Evento(2, ms("2026-10-16T10:00:00Z"), O));
        ev.add(new Evento(3, ms("2026-10-16T10:05:00Z"), B));   // 300 s
        ev.add(new Evento(4, ms("2026-10-16T11:00:00Z"), O));
        ev.add(new Evento(5, ms("2026-10-16T11:10:00Z"), B));   // 600 s
        ev.add(new Evento(6, ms("2026-10-16T12:00:00Z"), O));   // en curso
        cs = ResumenCortes.emparejar(ev);
        long ahora15 = ms("2026-10-16T12:20:00Z");               // en curso hace 1200 s
        f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p("min_duration", "600", "sort", "date_time:asc")), ahora15);
        check(f.size() == 2 && f.get(0).outageId == 4 && f.get(1).outageId == 6, ">= 600 s: el de 600 s (inclusivo) y el en curso de 1200 s");
        f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p("min_duration", "1500")), ahora15);
        check(f.isEmpty(), ">= 1500 s: ninguno (el en curso aun no llega)");
        f = ResumenCortes.filtrarYOrdenar(cs, PowerHistoryQuery.parse(p("min_duration", "0")), ahora15);
        check(f.size() == 3, ">= 0 s: todos menos el huerfano sin inicio");

        System.out.println(fallos == 0 ? "\nTODOS LOS ESCENARIOS OK" : "\n" + fallos + " FALLO(S)");
        if (fallos > 0) System.exit(1);
    }
}
