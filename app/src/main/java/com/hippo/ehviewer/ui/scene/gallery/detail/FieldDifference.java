package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FieldDifference {

    public final String fieldName;
    public final String displayLabel;
    public final String dbValue;
    public final String netValue;
    public boolean selected;

    public FieldDifference(String fieldName, String displayLabel, String dbValue, String netValue) {
        this.fieldName = fieldName;
        this.displayLabel = displayLabel;
        this.dbValue = dbValue;
        this.netValue = netValue;
        this.selected = true;
    }

    public static List<FieldDifference> detect(DownloadInfo db, GalleryDetail net, Context context) {
        List<FieldDifference> diffs = new ArrayList<>();

        check(diffs, "title", context.getString(R.string.field_update_label_title),
                truncate(db.title), truncate(net.title));
        // Skip titleJpn when DB is empty — auto-updated silently elsewhere
        if (!isEmpty(db.titleJpn)) {
            check(diffs, "titleJpn", context.getString(R.string.field_update_label_title_jpn),
                    truncate(db.titleJpn), truncate(net.titleJpn));
        }
        check(diffs, "uploader", context.getString(R.string.field_update_label_uploader),
                db.uploader, net.uploader);
        check(diffs, "category", context.getString(R.string.field_update_label_category),
                EhUtils.getCategory(db.category), EhUtils.getCategory(net.category));
        check(diffs, "posted", context.getString(R.string.field_update_label_posted),
                db.posted, net.posted);
        check(diffs, "thumb", context.getString(R.string.field_update_label_thumb),
                truncate(db.thumb), truncate(net.thumb));

        // pages and rating are auto-synced silently, not shown in dialog

        return diffs;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void check(List<FieldDifference> diffs, String fieldName,
                              String label, String dbVal, String netVal) {
        if (!Objects.equals(dbVal, netVal)) {
            diffs.add(new FieldDifference(fieldName, label,
                    dbVal != null ? dbVal : "", netVal != null ? netVal : ""));
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
