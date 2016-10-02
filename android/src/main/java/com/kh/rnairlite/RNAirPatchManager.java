package com.kh.rnairlite;

import android.app.Application;
import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.Log;

import junit.framework.Assert;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
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
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Created by KH on 9/14/16.
 */
public class RNAirPatchManager {
    static {
        System.loadLibrary("DiffAndBz2");
    }

    private static final String StablePatchPath= "stable_patch";
    private static final String NewestPatchPath = "newest_patch";
    private static final String TempPatchPath = "tmp_patch";
    private static final String PatchName = "patch.data";
    private static final String PatchMetaName = "patch.meta";
    private static final String AssetsName = "assets.tar";

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

    private native ByteBuffer decompress(ByteBuffer buffer);
    private native ByteBuffer patch(ByteBuffer raw, ByteBuffer patch);

    public RNAirPatchManager(Application application) {
        mApplication = application;
    }

    public void setup() {
        calcAvailablePatch();
        Log.v(RNAirLiteModule.Tag, "Current JS bundle is " + mCurrentJSBundle);
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

        deletePatch(patch);
        mCurrentJSBundle = null;
        calcAvailablePatch();
        return true;
    }

    private void deletePatch(File patch) {
        File dropped = new File(patch.getParentFile(),
                "dropped_patch_" + System.currentTimeMillis());
        if (!patch.renameTo(dropped)) {
            Log.e(RNAirLiteModule.Tag, "Fail to move " + patch.getAbsolutePath() + " to " +
                    dropped.getAbsolutePath());
            return;
        }

        new DeletePatchTask().execute(dropped.getAbsolutePath());
    }

    public String checkForUpdate() {
        if (mUpdateURI == null) {
            return "An URI where patches download from is required.";
        }

        InputStream is = null;
        BufferedInputStream ois = null;
        HttpURLConnection conn = null;

        try {
            URL url = new URL(getPatchURI(mUpdateURI, mVersion));
            Log.d(RNAirLiteModule.Tag, ">>" + url);
            conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(mTimeoutInMs);
            conn.setConnectTimeout(mTimeoutInMs);
            conn.setRequestProperty("Range", "bytes=" + PachVersionLength + "-" +
                    PatchVersionLength);
            conn.connect();
            int responseCode = conn.getResponseCode();
            Log.v(RNAirLiteModule.Tag, "Got a HTTP status " + responseCode);
            if (responseCode != 206) {
                String error = "Got a HTTP status " + responseCode +
                        " when patches had been downloaded";
                Log.d(RNAirLiteModule.Tag, error);
                return error;
            }

            is = conn.getInputStream();
            byte[] data = new byte[PatchVersionLength];
            int bytesRead = is.read(data);
            if (bytesRead != data.length) {
                String error = "Server returned only " + bytesRead + " bytes";
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            mRemoteVersion = ByteBuffer.wrap(data).getInt();
            Log.v(RNAirLiteModule.Tag, "The newest version is " + mRemoteVersion);
            return null;
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        } finally {
            try {
                if (conn != null) conn.disconnect();
                if (is != null) is.close();
                if (ois != null) ois.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public String downloadPatches(RNAirLiteModule.ProgressUpdater progress) {
        if (mUpdateURI == null) {
            return "An URI where patches download from is required.";
        }

        File patchDir = mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE);
        deleteRecursive(patchDir);
        patchDir = mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE);
        InputStream is = null;
        OutputStream dataOut = null;

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

            RNAirPatchMeta patchMeta = new RNAirPatchMeta(meta);
            String result = patchMeta.verify();
            if (result != null) return result;

            int patchVersion = patchMeta.getVersion();
            if (patchVersion <= mVersion) {
                String error = "The patch downloaded is not a new patch which version is "
                        + patchVersion;
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            result = patchMeta.save(new File(patchDir, PatchMetaName));
            if (result != null) return result;

            progress.update(PatchHeaderLength, total);

            File patchData = new File(patchDir, PatchName);
            dataOut = new FileOutputStream(patchData);

            byte data[] = new byte[ChunkSize];
            int count = 0;
            int offset = 0;

            while ((count = is.read(data)) != -1) {
                offset += count;
                progress.update(offset + PatchHeaderLength, total);
                dataOut.write(data, 0, count);
            }

            dataOut.flush();
            mRemoteVersion = patchVersion;
            Log.v(RNAirLiteModule.Tag, "The version of patch downloaded is " + mRemoteVersion);
            return null;
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        } finally {
            try {
                if (is != null) is.close();
                if (dataOut != null) dataOut.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public String installPatch() {
        File patchDir = mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE);
        if (!patchDir.isDirectory()) return "No patch found";

        File patchMetaFile = new File(patchDir, PatchMetaName);
        if (!patchMetaFile.exists()) return "No patch meta file found";

        File patchData = new File(patchDir, PatchName);
        if (!patchData.exists()) return "No patch data file found";

        File assets = null;
        if (mCurrentJSBundle != null) {
            assets = new File(mCurrentJSBundle, AssetsName);
            if (!assets.exists()) return "No assets file found";
        }

        InputStream metaStream = null, patchStream = null, curAssetsStream = null;
        OutputStream assetsStream = null;

        try {
            metaStream = new FileInputStream(patchMetaFile);
            byte metaData[] = new byte[PatchHeaderLength];
            if (metaStream.read(metaData) != metaData.length) {
                String error = "Meta file has been corrupted.";
                Log.w(RNAirLiteModule.Tag, error);
                return error;
            }

            RNAirPatchMeta patchMeta = new RNAirPatchMeta(metaData);
            String result = patchMeta.verify();
            if (result != null) return result;

            patchStream = new FileInputStream(patchData);
            ByteBuffer dataBytes = ByteBuffer.allocateDirect((int)patchData.length());
            ByteBuffer sample = ByteBuffer.allocateDirect((int)patchData.length());
            Log.d(RNAirLiteModule.Tag, "" + dataBytes.array().length);
            Log.v(RNAirLiteModule.Tag, "The whole patch size is " + patchData.length());
            if (patchStream.read(dataBytes.array(), dataBytes.arrayOffset(),
                    (int)patchData.length()) != patchData.length()) {
                String error = "Fail to read patch data file.";
                Log.w(RNAirLiteModule.Tag, error);
                return error;
            }

            result = patchMeta.verifyPatch(dataBytes);
            if (result != null) return result;

            ByteBuffer assetsTar;
            if (mCurrentJSBundle == null) {
                Log.v(RNAirLiteModule.Tag, "The whole assets will be extracting...");
                assetsTar = this.decompress(dataBytes);
            } else {
                ByteBuffer patch = this.decompress(dataBytes);
                File curAssets = new File(mCurrentJSBundle, AssetsName);
                if (!curAssets.exists()) return "No Local assets file found";

                curAssetsStream = new FileInputStream(assets);
                byte[] assetsData = new byte[(int) assets.length()];
                if (curAssetsStream.read(assetsData) != assetsData.length) {
                    curAssetsStream.close();
                    String error = "Local assets file is corrupted.";
                    Log.e(RNAirLiteModule.Tag, error);
                    return error;
                }

                curAssetsStream.close();

                assetsTar = this.patch(ByteBuffer.wrap(assetsData), patch);
            }

            File newAssets = new File(patchDir, AssetsName);
            assetsStream = new FileOutputStream(newAssets);
            byte[] assetsTarData = new byte[assetsTar.remaining()];
            assetsTar.get(assetsTarData);
            assetsStream.write(assetsTarData);
            extractTar(newAssets, patchDir);

            mRemoteVersion = patchMeta.getVersion();
            applyNewPatch();

            return null;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        } catch (ArchiveException e) {
            e.printStackTrace();
            return e.toString();
        } finally {
            try {
                if (metaStream != null) {
                    metaStream.close();
                }

                if (patchStream != null) {
                    patchStream.close();
                }

                if (curAssetsStream != null) {
                    curAssetsStream.close();
                }

                if (assetsStream != null) assetsStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void move(File src, File dst) {
        if (!src.exists()) return;
        deletePatch(dst);
        src.renameTo(dst);
    }

    private void applyNewPatch() {
        move(mApplication.getDir(NewestPatchPath, Context.MODE_PRIVATE),
                mApplication.getDir(StablePatchPath, Context.MODE_PRIVATE));
        move(mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE),
                mApplication.getDir(NewestPatchPath, Context.MODE_PRIVATE));
    }

    private void extractTar(File inputFile, File outputDir) throws IOException, ArchiveException {
        final InputStream is = new FileInputStream(inputFile);
        final TarArchiveInputStream debInputStream =
                (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", is);
        TarArchiveEntry entry = null;
        while ((entry = (TarArchiveEntry)debInputStream.getNextEntry()) != null) {
            Log.d(RNAirLiteModule.Tag, String.format("Extracting %s.", entry.getName()));
            final File outputFile = new File(outputDir, entry.getName());
            if (entry.isDirectory()) {
                Log.d(RNAirLiteModule.Tag, String.format("Attempting to write output directory %s.", outputFile.getAbsolutePath()));
                if (!outputFile.exists()) {
                    Log.d(RNAirLiteModule.Tag, String.format("Attempting to create output directory %s.", outputFile.getAbsolutePath()));
                    if (!outputFile.mkdirs()) {
                        throw new IllegalStateException(String.format("Couldn't create directory %s.", outputFile.getAbsolutePath()));
                    }
                }
            } else {
                Log.d(RNAirLiteModule.Tag, String.format("Creating output file %s.", outputFile.getAbsolutePath()));
                final OutputStream outputFileStream = new FileOutputStream(outputFile);
                IOUtils.copy(debInputStream, outputFileStream);
                outputFileStream.close();
            }
        }
        debInputStream.close();
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
            Log.i(RNAirLiteModule.Tag, patchPath + " is going to be loaded!");
            return bundleFile.getAbsolutePath();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (metaIn != null) metaIn.close();
                if (metaReader != null) metaReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String getPatchURI(String uri, int version) {
        if (!uri.endsWith("/")) uri += "/";
        if (mCurrentJSBundle == null) return uri + "android/newest/base";
        return uri + "android/" + version + "/patch";
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
