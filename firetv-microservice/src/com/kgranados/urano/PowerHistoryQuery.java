package com.kgranados.urano;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parametros validados de GET /api/power_history. Logica pura (sin Android) para
 * probarla en PC; ver test/TestPowerHistoryQuery.java.
 *
 *  - page_size: 1..50 (default 50); page: >= 1 (default 1)
 *  - type_id: 1 (OUTAGE) | 2 (BACK_ON)
 *  - date_start / date_end: "YYYY-MM-DD" o ISO-8601 completo (Z, offset, milisegundos).
 *    Se normalizan a UTC "yyyy-MM-ddTHH:mm:ssZ", el mismo formato que se guarda, para
 *    que la comparacion de texto en SQLite sea correcta. date_end solo-fecha cubre el dia.
 *  - sort: "id|date_time:asc|desc" (lista blanca: nunca se concatena texto del cliente)
 *  - status (solo /outages): closed | ongoing | started_before_range
 *  - min_duration (solo /outages): segundos >= 0; deja los cortes que duran al menos eso
 */
public class PowerHistoryQuery {

    public static final int TIPO_OUTAGE = 1;
    public static final int TIPO_BACK_ON = 2;
    public static final int MAX_PAGE_SIZE = 50;

    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?(Z|[+-]\\d{2}:?\\d{2})?)?$");

    public int pageSize = MAX_PAGE_SIZE;
    public int page = 1;
    public Integer typeId;          // null = todos
    public String dateStart;        // ISO UTC normalizado, inclusivo; null = sin limite
    public String dateEnd;          // ISO UTC normalizado; null = sin limite
    public boolean dateEndExclusive; // true si date_end era solo fecha (< dia siguiente)
    public String sortField = "date_time";
    public boolean sortAsc = false;
    public String status;           // null = todos (solo /api/power_history/outages)
    public int minDuracionSeg = -1; // -1 = sin filtro (solo /api/power_history/outages)

    /** Valida los parametros; lanza IllegalArgumentException con un mensaje legible. */
    public static PowerHistoryQuery parse(Map<String, String> p) {
        PowerHistoryQuery q = new PowerHistoryQuery();
        q.pageSize = entero(p, "page_size", q.pageSize);
        if (q.pageSize <= 0 || q.pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page_size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        q.page = entero(p, "page", q.page);
        if (q.page < 1) throw new IllegalArgumentException("page debe ser 1 o mayor");

        String t = valor(p, "type_id");
        if (t != null) {
            int v = entero(p, "type_id", 0);
            if (v != TIPO_OUTAGE && v != TIPO_BACK_ON) {
                throw new IllegalArgumentException("type_id debe ser " + TIPO_OUTAGE + " (OUTAGE) o " + TIPO_BACK_ON + " (BACK_ON)");
            }
            q.typeId = v;
        }

        String ds = valor(p, "date_start");
        if (ds != null) q.dateStart = iso(parseIso(ds, "date_start"));
        String de = valor(p, "date_end");
        if (de != null) {
            long ms = parseIso(de, "date_end");
            if (de.length() == 10) {            // solo fecha: todo ese dia
                ms += 24 * 3600 * 1000L;
                q.dateEndExclusive = true;
            }
            q.dateEnd = iso(ms);
        }
        if (q.dateStart != null && q.dateEnd != null && q.dateStart.compareTo(q.dateEnd) > 0) {
            throw new IllegalArgumentException("date_start no puede ser posterior a date_end");
        }

        String s = valor(p, "sort");
        if (s != null) {
            String[] partes = s.split(":", -1);
            String campo = partes[0].trim();
            String dir = partes.length > 1 ? partes[1].trim().toLowerCase(Locale.US) : "asc";
            if (partes.length > 2 || !(campo.equals("id") || campo.equals("date_time"))
                    || !(dir.equals("asc") || dir.equals("desc"))) {
                throw new IllegalArgumentException("sort debe ser id o date_time seguido de :asc o :desc (ej. date_time:desc)");
            }
            q.sortField = campo;
            q.sortAsc = dir.equals("asc");
        }

        String st = valor(p, "status");
        if (st != null) {
            if (!(st.equals(ResumenCortes.CERRADO) || st.equals(ResumenCortes.EN_CURSO)
                    || st.equals(ResumenCortes.ANTES_DEL_RANGO))) {
                throw new IllegalArgumentException("status debe ser " + ResumenCortes.CERRADO + ", "
                        + ResumenCortes.EN_CURSO + " o " + ResumenCortes.ANTES_DEL_RANGO);
            }
            q.status = st;
        }

        if (valor(p, "min_duration") != null) {
            q.minDuracionSeg = entero(p, "min_duration", -1);
            if (q.minDuracionSeg < 0) throw new IllegalArgumentException("min_duration debe ser 0 o mayor (segundos)");
        }
        return q;
    }

    /** Condicion WHERE (alias h = power_history) o null si no hay filtros. */
    public String where(boolean conTipo) {
        List<String> c = new ArrayList<String>();
        if (conTipo && typeId != null) c.add("h.type_id = ?");
        if (dateStart != null) c.add("h.date_time >= ?");
        if (dateEnd != null) c.add(dateEndExclusive ? "h.date_time < ?" : "h.date_time <= ?");
        if (c.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String x : c) b.append(b.length() == 0 ? "" : " AND ").append(x);
        return b.toString();
    }

    /** Argumentos en el mismo orden que where(conTipo). */
    public String[] args(boolean conTipo) {
        List<String> a = new ArrayList<String>();
        if (conTipo && typeId != null) a.add(Integer.toString(typeId));
        if (dateStart != null) a.add(dateStart);
        if (dateEnd != null) a.add(dateEnd);
        return a.toArray(new String[0]);
    }

    public String orderBy() {
        String dir = sortAsc ? "ASC" : "DESC";
        // id como desempate: orden estable entre paginas con la misma fecha
        return sortField.equals("id") ? "h.id " + dir : "h.date_time " + dir + ", h.id " + dir;
    }

    public int offset() { return (page - 1) * pageSize; }

    // ---- fechas ----

    /** epoch ms -> "yyyy-MM-ddTHH:mm:ssZ" (UTC, sin milisegundos). */
    public static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** ISO-8601 -> epoch ms. Sin zona se asume UTC. */
    public static long parseIso(String s, String campo) {
        Matcher m = ISO.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(campo + " no es una fecha ISO-8601 valida (ej. 2026-10-16 o 2026-10-16T18:00:00Z)");
        }
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.setLenient(false);
        c.set(num(m.group(1)), num(m.group(2)) - 1, num(m.group(3)),
                num(m.group(4)), num(m.group(5)), num(m.group(6)));
        long ms;
        try {
            ms = c.getTimeInMillis();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(campo + " tiene una fecha u hora fuera de rango");
        }
        if (m.group(7) != null) ms += num((m.group(7) + "00").substring(0, 3));
        String z = m.group(8);
        if (z != null && !z.equals("Z")) {
            String d = z.replace(":", "");
            int min = num(d.substring(1, 3)) * 60 + num(d.substring(3, 5));
            ms -= (d.charAt(0) == '-' ? -min : min) * 60 * 1000L;
        }
        return ms;
    }

    private static int num(String s) { return s == null ? 0 : Integer.parseInt(s); }

    private static String valor(Map<String, String> p, String k) {
        String v = p.get(k);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static int entero(Map<String, String> p, String k, int def) {
        String v = valor(p, k);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(k + " debe ser un numero entero");
        }
    }
}
