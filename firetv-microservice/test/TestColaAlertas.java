import com.kgranados.urano.ColaAlertas;
import com.kgranados.urano.ColaAlertas.Resultado;
import com.kgranados.urano.TelegramTransporte;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TimeZone;

/**
 * Pruebas de ColaAlertas (orden, reintentos, persistencia, tope, formato) y de
 * TelegramTransporte contra un servidor HTTP local que imita a la Bot API.
 * Ejecutar con:  ./build.sh test
 */
public class TestColaAlertas {

    static int fallos = 0;
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    // instante fijo: 2026-10-15 18:13:20 UTC
    static final long T0 = java.time.ZonedDateTime.of(2026, 10, 15, 18, 13, 20, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli();

    static void check(boolean ok, String detalle) {
        if (ok) System.out.println("  OK   " + detalle);
        else { fallos++; System.out.println("  FALLO " + detalle); }
    }

    static void escenario(String n) { System.out.println("\n== " + n); }

    /** Transporte falso: devuelve resultados programados y registra lo enviado. */
    static class Falso implements ColaAlertas.Transporte {
        final Deque<Resultado> guion = new ArrayDeque<>();
        final List<String> enviados = new ArrayList<>();
        public Resultado enviar(String texto) {
            Resultado r = guion.isEmpty() ? Resultado.ok() : guion.pollFirst();
            if (r.tipo == Resultado.Tipo.OK) enviados.add(texto.substring(0, texto.indexOf('\n')));
            return r;
        }
    }

    static List<String> logs = new ArrayList<>();
    static ColaAlertas.Registro reg = logs::add;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("cola-alertas").toFile();

        escenario("1) Entrega en orden y cola vacia");
        ColaAlertas c = new ColaAlertas(null, 200, reg);
        c.agregar(T0, "A"); c.agregar(T0, "B"); c.agregar(T0, "C");
        Falso f = new Falso();
        long r = c.drenar(f, T0, UTC);
        check(f.enviados.equals(List.of("A", "B", "C")), "enviados A,B,C -> " + f.enviados);
        check(r == 0 && c.tamano() == 0, "cola vacia y sin reintento");

        escenario("2) Falla la red 2 veces: backoff 15 s, 30 s y luego entrega todo en orden");
        c = new ColaAlertas(null, 200, reg);
        c.agregar(T0, "A"); c.agregar(T0, "B");
        f = new Falso();
        f.guion.add(Resultado.reintentar(0, "red")); f.guion.add(Resultado.reintentar(0, "red"));
        long r1 = c.drenar(f, T0, UTC), r2 = c.drenar(f, T0, UTC), r3 = c.drenar(f, T0, UTC);
        check(r1 == 15000 && r2 == 30000, "esperas 15000, 30000 -> " + r1 + ", " + r2);
        check(r3 == 0 && f.enviados.equals(List.of("A", "B")), "al recuperar: A,B en orden -> " + f.enviados);

        escenario("3) Backoff con tope de 5 min");
        StringBuilder seq = new StringBuilder();
        for (int i = 1; i <= 8; i++) seq.append(ColaAlertas.backoff(i) / 1000).append(i < 8 ? "," : "");
        check(seq.toString().equals("15,30,60,120,240,300,300,300"), "15,30,60,120,240,300,300,300 -> " + seq);

        escenario("4) 429: respeta retry_after del servidor");
        c = new ColaAlertas(null, 200, reg);
        c.agregar(T0, "A");
        f = new Falso();
        f.guion.add(Resultado.reintentar(7000, "HTTP 429"));
        check(c.drenar(f, T0, UTC) == 7000 && c.tamano() == 1, "espera 7000 y conserva el mensaje");

        escenario("5) Persistencia: sobrevive a un reinicio (con saltos de linea, tabs, \\ y emoji)");
        File arch = new File(dir, "alertas.cola");
        String raro = "⚡ Corte\nlínea 2\tcon tab \\ barra";
        c = new ColaAlertas(arch, 200, reg);
        c.agregar(T0, raro); c.agregar(T0 + 1000, "segundo");
        ColaAlertas c2 = new ColaAlertas(arch, 200, reg);
        List<ColaAlertas.Mensaje> p = c2.pendientes();
        check(p.size() == 2 && p.get(0).texto.equals(raro) && p.get(0).ts == T0
                && p.get(1).texto.equals("segundo"), "2 pendientes identicos tras recargar");
        f = new Falso();
        c2.drenar(f, T0, UTC);
        check(new ColaAlertas(arch, 200, reg).tamano() == 0, "tras entregar, el archivo queda vacio");

        escenario("6) Tope: se descartan los mas viejos");
        logs.clear();
        c = new ColaAlertas(null, 3, reg);
        for (String s : List.of("1", "2", "3", "4", "5")) c.agregar(T0, s);
        List<String> textos = new ArrayList<>();
        for (ColaAlertas.Mensaje m : c.pendientes()) textos.add(m.texto);
        check(textos.equals(List.of("3", "4", "5")), "quedan 3,4,5 -> " + textos);
        check(logs.size() == 2, "2 avisos de descarte en el log");

        escenario("7) Error de configuracion: no pierde el mensaje, reintenta en 5 min");
        c = new ColaAlertas(null, 200, reg);
        c.agregar(T0, "A");
        f = new Falso();
        f.guion.add(Resultado.errorConfig("HTTP 400 chat not found"));
        check(c.drenar(f, T0, UTC) == 300000 && c.tamano() == 1, "espera 300000 y conserva A");

        escenario("8) Mensaje invalido: se descarta y sigue con el siguiente");
        c = new ColaAlertas(null, 200, reg);
        c.agregar(T0, "malo"); c.agregar(T0, "bueno");
        f = new Falso();
        f.guion.add(Resultado.descartar("message is too long"));
        check(c.drenar(f, T0, UTC) == 0 && f.enviados.equals(List.of("bueno")), "entrega 'bueno' -> " + f.enviados);

        escenario("9) Formato: hora normal, con retraso y de otro dia");
        ColaAlertas cf = new ColaAlertas(null, 200, reg);
        cf.agregar(T0, "X");
        String enHora = ColaAlertas.formatear(cf.pendientes().get(0), T0 + 30_000, UTC);
        String tarde = ColaAlertas.formatear(cf.pendientes().get(0), T0 + 5 * 60_000, UTC);
        String otroDia = ColaAlertas.formatear(cf.pendientes().get(0), T0 + 7 * 3600_000L, UTC);
        check(enHora.equals("X\n🕒 6:13 p. m."), "a tiempo -> " + enHora.replace("\n", "\\n"));
        check(tarde.equals("X\n🕒 Ocurrió a las 6:13 p. m. (aviso con retraso)"), "con retraso -> " + tarde.replace("\n", "\\n"));
        check(otroDia.equals("X\n🕒 Ocurrió a las 15/10 6:13 p. m. (aviso con retraso)"), "otro dia -> " + otroDia.replace("\n", "\\n"));
        long medianoche = T0 - (18 * 60 + 13) * 60_000L - 20_000L;   // 2026-10-15 00:00:00 UTC
        check(ColaAlertas.hora12(medianoche + 5 * 60_000L, UTC).equals("12:05 a. m."), "00:05 -> 12:05 a. m.");
        check(ColaAlertas.hora12(medianoche + 12 * 3600_000L, UTC).equals("12:00 p. m."), "12:00 -> 12:00 p. m.");
        check(ColaAlertas.hora12(medianoche + (9 * 60 + 7) * 60_000L, UTC).equals("9:07 a. m."), "09:07 -> 9:07 a. m.");
        check(ColaAlertas.hora12(medianoche + (23 * 60 + 59) * 60_000L, UTC).equals("11:59 p. m."), "23:59 -> 11:59 p. m.");

        escenario("10) Texto demasiado largo se recorta a 4000 caracteres");
        c = new ColaAlertas(null, 200, reg);
        c.agregar(T0, "x".repeat(5000));
        check(c.pendientes().get(0).texto.length() == 4000, "longitud 4000");

        transporte();

        System.out.println("\n" + (fallos == 0 ? "TODOS LOS ESCENARIOS OK" : fallos + " FALLO(S)"));
        System.exit(fallos == 0 ? 0 : 1);
    }

    // ---- TelegramTransporte contra un servidor HTTP local que imita la Bot API ----

    static volatile int respCode;
    static volatile String respBody;
    static volatile String ultimoPath, ultimoCuerpo;

    static void transporte() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            ultimoPath = ex.getRequestURI().getPath();
            try (InputStream in = ex.getRequestBody()) { ultimoCuerpo = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            byte[] b = respBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(respCode, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        String token = "123456:SECRETO-abc";
        List<String> tlog = new ArrayList<>();
        TelegramTransporte t = new TelegramTransporte(token, "-1001234567890", tlog::add, base);

        escenario("11) Transporte: 200 OK, ruta y cuerpo correctos");
        respCode = 200; respBody = "{\"ok\":true,\"result\":{}}";
        Resultado r = t.enviar("⚡ Corte eléctrico\nlínea 2");
        check(r.tipo == Resultado.Tipo.OK, "OK");
        check(ultimoPath.equals("/bot" + token + "/sendMessage"), "ruta /bot<token>/sendMessage");
        String cuerpo = URLDecoder.decode(ultimoCuerpo, "UTF-8");
        check(cuerpo.contains("chat_id=-1001234567890") && cuerpo.contains("text=⚡ Corte eléctrico\nlínea 2"),
                "chat_id y texto (UTF-8, multilinea) bien codificados");

        escenario("12) Transporte: 429 con retry_after");
        respCode = 429; respBody = "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 7\",\"parameters\":{\"retry_after\":7}}";
        r = t.enviar("x");
        check(r.tipo == Resultado.Tipo.REINTENTAR && r.esperaMs == 7000, "REINTENTAR 7000 ms -> " + r.tipo + " " + r.esperaMs);

        escenario("13) Transporte: 400 chat not found = error de configuracion (sin filtrar el token)");
        respCode = 400; respBody = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}";
        r = t.enviar("x");
        check(r.tipo == Resultado.Tipo.ERROR_CONFIG && r.detalle.contains("chat not found") && !r.detalle.contains("SECRETO"),
                "ERROR_CONFIG -> " + r.detalle);

        escenario("14) Transporte: 400 message is too long = descartar");
        respCode = 400; respBody = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is too long\"}";
        check(t.enviar("x").tipo == Resultado.Tipo.DESCARTAR, "DESCARTAR");

        escenario("15) Transporte: 502 = reintentable");
        respCode = 502; respBody = "Bad Gateway";
        check(t.enviar("x").tipo == Resultado.Tipo.REINTENTAR, "REINTENTAR");

        escenario("16) Transporte: grupo migrado a supergrupo -> avisa el nuevo chat_id");
        respCode = 400; respBody = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: group chat was upgraded to a supergroup chat\",\"parameters\":{\"migrate_to_chat_id\":-1009876543210}}";
        r = t.enviar("x");
        check(r.tipo == Resultado.Tipo.ERROR_CONFIG && tlog.stream().anyMatch(s -> s.contains("-1009876543210")),
                "ERROR_CONFIG y log con el nuevo chat_id");

        srv.stop(0);

        escenario("17) Transporte: servidor caido = reintentable, sin filtrar el token");
        r = t.enviar("x");
        check(r.tipo == Resultado.Tipo.REINTENTAR && !String.valueOf(r.detalle).contains("SECRETO"),
                "REINTENTAR -> " + r.detalle);
    }
}
