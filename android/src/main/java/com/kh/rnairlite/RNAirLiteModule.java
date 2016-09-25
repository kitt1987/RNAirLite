package com.kh.rnairlite;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Created by KH on 5/22/16.
 */


public class RNAirLiteModule extends ReactContextBaseJavaModule {
    static {
        System.loadLibrary("DiffAndBz2");
    }

    public static final String Tag = "☁RNAirLite☁";

    private final String EventChecked = "checked";
    private final String EventProgress = "progress";
    private final String EventError = "error";
    private final String EventDownloaded = "downloaded";
    private final String EventInstalled = "installed";

    RNAirPatchManager mPatchManager;
    RNAirLiteHost mHostHandle;

    class CheckUpdateTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {
            return mPatchManager.checkUpdate();
        }

        @Override
        protected void onPostExecute(String error) {
            if (error != null) {
                sendError(error);
                return;
            }

            sendVersion(EventChecked, mPatchManager.getRemotePatchVersion());
        }
    }

    public interface ProgressUpdater {
        void update(int downloaded, int total);
    }

    class DownloadPatchTask extends AsyncTask<Void, Integer, String> {

        @Override
        protected String doInBackground(Void... params) {
            return mPatchManager.downloadPatches(new ProgressUpdater() {

                @Override
                public void update(int downloaded, int total) {
                    publishProgress(downloaded, total);
                }
            });
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            sendProgressEvent(progress[0], progress[1]);
        }

        @Override
        protected void onPostExecute(String error) {
            if (error != null) {
                sendError(error);
                return;
            }

            sendVersion(EventDownloaded, mPatchManager.getRemotePatchVersion());
        }
    }

    class InstallPatchTask extends AsyncTask<Void, Void, String> {
        private boolean mRestartManually = false;

        public InstallPatchTask(boolean restartManually) {
            super();
            mRestartManually = restartManually;
        }

        @Override
        protected String doInBackground(Void... params) {
            return mPatchManager.installPatch();
        }

        @Override
        protected void onPostExecute(String error) {
            if (error != null) {
                sendError(error);
                return;
            }

            if (mRestartManually) {
                sendVersion(EventInstalled, mPatchManager.getVersion());
                return;
            }

            restart();
        }
    }

    public RNAirLiteModule(ReactApplicationContext reactContext, RNAirPatchManager patchManager,
                           RNAirLiteHost hostHandle) {
        super(reactContext);
        this.mPatchManager = patchManager;
        this.mHostHandle = hostHandle;
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("EventChecked", EventChecked);
        constants.put("EventProgress", EventProgress);
        constants.put("EventError", EventError);
        constants.put("EventDownloaded", EventDownloaded);
        return constants;
    }

    @Override
    public String getName() {
        return "RNAirLite";
    }

    @ReactMethod
    public void init(String url, int bundleVersion, boolean storePatchInSD) {
        mPatchManager.setURI(url);
        mPatchManager.setBundleVersion(bundleVersion);
    }

    @ReactMethod
    public void checkUpdate() {
        new CheckUpdateTask().execute();
    }

    @ReactMethod
    public void downloadPatch() {
        new DownloadPatchTask().execute();
    }

    @ReactMethod
    public void installPatch(boolean restartManually) {
        new InstallPatchTask(restartManually).execute();
    }

    @ReactMethod
    public void restart() { mHostHandle.reboot(); }

//    private String getVersionURL(String url) {
//        return url + "patch@" + this.version;
//    }
//
//    private int getVersion(InputStream in) throws IOException {
//        int version = 0;
//        byte[] headerBin = new byte[HeaderSize];
//        int bytesRead = in.read(headerBin);
//        if (bytesRead < HeaderSize) {
//            this.sendErrorBack("Your patch is corrupt.");
//            return version;
//        }
//
//        ByteBuffer header = ByteBuffer.wrap(headerBin);
//        int packVersion = header.get(0);
//        if (packVersion > PackVersoinSupported) {
//            this.sendErrorBack("Your patch is packed in a newer version format.");
//            return version;
//        }
//
//        return header.getInt(1);
//    }
//
//    private void loadLocalPatchVersionTaskInBackground(File patch) {
//        int version = 0;
//        InputStream in = null;
//        try {
//            in = new BufferedInputStream(new FileInputStream(patch));
//            version = this.getVersion(in);
//            in.close();
//        } catch (IOException e) {
//            Log.d(TAG, e.toString());
//        } finally {
//            Log.d(TAG, "Stable patch version is " + version);
//            this.sendVersionBack(version);
//        }
//    }

    private static void sendEvent(ReactContext reactContext,
                                  String eventName,
                                  @Nullable WritableMap params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    private void sendError(String message) {
        WritableMap params = Arguments.createMap();
        params.putString("error", message);
        sendEvent(this.getReactApplicationContext(), EventError, params);
    }

    private void sendProgressEvent(int downloaded, int total) {
        WritableMap params = Arguments.createMap();
        params.putInt("downloaded", downloaded);
        params.putInt("total", total);
        sendEvent(this.getReactApplicationContext(), EventProgress, params);
    }

    private void sendVersion(String event, int version) {
        WritableMap params = Arguments.createMap();
        if (version > 0) params.putInt("version", version);
        sendEvent(this.getReactApplicationContext(), event, params);
    }

//    private void CheckUpdateInBackground(String patchUrl) {
//        URL url = null;
//        int version = 0;
//        HttpURLConnection urlConnection = null;
//        try {
//            Log.d(TAG, "Ready to check updating");
//            url = new URL(patchUrl);
//            urlConnection = (HttpURLConnection) url.openConnection();
//            urlConnection.setRequestProperty("Accept-Encoding", "identity");
//            urlConnection.setRequestProperty("Range", "bytes=0-64");
//            int status = urlConnection.getResponseCode();
//            if (status != 200) {
//                this.sendErrorBack("Server returns " + status);
//                return;
//            }
//
//            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
//            int newPatchVersion = this.getVersion(in);
//            if (newPatchVersion > this.version) {
//                this.sendUpdateEventBack(newPatchVersion);
//            }
//        } catch (MalformedURLException e) {
//            this.sendErrorBack(e.toString());
//        } catch (IOException e) {
//            this.sendErrorBack(e.toString());
//        } finally {
//            if (urlConnection != null) urlConnection.disconnect();
//        }
//    }
//
//    private void updateInBackground(String patchUrl) {
//        URL url = null;
//        HttpURLConnection urlConnection = null;
//        try {
//            url = new URL(patchUrl);
//            urlConnection = (HttpURLConnection) url.openConnection();
//            urlConnection.setRequestProperty("Accept-Encoding", "identity");
//            int status = urlConnection.getResponseCode();
//            if (status != 200) {
//                this.sendErrorBack("Server returns " + status);
//                return;
//            }
//
//            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
//            final int patchSize = urlConnection.getContentLength();
//            byte[] patchBin = new byte[patchSize];
//
//            int bytesRead = in.read(patchBin);
//            if (bytesRead < patchSize) {
//                this.sendErrorBack("The new patch is corrupt.");
//                return;
//            }
//
//            ByteBuffer patch = ByteBuffer.allocateDirect(patchSize);
//            patch.put(patchBin);
//            int packVersion = patch.get(0);
//            if (packVersion > PackVersoinSupported) {
//                this.sendErrorBack("Your patch is packed in a newer version format.");
//                return;
//            }
//
//            int newPatchVersion = patch.getInt(1);
//            if (newPatchVersion <= this.version) {
//                this.sendErrorBack("Patch downloaded with version " + newPatchVersion +
//                        " is not the newest one while local patch version is " + this.version);
//                return;
//            }
//
//            byte[] checksum = new byte[ChecksumSize];
//            patch.get(checksum);
//
//            byte[] defaultChecksum = new byte[ChecksumSize];
//            Arrays.fill(defaultChecksum, (byte) 0);
//
//            MessageDigest digester = MessageDigest.getInstance("SHA-256");
//            digester.update(patch.array(), 0, PackVersoinSize + PatchVersoinSize);
//            digester.update(defaultChecksum);
//            digester.update(patch.array(), PackVersoinSize + PatchVersoinSize + ChecksumSize,
//                    patchSize - PackVersoinSize - PatchVersoinSize - ChecksumSize);
//            byte[] digest = digester.digest();
//            if (!Arrays.equals(digest, checksum)) {
//                this.sendErrorBack("Fail to verify checksum.");
//                return;
//            }
//
//            ByteBuffer stableBundle = this.readStableBundle();
//            ByteBuffer newBundle = this.patch(stableBundle, this.decompress(patch));
//            this.writeNewBundle(newBundle);
//            this.bundleManager.saveNewBundle();
//            // FIXME try to reload bundle
//            // this.getReactApplicationContext().getPackageManager().
//        } catch (MalformedURLException e) {
//            this.sendErrorBack(e.toString());
//        } catch (IOException e) {
//            this.sendErrorBack(e.toString());
//        } catch (NoSuchAlgorithmException e) {
//            this.sendErrorBack(e.toString());
//        } finally {
//            if (urlConnection != null) urlConnection.disconnect();
//        }
//    }

//    private ByteBuffer readStableBundle() throws IOException {
//        File stableBundle = bundleManager.getCurrentBundle();
//        InputStream stableStream = null;
//        if (!stableBundle.exists() && version == 0) {
//            stableStream = new BufferedInputStream(
//                    this.getReactApplicationContext().getAssets().open(this.bundleAssetName));
//        } else {
//            stableStream = new BufferedInputStream(new FileInputStream(stableBundle));
//        }
//
//        final int stableSize = stableStream.available();
//        byte[] data = new byte[stableSize];
//        stableStream.read(data);
//        return ByteBuffer.wrap(data);
//    }
//
//    private void writeNewBundle(ByteBuffer data) throws IOException {
//        File newBundle = bundleManager.getNewBundle();
//        BufferedOutputStream newStream = new BufferedOutputStream(new FileOutputStream(newBundle));
//        newStream.write(data.array());
//    }
//
//    private native ByteBuffer decompress(ByteBuffer buffer);
//    private native ByteBuffer patch(ByteBuffer raw, ByteBuffer patch);
}
