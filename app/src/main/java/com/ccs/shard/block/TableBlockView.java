package com.ccs.shard.block;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.ccs.shard.R;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.Ui;

/**
 * An editable pipe-table rendered as a real grid.
 *
 * <p>Every cell is a live {@link EditText}, so editing a table never means
 * editing Markdown by hand. Structural edits are reached by <b>long-pressing the
 * table's frame</b>: the outer border opens a table menu, the strip above a
 * column opens a column menu, and the strip left of a row opens a row menu. Each
 * of those is an {@link AnchoredMenu} that appears beside the finger instead of
 * a full-screen dialog.
 *
 * <p>Views are rebuilt only when the table's shape changes; typing in a cell
 * updates the model in place, which keeps a large table from re-laying out on
 * every keystroke.
 */
public final class TableBlockView extends LinearLayout {

    /** Long-press within this distance of the outer edge counts as the frame. */
    private static final float FRAME_SLOP_DP = 18f;

    public interface Callbacks {
        /** Cell text or table shape changed; the editor should schedule a save. */
        void onTableChanged();
        /** Tap and hold on the table-level handle mirror other block handles. */
        void onBlockMenu(View anchor);
        void onBlockDrag(View anchor);
    }

    private static final int MENU_ROW_ABOVE = 1;
    private static final int MENU_ROW_BELOW = 2;
    private static final int MENU_ROW_DELETE = 3;
    private static final int MENU_ROW_UP = 4;
    private static final int MENU_ROW_DOWN = 5;
    private static final int MENU_COL_LEFT = 10;
    private static final int MENU_COL_RIGHT = 11;
    private static final int MENU_COL_DELETE = 12;
    private static final int MENU_COL_ALIGN_LEFT = 13;
    private static final int MENU_COL_ALIGN_CENTER = 14;
    private static final int MENU_COL_ALIGN_RIGHT = 15;
    private static final int MENU_COL_MOVE_LEFT = 16;
    private static final int MENU_COL_MOVE_RIGHT = 17;
    private static final int MENU_TABLE_ADD_ROW = 20;
    private static final int MENU_TABLE_ADD_COL = 21;
    private static final int MENU_TABLE_CLEAR = 22;
    private static final int MENU_TABLE_DELETE = 23;
    private static final int MENU_TABLE_COPY = 24;

    private TableData table;
    private Callbacks callbacks;
    private Runnable onDeleteRequest;
    private Runnable onCopyRequest;

    private final HorizontalScrollView scroller;
    private final LinearLayout grid;
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int frameSlop;
    private int colorOutline;
    private int colorHeaderBg;
    private int colorText;
    private int colorGrip;
    private boolean suppressWatchers;
    private boolean tableChangePending;
    private final Runnable notifyTableChanged = new Runnable() {
        @Override public void run() {
            tableChangePending = false;
            if (callbacks != null) callbacks.onTableChanged();
        }
    };

    public TableBlockView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        frameSlop = Ui.dp(context, FRAME_SLOP_DP);
        resolveColors();

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Ui.dp(context, 1.2f));
        borderPaint.setColor(colorOutline);
        setWillNotDraw(false);

        int pad = Ui.dp(context, 9);
        setPadding(pad, pad, pad, pad);

        grid = new LinearLayout(context);
        grid.setOrientation(VERTICAL);

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(grid);
        addView(scroller, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void resolveColors() {
        Context c = getContext();
        colorOutline = Ui.themeColor(c,
                com.google.android.material.R.attr.colorOutlineVariant, 0x33808080);
        colorText = Ui.themeColor(c,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        int surface = Ui.themeColor(c,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        colorHeaderBg = Ui.blend(surface, Ui.isLight(surface) ? 0xFF000000 : 0xFFFFFFFF, 0.05f);
        colorGrip = Ui.withAlpha(colorText, 0.16f);
    }

    public void bind(TableData data, Callbacks callbacks,
                     Runnable onDeleteRequest, Runnable onCopyRequest) {
        this.table = data;
        this.callbacks = callbacks;
        this.onDeleteRequest = onDeleteRequest;
        this.onCopyRequest = onCopyRequest;
        rebuild();
    }

    public TableData table() { return table; }

    // ---------------------------------------------------------------- frame

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = borderPaint.getStrokeWidth() / 2f;
        float radius = Ui.dp(getContext(), 10);
        canvas.drawRoundRect(inset, inset,
                getWidth() - inset, getHeight() - inset, radius, radius, borderPaint);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Only the frame region is claimed; everything inside belongs to the cells.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isOnFrame(event)) {
            return true;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isOnFrame(event)) {
            final float x = event.getX();
            final float y = event.getY();
            postDelayed(new Runnable() {
                @Override public void run() {
                    Ui.hapticLongPress(TableBlockView.this);
                    showTableMenu(x, y);
                }
            }, android.view.ViewConfiguration.getLongPressTimeout());
            return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean isOnFrame(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        return x <= frameSlop || y <= frameSlop
                || x >= getWidth() - frameSlop || y >= getHeight() - frameSlop;
    }

    // ---------------------------------------------------------------- building

    private void rebuild() {
        grid.removeAllViews();
        if (table == null) return;

        grid.addView(buildColumnGrips());
        for (int r = 0; r < table.rowCount(); r++) {
            grid.addView(buildRow(r));
        }
        grid.addView(buildAddRowButton());
    }

    /** The thin strip above the header: long-press a segment for its column menu. */
    private View buildColumnGrips() {
        LinearLayout strip = new LinearLayout(getContext());
        strip.setOrientation(HORIZONTAL);
        // This first grip owns the table as a block. The following grips continue
        // to own individual columns, so the two kinds of reordering never clash.
        android.widget.ImageView blockGrip = new android.widget.ImageView(getContext());
        blockGrip.setImageResource(R.drawable.ic_block_handle);
        blockGrip.setColorFilter(Ui.withAlpha(colorText, 0.45f));
        blockGrip.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        blockGrip.setContentDescription(getContext().getString(R.string.block_options));
        blockGrip.setLayoutParams(new LayoutParams(
                Ui.dp(getContext(), 16), Ui.dp(getContext(), 14)));
        blockGrip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (callbacks != null) callbacks.onBlockMenu(v);
            }
        });
        blockGrip.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Ui.hapticLongPress(v);
                if (callbacks != null) callbacks.onBlockDrag(v);
                return true;
            }
        });
        strip.addView(blockGrip);
        for (int c = 0; c < table.columnCount(); c++) {
            final int column = c;
            View grip = new View(getContext());
            grip.setBackground(Ui.roundRect(colorGrip, Ui.dp(getContext(), 3)));
            LayoutParams lp = new LayoutParams(cellWidth(), Ui.dp(getContext(), 5));
            lp.setMargins(Ui.dp(getContext(), 2), Ui.dp(getContext(), 4),
                    Ui.dp(getContext(), 2), Ui.dp(getContext(), 4));
            grip.setLayoutParams(lp);
            grip.setContentDescription(getContext().getString(R.string.table_column_handle));
            grip.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.hapticLongPress(v);
                    showColumnMenu(v, column);
                    return true;
                }
            });
            grip.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { showColumnMenu(v, column); }
            });
            strip.addView(grip);
        }
        View add = iconButton(R.drawable.ic_add, R.string.table_add_column);
        add.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                table.addColumn(table.columnCount());
                changedShape();
            }
        });
        strip.addView(add);
        return strip;
    }

    private View buildRow(final int row) {
        LinearLayout rowView = new LinearLayout(getContext());
        rowView.setOrientation(HORIZONTAL);
        boolean header = row == 0;

        View grip = new View(getContext());
        grip.setBackground(Ui.roundRect(colorGrip, Ui.dp(getContext(), 3)));
        LayoutParams gripParams = new LayoutParams(Ui.dp(getContext(), 5), Ui.dp(getContext(), 22));
        gripParams.setMargins(Ui.dp(getContext(), 5), Ui.dp(getContext(), 6),
                Ui.dp(getContext(), 6), Ui.dp(getContext(), 6));
        gripParams.gravity = Gravity.CENTER_VERTICAL;
        grip.setLayoutParams(gripParams);
        grip.setContentDescription(getContext().getString(R.string.table_row_handle));
        grip.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Ui.hapticLongPress(v);
                showRowMenu(v, row);
                return true;
            }
        });
        grip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { showRowMenu(v, row); }
        });
        rowView.addView(grip);

        for (int c = 0; c < table.columnCount(); c++) {
            rowView.addView(buildCell(row, c, header));
        }
        return rowView;
    }

    private View buildCell(final int row, final int column, boolean header) {
        final EditText cell = new EditText(getContext());
        cell.setText(table.cell(row, column));
        cell.setTextSize(14f);
        cell.setTextColor(colorText);
        cell.setBackground(Ui.roundRect(header ? colorHeaderBg : 0x00000000,
                Ui.dp(getContext(), 6), colorOutline, Ui.dp(getContext(), 1)));
        cell.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cell.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        cell.setSingleLine(false);
        cell.setMaxLines(4);
        cell.setGravity(alignmentGravity(table.alignment(column)));
        cell.setTypeface(null, header ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        cell.setHint(header ? getContext().getString(R.string.table_header_hint) : "");
        cell.setHintTextColor(Ui.withAlpha(colorText, 0.32f));
        Ui.setPaddingDp(cell, 9, 7, 9, 7);

        LayoutParams lp = new LayoutParams(cellWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(getContext(), 2), Ui.dp(getContext(), 2),
                Ui.dp(getContext(), 2), Ui.dp(getContext(), 2));
        cell.setLayoutParams(lp);

        cell.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressWatchers) return;
                table.setCell(row, column, s.toString());
                // Serialising a large table on every key is expensive. The model
                // updates immediately, while history/preview/save are coalesced.
                tableChangePending = true;
                removeCallbacks(notifyTableChanged);
                postDelayed(notifyTableChanged, 120L);
            }
        });
        cell.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // Let the platform handle text selection; structural edits live on
                // the frame and the grips, which is what keeps both discoverable.
                return false;
            }
        });
        return cell;
    }

    private View buildAddRowButton() {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(HORIZONTAL);
        container.addView(spacer(Ui.dp(getContext(), 16), Ui.dp(getContext(), 1)));
        View add = iconButton(R.drawable.ic_add, R.string.table_add_row);
        add.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                table.addRow(table.rowCount());
                changedShape();
            }
        });
        container.addView(add);
        return container;
    }

    private View iconButton(int iconRes, int descriptionRes) {
        android.widget.ImageView button = new android.widget.ImageView(getContext());
        button.setImageResource(iconRes);
        button.setColorFilter(Ui.withAlpha(colorText, 0.45f));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(Ui.rippleRect(getContext(),
                Ui.withAlpha(colorText, 0.12f), Ui.dp(getContext(), 8), 0x00000000));
        LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 28), Ui.dp(getContext(), 28));
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMargins(Ui.dp(getContext(), 2), Ui.dp(getContext(), 2),
                Ui.dp(getContext(), 2), Ui.dp(getContext(), 2));
        button.setLayoutParams(lp);
        button.setContentDescription(getContext().getString(descriptionRes));
        Ui.setPaddingDp(button, 5, 5, 5, 5);
        return button;
    }

    private View spacer(int width, int height) {
        View view = new View(getContext());
        view.setLayoutParams(new LayoutParams(width, height));
        return view;
    }

    private int cellWidth() {
        int columns = Math.max(1, table == null ? 1 : table.columnCount());
        int available = getWidth() > 0
                ? getWidth() - Ui.dp(getContext(), 50)
                : getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 82);
        int even = available / columns;
        int min = Ui.dp(getContext(), 92);
        int max = Ui.dp(getContext(), 240);
        return Math.max(min, Math.min(max, even));
    }

    private static int alignmentGravity(int alignment) {
        switch (alignment) {
            case TableData.ALIGN_CENTER: return Gravity.CENTER;
            case TableData.ALIGN_RIGHT: return Gravity.END | Gravity.CENTER_VERTICAL;
            default: return Gravity.START | Gravity.CENTER_VERTICAL;
        }
    }

    private void changedShape() {
        removeCallbacks(notifyTableChanged);
        tableChangePending = false;
        rebuild();
        if (callbacks != null) callbacks.onTableChanged();
    }

    @Override protected void onDetachedFromWindow() {
        if (tableChangePending) notifyTableChanged.run();
        removeCallbacks(notifyTableChanged);
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- menus

    private void showTableMenu(float x, float y) {
        AnchoredMenu.vertical(getContext())
                .title(getContext().getString(R.string.table_menu_title))
                .add(MENU_TABLE_ADD_ROW, R.drawable.ic_table_row_add, getContext().getString(R.string.table_add_row))
                .add(MENU_TABLE_ADD_COL, R.drawable.ic_table_col_add, getContext().getString(R.string.table_add_column))
                .divider()
                .add(MENU_TABLE_COPY, R.drawable.ic_copy, getContext().getString(R.string.copy_as_markdown))
                .add(MENU_TABLE_CLEAR, R.drawable.ic_clear, getContext().getString(R.string.table_clear))
                .add(new AnchoredMenu.Item(MENU_TABLE_DELETE, R.drawable.ic_delete,
                        getContext().getString(R.string.table_delete)).destructive())
                .onItem(new AnchoredMenu.OnItemClick() {
                    @Override public void onItem(int id) { handleTableMenu(id); }
                })
                .showAtPoint(this, x, y);
    }

    private void handleTableMenu(int id) {
        switch (id) {
            case MENU_TABLE_ADD_ROW:
                table.addRow(table.rowCount());
                changedShape();
                break;
            case MENU_TABLE_ADD_COL:
                table.addColumn(table.columnCount());
                changedShape();
                break;
            case MENU_TABLE_CLEAR:
                for (int r = 0; r < table.rowCount(); r++) {
                    for (int c = 0; c < table.columnCount(); c++) table.setCell(r, c, "");
                }
                changedShape();
                break;
            case MENU_TABLE_COPY:
                if (onCopyRequest != null) onCopyRequest.run();
                break;
            case MENU_TABLE_DELETE:
                if (onDeleteRequest != null) onDeleteRequest.run();
                break;
            default:
                break;
        }
    }

    private void showRowMenu(View anchor, final int row) {
        boolean header = row == 0;
        boolean canDelete = !header && table.rowCount() > 2;
        AnchoredMenu menu = AnchoredMenu.vertical(getContext())
                .title(getContext().getString(header
                        ? R.string.table_header_row : R.string.table_row_n, row));
        if (!header) {
            menu.add(MENU_ROW_ABOVE, R.drawable.ic_table_row_add,
                    getContext().getString(R.string.table_insert_row_above));
        }
        menu.add(MENU_ROW_BELOW, R.drawable.ic_table_row_add,
                getContext().getString(R.string.table_insert_row_below));
        if (!header) {
            menu.add(new AnchoredMenu.Item(MENU_ROW_UP, R.drawable.ic_arrow_up,
                    getContext().getString(R.string.table_move_up)).enabled(row > 1));
            menu.add(new AnchoredMenu.Item(MENU_ROW_DOWN, R.drawable.ic_arrow_down,
                    getContext().getString(R.string.table_move_down))
                    .enabled(row < table.rowCount() - 1));
            menu.divider();
            menu.add(new AnchoredMenu.Item(MENU_ROW_DELETE, R.drawable.ic_delete,
                    getContext().getString(R.string.table_delete_row))
                    .destructive().enabled(canDelete));
        }
        menu.onItem(new AnchoredMenu.OnItemClick() {
            @Override public void onItem(int id) {
                switch (id) {
                    case MENU_ROW_ABOVE: table.addRow(row); break;
                    case MENU_ROW_BELOW: table.addRow(row + 1); break;
                    case MENU_ROW_DELETE: table.removeRow(row); break;
                    case MENU_ROW_UP: table.moveRow(row, row - 1); break;
                    case MENU_ROW_DOWN: table.moveRow(row, row + 1); break;
                    default: return;
                }
                changedShape();
            }
        }).showAt(anchor);
    }

    private void showColumnMenu(View anchor, final int column) {
        int alignment = table.alignment(column);
        AnchoredMenu.vertical(getContext())
                .title(getContext().getString(R.string.table_column_n, column + 1))
                .add(MENU_COL_LEFT, R.drawable.ic_table_col_add,
                        getContext().getString(R.string.table_insert_col_left))
                .add(MENU_COL_RIGHT, R.drawable.ic_table_col_add,
                        getContext().getString(R.string.table_insert_col_right))
                .divider()
                .add(new AnchoredMenu.Item(MENU_COL_ALIGN_LEFT, R.drawable.ic_align_left,
                        getContext().getString(R.string.align_left))
                        .checked(alignment == TableData.ALIGN_LEFT))
                .add(new AnchoredMenu.Item(MENU_COL_ALIGN_CENTER, R.drawable.ic_align_center,
                        getContext().getString(R.string.align_center))
                        .checked(alignment == TableData.ALIGN_CENTER))
                .add(new AnchoredMenu.Item(MENU_COL_ALIGN_RIGHT, R.drawable.ic_align_right,
                        getContext().getString(R.string.align_right))
                        .checked(alignment == TableData.ALIGN_RIGHT))
                .divider()
                .add(new AnchoredMenu.Item(MENU_COL_MOVE_LEFT, R.drawable.ic_arrow_left,
                        getContext().getString(R.string.table_move_left)).enabled(column > 0))
                .add(new AnchoredMenu.Item(MENU_COL_MOVE_RIGHT, R.drawable.ic_arrow_right,
                        getContext().getString(R.string.table_move_right))
                        .enabled(column < table.columnCount() - 1))
                .add(new AnchoredMenu.Item(MENU_COL_DELETE, R.drawable.ic_delete,
                        getContext().getString(R.string.table_delete_column))
                        .destructive().enabled(table.columnCount() > 1))
                .onItem(new AnchoredMenu.OnItemClick() {
                    @Override public void onItem(int id) {
                        switch (id) {
                            case MENU_COL_LEFT: table.addColumn(column); break;
                            case MENU_COL_RIGHT: table.addColumn(column + 1); break;
                            case MENU_COL_DELETE: table.removeColumn(column); break;
                            case MENU_COL_ALIGN_LEFT:
                                table.setAlignment(column, TableData.ALIGN_LEFT); break;
                            case MENU_COL_ALIGN_CENTER:
                                table.setAlignment(column, TableData.ALIGN_CENTER); break;
                            case MENU_COL_ALIGN_RIGHT:
                                table.setAlignment(column, TableData.ALIGN_RIGHT); break;
                            case MENU_COL_MOVE_LEFT: table.moveColumn(column, column - 1); break;
                            case MENU_COL_MOVE_RIGHT: table.moveColumn(column, column + 1); break;
                            default: return;
                        }
                        changedShape();
                    }
                })
                .showAt(anchor);
    }

    /** Keeps cell views in sync after an external model change. */
    public void refreshCells() {
        suppressWatchers = true;
        try {
            rebuild();
        } finally {
            suppressWatchers = false;
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Cell width depends on the available width, so re-measure once we know it.
        if (width != oldWidth && table != null && oldWidth == 0) {
            post(new Runnable() {
                @Override public void run() { refreshCells(); }
            });
        }
    }

    /** Bounds of the frame region, used by the editor for hit testing hints. */
    public Rect frameBounds() {
        return new Rect(0, 0, getWidth(), getHeight());
    }
}
