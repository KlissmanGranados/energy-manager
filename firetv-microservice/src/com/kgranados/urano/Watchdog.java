package com.kgranados.urano;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * Re-asegura el servicio cada ~15 min via AlarmManager, por si Fire OS
 * reclamo el proceso bajo presion de memoria (RAM muy limitada en el stick).
 */
public class Watchdog {

    private static final long INTERVAL_MS = 15 * 60 * 1000L;
    public static final String ACTION_WATCHDOG = "com.kgranados.urano.WATCHDOG";

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, BootReceiver.class);
        i.setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, flags);

        long next = SystemClock.elapsedRealtime() + INTERVAL_MS;
        // inexacto: suficiente y amable con la bateria/CPU
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, INTERVAL_MS, pi);
    }
}
