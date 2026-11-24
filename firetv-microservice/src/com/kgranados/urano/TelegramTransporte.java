package com.kgranados.urano;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Envio de alertas a un grupo de Telegram via Bot API (sendMessage).
 * Java puro (sin Android) para probarlo en PC contra un servidor simulado.
 * El token del bot nunca aparece en logs ni en detalles de error.
 */
public class TelegramTransporte implements ColaAlertas.Transporte {

    private static final Pattern DESCRIPCION = Pattern.compile("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");
    private static final Pattern MIGRATE_TO = Pattern.compile("\"migrate_to_chat_id\"\\s*:\\s*(-?\\d+)");

    private final String token;
    private final String chatId;
    private final ColaAlertas.Registro reg;
    private final String base;

    public TelegramTransporte(String token, String chatId, ColaAlertas.Registro reg) {
        this(token, chatId, reg, "https://api.telegram.org");
    }

    /** base configurable para pruebas (servidor HTTP local). */
    public TelegramTransporte(String token, String chatId, ColaAlertas.Registro reg, String base) {
        this.token = token;
        this.chatId = chatId;
        this.reg = reg;
        this.base = base;
    }

    @Override
    public ColaAlertas.Resultado enviar(String texto) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/bot" + token + "/sendMessage").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] cuerpo = ("chat_id=" + enc(chatId) + "&text=" + enc(texto)
                    + "&disable_web_page_preview=true").getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(cuerpo.length);
            OutputStream os = c.getOutputStream();
            os.write(cuerpo);
            os.close();

            int code = c.getResponseCode();
            String resp = leer(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 200) return ColaAlertas.Resultado.ok();

            String desc = extraer(DESCRIPCION, resp);
            String detalle = "HTTP " + code + (desc != null ? " " + desc : "");
            if (code == 429) {
                String ra = extraer(RETRY_AFTER, resp);
                long espera = ra != null ? Long.parseLong(ra) * 1000L : 0;
                return ColaAlertas.Resultado.reintentar(espera, detalle);
            }
            if (code >= 500) return ColaAlertas.Resultado.reintentar(0, detalle);

            String nuevoChat = extraer(MIGRATE_TO, resp);
            if (nuevoChat != null) {
                reg.log("El grupo de Telegram ahora es supergrupo: actualiza telegramChatId a " + nuevoChat);
            }
            if (code == 400 && desc != null) {
                String d = desc.toLowerCase();
                // problemas del mensaje concreto (no de la configuracion): no bloquear la cola
                if (d.contains("message is too long") || d.contains("text must be non-empty")
                        || d.contains("can't parse")) {
                    return ColaAlertas.Resultado.descartar(detalle);
                }
            }
            return ColaAlertas.Resultado.errorConfig(detalle);
        } catch (Exception e) {
            return ColaAlertas.Resultado.reintentar(0, sinToken(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String sinToken(String s) {
        return (s == null || token.isEmpty()) ? s : s.replace(token, "***");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String extraer(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String leer(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0 && bos.size() < 65536) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
