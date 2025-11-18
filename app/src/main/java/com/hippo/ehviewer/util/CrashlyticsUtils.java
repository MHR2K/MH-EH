package com.hippo.ehviewer.util;

import android.content.Context;
import androidx.annotation.NonNull;
import com.hippo.ehviewer.Settings;

/**
 * Safe wrapper for Firebase Crashlytics calls. No-ops when Firebase is not configured.
 */
public final class CrashlyticsUtils {
    private CrashlyticsUtils() {}

    public static void record(@NonNull Throwable t) {
        // Respect user setting
        if (!Settings.getEnableAnalytics()) return;
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(t);
        } catch (IllegalStateException ignored) {
            // FirebaseApp not initialized or config missing – ignore in non-Firebase builds
        } catch (NoClassDefFoundError ignored) {
            // Crashlytics dependency absent – ignore
        } catch (Throwable ignored) {
            // Any other unforeseen issues – ignore to avoid crashing
        }
    }

    public static void initIfPossible(@NonNull Context context) {
        if (!Settings.getEnableAnalytics()) return;
        try {
            // Best-effort initialize; returns null if google-services config is missing
            com.google.firebase.FirebaseApp.initializeApp(context);
        } catch (Throwable ignored) {
        }
    }
}
