package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

public final class MiuHelper {

    // Hardcoded host seeded into this custom build (LAN IP of the streaming PC)
    private static final String MIU_HOST = "192.168.1.141";
    private static final int MIU_PORT = NvHTTP.DEFAULT_HTTP_PORT;

    private MiuHelper() {
        throw new IllegalStateException("Utility class");
    }

    public static void ensureHost(final ComputerManagerService.ComputerManagerBinder binder,
                                  final Context context, final Runnable onDone) {
        final SharedPreferences prefs =
                context.getSharedPreferences("miu_prefs", Context.MODE_PRIVATE);
        if (prefs.getBoolean("host_seeded", false)) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ComputerDetails details = new ComputerDetails();
                    details.name = "LiuHost";
                    details.manualAddress = new ComputerDetails.AddressTuple(MIU_HOST, MIU_PORT);
                    binder.addComputerBlocking(details);
                    prefs.edit().putBoolean("host_seeded", true).apply();
                } catch (InterruptedException e) {
                    // Not marked as seeded; retried on next launch
                } finally {
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            }
        }, "miu-seed-host").start();
    }
}