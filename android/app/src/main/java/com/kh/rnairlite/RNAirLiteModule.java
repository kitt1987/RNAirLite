package com.kh.rnairlite;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private final String EventUpdate = "update";
    private final String EventProgress = "progress";
    private final String EventError = "error";
    private final String EventUpdated = "updated";
    private final String StablePatch = "stable_patch";
    private final String StableBundle = "stable_bundle";
    private final String NewtPatch = "new_patch";
    private final String NewBundle = "new_bundle";

    private final int HeaderSize = 64;
    private final int PackVersoinSize = 1;
    private final int PatchVersoinSize = 4;
    private final int ChecksumSize = 32;
    private final int PackVersoinSupported = 1;

    private ReactApplicationContext context;
    private int version = 0;
    private ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);

    public RNAirLiteModule(ReactApplicationContext reactContext) {
        super(reactContext);
        context = reactContext;
        final RNAirLiteModule self = this;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                self.loadLocalPatchVersionTaskInBackground(StablePatch);
            }
        });
        reactContext.getFilesDir();
    }

    static {
        System.loadLibrary("DiffAndBz2");
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("EventUpdate", EventUpdate);
        constants.put("EventProgress", EventProgress);
        constants.put("EventError", EventError);
        constants.put("EventUpdated", EventUpdated);
        return constants;
    }

    @Override
    public String getName() {
        return "RNAirLite";
    }

    @ReactMethod
    public void checkUpdate(final String url) {
        final RNAirLiteModule self = this;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                self.CheckUpdateInBackground(url);
            }
        });
    }

    @ReactMethod
    public void update(final String url) {
        final RNAirLiteModule self = this;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                self.updateInBackground(url);
            }
        });
    }

    private String getVersionURL(String url) {
        return url + "patch@" + this.version;
    }

    private int getVersion(InputStream in) {
        int version = 0;
        try {
            byte[] headerBin = new byte[HeaderSize];
            int bytesRead = in.read(headerBin);
            if (bytesRead < HeaderSize) {
                this.sendErrorBack("Your patch is corrupt.");
                return version;
            }

            ByteBuffer header = ByteBuffer.wrap(headerBin);
            int packVersion = header.get(0);
            if (packVersion > PackVersoinSupported) {
                this.sendErrorBack("Your patch is packed in a newer version format.");
                return version;
            }

            version = header.getInt(1);
        } catch (IOException e) {
            this.sendErrorBack(e.toString());
        }

        return version;
    }

    private void loadLocalPatchVersionTaskInBackground(String patchFile) {
        int version = 0;
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(new File(this.context.getFilesDir(),
                    patchFile)));
            this.sendVersionBack(this.getVersion(in));
        } catch (IOException e) {
            this.sendErrorBack(e.toString());
        }
    }

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
        sendEvent(context, EventError, params);
    }

    private void sendUpdateEvent(int version) {
        WritableMap params = Arguments.createMap();
        params.putInt("version", version);
        sendEvent(context, EventUpdate, params);
    }

    private void sendErrorBack(final String message) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        final RNAirLiteModule self = this;

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                self.sendError(message);
            }
        };

        mainHandler.post(runnable);
    }

    private void sendUpdateEventBack(final int version) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        final RNAirLiteModule self = this;

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                self.sendUpdateEvent(version);
            }
        };

        mainHandler.post(runnable);
    }

    private void sendVersionBack(final int version) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        final RNAirLiteModule self = this;

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                self.version = version;
            }
        };

        mainHandler.post(runnable);
    }

    private void CheckUpdateInBackground(String patchUrl) {
        URL url = null;
        int version = 0;
        HttpURLConnection urlConnection = null;
        try {
            url = new URL(patchUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept-Encoding", "identity");
            urlConnection.setRequestProperty("Range", "bytes=0-64");
            int status = urlConnection.getResponseCode();
            if (status != 200) {
                this.sendErrorBack("Server returns " + status);
                return;
            }

            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            int newPatchVersion = this.getVersion(in);
            if (newPatchVersion > this.version) {
                this.sendUpdateEventBack(newPatchVersion);
            }
        } catch (MalformedURLException e) {
            this.sendErrorBack(e.toString());
        } catch (IOException e) {
            this.sendErrorBack(e.toString());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private void updateInBackground(String patchUrl) {
        URL url = null;
        HttpURLConnection urlConnection = null;
        try {
            url = new URL(patchUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept-Encoding", "identity");
            int status = urlConnection.getResponseCode();
            if (status != 200) {
                this.sendErrorBack("Server returns " + status);
                return;
            }

            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            final int patchSize = urlConnection.getContentLength();
            byte[] patchBin = new byte[patchSize];

            int bytesRead = in.read(patchBin);
            if (bytesRead < patchSize) {
                this.sendErrorBack("The new patch is corrupt.");
                return;
            }

            ByteBuffer patch = ByteBuffer.wrap(patchBin);
            int packVersion = patch.get(0);
            if (packVersion > PackVersoinSupported) {
                this.sendErrorBack("Your patch is packed in a newer version format.");
                return;
            }

            int newPatchVersion = patch.getInt(1);
            if (newPatchVersion <= this.version) {
                this.sendErrorBack("Patch downloaded with version " + newPatchVersion +
                        " is not the newest one while local patch version is " + this.version);
                return;
            }

            byte[] checksum = new byte[ChecksumSize];
            patch.get(checksum);

            byte[] defaultChecksum = new byte[ChecksumSize];
            Arrays.fill(defaultChecksum, (byte) 0);

            MessageDigest digester = MessageDigest.getInstance("SHA-256");
            digester.update(patch.array(), 0, PackVersoinSize + PatchVersoinSize);
            digester.update(defaultChecksum);
            digester.update(patch.array(), PackVersoinSize + PatchVersoinSize + ChecksumSize,
                    patchSize - PackVersoinSize - PatchVersoinSize - ChecksumSize);
            byte[] digest = digester.digest();
            if (!Arrays.equals(digest, checksum)) {
                this.sendErrorBack("Fail to verify checksum.");
                return;
            }

            // FIXME decompress and patch
        } catch (MalformedURLException e) {
            this.sendErrorBack(e.toString());
        } catch (IOException e) {
            this.sendErrorBack(e.toString());
        } catch (NoSuchAlgorithmException e) {
            this.sendErrorBack(e.toString());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }


    private native ByteBuffer decompress(ByteBuffer buffer);
    private native ByteBuffer patch(ByteBuffer raw, ByteBuffer patch);
}
