package com.ccs.shard.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Wraps one already-built view as a single-item adapter.
 *
 * <p>Lets a header or footer be concatenated onto a virtualising list with
 * {@link androidx.recyclerview.widget.ConcatAdapter}, instead of the usual trick
 * of teaching the main adapter about extra view types — which is how position
 * arithmetic bugs get in.
 */
public final class SingleViewAdapter extends RecyclerView.Adapter<SingleViewAdapter.Holder> {

    private final View view;
    private final long itemId;
    private boolean visible = true;

    public SingleViewAdapter(View view, long itemId) {
        this.view = view;
        this.itemId = itemId;
        setHasStableIds(true);
    }

    public View view() { return view; }

    public void setVisible(boolean value) {
        if (visible == value) return;
        visible = value;
        if (value) notifyItemInserted(0);
        else notifyItemRemoved(0);
    }

    /** Re-binds the hosted view; call after mutating its contents. */
    public void refresh() {
        if (visible) notifyItemChanged(0);
    }

    @Override
    public int getItemCount() { return visible ? 1 : 0; }

    @Override
    public long getItemId(int position) { return itemId; }

    @Override
    public int getItemViewType(int position) { return (int) itemId; }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewGroup.LayoutParams existing = view.getLayoutParams();
        if (!(existing instanceof RecyclerView.LayoutParams)) {
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        // The hosted view owns its own state; nothing to bind.
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View itemView) { super(itemView); }
    }
}
