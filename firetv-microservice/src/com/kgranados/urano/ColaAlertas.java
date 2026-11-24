package com.kgranados.urano;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cola FIFO persistente de alertas con reintentos. Logica pura (sin Android) para
 * probarla en PC; ver test/TestColaAlertas.java.
 *
 *  - Los mensajes se guardan en disco al encolarlos y se borran al entregarlos:
 *    sobreviven a reinicios y a caidas de internet, y se entregan en orden.
 *  - Ante un fallo reintentable se espera con backoff (15 s, 30 s, ... hasta 5 min)
 *    o lo que pida el servidor (429 retry_after).
 *  - Tope de mensajes: si se supera, se descartan los mas viejos.
 */
public class ColaAlertas {

    public interface Registro { void log(String msg); }

    /** Envia un texto ya formateado. */
    public interface Transporte { Resultado enviar(String texto); }

    public static final class Resultado {
        public enum Tipo { OK, REINTENTAR, ERROR_CONFIG, DESCARTAR }

        public final Tipo tipo;
        public final long esperaMs;     // solo REINTENTAR: espera pedida por el servidor (0 = backoff)
        public final String detalle;

        private Resultado(Tipo tipo, long esperaMs, String detalle) {
            this.tipo = tipo;
            this.esperaMs = esperaMs;
            this.detalle = detalle;
        }

        public static Resultado ok() { return new Resultado(Tipo.OK, 0, null); }
        public static Resultado reintentar(long esperaMs, String detalle) { return new Resultado(Tipo.REINTENTAR, esperaMs, detalle); }
        public static Resultado errorConfig(String detalle) { return new Resultado(Tipo.ERROR_CONFIG, 0, detalle); }
        public static Resultado descartar(String detalle) { return new Resultado(Tipo.DESCARTAR, 0, detalle); }
    }

    public static final class Mensaje {
        public final long ts;       // epoch ms del evento
        public final String texto;

        Mensaje(long ts, String texto) {
            this.ts = ts;
            this.texto = texto;
        }
    }

    static final long BACKOFF_MIN_MS = 15 * 1000L;
    static final long BACKOFF_MAX_MS = 5 * 60 * 1000L;
    static final long RETRASO_AVISO_MS = 2 * 60 * 1000L;   // a partir de aqui se marca "con retraso"
    static final int MAX_CHARS = 4000;                     // Telegram admite 4096 por mensaje

    private final File archivo;   // null = solo en memoria
    private final int tope;
    private final Registro reg;
    private final ArrayDeque<Mensaje> cola = new ArrayDeque<Mensaje>();
    private int fallosSeguidos = 0;

    public ColaAlertas(File archivo, int tope, Registro reg) {
        this.archivo = archivo;
        this.tope = tope;
        this.reg = reg;
        cargar();
    }

    public synchronized void agregar(long ts, String texto) {
        if (texto == null || texto.trim().isEmpty()) return;
        if (texto.length() > MAX_CHARS) texto = texto.substring(0, MAX_CHARS - 1) + "…";
        cola.addLast(new Mensaje(ts, texto));
        while (cola.size() > tope) {
            Mensaje viejo = cola.pollFirst();
            reg.log("Cola de alertas llena (" + tope + "): se descarta la mas vieja: " + resumen(viejo.texto));
        }
        guardar();
    }

    public synchronized int tamano() { return cola.size(); }

    public synchronized List<Mensaje> pendientes() { return new ArrayList<Mensaje>(cola); }

    /**
     * Intenta entregar los pendientes en orden. Devuelve cuantos ms esperar antes del
     * proximo intento, o 0 si la cola quedo vacia.
     */
    public long drenar(Transporte t, long ahora, TimeZone tz) {
        while (true) {
            Mensaje m;
            synchronized (this) { m = cola.peekFirst(); }
            if (m == null) {
                fallosSeguidos = 0;
                return 0;
            }
            Resultado r = t.enviar(formatear(m, ahora, tz));
            switch (r.tipo) {
                case OK:
                    quitar(m);
                    fallosSeguidos = 0;
                    break;
                case DESCARTAR:
                    quitar(m);
                    reg.log("Alerta descartada (" + r.detalle + "): " + resumen(m.texto));
                    break;
                case ERROR_CONFIG:
                    fallosSeguidos++;
                    reg.log("No se pudo enviar la alerta por configuracion (" + r.detalle
                            + "); se reintentara en " + (BACKOFF_MAX_MS / 1000) + " s");
                    return BACKOFF_MAX_MS;
                case REINTENTAR:
                default:
                    fallosSeguidos++;
                    long espera = r.esperaMs > 0 ? r.esperaMs : backoff(fallosSeguidos);
                    reg.log("No se pudo enviar la alerta (" + r.detalle + "); reintento en " + (espera / 1000)
                            + " s, pendientes: " + tamano());
                    return espera;
            }
        }
    }

    /** 15 s, 30 s, 60 s, 120 s, 240 s, 300 s, 300 s... */
    public static long backoff(int fallos) {
        long ms = BACKOFF_MIN_MS;
        for (int i = 1; i < fallos && ms < BACKOFF_MAX_MS; i++) ms *= 2;
        return Math.min(ms, BACKOFF_MAX_MS);
    }

    /** Texto final: mensaje + hora del evento (con fecha y "con retraso" si llega tarde). */
    public static String formatear(Mensaje m, long ahora, TimeZone tz) {
        String cuando = hora12(m.ts, tz);
        if (ahora - m.ts <= RETRASO_AVISO_MS) {
            return m.texto + "\n🕒 " + cuando;
        }
        if (!mismoDia(m.ts, ahora, tz)) {
            SimpleDateFormat dia = new SimpleDateFormat("dd/MM", Locale.US);
            dia.setTimeZone(tz);
            cuando = dia.format(new Date(m.ts)) + " " + cuando;
        }
        return m.texto + "\n🕒 Ocurrió a las " + cuando + " (aviso con retraso)";
    }

    /**
     * Hora en formato de 12 horas: "6:13 p. m.", "12:05 a. m.". Se arma a mano porque
     * el texto de AM/PM de SimpleDateFormat depende del locale y de la version de ICU.
     */
    public static String hora12(long ts, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ts);
        int h24 = c.get(Calendar.HOUR_OF_DAY);
        int h = h24 % 12 == 0 ? 12 : h24 % 12;
        int min = c.get(Calendar.MINUTE);
        return h + ":" + (min < 10 ? "0" : "") + min + (h24 < 12 ? " a. m." : " p. m.");
    }

    private static boolean mismoDia(long a, long b, TimeZone tz) {
        Calendar ca = Calendar.getInstance(tz);
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance(tz);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private synchronized void quitar(Mensaje m) {
        if (cola.peekFirst() == m) {
            cola.pollFirst();
            guardar();
        }
    }

    private static String resumen(String texto) {
        String t = texto.replace('\n', ' ');
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    // ---- persistencia: una linea por mensaje "ts<TAB>texto escapado" ----

    private void cargar() {
        if (archivo == null || !archivo.exists()) return;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_8));
            String linea;
            while ((linea = br.readLine()) != null) {
                int tab = linea.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    cola.addLast(new Mensaje(Long.parseLong(linea.substring(0, tab)), desescapar(linea.substring(tab + 1))));
                } catch (NumberFormatException ignored) {
                    // linea corrupta: se ignora
                }
            }
        } catch (Exception e) {
            reg.log("No se pudo leer la cola de alertas: " + e);
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
    }

    private void guardar() {
        if (archivo == null) return;
        File tmp = new File(archivo.getPath() + ".tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            for (Mensaje m : cola) {
                w.write(Long.toString(m.ts));
                w.write('\t');
                w.write(escapar(m.texto));
                w.write('\n');
            }
            w.close();
            w = null;
            if (!tmp.renameTo(archivo)) {
                // en algunos sistemas renameTo no reemplaza: borrar y reintentar
                archivo.delete();
                if (!tmp.renameTo(archivo)) reg.log("No se pudo guardar la cola de alertas");
            }
        } catch (Exception e) {
            reg.log("No se pudo guardar la cola de alertas: " + e);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    static String escapar(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\t') b.append("\\t");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.toString();
    }

    static String desescapar(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') b.append('\n');
                else if (n == 't') b.append('\t');
                else if (n == 'r') b.append('\r');
                else b.append(n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
