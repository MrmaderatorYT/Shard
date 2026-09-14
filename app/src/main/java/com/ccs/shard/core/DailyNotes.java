package com.ccs.shard.core;

import android.content.Context;

import com.ccs.shard.NoteEditorActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Creates and opens one durable Markdown note per calendar day. */
public final class DailyNotes {

    public static final String FOLDER = "Daily";

    private DailyNotes() {}

    public static String title(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date);
    }

    public static void open(Context context, VaultRepository repository, Date date) {
        String title = title(date);
        String id = FOLDER + "/" + title + NoteFile.EXT;
        Note note = repository.meta(id);
        if (note == null) {
            String pretty = new SimpleDateFormat("EEEE, d MMMM yyyy",
                    Locale.getDefault()).format(date);
            String body = "# " + pretty + "\n\n## Tasks\n\n- [ ] \n\n## Notes\n\n";
            note = repository.createNote(title, FOLDER, body);
        }
        NoteEditorActivity.open(context, note.getId());
    }
}
