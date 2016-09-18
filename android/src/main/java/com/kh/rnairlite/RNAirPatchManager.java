package com.kh.rnairlite;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.Log;

import junit.framework.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by KH on 9/14/16.
 */
public class RNAirPatchManager {
    private static final String StablePatchPath= "stable_patch";
    private static final String NewestPatchPath = "newest_patch";
    private static final String TempPatchPath = "tmp_patch";
    private static final String PatchName = "patch.data";
    private static final String PatchMetaName = "patch.meta";

    private final int PatchHeaderLength = 64;
    private final int PatchVersionLength = 4;
    private final int PachVersionLength = 1;
    private final int ChecksumLength = 32;
    private final int PackVersoinSupported = 1;

    private static final int ChunkSize = 10240;

    private boolean mSaveInSD = false;
    private String mUpdateURI;
    private int mTimeoutInMs = 10000;
    private @Nullable String mCurrentJSBundle;
    private @Nullable final Application mApplication;
    private int mVersion = 0;
    private int mRemoteVersion = 0;

    public RNAirPatchManager(Application application) {
        mApplication = application;
    }

    public void setup() {
        calcAvailablePatch();
    }

    public void setURI(String uri) {
        mUpdateURI = uri;
    }

    public void setBundleVersion(int version) {
        if (mVersion < version) mVersion = version;
    }

    public void savePatchInSDCard() {
        mSaveInSD = true;
    }

    public int getRemotePatchVersion() {
        return mRemoteVersion;
    }

    public int getVersion() { return mVersion; }

    public String getJSBundleFile() {
        return mCurrentJSBundle;
    }

    public boolean hasAnyPatches() { return mCurrentJSBundle != null; }

    public String getBundleAssetName() { return "main.jsbundle"; }

    public boolean rollback() {
        Assert.assertNotNull(mCurrentJSBundle);

        File bundle = new File(mCurrentJSBundle);
        File patch = bundle.getParentFile();
        if (!patch.exists()) {
            Log.w(RNAirLiteModule.Tag, patch.getAbsolutePath() + " doesn't exist!");
            mCurrentJSBundle = null;
            calcAvailablePatch();
            return true;
        }

        File dropped = new File(patch.getParentFile(),
                "dropped_patch_" + System.currentTimeMillis());
        if (!patch.renameTo(dropped)) {
            Log.e(RNAirLiteModule.Tag, "Fail to move " + patch.getAbsolutePath() + " to " +
                    dropped.getAbsolutePath());
            return false;
        }

        new DeletePatchTask().execute(dropped.getAbsolutePath());
        mCurrentJSBundle = null;
        calcAvailablePatch();
        return true;
    }

    public String checkUpdate() {
        if (mUpdateURI == null) {
            return "An URI where patches download from is required.";
        }

        InputStream is = null;
        ObjectInputStream ois = null;

        try {
            URL url = new URL(getPatchURI(mUpdateURI, mVersion));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(mTimeoutInMs);
            conn.setConnectTimeout(mTimeoutInMs);
            conn.setRequestProperty("Range", "bytes=" + PachVersionLength + "-" +
                    PatchVersionLength);
            conn.connect();
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String error = "Got a HTTP status " + responseCode +
                        " when patches had been downloaded";
                Log.d(RNAirLiteModule.Tag, error);
                return error;
            }

            is = conn.getInputStream();
            if (is.available() < PatchVersionLength) {
                String error = "Server returned only " + is.available() + " bytes";
                Log.e(RNAirLiteModule.Tag, error);
                is.close();
                return error;
            }

            ois = new ObjectInputStream(is);
            mRemoteVersion = ois.readInt();
            if (ois != null) {
                ois.close();
            }

            if (is != null) {
                is.close();
            }

            return "";
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        }
    }

    public String downloadPatches(RNAirLiteModule.ProgressUpdater progress) {
        if (mUpdateURI == null) {
            return "An URI where patches download from is required.";
        }

        File patchDir = mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE);
        deleteRecursive(patchDir);
        InputStream is = null;
        OutputStream dataOut = null, metaOut = null;

        try {
            URL url = new URL(getPatchURI(mUpdateURI, mVersion));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(mTimeoutInMs);
            conn.setConnectTimeout(mTimeoutInMs);
            conn.connect();
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String error = "Got a HTTP status " + responseCode +
                        " when patches had been downloaded";
                Log.d(RNAirLiteModule.Tag, error);
                return error;
            }

            is = conn.getInputStream();
            int total = conn.getContentLength();
            if (total < PatchHeaderLength) {
                is.close();
                String error = "The patch which length is " + total + " is corrupted";
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            byte meta[] = new byte[PatchHeaderLength];
            if (is.read(meta) != PatchHeaderLength) {
                is.close();
                String error = "The patch header which length is " + total + " is corrupted";
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            int packVersion = meta[0];
            if (packVersion != PackVersoinSupported) {
                String error = "Unsupported pack version " + packVersion;
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            // FIXME compare version in patch to mVersion


            File patchMeta = new File(patchDir, PatchMetaName);
            metaOut = new FileOutputStream(patchMeta);
            metaOut.write(meta, 0, PatchHeaderLength);
            metaOut.flush();
            metaOut.close();

            progress.update(PatchHeaderLength, total);

            File patchData = new File(patchDir, PatchName);
            dataOut = new FileOutputStream(patchMeta);

            byte data[] = new byte[ChunkSize];
            int count = 0;
            int offset = 0;

            while ((count = is.read(data)) != -1) {
                offset += count;
                progress.update(offset + PatchHeaderLength, total);
                dataOut.write(data, offset, count);
            }

            dataOut.flush();
            dataOut.close();
            is.close();
            return "";
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        }
    }

    public String installPatch() {
        // FIXME validate patch
        // FIXME if mVersion is 0, then just extract the patct. Else patch to the running bundle.
        // FIXME move all bundles
    }

    private void calcAvailablePatch() {
        Assert.assertNull(mCurrentJSBundle);
        mCurrentJSBundle = getPatchAvailable(NewestPatchPath);
        if (mCurrentJSBundle != null) return;

        mCurrentJSBundle = getPatchAvailable(StablePatchPath);
    }

    private @Nullable String getPatchAvailable(String patchPath) {
        // FIXME JS codes could decide to save patches in inner or external storage.
        File stablePatchDir = mApplication.getDir(patchPath, Context.MODE_PRIVATE);
        if (!stablePatchDir.exists() || !stablePatchDir.isDirectory()) {
            Log.d(RNAirLiteModule.Tag, "No patch found in " + stablePatchDir.getAbsolutePath());
            return null;
        }

        Log.d(RNAirLiteModule.Tag, "The stable patch is stored in " + stablePatchDir.getAbsolutePath());

        File bundleFile = new File(stablePatchDir, getBundleAssetName());
        if (!bundleFile.exists()) {
            Log.w(RNAirLiteModule.Tag, patchPath + " do not found.");
            return null;
        }

        File metaFile = new File(stablePatchDir, PatchMetaName);
        if (!metaFile.exists()) {
            Log.w(RNAirLiteModule.Tag, "Meta file does not found.");
            return null;
        }

        InputStream metaIn = null;
        ObjectInputStream metaReader = null;
        try {
            metaIn = new FileInputStream(metaFile);
            if (metaIn.skip(PachVersionLength) != PachVersionLength) {
                metaIn.close();
                Log.w(RNAirLiteModule.Tag, "Meta file has been corrupted.");
                return null;
            }

            metaReader = new ObjectInputStream(metaIn);
            mVersion = metaReader.readInt();
            metaIn.close();
            metaReader.close();
            Log.i(RNAirLiteModule.Tag, patchPath + " is going to be loaded!");
            return bundleFile.getAbsolutePath();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getPatchURI(String uri, int version) {
        if (!uri.endsWith("/")) uri += "/";
        if (version == 0) return uri + "android/newest";
        return uri + "android/" + version;
    }

    private static void deleteRecursive(File fileOrDirectory) {

        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        fileOrDirectory.delete();
    }

    class DeletePatchTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... params) {
            deleteRecursive(new File(params[0]));
            return null;
        }
    }
}
