// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.psiphon3.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.slipnet.scanner.DnsTunnelResult;
import app.slipnet.scanner.ScanResult;

/** Rows of the resolver scan. The activity owns the selection set; the adapter only reads it. */
public class ScanResultAdapter extends BaseAdapter {
    private static final String DASH = "\u2014";

    private final LayoutInflater inflater;
    private final List<ScanResult> items = new ArrayList<ScanResult>();
    private final Set<String> selected;
    private final ColorDrawable selectedBackground = new ColorDrawable(0x332196F3);

    public ScanResultAdapter(Context context, Set<String> selected) {
        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.selected = selected;
    }

    public void setItems(List<ScanResult> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public ScanResult getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        Holder holder;
        if (row == null) {
            row = inflater.inflate(R.layout.slipnet_scan_row, parent, false);
            holder = new Holder();
            holder.host = row.findViewById(R.id.slipnet_scan_row_host);
            holder.status = row.findViewById(R.id.slipnet_scan_row_status);
            holder.detail = row.findViewById(R.id.slipnet_scan_row_detail);
            holder.prism = row.findViewById(R.id.slipnet_scan_row_prism);
            holder.e2e = row.findViewById(R.id.slipnet_scan_row_e2e);
            row.setTag(holder);
        } else {
            holder = (Holder) row.getTag();
        }

        ScanResult result = items.get(position);
        String key = result.key();
        holder.host.setText(key);
        holder.status.setText(statusText(result));
        if (result.tunnel == null) {
            holder.detail.setVisibility(View.GONE);
        } else {
            holder.detail.setVisibility(View.VISIBLE);
            holder.detail.setText(result.tunnel.score() + "/" + DnsTunnelResult.MAX_SCORE
                    + "  " + result.tunnel.details());
        }
        holder.prism.setText(prismText(result));
        holder.e2e.setText(e2eText(result));
        row.setBackgroundDrawable(selected.contains(key) ? selectedBackground : null);
        return row;
    }

    private static String statusText(ScanResult result) {
        StringBuilder sb = new StringBuilder(result.status.name());
        if (result.responseTimeMs >= 0) {
            sb.append(" \u00b7 ").append(result.responseTimeMs).append(" ms");
        }
        return sb.toString();
    }

    private static String prismText(ScanResult result) {
        if (result.prismPassed == null || result.prismTotal == null || result.prismTotal == 0) {
            return "Prism " + DASH;
        }
        return "Prism " + result.prismPassed + "/" + result.prismTotal;
    }

    private static String e2eText(ScanResult result) {
        if (result.e2e == null) {
            return "E2E " + DASH;
        }
        if (result.e2e.success) {
            return "E2E " + result.e2e.totalMs + " ms";
        }
        String phase = result.e2e.phase;
        return phase == null || phase.length() == 0 ? "E2E fail" : "E2E fail " + phase;
    }

    private static final class Holder {
        TextView host;
        TextView status;
        TextView detail;
        TextView prism;
        TextView e2e;
    }
}
