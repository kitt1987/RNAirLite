package com.kh.rnairlite;

import android.os.AsyncTask;
import android.util.Log;

import java.io.File;

/**
 * Created by KH on 05/10/2016.
 */
public class RNAirFS {
    public static void deletePatch(File patch) {
        final File dropped = new File(patch.getParentFile(),
                "dropped_patch_" + System.currentTimeMillis());
        if (!patch.renameTo(dropped)) {
            Log.e(RNAirLiteModule.Tag, "Fail to move " + patch.getAbsolutePath() + " to " +
                    dropped.getAbsolutePath());
            return;
        }

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                deleteRecursive(dropped);
                return null;
            }
        };
    }

    public static void move(File src, File dst) {
        if (!src.exists()) return;
        deleteRecursive(dst);
        src.renameTo(dst);
    }

    private static void deleteRecursive(File fileOrDirectory) {

        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        fileOrDirectory.delete();
    }
}
