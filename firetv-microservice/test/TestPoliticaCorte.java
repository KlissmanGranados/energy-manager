import com.kgranados.urano.PoliticaCorte;

import java.util.ArrayList;
import java.util.List;

/**
 * Prueba de PoliticaCorte con reloj simulado (ticks de 15 s, como el monitor real).
 * Simula a Urano: transiciones de apagado/encendido, SSH o WoL fallidos y encendidos manuales.
 * Ejecutar con:  ./build.sh test
 */
public class TestPoliticaCorte {

    static final long S = 1000, M = 60 * S, TICK = 15 * S;
    static int fallos = 0;

    interface Luz { boolean en(long t); }

    /** Luz presente salvo en los intervalos [ini, fin) dados (en ms). */
    static Luz cortes(long... intervalos) {
        return t -> {
            for (int i = 0; i < intervalos.length; i += 2)
                if (t >= intervalos[i] && t < intervalos[i + 1]) return false;
            return true;
        };
    }

    static String mmss(long t) {
        long s = t / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    static class Sim {
        long apagadoDura = 30 * S;   // APAGANDO -> ABAJO
        boolean sshOk = true;        // si falla, vuelve a ARRIBA enseguida
        long encendidoDura = 60 * S; // ENCENDIENDO -> ARRIBA
        boolean wolOk = true;        // si falla, vuelve a ABAJO tras el timeout de 120 s
        Long manualEncenderEn = null;
        Long caeEn = null;            // Urano se apaga solo en ese instante (p. ej. UPS agotado)
        long desIni = -1, desFin = -1; // automatizacion desactivada en [desIni, desFin)
        boolean activoAplicado = true;
        boolean apagar = true, encender = true; // interruptores independientes
        Luz v110 = t -> true;          // 110 V presente (solo condiciona el encendido)
        int esperasIniciadas = 0;
        int rendidos = 0;
        String estado = "ARRIBA";
        long finTransicion = -1;
        String destino;
        final List<String> eventos = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        PoliticaCorte p;

        Sim(boolean encenderPendienteInicial) {
            p = new PoliticaCorte(10 * M, 5 * M, 60 * S, 5 * M, 3, encenderPendienteInicial,
                    msg -> logs.add(msg));
        }

        Sim run(Luz luz, long hasta) {
            p.configurar(apagar, encender);
            for (long t = 0; t <= hasta; t += TICK) {
                if (finTransicion >= 0 && t >= finTransicion) {
                    estado = destino;
                    finTransicion = -1;
                    eventos.add(mmss(t) + " " + estado);
                }
                if (caeEn != null && t >= caeEn) {
                    estado = "ABAJO";
                    finTransicion = -1;
                    eventos.add(mmss(t) + " ABAJO(cae solo)");
                    caeEn = null;
                }
                if (manualEncenderEn != null && t >= manualEncenderEn && estado.equals("ABAJO")) {
                    estado = "ARRIBA";
                    eventos.add(mmss(t) + " ARRIBA(manual)");
                    manualEncenderEn = null;
                }
                // igual que AutoCorteMonitor: al cambiar el modo se reinicia la politica;
                // desactivada no evalua nada (control manual)
                boolean activo = !(t >= desIni && t < desFin);
                if (activo != activoAplicado) {
                    p.reiniciar();
                    activoAplicado = activo;
                    eventos.add(mmss(t) + (activo ? " AUTO-ON" : " AUTO-OFF"));
                }
                if (!activo) continue;
                PoliticaCorte.Accion a = p.evaluar(t, luz.en(t), v110.en(t),
                        estado.equals("ARRIBA"), estado.equals("ABAJO"));
                if (p.consumirRendido()) rendidos++;
                if (p.consumirEsperaIniciada()) { esperasIniciadas++; eventos.add(mmss(t) + " ESPERA"); }
                if (a == PoliticaCorte.Accion.APAGAR) {
                    eventos.add(mmss(t) + " APAGAR");
                    estado = "APAGANDO";
                    destino = sshOk ? "ABAJO" : "ARRIBA";
                    finTransicion = t + (sshOk ? apagadoDura : 1);
                } else if (a == PoliticaCorte.Accion.ENCENDER) {
                    eventos.add(mmss(t) + " ENCENDER");
                    estado = "ENCENDIENDO";
                    destino = wolOk ? "ARRIBA" : "ABAJO";
                    finTransicion = t + (wolOk ? encendidoDura : 120 * S);
                }
            }
            return this;
        }

        List<String> acciones(String tipo) {
            List<String> r = new ArrayList<>();
            for (String e : eventos) if (e.endsWith(" " + tipo)) r.add(e.substring(0, 5));
            return r;
        }
    }

    static void check(String escenario, boolean ok, String detalle, Sim sim) {
        if (ok) {
            System.out.println("  OK   " + detalle);
        } else {
            fallos++;
            System.out.println("  FALLO " + detalle);
            System.out.println("        eventos: " + sim.eventos);
            System.out.println("        logs:    " + sim.logs);
        }
    }

    static void escenario(String nombre) { System.out.println("\n== " + nombre); }

    public static void main(String[] args) {
        Sim s;

        escenario("1) Corte corto (5 min): no se apaga");
        s = new Sim(false).run(cortes(1 * M, 6 * M), 30 * M);
        check("1", s.acciones("APAGAR").isEmpty(), "sin APAGAR", s);
        check("1", s.estado.equals("ARRIBA"), "Urano sigue ARRIBA", s);

        escenario("2) Corte largo (20 min): apaga a los 10, enciende 5 min tras volver la luz");
        s = new Sim(false).run(cortes(1 * M, 21 * M), 40 * M);
        check("2", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR solo a las 11:00 -> " + s.acciones("APAGAR"), s);
        check("2", s.acciones("ENCENDER").equals(List.of("26:00")), "ENCENDER solo a las 26:00 -> " + s.acciones("ENCENDER"), s);
        check("2", s.estado.equals("ARRIBA") && !s.p.isEncenderPendiente(), "termina ARRIBA y ciclo cerrado", s);

        escenario("3) Luz inestable al volver: la espera de 5 min se reinicia");
        s = new Sim(false).run(cortes(1 * M, 21 * M, 24 * M, 24 * M + 20 * S), 45 * M);
        check("3", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR a las 11:00 -> " + s.acciones("APAGAR"), s);
        check("3", s.acciones("ENCENDER").equals(List.of("29:30")), "ENCENDER a las 29:30 (5 min tras la luz estable) -> " + s.acciones("ENCENDER"), s);

        escenario("4) Urano apagado a mano antes de un corte largo: se enciende al volver la luz");
        s = new Sim(false);
        s.estado = "ABAJO";
        s.run(cortes(1 * M, 21 * M), 40 * M);
        check("4", s.acciones("APAGAR").isEmpty(), "sin APAGAR (ya estaba ABAJO)", s);
        check("4", s.acciones("ENCENDER").equals(List.of("26:00")), "ENCENDER a las 26:00 -> " + s.acciones("ENCENDER"), s);
        check("4", s.estado.equals("ARRIBA") && !s.p.isEncenderPendiente(), "termina ARRIBA y ciclo cerrado", s);

        escenario("5) La luz vuelve mientras se esta apagando");
        s = new Sim(false);
        s.apagadoDura = 120 * S;
        s.run(cortes(1 * M, 11 * M + 40 * S), 30 * M);
        check("5", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR a las 11:00 -> " + s.acciones("APAGAR"), s);
        check("5", s.acciones("ENCENDER").equals(List.of("16:45")), "ENCENDER a las 16:45 (luz desde 11:45) -> " + s.acciones("ENCENDER"), s);
        check("5", s.estado.equals("ARRIBA"), "termina ARRIBA", s);

        escenario("6) Wake-on-LAN falla: 3 intentos cada 5 min y se rinde");
        s = new Sim(false);
        s.wolOk = false;
        s.run(cortes(1 * M, 21 * M), 70 * M);
        check("6", s.acciones("ENCENDER").equals(List.of("26:00", "31:00", "36:00")), "ENCENDER 26:00, 31:00, 36:00 -> " + s.acciones("ENCENDER"), s);
        check("6", !s.p.isEncenderPendiente(), "ciclo cerrado tras rendirse", s);
        check("6", s.rendidos == 1, "evento 'se rindio' exactamente 1 vez -> " + s.rendidos, s);

        escenario("7) Lo enciendes a mano durante la espera: la politica no hace nada mas");
        s = new Sim(false);
        s.manualEncenderEn = 23 * M;
        s.run(cortes(1 * M, 21 * M), 40 * M);
        check("7", s.acciones("ENCENDER").isEmpty(), "sin ENCENDER automatico", s);
        check("7", s.estado.equals("ARRIBA") && !s.p.isEncenderPendiente(), "ARRIBA y ciclo cerrado", s);

        escenario("8) SSH falla al apagar: reintenta cada 60 s mientras dure el corte");
        s = new Sim(false);
        s.sshOk = false;
        s.run(cortes(1 * M, 21 * M), 30 * M);
        check("8", s.acciones("APAGAR").size() == 10 && s.acciones("APAGAR").get(0).equals("11:00")
                && s.acciones("APAGAR").get(9).equals("20:00"), "10 intentos de 11:00 a 20:00 -> " + s.acciones("APAGAR"), s);
        check("8", s.acciones("ENCENDER").isEmpty() && !s.p.isEncenderPendiente(), "sin ENCENDER (Urano nunca bajo) y ciclo cerrado", s);

        escenario("9) Reinicio del stick con el encendido pendiente guardado: enciende 5 min despues");
        s = new Sim(true);
        s.estado = "ABAJO";
        s.run(t -> true, 15 * M);
        check("9", s.acciones("ENCENDER").equals(List.of("05:00")), "ENCENDER a las 05:00 -> " + s.acciones("ENCENDER"), s);

        escenario("10) Limite exacto de 10 min");
        s = new Sim(false).run(cortes(1 * M, 11 * M), 20 * M);
        check("10", s.acciones("APAGAR").isEmpty(), "corte de 9m45s observados: sin APAGAR", s);
        s = new Sim(false).run(cortes(1 * M, 11 * M + 15 * S), 20 * M);
        check("10", s.acciones("APAGAR").equals(List.of("11:00")), "corte de 10m00s observados: APAGAR a las 11:00 -> " + s.acciones("APAGAR"), s);

        escenario("11) Se desactiva antes de cumplir 10 min de corte: nada automatico");
        s = new Sim(false);
        s.desIni = 5 * M; s.desFin = 40 * M;
        s.run(cortes(1 * M, 21 * M), 45 * M);
        check("11", s.acciones("APAGAR").isEmpty() && s.acciones("ENCENDER").isEmpty(), "sin APAGAR ni ENCENDER", s);
        check("11", s.estado.equals("ARRIBA"), "Urano sigue ARRIBA", s);

        escenario("12) Se desactiva despues del apagado automatico: queda manual (no enciende)");
        s = new Sim(false);
        s.desIni = 15 * M; s.desFin = 60 * M;
        s.run(cortes(1 * M, 21 * M), 60 * M);
        check("12", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR a las 11:00 -> " + s.acciones("APAGAR"), s);
        check("12", s.acciones("ENCENDER").isEmpty() && s.estado.equals("ABAJO"), "sin ENCENDER: sigue ABAJO", s);
        check("12", !s.p.isEncenderPendiente(), "pendiente olvidado al desactivar", s);

        escenario("13) Se activa en mitad de un corte: los 10 min cuentan desde la activacion");
        s = new Sim(false);
        s.desIni = 0; s.desFin = 5 * M;
        s.run(cortes(1 * M, 30 * M), 40 * M);
        check("13", s.acciones("APAGAR").equals(List.of("15:00")), "APAGAR a las 15:00 (no 11:00) -> " + s.acciones("APAGAR"), s);

        escenario("14) Desactivar y reactivar tras un apagado automatico: no reenciende solo");
        s = new Sim(false);
        s.desIni = 15 * M; s.desFin = 25 * M;
        s.run(cortes(1 * M, 21 * M), 45 * M);
        check("14", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR a las 11:00 -> " + s.acciones("APAGAR"), s);
        check("14", s.acciones("ENCENDER").isEmpty() && s.estado.equals("ABAJO"), "sin ENCENDER al reactivar: sigue ABAJO", s);

        escenario("15) Corte corto con Urano apagado a mano: no se enciende");
        s = new Sim(false);
        s.estado = "ABAJO";
        s.run(cortes(1 * M, 6 * M), 30 * M);
        check("15", s.acciones("ENCENDER").isEmpty() && s.estado.equals("ABAJO"), "sin ENCENDER: sigue ABAJO", s);

        escenario("16) Urano se cae solo durante un corte largo (UPS agotado, SSH fallando): se enciende al volver la luz");
        s = new Sim(false);
        s.sshOk = false;
        s.caeEn = 15 * M;
        s.run(cortes(1 * M, 31 * M), 50 * M);
        check("16", s.acciones("ENCENDER").equals(List.of("36:00")), "ENCENDER a las 36:00 -> " + s.acciones("ENCENDER"), s);
        check("16", s.estado.equals("ARRIBA"), "termina ARRIBA", s);

        // reinicio del servicio: t=0 es el arranque (elapsedRealtime chico tras un reboot) y el
        // inicio sale de la fecha del historial, asi que queda en el pasado (negativo)
        escenario("17) Reinicio en pleno corte que empezo hace 7 min: APAGAR a los 3 min, no a los 10");
        s = new Sim(false);
        s.p.restaurar(false, -7 * M);
        s.run(cortes(0, 30 * M), 30 * M);
        check("17", s.acciones("APAGAR").equals(List.of("03:00")), "APAGAR a las 03:00 -> " + s.acciones("APAGAR"), s);

        escenario("18) Reinicio en pleno corte que empezo hace 12 min: APAGAR en la primera muestra");
        s = new Sim(false);
        s.p.restaurar(false, -12 * M);
        s.run(cortes(0, 30 * M), 30 * M);
        check("18", s.acciones("APAGAR").equals(List.of("00:00")), "APAGAR a las 00:00 -> " + s.acciones("APAGAR"), s);

        escenario("19) Reinicio con luz desde hace 4 min y encendido pendiente: ENCENDER al minuto, no a los 5");
        s = new Sim(true);
        s.estado = "ABAJO";
        s.p.restaurar(true, -4 * M);
        s.run(cortes(), 20 * M);
        check("19", s.acciones("ENCENDER").equals(List.of("01:00")), "ENCENDER a las 01:00 -> " + s.acciones("ENCENDER"), s);
        check("19", s.estado.equals("ARRIBA") && !s.p.isEncenderPendiente(), "termina ARRIBA, ciclo cerrado", s);

        // 110 V: solo condiciona el encendido; los 220 V (luz) siguen decidiendo el corte
        escenario("20) El 110 V vuelve 3 min despues de los 220 V: la espera de 5 min empieza ahi");
        s = new Sim(false);
        s.v110 = cortes(1 * M, 24 * M);
        s.run(cortes(1 * M, 21 * M), 45 * M);
        check("20", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR a las 11:00 (el 110 V no cambia el corte)", s);
        check("20", s.acciones("ESPERA").equals(List.of("24:00")), "espera iniciada una vez, a las 24:00 -> " + s.acciones("ESPERA"), s);
        check("20", s.acciones("ENCENDER").equals(List.of("29:00")), "ENCENDER a las 29:00 (no 26:00) -> " + s.acciones("ENCENDER"), s);

        escenario("21) El 110 V se cae a mitad de la espera: se reinicia");
        s = new Sim(false);
        s.v110 = cortes(1 * M, 21 * M, 23 * M, 24 * M);
        s.run(cortes(1 * M, 21 * M), 45 * M);
        check("21", s.acciones("ESPERA").equals(List.of("21:00", "24:00")), "espera a las 21:00 y de nuevo a las 24:00 -> " + s.acciones("ESPERA"), s);
        check("21", s.acciones("ENCENDER").equals(List.of("29:00")), "ENCENDER a las 29:00 -> " + s.acciones("ENCENDER"), s);

        escenario("22) El 110 V nunca vuelve: no se enciende ni se gastan intentos");
        s = new Sim(false);
        s.v110 = cortes(1 * M, 120 * M);
        s.run(cortes(1 * M, 21 * M), 90 * M);
        check("22", s.acciones("ENCENDER").isEmpty() && s.estado.equals("ABAJO"), "sin ENCENDER: sigue ABAJO", s);
        check("22", s.p.isEncenderPendiente() && s.rendidos == 0, "sigue pendiente, sin rendirse", s);

        escenario("23) Solo apagado automatico: apaga a los 10 min y no reenciende");
        s = new Sim(false);
        s.encender = false;
        s.run(cortes(1 * M, 21 * M), 40 * M);
        check("23", s.acciones("APAGAR").equals(List.of("11:00")), "APAGAR a las 11:00 -> " + s.acciones("APAGAR"), s);
        check("23", s.acciones("ENCENDER").isEmpty() && s.estado.equals("ABAJO"), "sin ENCENDER: sigue ABAJO", s);
        check("23", !s.p.isEncenderPendiente(), "sin encendido pendiente", s);

        escenario("24) Solo encendido automatico: no apaga; si Urano cae en el corte, enciende al volver la luz");
        s = new Sim(false);
        s.apagar = false;
        s.run(cortes(1 * M, 21 * M), 40 * M);
        check("24", s.acciones("APAGAR").isEmpty() && s.estado.equals("ARRIBA"), "sin APAGAR: sigue ARRIBA", s);
        check("24", s.acciones("ENCENDER").isEmpty() && !s.p.isEncenderPendiente(), "sin ENCENDER y ciclo cerrado", s);
        s = new Sim(false);
        s.apagar = false;
        s.caeEn = 15 * M;
        s.run(cortes(1 * M, 21 * M), 40 * M);
        check("24", s.acciones("APAGAR").isEmpty(), "sin APAGAR", s);
        check("24", s.acciones("ENCENDER").equals(List.of("26:00")), "ENCENDER a las 26:00 -> " + s.acciones("ENCENDER"), s);

        escenario("25) Desactivar el encendido con el encendido pendiente: lo olvida");
        s = new Sim(true);
        s.encender = false;
        s.estado = "ABAJO";
        s.run(t -> true, 15 * M);
        check("25", s.acciones("ENCENDER").isEmpty() && !s.p.isEncenderPendiente(), "sin ENCENDER y pendiente olvidado", s);

        System.out.println("\n" + (fallos == 0 ? "TODOS LOS ESCENARIOS OK" : fallos + " FALLO(S)"));
        System.exit(fallos == 0 ? 0 : 1);
    }
}
