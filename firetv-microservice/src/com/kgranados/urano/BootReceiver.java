package com.kgranados.urano;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Arranca el servicio al encender el dispositivo (BOOT_COMPLETED / QUICKBOOT)
 * y cuando dispara la alarma del watchdog.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "UranoBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onReceive: " + action);
        ControlService.startSelf(context);
    }
}
