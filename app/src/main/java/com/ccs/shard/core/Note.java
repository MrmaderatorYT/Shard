package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single note.
 *
 * <p>Two states matter for memory on low-end devices: an <em>indexed</em> note
 * carries only front matter plus a short excerpt (a few hundred bytes), while a
 * <em>loaded</em> note also holds its body. The note list only ever holds
 * indexed notes, so a 5000-note vault costs a couple of megabytes rather than
 * the whole corpus.
 *
 * <p>Deliberately not {@link java.io.Serializable}: notes are passed between
 * screens by {@link #id} and re-read from the index, which avoids multi-hundred
 * kilobyte Intent payloads (and the TransactionTooLargeException that follows).
 */
public class Note {

    public static final int BLOCK_TEXT = 1;
    public static final int BLOCK_HEADING = 1 << 1;
    public static final int BLOCK_LIST = 1 << 2;
    public static final int BLOCK_TASK = 1 << 3;
    public static final int BLOCK_CODE = 1 << 4;
    public static final int BLOCK_TABLE = 1 << 5;
    public static final int BLOCK_IMAGE = 1 << 6;
    public static final int BLOCK_QUOTE = 1 << 7;

    /** Vault-relative path including extension, e.g. {@code work/ideas.md}. Stable identity. */
    private String id;
    private String title;
    /** Body without front matter. Null when only the index entry has been read. */
    private String content;
    private String excerpt = "";

    private long created;
    private long modified;
    private long sizeBytes;

    private List<String> tags = new ArrayList<>(0);
    /** Obsidian front-matter aliases which resolve exactly like the file title. */
    private List<String> aliases = new ArrayList<>(0);
    private List<String> outgoingLinks = new ArrayList<>(0);
    private List<String> backlinks = new ArrayList<>(0);

    private int color;
    private String emoji = "";
    private boolean pinned;
    private boolean archived;
    private boolean bookmarked;

    /** Cached word count from indexing time; -1 when unknown. */
    private int wordCount = -1;
    private int blockTypeMask = BLOCK_TEXT;
    /**
     * True when the file carries an explicit creation date, or when Shard created
     * the note. Notes written by other tools are left without the key so saving
     * does not inject front matter they never had.
     */
    private boolean hasStoredCreated;

    /**
     * Front-matter lines this app does not understand, kept verbatim so a vault
     * shared with Obsidian or a static site generator does not lose its own keys
     * when Shard saves the file.
     */
    private List<String> frontMatterExtra = new ArrayList<>(0);

    public Note() {
        long now = System.currentTimeMillis();
        this.created = now;
        this.modified = now;
    }

    public Note(String id, String title) {
        this();
        this.id = id;
        this.title = title;
    }

    // ---------------------------------------------------------------- identity

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    /** Normalised key used to resolve {@code [[wiki links]]}. Case- and space-insensitive. */
    public String linkKey() { return linkKey(title); }

    public static String linkKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Vault-relative parent folder, or {@code ""} for notes at the vault root. */
    public String folder() {
        if (id == null) return "";
        int slash = id.lastIndexOf('/');
        return slash < 0 ? "" : id.substring(0, slash);
    }

    public String fileName() {
        if (id == null) return "";
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    // ---------------------------------------------------------------- body

    public boolean isLoaded() { return content != null; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }

    public String getExcerpt() { return excerpt == null ? "" : excerpt; }

    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }

    /** Kept for the legacy call sites that truncate an already-loaded body. */
    public String getExcerpt(int maxLength) {
        String source = content != null ? content : getExcerpt();
        if (source.isEmpty()) return "";
        if (source.length() <= maxLength) return source;
        return source.substring(0, maxLength) + "…";
    }

    // ---------------------------------------------------------------- timestamps

    public long getCreatedMillis() { return created; }

    public void setCreatedMillis(long millis) {
        this.created = millis;
        hasStoredCreated = true;
    }

    /** Sets the creation time without marking it for serialization. */
    public void setCreatedMillisSilently(long millis) { this.created = millis; }

    public boolean hasStoredCreated() { return hasStoredCreated; }

    public long getModifiedMillis() { return modified; }

    public void setModifiedMillis(long millis) { this.modified = millis; }

    public void touch() { this.modified = System.currentTimeMillis(); }

    public java.util.Date getCreated() { return new java.util.Date(created); }

    public void setCreated(java.util.Date date) { if (date != null) created = date.getTime(); }

    public java.util.Date getModified() { return new java.util.Date(modified); }

    public void setModified(java.util.Date date) { if (date != null) modified = date.getTime(); }

    public long getSizeBytes() { return sizeBytes; }

    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    // ---------------------------------------------------------------- metadata

    public List<String> getTags() { return tags; }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<String>(0);
    }

    public void addTag(String tag) {
        if (tag == null) return;
        tag = tag.trim();
        if (tag.isEmpty()) return;
        if (tag.startsWith("#")) tag = tag.substring(1);
        if (!tags.contains(tag)) tags.add(tag);
    }

    public void removeTag(String tag) { tags.remove(tag); }

    public boolean hasTag(String tag) { return tags.contains(tag); }

    public List<String> getOutgoingLinks() { return outgoingLinks; }

    public void setOutgoingLinks(List<String> links) {
        this.outgoingLinks = links != null ? links : new ArrayList<String>(0);
    }

    public List<String> getBacklinks() { return backlinks; }

    public void setBacklinks(List<String> backlinks) {
        this.backlinks = backlinks != null ? backlinks : new ArrayList<String>(0);
    }

    public boolean hasLinkTo(String key) { return outgoingLinks.contains(key); }

    public int getColor() { return color; }

    /** 0 means "no explicit colour"; the UI then derives one from the title hash. */
    public void setColor(int color) { this.color = color; }

    public String getEmoji() { return emoji == null ? "" : emoji; }

    public void setEmoji(String emoji) { this.emoji = emoji == null ? "" : emoji; }

    public boolean isPinned() { return pinned; }

    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public boolean isArchived() { return archived; }

    public void setArchived(boolean archived) { this.archived = archived; }

    public boolean isBookmarked() { return bookmarked; }

    public void setBookmarked(boolean bookmarked) { this.bookmarked = bookmarked; }

    // ---------------------------------------------------------------- derived

    /**
     * Title only. The emoji is rendered as a separate leading element by the UI,
     * so folding it into the display name here would double it up.
     */
    public String getDisplayName() { return title == null ? "" : title; }

    public void setWordCount(int wordCount) { this.wordCount = wordCount; }

    public int getWordCount() {
        if (wordCount >= 0) return wordCount;
        if (content == null || content.trim().isEmpty()) return 0;
        // Counts what a reader sees, not Markdown syntax.
        wordCount = Md.wordCount(content);
        return wordCount;
    }

    public static int countWords(CharSequence text) {
        if (text == null) return 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0, n = text.length(); i < n; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                words++;
            }
        }
        return words;
    }

    public int getReadingTimeMinutes() {
        return Math.max(1, (int) Math.ceil(getWordCount() / 200.0));
    }

    public int getBlockTypeMask() { return blockTypeMask; }

    public void setBlockTypeMask(int mask) { blockTypeMask = mask == 0 ? BLOCK_TEXT : mask; }

    public boolean hasBlockType(int mask) { return (blockTypeMask & mask) != 0; }

    public List<String> getFrontMatterExtra() { return frontMatterExtra; }

    public void setFrontMatterExtra(List<String> lines) {
        this.frontMatterExtra = lines != null ? lines : new ArrayList<String>(0);
    }

    public List<String> getAliases() { return aliases; }

    public void setAliases(List<String> values) {
        List<String> cleaned = new ArrayList<>(values == null ? 0 : values.size());
        if (values != null) {
            for (String value : values) {
                String alias = value == null ? "" : value.trim();
                if (!alias.isEmpty() && !cleaned.contains(alias)) cleaned.add(alias);
            }
        }
        aliases = cleaned;
    }

    public void addAlias(String value) {
        String alias = value == null ? "" : value.trim();
        if (!alias.isEmpty() && !aliases.contains(alias)) aliases.add(alias);
    }

    public List<String> unmodifiableTags() { return Collections.unmodifiableList(tags); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Note)) return false;
        String other = ((Note) o).id;
        return id != null ? id.equals(other) : other == null;
    }

    @Override
    public int hashCode() { return id != null ? id.hashCode() : 0; }

    @Override
    public String toString() { return "Note{" + id + "}"; }
}
