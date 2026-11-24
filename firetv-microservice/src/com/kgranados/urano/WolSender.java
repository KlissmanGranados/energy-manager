package com.kgranados.urano;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Envia un paquete magico Wake-on-LAN por broadcast UDP. */
public class WolSender {

    public static void wake(String mac, String broadcast, int port) throws Exception {
        byte[] macBytes = parseMac(mac);
        byte[] packet = new byte[6 + 16 * 6];
        // 6 bytes 0xFF
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        // 16 repeticiones de la MAC
        for (int i = 6; i < packet.length; i += 6) {
            System.arraycopy(macBytes, 0, packet, i, 6);
        }
        InetAddress addr = InetAddress.getByName(broadcast);
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(packet, packet.length, addr, port));
            // enviar un par de veces por si acaso se pierde el UDP
            socket.send(new DatagramPacket(packet, packet.length, addr, port));
        } finally {
            socket.close();
        }
    }

    private static byte[] parseMac(String mac) {
        String[] parts = mac.split("[:\\-]");
        if (parts.length != 6) throw new IllegalArgumentException("MAC invalida: " + mac);
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) {
            b[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return b;
    }
}
