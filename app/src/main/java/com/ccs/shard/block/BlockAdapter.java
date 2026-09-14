package com.ccs.shard.block;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.shard.core.TaskMetadata;
import com.ccs.shard.R;
import com.ccs.shard.ui.Ui;

import java.util.List;

/**
 * Renders a {@link BlockDocument} as a list of editable blocks.
 *
 * <p>Only the blocks on screen exist as views, which is the whole point of the
 * block model on a low-end device: a 500-block note costs the same to scroll as
 * a 10-block one, where a single giant {@code EditText} would re-layout and
 * re-span the entire document on every keystroke.
 *
 * <p>Editing behaviour lives here rather than in the holders so that all the
 * cross-block operations — Enter splitting a block, Backspace merging into the
 * previous one, Markdown shortcuts converting a block as you type — can see the
 * whole document.
 */
public final class BlockAdapter extends RecyclerView.Adapter<BlockAdapter.Holder> {

    public static final int INLINE_WIKI_LINK = 1;
    public static final int INLINE_TAG = 2;

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_CODE = 1;
    private static final int TYPE_TABLE = 2;
    private static final int TYPE_IMAGE = 3;
    private static final int TYPE_DIVIDER = 4;

    /** Callbacks into the editor screen. */
    public interface Host {
        /**
         * Content changed; schedule an autosave.
         *
         * <p>Deliberately not called {@code onContentChanged}: that name collides
         * with {@link android.view.Window.Callback#onContentChanged()}, which
         * AppCompat invokes from {@code setContentView} — before an activity's
         * views exist.
         */
        void onDocumentEdited();

        /** True when a wiki-link target resolves to an existing note. */
        boolean isLinkResolved(String target);

        void onLinkClicked(int kind, String target);

        /** The user typed {@code /} at the start of an empty block. */
        void onSlashOpened(BlockEditText field, int position);

        /** Text after the slash changed. */
        void onSlashQuery(String query);

        void onSlashClosed();

        /** Autocomplete target immediately before the caret. */
        void onInlineSuggestion(BlockEditText field, int position, int kind,
                                String query, int replaceStart, int replaceEnd);

        void onInlineSuggestionClosed();

        /** Long-press on a block's handle. */
        void onBlockMenu(View anchor, int position);

        /** Long-press on a block's handle starts direct reordering. */
        void onBlockDrag(RecyclerView.ViewHolder holder);

        void onPickImage(int position);

        /** Copy text to the clipboard with a confirmation. */
        void onCopyText(String text, int confirmationRes);

        InlineMd inline();
    }

    private final Context context;
    private final Host host;
    private BlockDocument document;

    /**
     * Guards holder callbacks while the adapter is writing into the views.
     * A depth counter rather than a boolean, because rendering a block happens
     * inside {@code onBindViewHolder}, which is itself guarded.
     */
    private int bindingDepth;
    /** Block whose field should take focus once it is bound. */
    private long pendingFocusUid = -1;
    private int pendingFocusOffset;
    /** The block that currently owns the caret, so it renders raw Markdown. */
    private long focusedUid = -1;
    /** The holder that owns the caret, so slash text can be cleared without a lookup. */
    private TextHolder focusedHolder;

    private final BlockStyler styler;
    private float baseTextSize = 16f;
    private boolean monospaceBody;
    /** Reading view: blocks render formatted and cannot take the caret. */
    private boolean readOnly;

    public BlockAdapter(Context context, Host host, BlockDocument document) {
        this.context = context;
        this.host = host;
        this.document = document;
        this.styler = new BlockStyler(context, new BlockStyler.Callbacks() {
            @Override public void onToggleChecked(Block block) {
                // A repeated task is completed by rolling it forward, not by
                // leaving a checked historical instance in the note.  The same
                // rule is used from the vault-wide Tasks screen.
                if (!block.checked && TaskMetadata.isRecurring(block.text)) {
                    block.text = TaskMetadata.advanceRecurring(block.text);
                    block.checked = false;
                } else {
                    block.checked = !block.checked;
                }
                notifyBlockChanged(block);
            }

            @Override public void onCycleCallout(Block block) {
                block.calloutKind = BlockStyler.nextCalloutKind(block.calloutKind);
                notifyBlockChanged(block);
            }
        });
        styler.setTypography(baseTextSize, monospaceBody);
        setHasStableIds(true);
    }

    public void setDocument(BlockDocument document) {
        this.document = document;
        focusedUid = -1;
        pendingFocusUid = -1;
        notifyDataSetChanged();
    }

    public BlockDocument document() { return document; }

    /** True while the adapter is writing into views, so holders must stay passive. */
    private boolean binding() { return bindingDepth > 0; }

    public void setBaseTextSize(float sizeSp) {
        this.baseTextSize = sizeSp;
        styler.setTypography(baseTextSize, monospaceBody);
        notifyDataSetChanged();
    }

    public void setMonospaceBody(boolean monospace) {
        this.monospaceBody = monospace;
        styler.setTypography(baseTextSize, monospaceBody);
        notifyDataSetChanged();
    }

    /**
     * Switches between editing and reading view. In reading view no field can
     * take focus, so wiki links become tappable instead of moving the caret.
     */
    public void setReadOnly(boolean value) {
        if (readOnly == value) return;
        readOnly = value;
        focusedUid = -1;
        focusedHolder = null;
        notifyDataSetChanged();
    }

    public boolean isReadOnly() { return readOnly; }

    // ---------------------------------------------------------------- adapter

    @Override
    public int getItemCount() {
        return document == null ? 0 : document.size();
    }

    @Override
    public long getItemId(int position) {
        Block block = document.get(position);
        return block == null ? RecyclerView.NO_ID : block.uid;
    }

    @Override
    public int getItemViewType(int position) {
        Block block = document.get(position);
        if (block == null) return TYPE_TEXT;
        switch (block.type) {
            case CODE: return TYPE_CODE;
            case TABLE: return TYPE_TABLE;
            case IMAGE: return TYPE_IMAGE;
            case DIVIDER: return TYPE_DIVIDER;
            default: return TYPE_TEXT;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        switch (viewType) {
            case TYPE_CODE: return new CodeHolder(new CodeBlockView(context));
            case TYPE_TABLE: return new TableHolder(new TableBlockView(context));
            case TYPE_IMAGE: return new ImageHolder(buildImageView());
            case TYPE_DIVIDER: return new DividerHolder(buildDividerView());
            default: return new TextHolder(buildTextRow());
        }
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Block block = document.get(position);
        if (block == null) return;
        bindingDepth++;
        try {
            holder.bind(block, position);
        } finally {
            bindingDepth--;
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.recycle();
        super.onViewRecycled(holder);
    }

    // ---------------------------------------------------------------- focus

    /** Requests focus in the block at {@code position}, caret at {@code offset}. */
    public void requestFocus(int position, int offset) {
        Block block = document.get(position);
        if (block == null) return;
        pendingFocusUid = block.uid;
        pendingFocusOffset = offset;
        notifyItemChanged(position);
    }

    public void requestFocusAtEnd(int position) {
        requestFocus(position, Integer.MAX_VALUE);
    }

    public int focusedPosition() {
        return focusedUid < 0 ? -1 : document.indexOfUid(focusedUid);
    }

    private void setFocused(long uid) {
        if (focusedUid == uid) return;
        long previous = focusedUid;
        focusedUid = uid;
        // Re-render the block that lost the caret so its markers disappear.
        if (previous >= 0) {
            int index = document.indexOfUid(previous);
            if (index >= 0) notifyItemChanged(index);
        }
    }

    // ---------------------------------------------------------------- editing

    /** Applies a block-type change at {@code position}, keeping focus. */
    public void convert(int position, BlockType type) {
        Block block = document.get(position);
        if (block == null) return;
        document.markDefinitionsDirty();
        if (type == BlockType.TABLE) {
            // A table replaces the (empty) block it was invoked from.
            Block table = Block.table(TableData.empty(3, 2));
            document.blocks().set(position, table);
            notifyItemChanged(position);
            ensureTrailingParagraph();
            host.onDocumentEdited();
            return;
        }
        if (type == BlockType.DIVIDER) {
            Block divider = Block.divider();
            document.blocks().set(position, divider);
            notifyItemChanged(position);
            int next = position + 1;
            if (next >= document.size()) {
                document.add(Block.paragraph(""));
                notifyItemInserted(next);
            }
            requestFocusAtEnd(Math.min(next, document.size() - 1));
            host.onDocumentEdited();
            return;
        }
        if (type == BlockType.IMAGE) {
            host.onPickImage(position);
            return;
        }
        block.convertTo(type);
        if (type.isHeading()) block.indent = BlockDocument.headingLevelOf(type);
        else if (!type.isList()) block.indent = 0;
        notifyItemChanged(position);
        requestFocusAtEnd(position);
        host.onDocumentEdited();
    }

    /** Inserts {@code block} after {@code position} and focuses it. */
    public void insertAfter(int position, Block block) {
        document.add(position + 1, block);
        notifyItemInserted(position + 1);
        requestFocusAtEnd(position + 1);
        host.onDocumentEdited();
    }

    public void deleteBlock(int position) {
        if (document.size() <= 1) {
            Block only = document.get(0);
            if (only != null) {
                only.convertTo(BlockType.PARAGRAPH);
                only.text = "";
                document.markDefinitionsDirty();
                notifyItemChanged(0);
            }
            host.onDocumentEdited();
            return;
        }
        document.remove(position);
        notifyItemRemoved(position);
        int focus = Math.max(0, position - 1);
        requestFocusAtEnd(focus);
        host.onDocumentEdited();
    }

    public void moveBlock(int from, int to) {
        if (to < 0 || to >= document.size() || from == to) return;
        document.move(from, to);
        notifyItemMoved(from, to);
        host.onDocumentEdited();
    }

    public void duplicateBlock(int position) {
        Block block = document.get(position);
        if (block == null) return;
        document.add(position + 1, block.copy());
        notifyItemInserted(position + 1);
        host.onDocumentEdited();
    }

    public void indentBlock(int position, int delta) {
        Block block = document.get(position);
        if (block == null || !block.type.isList()) return;
        int updated = Math.max(0, Math.min(5, block.indent + delta));
        if (updated == block.indent) return;
        block.indent = updated;
        notifyItemChanged(position);
        host.onDocumentEdited();
    }

    /** Guarantees the note ends with an empty paragraph so there is always a tap target. */
    public void ensureTrailingParagraph() {
        Block last = document.get(document.size() - 1);
        if (last == null || last.type != BlockType.PARAGRAPH || !last.text.isEmpty()) {
            document.add(Block.paragraph(""));
            notifyItemInserted(document.size() - 1);
        }
    }

    /** Splits the block at {@code position} on the caret. */
    private void splitBlock(int position, int caret) {
        Block block = document.get(position);
        if (block == null) return;
        String text = block.text;
        int at = Math.max(0, Math.min(caret, text.length()));
        String head = text.substring(0, at);
        String tail = text.substring(at);
        if (block.type.isDefinition()) document.markDefinitionsDirty();

        // Enter on an empty list item ends the list, matching every other editor.
        if (block.type.isList() && text.trim().isEmpty()) {
            if (block.indent > 0) {
                block.indent--;
                notifyItemChanged(position);
            } else {
                block.convertTo(BlockType.PARAGRAPH);
                notifyItemChanged(position);
                requestFocusAtEnd(position);
            }
            host.onDocumentEdited();
            return;
        }

        block.text = head;
        Block next = Block.text(block.type.typeForNextBlock(), tail);
        next.indent = block.type.isList() ? block.indent : 0;
        if (next.type.isHeading()) next.indent = BlockDocument.headingLevelOf(next.type);
        document.add(position + 1, next);
        notifyItemChanged(position);
        notifyItemInserted(position + 1);
        requestFocus(position + 1, 0);
        host.onDocumentEdited();
    }

    /** Merges the block at {@code position} into the previous one. */
    private boolean mergeBackwards(int position) {
        if (position <= 0) {
            Block block = document.get(0);
            // Backspace on the first block just drops its block formatting.
            if (block != null && block.type != BlockType.PARAGRAPH) {
                if (block.type.isDefinition()) document.markDefinitionsDirty();
                block.convertTo(BlockType.PARAGRAPH);
                block.indent = 0;
                notifyItemChanged(0);
                requestFocus(0, 0);
                host.onDocumentEdited();
                return true;
            }
            return false;
        }
        Block current = document.get(position);
        Block previous = document.get(position - 1);
        if (current == null || previous == null) return false;

        // Outdent or un-format before removing anything.
        if (current.type.isList() && current.indent > 0) {
            current.indent--;
            notifyItemChanged(position);
            host.onDocumentEdited();
            return true;
        }
        if (current.type != BlockType.PARAGRAPH && !current.text.isEmpty()) {
            if (current.type.isDefinition()) document.markDefinitionsDirty();
            current.convertTo(BlockType.PARAGRAPH);
            current.indent = 0;
            notifyItemChanged(position);
            requestFocus(position, 0);
            host.onDocumentEdited();
            return true;
        }

        if (!previous.type.isText) {
            // Previous block is a table/image/divider: remove it if this one is empty.
            if (current.text.isEmpty()) {
                document.remove(position - 1);
                notifyItemRemoved(position - 1);
                requestFocus(position - 1, 0);
                host.onDocumentEdited();
                return true;
            }
            return false;
        }

        int caret = previous.text.length();
        previous.text = previous.text + current.text;
        document.remove(position);
        notifyItemRemoved(position);
        notifyItemChanged(position - 1);
        requestFocus(position - 1, caret);
        host.onDocumentEdited();
        return true;
    }

    /**
     * Applies Markdown shortcuts as the user types, e.g. {@code "## "} at the
     * start of a block turns it into a heading and swallows the marker.
     *
     * @return true when the block was converted
     */
    private boolean applyMarkdownShortcut(int position, Block block, BlockEditText field) {
        String text = block.text;
        // Only fires when the trigger is the entire block and the caret is after it,
        // so editing existing text never reformats the line under the user.
        if (field.getSelectionStart() != text.length()) return false;
        MarkdownShortcut shortcut = MarkdownShortcut.match(text);
        if (shortcut == null) return false;

        BlockType target = shortcut.target;
        if (block.type == target && target != BlockType.DIVIDER) return false;

        block.text = text.substring(shortcut.consumed());
        document.markDefinitionsDirty();
        if (target == BlockType.CODE) {
            document.blocks().set(position, Block.code("", ""));
            notifyItemChanged(position);
            requestFocusAtEnd(position);
            host.onDocumentEdited();
            return true;
        }
        if (target == BlockType.DIVIDER) {
            convert(position, BlockType.DIVIDER);
            return true;
        }
        block.convertTo(target);
        block.checked = shortcut.checked;
        if (target.isHeading()) block.indent = BlockDocument.headingLevelOf(target);
        notifyItemChanged(position);
        requestFocus(position, 0);
        host.onDocumentEdited();
        return true;
    }

    /** Replaces one block with the blocks parsed from pasted multi-line text. */
    private void replaceWithParsed(int position, String combinedText) {
        BlockDocument parsed = BlockDocument.parse(combinedText);
        List<Block> newBlocks = parsed.blocks();
        document.remove(position);
        for (int i = 0; i < newBlocks.size(); i++) {
            document.add(position + i, newBlocks.get(i));
        }
        notifyDataSetChanged();
        requestFocusAtEnd(Math.min(position + newBlocks.size() - 1, document.size() - 1));
        host.onDocumentEdited();
    }

    // ---------------------------------------------------------------- view building

    private View buildTextRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        BlockEditText input = new BlockEditText(context);
        ImageView handle = new ImageView(context) {
            private final Rect letterBounds = new Rect();

            @Override public int getBaseline() {
                // Align the grip's centre with the letters on the first line,
                // even when the block wraps or uses heading typography.
                input.getPaint().getTextBounds("H", 0, 1, letterBounds);
                return Math.round(getMeasuredHeight() / 2f - letterBounds.exactCenterY());
            }
        };
        handle.setId(R.id.block_handle);
        handle.setImageResource(R.drawable.ic_block_handle);
        handle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams handleParams =
                new LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 30));
        handle.setLayoutParams(handleParams);
        handle.setAlpha(0.28f);
        handle.setContentDescription(context.getString(R.string.block_options));
        row.addView(handle);

        LinearLayout leading = new LinearLayout(context) {
            @Override public int getBaseline() {
                // Expose the marker's baseline to the outer horizontal row.
                // Non-text decorations and empty containers have no baseline.
                if (getChildCount() == 0) return -1;
                View marker = getChildAt(0);
                int baseline = marker.getBaseline();
                return baseline < 0 ? -1 : marker.getTop() + baseline;
            }
        };
        leading.setId(R.id.block_leading);
        leading.setOrientation(LinearLayout.HORIZONTAL);
        leading.setGravity(Gravity.TOP);
        leading.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(leading);

        input.setId(R.id.block_input);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setSingleLine(false);
        input.setHorizontallyScrolling(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(input);
        return row;
    }

    private View buildImageView() {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.setPaddingDp(container, 22, 6, 4, 6);

        ImageView image = new ImageView(context);
        image.setId(R.id.block_image);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(image);

        TextView caption = new TextView(context);
        caption.setId(R.id.block_caption);
        caption.setTextSize(12f);
        caption.setTextColor(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575));
        Ui.setPaddingDp(caption, 2, 6, 2, 0);
        container.addView(caption);
        return container;
    }

    private View buildDividerView() {
        LinearLayout container = new LinearLayout(context);
        container.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 34)));
        container.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(container, 22, 0, 4, 0);

        View line = new View(context);
        line.setId(R.id.block_divider_line);
        line.setBackgroundColor(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0x33808080));
        container.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1)));
        return container;
    }

    // ---------------------------------------------------------------- holders

    public abstract class Holder extends RecyclerView.ViewHolder {
        Holder(View itemView) { super(itemView); }

        abstract void bind(Block block, int position);

        void recycle() {}
    }

    /** Paragraphs, headings, list items, quotes and callouts. */
    final class TextHolder extends Holder {
        private final ImageView handle;
        private final LinearLayout leading;
        private final BlockEditText input;
        private final TextWatcher watcher;
        private Block bound;
        /** Set while a slash palette is open for this field. */
        private boolean slashActive;
        private int slashStart = -1;

        TextHolder(View row) {
            super(row);
            handle = row.findViewById(R.id.block_handle);
            leading = row.findViewById(R.id.block_leading);
            input = row.findViewById(R.id.block_input);

            watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (binding() || bound == null) return;
                    bound.text = s.toString();
                    if (bound.type.isDefinition()) document.markDefinitionsDirty();
                    int position = document.indexOfUid(bound.uid);
                    if (position < 0) return;
                    if (applyMarkdownShortcut(position, bound, input)) return;
                    updateSlashState();
                    updateInlineSuggestion(position);
                    host.onDocumentEdited();
                }
            };

            input.setFilters(new InputFilter[]{new InputFilter() {
                @Override
                public CharSequence filter(CharSequence source, int start, int end,
                                           Spanned dest, int dstart, int dend) {
                    // Only user input splits blocks. Rendered block text legitimately
                    // contains newlines (a paragraph with a soft line break), and
                    // treating those as an Enter press blanked the block and queued a
                    // spurious document rewrite.
                    if (source == null || binding()) return null;
                    boolean hasNewline = false;
                    for (int i = start; i < end; i++) {
                        if (source.charAt(i) == '\n') { hasNewline = true; break; }
                    }
                    if (!hasNewline || bound == null || input.isInsertingSoftLineBreak()) {
                        return null;
                    }

                    final int position = document.indexOfUid(bound.uid);
                    if (position < 0) return null;
                    final String inserted = source.subSequence(start, end).toString();

                    if (inserted.equals("\n")) {
                        final int caret = dstart;
                        input.post(new Runnable() {
                            @Override public void run() { splitBlock(position, caret); }
                        });
                        return "";
                    }
                    // Multi-line paste: reparse into real blocks.
                    final String combined = dest.subSequence(0, dstart)
                            + inserted
                            + dest.subSequence(dend, dest.length());
                    input.post(new Runnable() {
                        @Override public void run() { replaceWithParsed(position, combined); }
                    });
                    return "";
                }
            }});

            input.setListener(new BlockEditText.Listener() {
                @Override public boolean onBackspaceAtStart() {
                    if (bound == null) return false;
                    int position = document.indexOfUid(bound.uid);
                    if (position < 0) return false;
                    return mergeBackwards(position);
                }

                @Override public boolean onTab(boolean shift) {
                    if (bound == null || !bound.type.isList()) return false;
                    int position = document.indexOfUid(bound.uid);
                    if (position < 0) return false;
                    indentBlock(position, shift ? -1 : 1);
                    return true;
                }
            });

            input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (bound == null) return;
                    handle.setAlpha(hasFocus ? 0.75f : 0.28f);
                    if (hasFocus) {
                        setFocused(bound.uid);
                        focusedHolder = TextHolder.this;
                        renderText(bound, true);
                    } else {
                        closeSlash();
                        host.onInlineSuggestionClosed();
                        if (focusedUid == bound.uid) focusedUid = -1;
                        if (focusedHolder == TextHolder.this) focusedHolder = null;
                        renderText(bound, false);
                    }
                }
            });

            input.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() != MotionEvent.ACTION_UP) return false;
                    return handleLinkTap(event.getX(), event.getY());
                }
            });

            handle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openBlockMenu(v);
                }
            });
            handle.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        // Immediate hold feedback teaches the gesture before the
                        // long-press threshold is reached.
                        handle.animate().cancel();
                        handle.animate().scaleX(1.28f).scaleY(1.28f)
                                .alpha(0.9f).setDuration(180L).start();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        handle.animate().cancel();
                        handle.animate().scaleX(1f).scaleY(1f)
                                .alpha(input.hasFocus() ? 0.75f : 0.28f)
                                .setDuration(160L).start();
                    }
                    // Keep click and long-click dispatch owned by ImageView.
                    return false;
                }
            });
            handle.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.hapticLongPress(v);
                    host.onBlockDrag(TextHolder.this);
                    return true;
                }
            });
        }

        private void openBlockMenu(View anchor) {
            if (bound == null) return;
            int position = document.indexOfUid(bound.uid);
            if (position >= 0) host.onBlockMenu(anchor, position);
        }

        private boolean handleLinkTap(float x, float y) {
            if (bound == null) return false;
            CharSequence text = input.getText();
            if (!(text instanceof Spanned)) return false;
            int offset = input.offsetForPosition(x, y);
            if (offset < 0) return false;
            LinkSpan[] spans = ((Spanned) text).getSpans(offset, offset, LinkSpan.class);
            if (spans.length == 0) return false;
            // While editing, a tap inside the text should move the caret rather
            // than follow a link; in reading view the link always wins.
            if (!readOnly && input.hasFocus()) return false;
            host.onLinkClicked(spans[0].kind, spans[0].target);
            return true;
        }

        @Override
        void bind(Block block, int position) {
            bound = null;
            input.removeTextChangedListener(watcher);
            this.bound = block;

            styleFor(block);
            buildLeading(block, position);
            boolean focused = focusedUid == block.uid;
            renderText(block, focused);
            input.addTextChangedListener(watcher);

            if (pendingFocusUid == block.uid) {
                pendingFocusUid = -1;
                final int offset = pendingFocusOffset;
                input.post(new Runnable() {
                    @Override public void run() {
                        input.requestFocus();
                        int length = input.getText().length();
                        input.setSelection(Math.max(0, Math.min(offset, length)));
                        Ui.showKeyboard(input);
                    }
                });
            }
            handle.setVisibility(readOnly ? View.GONE : View.VISIBLE);
            handle.setAlpha(focused ? 0.75f : 0.28f);
        }

        private void renderText(Block block, boolean raw) {
            bindingDepth++;
            try {
                InlineMd inline = host.inline();
                CharSequence rendered = raw
                        ? inline.renderRaw(block.text)
                        : inline.render(block.text);
                input.setText(rendered);
                if (raw) {
                    int length = input.getText().length();
                    input.setSelection(Math.min(input.getSelectionStart() < 0
                            ? length : input.getSelectionStart(), length));
                }
            } finally {
                bindingDepth--;
            }
        }

        /** Applies typography, spacing and background for the block's type. */
        private void styleFor(Block block) {
            styler.applyTo(itemView, input, block, readOnly);
        }

        private void buildLeading(final Block block, int position) {
            styler.buildLeading(leading, block, numberFor(position, block));
        }

        /** Detects and tracks a {@code /} palette session for this field. */
        private void updateSlashState() {
            String text = bound.text;
            int caret = input.getSelectionStart();
            if (!slashActive) {
                // Only trigger at the very start of an otherwise empty block: an
                // unconditional trigger fires on file paths and dates.
                if (caret == 1 && text.equals("/")) {
                    slashActive = true;
                    slashStart = 0;
                    host.onSlashOpened(input, document.indexOfUid(bound.uid));
                }
                return;
            }
            if (caret <= slashStart || slashStart >= text.length()
                    || text.charAt(slashStart) != '/') {
                closeSlash();
                return;
            }
            host.onSlashQuery(text.substring(slashStart + 1, Math.min(caret, text.length())));
        }

        private void closeSlash() {
            if (!slashActive) return;
            slashActive = false;
            slashStart = -1;
            host.onSlashClosed();
        }

        private void updateInlineSuggestion(int position) {
            if (slashActive) {
                host.onInlineSuggestionClosed();
                return;
            }
            String text = bound.text == null ? "" : bound.text;
            int caret = input.getSelectionStart();
            if (caret < 0 || caret > text.length()) {
                host.onInlineSuggestionClosed();
                return;
            }
            int start = Math.max(0, caret - 100);
            String before = text.substring(start, caret);

            int wiki = before.lastIndexOf("[[");
            int closedWiki = before.lastIndexOf("]]");
            if (wiki >= 0 && wiki > closedWiki) {
                String query = before.substring(wiki + 2);
                if (query.length() <= 80 && query.indexOf('[') < 0
                        && query.indexOf(']') < 0 && query.indexOf('\n') < 0) {
                    host.onInlineSuggestion(input, position, INLINE_WIKI_LINK,
                            query, start + wiki, caret);
                    return;
                }
            }

            int tokenStart = caret;
            int minTokenStart = Math.max(0, caret - 60);
            while (tokenStart > minTokenStart && !Character.isWhitespace(text.charAt(tokenStart - 1))) {
                tokenStart--;
            }
            if (tokenStart < caret && text.charAt(tokenStart) == '#') {
                String query = text.substring(tokenStart + 1, caret);
                boolean valid = query.length() <= 48;
                for (int i = 0; valid && i < query.length(); i++) {
                    char c = query.charAt(i);
                    valid = Character.isLetterOrDigit(c) || c == '_' || c == '-'
                            || c == '/';
                }
                if (valid) {
                    host.onInlineSuggestion(input, position, INLINE_TAG,
                            query, tokenStart, caret);
                    return;
                }
            }
            host.onInlineSuggestionClosed();
        }

        /** Removes the typed {@code /query} once a command has been chosen. */
        void consumeSlashText() {
            if (bound == null) return;
            bound.text = "";
            slashActive = false;
            slashStart = -1;
            bindingDepth++;
            try {
                input.setText("");
            } finally {
                bindingDepth--;
            }
        }

        int caretOffset() {
            return input.getSelectionStart();
        }

        void insertAtCaret(String text) {
            int start = Math.max(0, input.getSelectionStart());
            int end = Math.max(start, input.getSelectionEnd());
            Editable editable = input.getText();
            editable.replace(start, end, text);
            input.setSelection(Math.min(start + text.length(), input.length()));
        }

        void wrapSelection(String prefix, String suffix) {
            int start = Math.max(0, input.getSelectionStart());
            int end = Math.max(start, input.getSelectionEnd());
            Editable editable = input.getText();
            String selected = editable.subSequence(start, end).toString();
            editable.replace(start, end, prefix + selected + suffix);
            if (selected.isEmpty()) {
                input.setSelection(Math.min(start + prefix.length(), input.length()));
            } else {
                input.setSelection(Math.min(end + prefix.length() + suffix.length(),
                        input.length()));
            }
        }

        @Override
        void recycle() {
            input.removeTextChangedListener(watcher);
            closeSlash();
            if (focusedHolder == this) focusedHolder = null;
            bound = null;
        }
    }

    /** Redraws the row for {@code block} and reports the edit. */
    private void notifyBlockChanged(Block block) {
        int index = document.indexOfUid(block.uid);
        if (index >= 0) notifyItemChanged(index);
        host.onDocumentEdited();
    }

    private int numberFor(int position, Block block) {
        int number = 1;
        for (int i = position - 1; i >= 0; i--) {
            Block previous = document.get(i);
            if (previous == null) break;
            if (previous.type != BlockType.NUMBERED) break;
            if (previous.indent != block.indent) {
                if (previous.indent < block.indent) break;
                continue;
            }
            number++;
        }
        return number;
    }

    // ---------------------------------------------------------------- other holders

    final class CodeHolder extends Holder {
        private final CodeBlockView view;

        CodeHolder(CodeBlockView view) {
            super(view);
            this.view = view;
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        void bind(final Block block, int position) {
            view.bind(block, new CodeBlockView.Callbacks() {
                @Override public void onCodeChanged() { host.onDocumentEdited(); }

                @Override public void onMenu(View anchor) {
                    int index = document.indexOfUid(block.uid);
                    if (index >= 0) host.onBlockMenu(anchor, index);
                }

                @Override public void onDrag(View anchor) {
                    Ui.hapticLongPress(anchor);
                    host.onBlockDrag(CodeHolder.this);
                }

                @Override public void onCopy(String text) {
                    host.onCopyText(text, R.string.copied_code);
                }
            });
        }
    }

    final class TableHolder extends Holder {
        private final TableBlockView view;

        TableHolder(TableBlockView view) {
            super(view);
            this.view = view;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(context, 6);
            lp.bottomMargin = Ui.dp(context, 6);
            lp.leftMargin = Ui.dp(context, 18);
            view.setLayoutParams(lp);
        }

        @Override
        void bind(final Block block, int position) {
            if (block.table == null) block.table = TableData.empty(3, 2);
            view.bind(block.table,
                    new TableBlockView.Callbacks() {
                        @Override public void onTableChanged() { host.onDocumentEdited(); }

                        @Override public void onBlockMenu(View anchor) {
                            int index = document.indexOfUid(block.uid);
                            if (index >= 0) host.onBlockMenu(anchor, index);
                        }

                        @Override public void onBlockDrag(View anchor) {
                            host.onBlockDrag(TableHolder.this);
                        }
                    },
                    new Runnable() {
                        @Override public void run() {
                            int index = document.indexOfUid(block.uid);
                            if (index >= 0) deleteBlock(index);
                        }
                    },
                    new Runnable() {
                        @Override public void run() {
                            host.onCopyText(block.table.toMarkdown(), R.string.copied_table);
                        }
                    });
        }
    }

    final class ImageHolder extends Holder {
        private final ImageView image;
        private final TextView caption;

        ImageHolder(View container) {
            super(container);
            image = container.findViewById(R.id.block_image);
            caption = container.findViewById(R.id.block_caption);
        }

        @Override
        void bind(final Block block, int position) {
            caption.setText(block.imageAlt);
            caption.setVisibility(block.imageAlt.isEmpty() ? View.GONE : View.VISIBLE);
            ImageLoader.load(image, block.imageRef);
            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.hapticLongPress(v);
                    host.onBlockDrag(ImageHolder.this);
                    return true;
                }
            });
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int index = document.indexOfUid(block.uid);
                    if (index >= 0) host.onBlockMenu(v, index);
                }
            });
        }
    }

    final class DividerHolder extends Holder {
        DividerHolder(View container) {
            super(container);
        }

        @Override
        void bind(final Block block, int position) {
            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.hapticLongPress(v);
                    host.onBlockDrag(DividerHolder.this);
                    return true;
                }
            });
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int index = document.indexOfUid(block.uid);
                    if (index >= 0) host.onBlockMenu(v, index);
                }
            });
        }
    }

    /** Clears the typed {@code /query} from the block that is being edited. */
    public void consumeSlashText() {
        if (focusedHolder != null) {
            focusedHolder.consumeSlashText();
            return;
        }
        int position = focusedPosition();
        Block block = document.get(position);
        if (block != null) block.text = "";
    }

    /** Position of the block that owns the caret, resolved fresh. */
    public int caretPosition() {
        int position = focusedPosition();
        return position >= 0 ? position : Math.max(0, document.size() - 1);
    }

    /** Caret offset inside the focused block, or -1. */
    public int caretOffset() {
        return focusedHolder != null ? focusedHolder.caretOffset() : -1;
    }

    /** Inserts inline text at the caret of the focused block. */
    public void insertAtCaret(String text) {
        if (focusedHolder != null) {
            focusedHolder.insertAtCaret(text);
            return;
        }
        int position = caretPosition();
        Block block = document.get(position);
        if (block == null || !block.type.isText) return;
        block.text = block.text + text;
        notifyItemChanged(position);
        requestFocusAtEnd(position);
        host.onDocumentEdited();
    }

    /** Wraps the selection (or inserts markers at the caret) of the focused block. */
    public void wrapSelection(String prefix, String suffix) {
        if (focusedHolder == null) {
            insertAtCaret(prefix + suffix);
            return;
        }
        focusedHolder.wrapSelection(prefix, suffix);
    }
}
