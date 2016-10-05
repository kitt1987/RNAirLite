package com.kh.rnairlite;

import android.app.Application;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Created by KH on 9/14/16.
 */
public class RNAirPatchManager {
    static {
        System.loadLibrary("DiffAndBz2");
    }

    private static final int ChunkSize = 10240;

    private boolean mSaveInSD = false;
    private String mUpdateURI;
    private int mTimeoutInMs = 10000;
    private @Nullable RNAirFolder.PatchScheme mCurrentJSBundle;
    private int mVersion = 0;
    private int mRemoteVersion = 0;
    private RNAirFolder mFolderManager;

    private native ByteBuffer decompress(ByteBuffer buffer);
    private native ByteBuffer patch(ByteBuffer raw, ByteBuffer patch);

    public RNAirPatchManager(Application application, String jsMainModuleName) {
        mFolderManager = new RNAirFolder(application, jsMainModuleName);
    }

    public void setup() {
        mFolderManager.init();
        calcAvailablePatch();
        if (mCurrentJSBundle != null) {
            Log.v(RNAirLiteModule.Tag, "Current JS bundle is " + mCurrentJSBundle.getDataFolder());
        }
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
        if (mCurrentJSBundle == null) return null;
        return mCurrentJSBundle.getBundleFile().getAbsolutePath();
    }

    public boolean hasAnyPatches() { return mCurrentJSBundle != null; }

    public boolean rollback() {
        Assert.assertNotNull(mCurrentJSBundle);

        File bundle = mCurrentJSBundle.getBundleFile();
        File patch = bundle.getParentFile();
        if (!patch.exists()) {
            Log.w(RNAirLiteModule.Tag, patch.getAbsolutePath() + " doesn't exist!");
            mCurrentJSBundle = null;
            calcAvailablePatch();
            return true;
        }

        RNAirFS.deletePatch(patch);
        mCurrentJSBundle = null;
        calcAvailablePatch();
        return true;
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
            conn.setRequestProperty("Range", "bytes=" + RNAirPatchMeta.getVersionByteRange());
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
            byte[] data = RNAirPatchMeta.createVersionBuffer();
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

        mFolderManager.createTempWritingFolder();
        RNAirFolder.PatchScheme ps = mFolderManager.getTempPatchSchema();
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
            byte meta[] = RNAirPatchMeta.createMetaBuffer();
            if (is.read(meta) != meta.length) {
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

            result = patchMeta.save(ps.getMetaFile());
            if (result != null) return result;

            progress.update(meta.length, total);

            dataOut = new FileOutputStream(ps.getPatchFile());

            byte data[] = new byte[ChunkSize];
            int count = 0;
            int offset = 0;

            while ((count = is.read(data)) != -1) {
                offset += count;
                progress.update(offset + meta.length, total);
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
        RNAirFolder.PatchScheme ps = mFolderManager.getTempPatchSchema();
        File patchDir = ps.getDataFolder();
        if (!patchDir.isDirectory()) return "No patch found";

        File patchMetaFile = ps.getMetaFile();
        if (!patchMetaFile.exists()) return "No patch meta file found";

        File patchData = ps.getPatchFile();
        if (!patchData.exists()) return "No patch data file found";

        File assets = null;
        if (mCurrentJSBundle != null) {
            assets = mCurrentJSBundle.getAssetsFile();
            if (!assets.exists()) return "No assets file found at " + assets.getAbsolutePath();
        }

        InputStream metaStream = null, patchStream = null, curAssetsStream = null;
        OutputStream assetsStream = null;

        try {
            metaStream = new FileInputStream(patchMetaFile);
            byte metaData[] = RNAirPatchMeta.createMetaBuffer();
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

            File newAssets = ps.getAssetsFile();
            assetsStream = new FileOutputStream(newAssets);
            byte[] assetsTarData = new byte[assetsTar.remaining()];
            assetsTar.get(assetsTarData);
            assetsStream.write(assetsTarData);
            extractTar(newAssets, patchDir);
            applyNewPatch();
            mRemoteVersion = patchMeta.getVersion();

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

    private void applyNewPatch() {
        RNAirFolder.PatchScheme newest = mFolderManager.getNewestPatchSchema();
        File newestPatchFolder = newest.getPatchFolder();
        RNAirFS.move(newestPatchFolder, mFolderManager.getStablePatchFolder());
        RNAirFS.move(mFolderManager.getTempPatchFolder(), newestPatchFolder);
        mCurrentJSBundle = newest;
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
        mCurrentJSBundle = getPatchAvailable(mFolderManager.getNewestPatchSchema());
        if (mCurrentJSBundle != null) return;

        mCurrentJSBundle = getPatchAvailable(mFolderManager.getStablePatchSchema());
    }

    private @Nullable RNAirFolder.PatchScheme getPatchAvailable(RNAirFolder.PatchScheme patchScheme) {
        // FIXME JS codes could decide to save patches in inner or external storage.
        File stablePatchDir = patchScheme.getDataFolder();
        if (!stablePatchDir.exists() || !stablePatchDir.isDirectory()) {
            Log.d(RNAirLiteModule.Tag, "No patch found in " + stablePatchDir.getAbsolutePath());
            return null;
        }

        Log.d(RNAirLiteModule.Tag, "The stable patch is stored in " + stablePatchDir.getAbsolutePath());

        File bundleFile = patchScheme.getBundleFile();
        if (!bundleFile.exists()) {
            Log.w(RNAirLiteModule.Tag, bundleFile.getAbsolutePath() + " does not found.");
            return null;
        }

        File metaFile = patchScheme.getMetaFile();
        if (!metaFile.exists()) {
            Log.w(RNAirLiteModule.Tag, metaFile.getAbsolutePath() + " does not found.");
            return null;
        }

        InputStream metaIn = null;
        try {
            metaIn = new FileInputStream(metaFile);
            byte metaData[] = RNAirPatchMeta.createMetaBuffer();
            if (metaIn.read(metaData) != metaData.length) {
                String error = "Meta file has been corrupted.";
                Log.w(RNAirLiteModule.Tag, error);
                return null;
            }

            RNAirPatchMeta patchMeta = new RNAirPatchMeta(metaData);
            String result = patchMeta.verify();
            if (result != null) return null;

            mVersion = patchMeta.getVersion();
            return patchScheme;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (metaIn != null) metaIn.close();
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
}
