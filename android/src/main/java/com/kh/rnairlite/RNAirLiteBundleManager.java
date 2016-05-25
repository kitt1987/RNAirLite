package com.kh.rnairlite;

import android.support.annotation.Nullable;
import android.util.Log;

import android.content.Context;

import java.io.File;

/**
 * Created by KH on 5/23/16.
 */
public class RNAirLiteBundleManager {
    private final String TAG = "#RNAirLiteBundle#";
    private final String StablePatch = "stable_patch";
    private final String StableBundle = "stable_bundle";
    private final String CurrentBundle = "cur_bundle";
    private final String NewPatch = "new_patch";
    private final String NewBundle = "new_bundle";
    private final String AirLiteDirectory = "com.kh.rnairlite";

    private Context context;
    private File base;

    public RNAirLiteBundleManager(Context reactContext) {
        this.context = reactContext;
        this.init();
    }

    public @Nullable String getBundleFile() {
        File base = new File(this.context.getFilesDir(), AirLiteDirectory);
        if (!base.isDirectory()) return null;
        File currentBundle = new File(base, CurrentBundle);
        if (currentBundle.isFile()) return currentBundle.getAbsolutePath();
        return null;
    }

    public @Nullable File getCurrentBundle() {
        return getBundleOrPatch(CurrentBundle);
    }

    public @Nullable File getStablePatch() {
        return getBundleOrPatch(StablePatch);
    }

    public @Nullable File getNewBundle() {
        return getBundleOrPatch(NewBundle);
    }

    public @Nullable File getNewPatch() {
        return getBundleOrPatch(NewPatch);
    }

    public void saveNewPatch() {
        File stablePatch = getBundleOrPatch(StablePatch);
        if (stablePatch.exists()) stablePatch.delete();
        File newPatch = getBundleOrPatch(NewPatch);
        newPatch.renameTo(stablePatch);
    }

    public void saveNewBundle() {
        File stableBundle = getBundleOrPatch(StableBundle);
        if (stableBundle.exists()) stableBundle.delete();
        File curBundle = getBundleOrPatch(CurrentBundle);
        if (curBundle.exists()) curBundle.renameTo(stableBundle);
        File newBundle = getBundleOrPatch(NewBundle);
        newBundle.renameTo(curBundle);
    }

    private @Nullable File getBundleOrPatch(String name) {
        if (!base.exists()) {
            Log.d(TAG, "The air lite base directory is not found");
            return null;
        }

        return new File(base, name);
    }

    private void init() {
        base = new File(this.context.getFilesDir(), AirLiteDirectory);
        if (base.isDirectory()) return;
        if (base.exists()) base.delete();
        if (!base.mkdir())
            Log.d(TAG, "Fail to create air lite base directory");
    }
}
