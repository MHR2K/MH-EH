package com.hippo.ehviewer.ui.scene.gallery.detail;

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
        holder.fieldLabel.setText(item.displayLabel);
        holder.dbValue.setText(item.dbValue);
        holder.netValue.setText(item.netValue);
        holder.checkBox.setChecked(item.selected);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> item.selected = isChecked);
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
