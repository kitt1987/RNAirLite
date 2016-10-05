package com.kh.rnairlite;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;

/**
 * Created by KH on 05/10/2016.
 */
public class RNAirFolder {
    private static final String StablePatchPath= "stable_patch";
    private static final String NewestPatchPath = "newest_patch";
    private static final String TempPatchPath = "tmp_patch";
    private static final String PatchName = "patch.data";
    private static final String PatchMetaName = "patch.meta";
    private static final String AssetsName = "assets.tar";

    private final Application mApplication;
    private final String mJSMainModuleName;
    private String mLastUpdatedTs = "0";
    private @Nullable File mPatchDir;

    public RNAirFolder(Application app, String jsMainModuleName) {
        mApplication = app;
        mJSMainModuleName = jsMainModuleName;
    }

    public void init() {
        PackageInfo info = null;
        try {
            info = mApplication.getPackageManager().getPackageInfo(mApplication.getPackageName(),
                    0);
            mLastUpdatedTs = "" + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            Log.d(RNAirLiteModule.Tag, "Fail to load last update time of the APP.");
        }
    }

    public void createTempWritingFolder() {
        File tempDir = getTempPatchFolder();
        RNAirFS.deletePatch(tempDir);
        tempDir = getTempPatchFolder();
        mPatchDir = new File(tempDir, mLastUpdatedTs);
        mPatchDir.mkdir();
    }

    public File getNewestPatchFolder() {
        return mApplication.getDir(NewestPatchPath, Context.MODE_PRIVATE);
    }

    public File getStablePatchFolder() {
        return mApplication.getDir(StablePatchPath, Context.MODE_PRIVATE);
    }

    public File getTempPatchFolder() {
        return mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE);
    }

    public File getAssetsName(String bundleFolder) {
        return new File(bundleFolder, AssetsName);
    }

    public PatchScheme getNewestPatchSchema() {
        return new PatchScheme(NewestPatchPath);
    }

    public PatchScheme getStablePatchSchema() {
        return new PatchScheme(StablePatchPath);
    }

    public PatchScheme getTempPatchSchema() {
        return new PatchScheme(TempPatchPath);
    }

    public class PatchScheme {
        private final String mType;
        private File mDataFolder;

        PatchScheme(String patchType) {
            mType = patchType;
            mDataFolder = new File(getPatchFolder(), mLastUpdatedTs);
        }

        public File getDataFolder() {
            return mDataFolder;
        }

        public File getPatchFolder() {
            return mApplication.getDir(mType, Context.MODE_PRIVATE);
        }

        public File getBundleFile() {
            return new File(mDataFolder, mJSMainModuleName);
        }

        public File getMetaFile() {
            return new File(mDataFolder, PatchMetaName);
        }

        public File getPatchFile() {
            return new File(mDataFolder, PatchName);
        }

        public File getAssetsFile() {
            return new File(mDataFolder, AssetsName);
        }
    }
}
