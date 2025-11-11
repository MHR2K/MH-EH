package com.hippo.ehviewer.util;

import android.text.TextUtils;

public final class ArchiveSupportUtils {

    private static final String[] SUPPORTED_EXT = {"zip", "cbz"};

    private ArchiveSupportUtils() {}

    public static boolean isSupportedArchiveName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String lower = name.toLowerCase();
        for (String ext : SUPPORTED_EXT) {
            if (lower.endsWith("." + ext)) return true;
        }
        return false;
    }

    public static boolean isSupportedArchiveExtension(String ext) {
        if (ext == null) return false;
        String lower = ext.toLowerCase();
        for (String e : SUPPORTED_EXT) {
            if (lower.equals(e)) return true;
        }
        return false;
    }
}
