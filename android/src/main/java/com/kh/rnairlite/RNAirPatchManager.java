package com.kh.rnairlite;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.Log;

import junit.framework.Assert;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;

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
            metaOut.write(meta);
            metaOut.flush();
            metaOut.close();

            progress.update(PatchHeaderLength, total);

            File patchData = new File(patchDir, PatchName);
            dataOut = new FileOutputStream(patchData);

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
        File patchDir = mApplication.getDir(TempPatchPath, Context.MODE_PRIVATE);
        if (!patchDir.isDirectory()) return "No patch found";

        File patchMeta = new File(patchDir, PatchMetaName);
        if (!patchMeta.exists()) return "No patch meta file found";

        File patchData = new File(patchDir, PatchName);
        if (!patchData.exists()) return "No patch data file found";

        File assets = new File(patchDir, AssetsName);
        if (!assets.exists()) return "No assets file found";

        InputStream metaStream, patchStream, curAssetsStream;
        OutputStream assetsStream;

        try {
            metaStream = new FileInputStream(patchMeta);
            byte metaData[] = new byte[PatchHeaderLength];
            if (metaStream.read(metaData) != metaData.length) {
                metaStream.close();
                Log.w(RNAirLiteModule.Tag, "Meta file has been corrupted.");
                return "Meta file has been corrupted.";
            }

            if (metaData[0] != PackVersoinSupported) {
                String error = "Unsupported pack version " + metaData[0];
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            patchStream = new FileInputStream(patchData);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dataBytes = new byte[(int)patchData.length()];
            patchStream.read(dataBytes);
            md.update(dataBytes, 0, dataBytes.length);

            byte[] checksum = md.digest();
            if (!Arrays.equals(checksum, Arrays.copyOfRange(metaData,
                    PachVersionLength + PatchVersionLength, ChecksumLength))) {
                String error = "Fail to verify the checksum";
                Log.e(RNAirLiteModule.Tag, error);
                return error;
            }

            ByteBuffer assetsTar;
            if (mCurrentJSBundle == null) {
                assetsTar = this.decompress(ByteBuffer.wrap(dataBytes));
            } else {
                ByteBuffer patch = this.decompress(ByteBuffer.wrap(dataBytes));
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

            assetsStream = new FileOutputStream(assets);
            byte[] assetsTarData = new byte[assetsTar.remaining()];
            assetsTar.get(assetsTarData);
            assetsStream.write(assetsTarData);
            extractTar(assets, patchDir);

            ObjectInputStream metaReader = new ObjectInputStream(metaStream);
            int version = metaReader.readInt();
            metaReader.close();

            metaStream.close();
            patchStream.close();

            applyNewPatch();

            return "" + version;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return e.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return e.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return e.toString();
        } catch (ArchiveException e) {
            e.printStackTrace();
            return e.toString();
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
        if (version == 0) return uri + "android/newest/base";
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
