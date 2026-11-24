package com.kgranados.urano;

/**
 * Determina si Urano responde en red usando el binario ping del sistema
 * (toybox, presente en Fire OS). InetAddress.isReachable es poco fiable sin root.
 */
public class Pinger {

    /** true si la IP respondio al ping. */
    public static boolean isUp(String ip) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
                    .redirectErrorStream(true)
                    .start();
            // drenar salida para que no se bloquee en buffers llenos
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            while (in.read(buf) > 0) { /* descartar */ }
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroy(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            android.util.Log.e("UranoPinger", "ping fallo", e);
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
