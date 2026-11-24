package com.kgranados.urano;

import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP minimo (HTTP/1.1, Connection: close) para:
 *   GET  /                    -> UI (index.html de assets)
 *   GET  /api/urano/status    -> APAGANDO|ABAJO|ARRIBA|ENCENDIENDO
 *   POST /api/urano/energy/   -> {"accion":"encender|apagar"}
 *   GET  /power_dashboard     -> historial de cortes (power_dashboard.html de assets)
 *   GET  /api/power_history   -> eventos paginados (ver PowerHistoryQuery)
 *   GET  /api/power_history/summary -> KPIs del historial
 *   GET  /api/power_history/outages -> cortes agrupados (OUTAGE + BACK_ON) con duracion
 *
 * Escucha en 0.0.0.0:puerto. Sin dependencias externas.
 */
public class TinyHttpServer {

    private static final String TAG = "UranoHttp";

    private final int port;
    private final PowerController controller;
    private final AutoCorteMonitor auto;
    private final byte[] indexHtml;
    private final byte[] dashboardHtml;
    private final PowerHistoryDb historial;
    private final String token;

    private ServerSocket server;
    private volatile boolean running = false;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private Thread acceptThread;

    public TinyHttpServer(int port, PowerController controller, AutoCorteMonitor auto,
                          byte[] indexHtml, byte[] dashboardHtml, PowerHistoryDb historial,
                          String token) {
        this.port = port;
        this.controller = controller;
        this.auto = auto;
        this.indexHtml = indexHtml;
        this.dashboardHtml = dashboardHtml;
        this.historial = historial;
        this.token = token == null ? "" : token;
    }

    public void start() throws Exception {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("0.0.0.0", port));
        running = true;
        acceptThread = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "urano-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Escuchando en 0.0.0.0:" + port);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.submit(new Runnable() { public void run() { handle(s); } });
            } catch (Exception e) {
                if (running) Log.e(TAG, "accept fallo", e);
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream rawIn = s.getInputStream();
            // leer cabeceras linea por linea (ASCII)
            StringBuilder head = new StringBuilder();
            int prev = -1, cur;
            int blankCount = 0;
            while ((cur = rawIn.read()) != -1) {
                head.append((char) cur);
                if (cur == '\n') {
                    // fin de cabeceras tras \r\n\r\n
                    if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
                }
                if (head.length() > 16384) break; // proteccion
            }
            String[] lines = head.toString().split("\r\n");
            if (lines.length == 0 || lines[0].isEmpty()) { s.close(); return; }
            String[] req = lines[0].split(" ");
            if (req.length < 2) { s.close(); return; }
            String method = req[0];
            String path = req[1];
            String query = "";
            int qpos = path.indexOf('?');
            if (qpos >= 0) {
                query = path.substring(qpos + 1);
                path = path.substring(0, qpos);
            }

            int contentLength = 0;
            String reqToken = null;
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i];
                int c = l.indexOf(':');
                if (c < 0) continue;
                String k = l.substring(0, c).trim().toLowerCase();
                String v = l.substring(c + 1).trim();
                if (k.equals("content-length")) { try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {} }
                else if (k.equals("x-token")) reqToken = v;
            }

            // leer body si lo hay
            String body = "";
            if (contentLength > 0 && contentLength < 1_000_000) {
                byte[] buf = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = rawIn.read(buf, off, contentLength - off);
                    if (n < 0) break;
                    off += n;
                }
                body = new String(buf, 0, off, StandardCharsets.UTF_8);
            }

            route(s, method, path, parseQuery(query), reqToken, body);
        } catch (Exception e) {
            Log.e(TAG, "handle fallo", e);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** "a=1&b=x%20y" -> {a: "1", b: "x y"} (si se repite una clave, gana la ultima) */
    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.isEmpty()) return m;
        for (String par : q.split("&")) {
            if (par.isEmpty()) continue;
            int eq = par.indexOf('=');
            String k = eq >= 0 ? par.substring(0, eq) : par;
            String v = eq >= 0 ? par.substring(eq + 1) : "";
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                // escape % mal formado: se ignora el parametro
            }
        }
        return m;
    }

    private void route(Socket s, String method, String path, Map<String, String> query,
                       String reqToken, String body) throws Exception {
        OutputStream out = s.getOutputStream();

        // UI sin token
        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            send(out, 200, "text/html; charset=utf-8", indexHtml);
            return;
        }
        if (method.equals("GET") && dashboardHtml != null
                && (path.equals("/power_dashboard") || path.equals("/power_dashboard.html"))) {
            send(out, 200, "text/html; charset=utf-8", dashboardHtml);
            return;
        }

        boolean isApi = path.startsWith("/api/");
        if (isApi && !token.isEmpty() && !token.equals(reqToken)) {
            sendText(out, 401, "text/plain", "NO AUTORIZADO");
            return;
        }

        if (method.equals("GET") && path.equals("/api/urano/status")) {
            sendText(out, 200, "text/plain", controller.status().name());
            return;
        }

        if (method.equals("GET") && path.equals("/api/service-electric")) {
            sendText(out, 200, "text/plain", controller.hayServicioElectrico() ? "true" : "false");
            return;
        }

        // automatico por cortes: GET consulta, POST {"apagar": bool, "encender": bool} cambia
        // uno o ambos (idempotente); {"activo": bool} (version anterior) cambia los dos
        if (path.equals("/api/auto") && auto != null && (method.equals("GET") || method.equals("POST"))) {
            if (method.equals("POST")) {
                Boolean ap = null, en = null;
                try {
                    JSONObject j = new JSONObject(body);
                    if (j.has("activo")) ap = en = j.getBoolean("activo");
                    if (j.has("apagar")) ap = j.getBoolean("apagar");
                    if (j.has("encender")) en = j.getBoolean("encender");
                } catch (Exception ignored) {}
                if (ap == null && en == null) {
                    JSONObject err = new JSONObject();
                    err.put("error", "se espera {\"apagar\": true|false, \"encender\": true|false}");
                    send(out, 400, "application/json; charset=utf-8", err.toString().getBytes(StandardCharsets.UTF_8));
                    return;
                }
                auto.setModo(ap, en);
            }
            JSONObject r = new JSONObject();
            r.put("apagar", auto.isApagar());
            r.put("encender", auto.isEncender());
            r.put("activo", auto.isApagar() || auto.isEncender());
            r.put("corteApagarSeg", auto.getCorteApagarSeg());
            r.put("restablecerEncenderSeg", auto.getRestablecerEncenderSeg());
            send(out, 200, "application/json; charset=utf-8", r.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        // historial de cortes: GET /api/power_history?page_size&page&type_id&date_start&date_end&sort
        if (method.equals("GET") && historial != null
                && (path.equals("/api/power_history") || path.equals("/api/power_history/summary")
                    || path.equals("/api/power_history/outages"))) {
            PowerHistoryQuery q;
            try {
                q = PowerHistoryQuery.parse(query);
            } catch (IllegalArgumentException e) {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                send(out, 400, "application/json; charset=utf-8", err.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            JSONObject r = path.endsWith("/summary") ? historial.resumen(q)
                    : path.endsWith("/outages") ? historial.cortes(q) : historial.consultar(q);
            send(out, 200, "application/json; charset=utf-8", r.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        // aceptar con o sin barra final
        if (method.equals("POST") && (path.equals("/api/urano/energy/") || path.equals("/api/urano/energy"))) {
            String accion = "";
            try { accion = new JSONObject(body).optString("accion", ""); } catch (Exception ignored) {}
            PowerController.Resultado r = controller.energy(accion, PowerController.ORIGEN_MANUAL);
            JSONObject resp = new JSONObject();
            resp.put("resultado", r.name());
            resp.put("estado", controller.status().name());
            int code;
            switch (r) {
                case OK:            code = 202; break; // aceptado, transicion en curso
                case NOOP:          code = 200; break; // ya estaba en ese estado
                case BLOQUEADO:     code = 409; break; // hay otra operacion en curso
                case SIN_CORRIENTE: code = 409; break; // no hay servicio electrico
                case SIN_110V:      code = 409; break; // hay 220 V pero no responde el 110 V
                default:            code = 400; break; // accion invalida
            }
            send(out, code, "application/json; charset=utf-8", resp.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        sendText(out, 404, "text/plain", "NO ENCONTRADO");
    }

    private void sendText(OutputStream out, int code, String ctype, String text) throws Exception {
        send(out, code, ctype, text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(OutputStream out, int code, String ctype, byte[] payload) throws Exception {
        String status = statusLine(code);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append("\r\n");
        h.append("Content-Type: ").append(ctype).append("\r\n");
        h.append("Content-Length: ").append(payload.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: X-Token, Content-Type\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private String statusLine(int code) {
        switch (code) {
            case 200: return "200 OK";
            case 202: return "202 Accepted";
            case 400: return "400 Bad Request";
            case 401: return "401 Unauthorized";
            case 404: return "404 Not Found";
            case 409: return "409 Conflict";
            default:  return code + " Status";
        }
    }
}
