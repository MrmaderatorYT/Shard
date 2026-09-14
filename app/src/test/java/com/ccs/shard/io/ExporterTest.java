package com.ccs.shard.io;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import com.ccs.shard.editor.TexPreview;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExporterTest {

    @Test
    public void lastPageMarkerIsResolvedInsideLongtableHeaderPayload() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{longtable}{ll}\n"
                + "Перша A & Перша B \\\\\n"
                + "\\endfirsthead\n"
                + "Сторінка~\\pageref{LastPage} & Повтор B \\\\\n"
                + "\\endhead\n"
                + "Дані A & Дані B \\\\\n"
                + "\\end{longtable}\n"
                + "Усього сторінок: \\pageref{LastPage}.\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        SpannableStringBuilder resolved = Exporter.replaceStructuralMarker(
                rendered, TexPreview.LAST_PAGE_MARKER, "12");

        assertFalse(resolved.toString().contains(
                String.valueOf(TexPreview.LAST_PAGE_MARKER)));
        assertTrue(resolved.toString().contains("Усього сторінок: 12."));

        TexPreview.LongtableRepeatHeaderSpan[] headers = resolved.getSpans(
                0, resolved.length(), TexPreview.LongtableRepeatHeaderSpan.class);
        assertEquals(1, headers.length);
        String header = headers[0].getHeader().toString();
        assertTrue(header.contains("Сторінка\u00A012"));
        assertFalse(header.contains(String.valueOf(TexPreview.LAST_PAGE_MARKER)));
        assertFalse(headers[0].repeatsOnFirstPage());
    }
}
