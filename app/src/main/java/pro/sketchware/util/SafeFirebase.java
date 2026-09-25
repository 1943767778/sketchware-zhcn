package pro.sketchware.util;

import android.util.Log;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Null-safe wrapper around Firebase Crashlytics.
 * The local build has no google-services.json, so Firebase may be uninitialized.
 * All crash reporting calls degrade to no-ops instead of crashing the app.
 */
public final class SafeFirebase {

    private static final String TAG = "SafeFirebase";
    private static volatile FirebaseCrashlytics sCrashlytics;

    static {
        try {
            sCrashlytics = FirebaseCrashlytics.getInstance();
        } catch (Throwable t) {
            Log.w(TAG, "Firebase Crashlytics unavailable, running without crash reporting", t);
            sCrashlytics = null;
        }
    }

    private SafeFirebase() {
    }

    public static FirebaseCrashlytics getCrashlytics() {
        return sCrashlytics;
    }

    public static void log(String message) {
        try {
            if (sCrashlytics != null) {
                sCrashlytics.log(message);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void recordException(Throwable throwable) {
        try {
            if (sCrashlytics != null) {
                sCrashlytics.recordException(throwable);
            }
        } catch (Throwable ignored) {
        }
    }
}
