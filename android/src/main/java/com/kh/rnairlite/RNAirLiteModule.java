package com.kh.rnairlite;

import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.HashMap;
import java.util.Map;

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
            return mPatchManager.checkForUpdate();
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
                sendVersion(EventInstalled, mPatchManager.getRemotePatchVersion());
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
        constants.put("EventInstalled", EventInstalled);
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
        if (storePatchInSD) mPatchManager.savePatchInSDCard();
    }

    @ReactMethod
    public void checkForUpdate() {
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

    private static void sendEvent(ReactContext reactContext,
                                  String eventName,
                                  @Nullable Object params) {
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
        if (version > 0)
            sendEvent(this.getReactApplicationContext(), event, version);
    }
}
