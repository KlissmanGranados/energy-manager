package com.kgranados.urano;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.util.Properties;

/** Cliente SSH (JSch) para apagar Urano con autenticacion por llave. */
public class SshClient {

    /**
     * Abre sesion con la llave privada, ejecuta el comando de apagado y cierra.
     * No espera "exito" del comando (el equipo se apaga y corta la conexion).
     */
    public static void shutdown(Config cfg) throws Exception {
        JSch jsch = new JSch();
        jsch.addIdentity(cfg.keyPath);

        Session session = jsch.getSession(cfg.sshUser, cfg.ip, cfg.sshPort);
        Properties props = new Properties();
        // LAN domestica, equipo propio: no verificamos host key
        props.put("StrictHostKeyChecking", "no");
        session.setConfig(props);
        session.setConfig("PreferredAuthentications", "publickey");
        session.setTimeout(10000);
        session.connect(10000);

        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        try {
            ch.setCommand(cfg.shutdownCmd);
            ch.setInputStream(null);
            ch.setErrStream(System.err);
            InputStream in = ch.getInputStream();
            ch.connect();
            // Comando enviado. Drenar brevemente; la conexion caera cuando el equipo se apague,
            // asi que un error a partir de aqui es lo esperado y no es un fallo del apagado
            // (el resultado real lo decide PowerController con ping).
            try {
                byte[] buf = new byte[256];
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    while (in.available() > 0) { if (in.read(buf) < 0) break; }
                    if (ch.isClosed()) break;
                    Thread.sleep(200);
                }
            } catch (Exception ignoradaPorqueElEquipoSeApaga) {
                // conexion cortada por el apagado
            }
        } finally {
            if (ch.isConnected()) ch.disconnect();
            if (session.isConnected()) session.disconnect();
        }
    }
}
