package com.ccs.shard.block;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.NoteProperties;

import org.junit.Test;

/** Regression coverage for Markdown commonly produced by Obsidian and GitHub. */
public final class MarkdownCompatibilityTest {

    @Test
    public void foldedCallout_roundTripsItsKindFoldStateAndBody() {
        String markdown = "> [!warning]- Deploy carefully\n> Restart the service afterwards.";

        BlockDocument document = BlockDocument.parse(markdown);

        assertEquals(BlockType.CALLOUT, document.get(0).type);
        assertEquals("warning", document.get(0).calloutKind);
        assertEquals("-", document.get(0).calloutFold);
        assertEquals("Deploy carefully\nRestart the service afterwards.", document.get(0).text);
        assertEquals(markdown, document.toMarkdown().trim());
    }

    @Test
    public void gfmFootnotesAndReferenceLinks_areStructuredAndStable() {
        String markdown = "Read [the guide][docs] and the source[^source].\n\n"
                + "[docs]: https://example.com/guide \"Guide\"\n"
                + "[^source]: Original research\n"
                + "    with an indented continuation.";

        BlockDocument document = BlockDocument.parse(markdown);

        assertEquals(BlockType.PARAGRAPH, document.get(0).type);
        assertEquals(BlockType.REFERENCE, document.get(1).type);
        assertEquals(BlockType.FOOTNOTE, document.get(2).type);
        assertEquals("https://example.com/guide", document.referenceTarget("DOCS"));
        assertTrue(document.hasFootnote("SOURCE"));
        assertEquals(2, document.indexOfFootnote("source"));
        assertEquals(markdown, document.toMarkdown().trim());
    }

    @Test
    public void obsidianEmbedMermaidAndNestedLists_getDedicatedBlocks() {
        String markdown = "![[Assets/diagram.png|Architecture]]\n\n"
                + "```mermaid\nflowchart LR\n  App --> Vault\n```\n\n"
                + "- Parent\n    - Child\n        - Deep\n- Next";

        BlockDocument document = BlockDocument.parse(markdown);

        assertEquals(BlockType.EMBED, document.get(0).type);
        assertEquals(BlockType.CODE, document.get(1).type);
        assertEquals("mermaid", document.get(1).language);
        assertEquals(BlockType.BULLET, document.get(2).type);
        assertEquals(0, document.get(2).indent);
        assertEquals(1, document.get(3).indent);
        assertEquals(2, document.get(4).indent);
        assertEquals(0, document.get(5).indent);
        assertEquals(markdown, document.toMarkdown().trim());
    }

    @Test
    public void obsidianAliases_areParsedIndexedAsPropertiesAndWrittenBack() {
        String markdown = "---\n"
                + "aliases:\n"
                + "  - \"Start here\"\n"
                + "  - Home\n"
                + "cssclasses: wide-page\n"
                + "---\n\nBody";

        Note note = NoteFile.parse(markdown, "Welcome.md", 1L);

        assertEquals(2, note.getAliases().size());
        assertEquals("Start here", note.getAliases().get(0));
        assertEquals("Home", note.getAliases().get(1));
        assertEquals("Start here, Home", NoteProperties.read(note).get("aliases"));
        String written = NoteFile.serialize(note);
        assertTrue(written.contains("aliases:\n  - \"Start here\"\n  - \"Home\""));
        assertTrue(written.contains("cssclasses: wide-page"));

        Note restored = NoteFile.parse(written, "Welcome.md", 2L);
        assertEquals(note.getAliases(), restored.getAliases());
        assertNotNull(restored.getContent());
    }
}
