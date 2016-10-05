package com.kh.rnairlite;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.facebook.infer.annotation.Assertions;
import com.facebook.react.LifecycleState;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactNativeHost;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.DefaultNativeModuleCallExceptionHandler;
import com.facebook.react.bridge.NativeModuleCallExceptionHandler;
import com.facebook.react.bridge.UiThreadUtil;

import junit.framework.Assert;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

/**
 * Created by KH on 9/14/16.
 */
public abstract class RNAirLiteHost extends ReactNativeHost {
    private final
      DefaultNativeModuleCallExceptionHandler mDefaultExceptionHandler =
      new DefaultNativeModuleCallExceptionHandler();

    private final RNAirPatchManager mPatchManager;
    private final Application mApplication;

    public RNAirLiteHost(Application application) {
        super(application);
        Assert.assertNotNull(application);
        mApplication = application;
        mPatchManager = new RNAirPatchManager(application, getJSMainModuleName());
    }

    @Override
    protected final @Nullable String getJSBundleFile() {
        if (getUseDeveloperSupport()) return null;
        return mPatchManager.getJSBundleFile();
    }

    @Override
    protected final ReactInstanceManager createReactInstanceManager() {
        // FIXME init patch manager as early as possible once Application constructed.
        mPatchManager.setup();
        ReactInstanceManager.Builder builder = ReactInstanceManager.builder()
                .setApplication(mApplication)
                .setJSMainModuleName(getJSMainModuleName())
                .setUseDeveloperSupport(getUseDeveloperSupport())
                .setInitialLifecycleState(LifecycleState.BEFORE_CREATE)
                .setNativeModuleCallExceptionHandler(
                new NativeModuleCallExceptionHandler() {
                    @Override
                    public void handleException(Exception e) {
                        Log.e(RNAirLiteModule.Tag, "Got an Exception!");
                        if (!hasInstance() || !mPatchManager.hasAnyPatches()) {
                            mDefaultExceptionHandler.handleException(e);
                            return;
                        }

                        if (!mPatchManager.rollback()) {
                            mDefaultExceptionHandler.handleException(e);
                            return;
                        }

                        reboot();
                    }
                });

        for (ReactPackage reactPackage : getPackages()) {
            builder.addPackage(reactPackage);
        }

        builder.addPackage(new RNAirLitePackage(mPatchManager, this));

        String jsBundleFile = getJSBundleFile();
        if (jsBundleFile != null) {
            builder.setJSBundleFile(jsBundleFile);
        } else {
            builder.setBundleAssetName(Assertions.assertNotNull(getBundleAssetName()));
        }

        return builder.build();
    }

    public void reboot() {
        UiThreadUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> RIManagerClazz = getReactInstanceManager().getClass();
                    Method method = RIManagerClazz.getDeclaredMethod("recreateReactContextInBackground");
                    method.setAccessible(true);
                    method.invoke(getReactInstanceManager());
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
