package com.kgranados.urano;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Configuracion de Urano, leida desde el almacenamiento interno de la app:
 *   filesDir/urano.json   (parametros)
 *   filesDir/id_urano     (llave privada SSH)
 *
 * Ambos se empujan con build.sh (adb push + run-as). Nunca van en el repo.
 */
public class Config {
    public String ip = "192.168.0.4";
    public String mac = "00:00:00:00:00:00";
    public String broadcast = "192.168.0.255";
    public int wolPort = 9;

    public String sshUser = "urano";
    public int sshPort = 22;
    public String shutdownCmd = "sudo /usr/sbin/poweroff";

    public String token = "";               // vacio = sin auth

    public int encenderTimeoutSec = 120;    // espera max a que Urano responda ping
    public int apagarTimeoutSec = 90;       // espera max a que Urano deje de responder
    public int pollStepSec = 3;             // cada cuanto re-sondea durante una transicion

    // IPs cuyo ping indica si hay servicio electrico (si alguna responde -> hay luz)
    public String[] electricIps = {"192.168.2.12", "192.168.2.13"};

    // IP en 110 V que debe responder ping para encender Urano (vacio = sin chequeo).
    // No cuenta para decidir si hay luz: eso lo deciden solo las electricIps (220 V).
    public String ip110v = "";

    // Automatizacion ante cortes de luz (ver PoliticaCorte)
    public boolean autoApagar = true;         // apagar Urano tras un corte largo
    public boolean autoEncender = true;       // encenderlo al volver la luz tras ese corte
    public int corteApagarSeg = 600;          // corte continuo para apagar Urano (10 min)
    public int restablecerEncenderSeg = 300;  // luz estable para volver a encenderlo (5 min)
    public int autoCheckSeg = 15;             // cada cuanto se muestrea la luz
    public int encenderReintentos = 3;        // intentos de encendido si el WoL falla
    public int encenderReintentoSeg = 300;    // separacion entre intentos de encendido
    public boolean autoDryRun = false;        // true: solo registra lo que haria (pruebas)

    // Alertas por Telegram (Bot API). Sin token o sin chat_id, las alertas quedan desactivadas.
    public boolean alertas = true;
    public String telegramBotToken = "";
    public String telegramChatId = "";       // grupo: numero negativo (-100... si es supergrupo)

    public String keyPath;                  // ruta a la llave privada (filesDir/id_urano)

    public static Config load(Context ctx) {
        Config c = new Config();
        c.keyPath = new File(ctx.getFilesDir(), "id_urano").getAbsolutePath();
        try {
            File f = new File(ctx.getFilesDir(), "urano.json");
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                JSONObject j = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                c.ip = j.optString("ip", c.ip);
                c.mac = j.optString("mac", c.mac);
                c.broadcast = j.optString("broadcast", c.broadcast);
                c.wolPort = j.optInt("wolPort", c.wolPort);
                c.sshUser = j.optString("sshUser", c.sshUser);
                c.sshPort = j.optInt("sshPort", c.sshPort);
                c.shutdownCmd = j.optString("shutdownCmd", c.shutdownCmd);
                c.token = j.optString("token", c.token);
                c.encenderTimeoutSec = j.optInt("encenderTimeoutSec", c.encenderTimeoutSec);
                c.apagarTimeoutSec = j.optInt("apagarTimeoutSec", c.apagarTimeoutSec);
                c.pollStepSec = j.optInt("pollStepSec", c.pollStepSec);
                // "autoCorte" (version anterior) da el valor por defecto de ambos
                boolean autoCorte = j.optBoolean("autoCorte", true);
                c.autoApagar = j.optBoolean("autoApagar", autoCorte);
                c.autoEncender = j.optBoolean("autoEncender", autoCorte);
                c.corteApagarSeg = j.optInt("corteApagarSeg", c.corteApagarSeg);
                c.restablecerEncenderSeg = j.optInt("restablecerEncenderSeg", c.restablecerEncenderSeg);
                c.autoCheckSeg = Math.max(5, j.optInt("autoCheckSeg", c.autoCheckSeg));
                c.encenderReintentos = j.optInt("encenderReintentos", c.encenderReintentos);
                c.encenderReintentoSeg = j.optInt("encenderReintentoSeg", c.encenderReintentoSeg);
                c.autoDryRun = j.optBoolean("autoDryRun", c.autoDryRun);
                c.alertas = j.optBoolean("alertas", c.alertas);
                c.telegramBotToken = j.optString("telegramBotToken", c.telegramBotToken).trim();
                c.telegramChatId = j.optString("telegramChatId", c.telegramChatId).trim();
                c.ip110v = j.optString("ip110v", c.ip110v).trim();
                org.json.JSONArray arr = j.optJSONArray("electricIps");
                if (arr != null && arr.length() > 0) {
                    String[] a = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) a[i] = arr.optString(i);
                    c.electricIps = a;
                }
            }
        } catch (Exception e) {
            android.util.Log.e("UranoConfig", "No se pudo leer urano.json, usando defaults", e);
        }
        return c;
    }
}
