package com.yadaventerprise.phoneserver.ui.security;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.security.SecurityFinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SecurityFindingAdapter extends RecyclerView.Adapter<SecurityFindingAdapter.Holder> {
    private final List<SecurityFinding> findings = new ArrayList<>();

    public void submit(List<SecurityFinding> items) {
        findings.clear();
        if (items != null) findings.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_security_finding, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SecurityFinding finding = findings.get(position);
        holder.title.setText(finding.title);
        holder.severity.setText(finding.severity.toUpperCase(Locale.US));
        holder.detail.setText(finding.detail);
        holder.remediation.setText(finding.remediation);
        holder.remediation.setVisibility(finding.remediation.isEmpty() ? View.GONE : View.VISIBLE);

        int color = colorFor(holder.itemView, finding.severity);
        holder.severity.setTextColor(color);
        holder.severity.setBackgroundTintList(ColorStateList.valueOf(
                Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))));
    }

    @Override
    public int getItemCount() {
        return findings.size();
    }

    private static int colorFor(View view, String severity) {
        int res;
        switch (severity) {
            case SecurityFinding.CRITICAL:
            case SecurityFinding.HIGH:
                res = R.color.offline_red;
                break;
            case SecurityFinding.MEDIUM:
                res = R.color.home_accent;
                break;
            default:
                res = R.color.home_text_secondary;
                break;
        }
        return view.getContext().getColor(res);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView severity;
        final TextView detail;
        final TextView remediation;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.finding_title);
            severity = itemView.findViewById(R.id.finding_severity);
            detail = itemView.findViewById(R.id.finding_detail);
            remediation = itemView.findViewById(R.id.finding_remediation);
        }
    }
}
