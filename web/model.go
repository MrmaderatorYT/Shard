package main

import (
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxTitleRunes      = 200
	maxContentBytes    = 1 << 20 // 1 MiB
	maxCollectionRunes = 80
	maxTagRunes        = 40
	maxTags            = 32
	maxEmojiRunes      = 8
)

var colorPattern = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// Note is the canonical representation returned by the API and persisted on disk.
// IDs, timestamps, excerpts, and word counts are always owned by the server.
type Note struct {
	ID         string     `json:"id"`
	Title      string     `json:"title"`
	Content    string     `json:"content"`
	Excerpt    string     `json:"excerpt"`
	Tags       []string   `json:"tags"`
	Collection string     `json:"collection"`
	Emoji      string     `json:"emoji"`
	Color      string     `json:"color"`
	Pinned     bool       `json:"pinned"`
	Favorite   bool       `json:"favorite"`
	CreatedAt  time.Time  `json:"createdAt"`
	UpdatedAt  time.Time  `json:"updatedAt"`
	DeletedAt  *time.Time `json:"deletedAt"`
	WordCount  int        `json:"wordCount"`
}

// NoteSummary intentionally omits Content so list and search responses stay small.
// The complete document is available from GET /api/notes/{id}.
type NoteSummary struct {
	ID         string     `json:"id"`
	Title      string     `json:"title"`
	Excerpt    string     `json:"excerpt"`
	Tags       []string   `json:"tags"`
	Collection string     `json:"collection"`
	Emoji      string     `json:"emoji"`
	Color      string     `json:"color"`
	Pinned     bool       `json:"pinned"`
	Favorite   bool       `json:"favorite"`
	CreatedAt  time.Time  `json:"createdAt"`
	UpdatedAt  time.Time  `json:"updatedAt"`
	DeletedAt  *time.Time `json:"deletedAt"`
	WordCount  int        `json:"wordCount"`
}

type CreateNoteInput struct {
	Title      string   `json:"title"`
	Content    string   `json:"content"`
	Tags       []string `json:"tags"`
	Collection string   `json:"collection"`
	Emoji      string   `json:"emoji"`
	Color      string   `json:"color"`
	Pinned     bool     `json:"pinned"`
	Favorite   bool     `json:"favorite"`
}

type UpdateNoteInput struct {
	Title      *string   `json:"title"`
	Content    *string   `json:"content"`
	Tags       *[]string `json:"tags"`
	Collection *string   `json:"collection"`
	Emoji      *string   `json:"emoji"`
	Color      *string   `json:"color"`
	Pinned     *bool     `json:"pinned"`
	Favorite   *bool     `json:"favorite"`
}

type ValidationError struct {
	Field   string
	Message string
}

func (e *ValidationError) Error() string {
	if e.Field == "" {
		return e.Message
	}
	return e.Field + ": " + e.Message
}

func normalizeCreate(input CreateNoteInput) (CreateNoteInput, error) {
	input.Title = strings.TrimSpace(input.Title)
	if input.Title == "" {
		input.Title = "Untitled"
	}
	input.Collection = strings.TrimSpace(input.Collection)
	input.Emoji = strings.TrimSpace(input.Emoji)
	input.Color = strings.TrimSpace(input.Color)

	var err error
	input.Tags, err = normalizeTags(input.Tags)
	if err != nil {
		return CreateNoteInput{}, err
	}
	if err := validateFields(input.Title, input.Content, input.Collection, input.Emoji, input.Color); err != nil {
		return CreateNoteInput{}, err
	}
	return input, nil
}

func validateUpdate(input UpdateNoteInput) (UpdateNoteInput, error) {
	if input.Title != nil {
		value := strings.TrimSpace(*input.Title)
		if value == "" {
			value = "Untitled"
		}
		input.Title = &value
	}
	if input.Collection != nil {
		value := strings.TrimSpace(*input.Collection)
		input.Collection = &value
	}
	if input.Emoji != nil {
		value := strings.TrimSpace(*input.Emoji)
		input.Emoji = &value
	}
	if input.Color != nil {
		value := strings.TrimSpace(*input.Color)
		input.Color = &value
	}
	if input.Tags != nil {
		value, err := normalizeTags(*input.Tags)
		if err != nil {
			return UpdateNoteInput{}, err
		}
		input.Tags = &value
	}

	title, content, collection, emoji, color := "", "", "", "", ""
	if input.Title != nil {
		title = *input.Title
	}
	if input.Content != nil {
		content = *input.Content
	}
	if input.Collection != nil {
		collection = *input.Collection
	}
	if input.Emoji != nil {
		emoji = *input.Emoji
	}
	if input.Color != nil {
		color = *input.Color
	}
	if err := validateFields(title, content, collection, emoji, color); err != nil {
		return UpdateNoteInput{}, err
	}
	return input, nil
}

func validateFields(title, content, collection, emoji, color string) error {
	if !utf8.ValidString(title) || !utf8.ValidString(content) || !utf8.ValidString(collection) || !utf8.ValidString(emoji) {
		return &ValidationError{Message: "text fields must contain valid UTF-8"}
	}
	if utf8.RuneCountInString(title) > maxTitleRunes {
		return &ValidationError{Field: "title", Message: "must be at most 200 characters"}
	}
	if len(content) > maxContentBytes {
		return &ValidationError{Field: "content", Message: "must be at most 1 MiB"}
	}
	if utf8.RuneCountInString(collection) > maxCollectionRunes {
		return &ValidationError{Field: "collection", Message: "must be at most 80 characters"}
	}
	if utf8.RuneCountInString(emoji) > maxEmojiRunes {
		return &ValidationError{Field: "emoji", Message: "must be at most 8 characters"}
	}
	if color != "" && !colorPattern.MatchString(color) {
		return &ValidationError{Field: "color", Message: "must be an RGB hex color such as #6C63FF"}
	}
	return nil
}

func normalizeTags(tags []string) ([]string, error) {
	if len(tags) > maxTags {
		return nil, &ValidationError{Field: "tags", Message: "must contain at most 32 tags"}
	}
	result := make([]string, 0, len(tags))
	seen := make(map[string]struct{}, len(tags))
	for _, raw := range tags {
		tag := strings.TrimSpace(strings.TrimPrefix(raw, "#"))
		if tag == "" {
			continue
		}
		if !utf8.ValidString(tag) {
			return nil, &ValidationError{Field: "tags", Message: "must contain valid UTF-8"}
		}
		if utf8.RuneCountInString(tag) > maxTagRunes {
			return nil, &ValidationError{Field: "tags", Message: "each tag must be at most 40 characters"}
		}
		key := strings.ToLower(tag)
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		result = append(result, tag)
	}
	if result == nil {
		result = []string{}
	}
	return result, nil
}

func populateDerived(note *Note) {
	note.Excerpt = makeExcerpt(note.Content, 180)
	note.WordCount = len(strings.Fields(note.Content))
	if note.Tags == nil {
		note.Tags = []string{}
	}
}

func makeExcerpt(content string, maxRunes int) string {
	compact := strings.Join(strings.Fields(content), " ")
	runes := []rune(compact)
	if len(runes) <= maxRunes {
		return compact
	}
	return strings.TrimSpace(string(runes[:maxRunes])) + "…"
}

var (
	ErrNotFound       = errors.New("note not found")
	ErrAlreadyDeleted = errors.New("note is already in trash")
	ErrNotDeleted     = errors.New("note is not in trash")
)
