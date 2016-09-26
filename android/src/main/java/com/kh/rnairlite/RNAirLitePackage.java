package com.kh.rnairlite;

import android.content.Context;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.JavaScriptModule;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by KH on 5/22/16.
 */
public class RNAirLitePackage implements ReactPackage {

    private final RNAirPatchManager mPatchManager;
    private final RNAirLiteHost mHostHandle;

    public RNAirLitePackage(RNAirPatchManager patchManager, RNAirLiteHost host) {
        mPatchManager = patchManager;
        mHostHandle = host;
    }

    @Override
    public List<Class<? extends JavaScriptModule>> createJSModules() {
        return Collections.emptyList();
    }

    @Override
    public List<ViewManager> createViewManagers(ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }

    @Override
    public List<NativeModule> createNativeModules(
            ReactApplicationContext reactContext) {
        List<NativeModule> modules = new ArrayList<>();

        modules.add(new RNAirLiteModule(reactContext, mPatchManager, mHostHandle));

        return modules;
    }
}
