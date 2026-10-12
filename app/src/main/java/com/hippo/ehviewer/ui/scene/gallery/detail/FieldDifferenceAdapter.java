package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FieldDifferenceAdapter extends RecyclerView.Adapter<FieldDifferenceAdapter.ViewHolder> {

    private final List<FieldDifference> items;

    public FieldDifferenceAdapter(List<FieldDifference> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dialog_field_update_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FieldDifference item = items.get(position);
        Context context = holder.itemView.getContext();
        holder.fieldLabel.setText(item.displayLabel);

        String emptyStr = context.getString(R.string.field_update_empty);
        String dbText = isEmpty(item.dbValue) ? emptyStr : item.dbValue;
        String netText = isEmpty(item.netValue) ? emptyStr : item.netValue;

        holder.dbValue.setText(context.getString(R.string.field_update_local, dbText));
        holder.netValue.setText(context.getString(R.string.field_update_online, netText));

        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(item.selected);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> item.selected = isChecked);

        holder.itemView.setOnClickListener(v -> holder.checkBox.toggle());
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public Set<String> getSelectedFields() {
        Set<String> fields = new HashSet<>();
        for (FieldDifference item : items) {
            if (item.selected) {
                fields.add(item.fieldName);
            }
        }
        return fields;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final CheckBox checkBox;
        final TextView fieldLabel;
        final TextView dbValue;
        final TextView netValue;

        ViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.checkbox);
            fieldLabel = itemView.findViewById(R.id.field_label);
            dbValue = itemView.findViewById(R.id.db_value);
            netValue = itemView.findViewById(R.id.net_value);
        }
    }
}
