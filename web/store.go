package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type Store struct {
	mu    sync.RWMutex
	path  string
	notes []Note
	now   func() time.Time
	newID func() (string, error)
}

type diskData struct {
	Version int    `json:"version"`
	Notes   []Note `json:"notes"`
}

type NoteFilter struct {
	Query      string
	Tag        string
	Collection string
	View       string
}

type NamedCount struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

type Stats struct {
	Total       int `json:"total"`
	Active      int `json:"active"`
	Trashed     int `json:"trashed"`
	Favorites   int `json:"favorites"`
	Pinned      int `json:"pinned"`
	Collections int `json:"collections"`
	Tags        int `json:"tags"`
	Words       int `json:"words"`
}

func NewStore(path string) (*Store, error) {
	store := &Store{
		path:  path,
		now:   func() time.Time { return time.Now().UTC() },
		newID: randomID,
	}
	if err := store.load(); err != nil {
		return nil, err
	}
	return store, nil
}

func (s *Store) load() error {
	data, err := os.ReadFile(s.path)
	seed := false
	switch {
	case errors.Is(err, os.ErrNotExist):
		seed = true
	case err != nil:
		return fmt.Errorf("read notes: %w", err)
	case len(strings.TrimSpace(string(data))) == 0:
		seed = true
	}

	if seed {
		s.notes = demoNotes(s.now())
		if err := s.persist(s.notes); err != nil {
			return fmt.Errorf("seed notes: %w", err)
		}
		return nil
	}

	var file diskData
	if err := json.Unmarshal(data, &file); err != nil {
		return fmt.Errorf("decode notes: %w", err)
	}
	if file.Version != 1 {
		return fmt.Errorf("decode notes: unsupported data version %d", file.Version)
	}
	if file.Notes == nil {
		file.Notes = []Note{}
	}
	seen := make(map[string]struct{}, len(file.Notes))
	for i := range file.Notes {
		note := &file.Notes[i]
		if note.ID == "" {
			return fmt.Errorf("decode notes: note %d has no id", i)
		}
		if _, exists := seen[note.ID]; exists {
			return fmt.Errorf("decode notes: duplicate note id %q", note.ID)
		}
		seen[note.ID] = struct{}{}
		if note.CreatedAt.IsZero() || note.UpdatedAt.IsZero() {
			return fmt.Errorf("decode notes: note %q has invalid timestamps", note.ID)
		}
		populateDerived(note)
	}
	s.notes = file.Notes
	return nil
}

func (s *Store) List(filter NoteFilter) []NoteSummary {
	s.mu.RLock()
	defer s.mu.RUnlock()

	query := strings.ToLower(strings.TrimSpace(filter.Query))
	tag := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(filter.Tag, "#")))
	collection := strings.ToLower(strings.TrimSpace(filter.Collection))
	view := strings.ToLower(strings.TrimSpace(filter.View))
	if view == "" {
		view = "all"
	}

	result := make([]NoteSummary, 0, len(s.notes))
	for _, note := range s.notes {
		if !matchesView(note, view) || !matchesFacet(note, tag, collection) || !matchesQuery(note, query) {
			continue
		}
		result = append(result, summaryOf(note))
	}
	sort.SliceStable(result, func(i, j int) bool {
		if result[i].Pinned != result[j].Pinned {
			return result[i].Pinned
		}
		if !result[i].UpdatedAt.Equal(result[j].UpdatedAt) {
			return result[i].UpdatedAt.After(result[j].UpdatedAt)
		}
		return result[i].ID < result[j].ID
	})
	return result
}

func (s *Store) Get(id string) (Note, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	index := indexOf(s.notes, id)
	if index < 0 {
		return Note{}, ErrNotFound
	}
	return cloneNote(s.notes[index]), nil
}

func (s *Store) Create(input CreateNoteInput) (Note, error) {
	normalized, err := normalizeCreate(input)
	if err != nil {
		return Note{}, err
	}
	id, err := s.newID()
	if err != nil {
		return Note{}, fmt.Errorf("generate id: %w", err)
	}
	now := s.now().UTC()
	note := Note{
		ID:         id,
		Title:      normalized.Title,
		Content:    normalized.Content,
		Tags:       normalized.Tags,
		Collection: normalized.Collection,
		Emoji:      normalized.Emoji,
		Color:      normalized.Color,
		Pinned:     normalized.Pinned,
		Favorite:   normalized.Favorite,
		CreatedAt:  now,
		UpdatedAt:  now,
	}
	populateDerived(&note)

	s.mu.Lock()
	defer s.mu.Unlock()
	if indexOf(s.notes, id) >= 0 {
		return Note{}, errors.New("generated duplicate note id")
	}
	next := append(cloneNotes(s.notes), note)
	if err := s.persist(next); err != nil {
		return Note{}, err
	}
	s.notes = next
	return cloneNote(note), nil
}

func (s *Store) Update(id string, input UpdateNoteInput) (Note, error) {
	normalized, err := validateUpdate(input)
	if err != nil {
		return Note{}, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	index := indexOf(s.notes, id)
	if index < 0 {
		return Note{}, ErrNotFound
	}
	if s.notes[index].DeletedAt != nil {
		return Note{}, ErrAlreadyDeleted
	}
	next := cloneNotes(s.notes)
	note := &next[index]
	if normalized.Title != nil {
		note.Title = *normalized.Title
	}
	if normalized.Content != nil {
		note.Content = *normalized.Content
	}
	if normalized.Tags != nil {
		note.Tags = append([]string(nil), (*normalized.Tags)...)
	}
	if normalized.Collection != nil {
		note.Collection = *normalized.Collection
	}
	if normalized.Emoji != nil {
		note.Emoji = *normalized.Emoji
	}
	if normalized.Color != nil {
		note.Color = *normalized.Color
	}
	if normalized.Pinned != nil {
		note.Pinned = *normalized.Pinned
	}
	if normalized.Favorite != nil {
		note.Favorite = *normalized.Favorite
	}
	note.UpdatedAt = nextTimestamp(s.now().UTC(), note.UpdatedAt)
	populateDerived(note)
	if err := s.persist(next); err != nil {
		return Note{}, err
	}
	s.notes = next
	return cloneNote(*note), nil
}

func (s *Store) Trash(id string) (Note, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := indexOf(s.notes, id)
	if index < 0 {
		return Note{}, ErrNotFound
	}
	if s.notes[index].DeletedAt != nil {
		return cloneNote(s.notes[index]), nil
	}
	next := cloneNotes(s.notes)
	now := nextTimestamp(s.now().UTC(), next[index].UpdatedAt)
	next[index].DeletedAt = &now
	next[index].UpdatedAt = now
	if err := s.persist(next); err != nil {
		return Note{}, err
	}
	s.notes = next
	return cloneNote(next[index]), nil
}

func (s *Store) Restore(id string) (Note, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := indexOf(s.notes, id)
	if index < 0 {
		return Note{}, ErrNotFound
	}
	if s.notes[index].DeletedAt == nil {
		return cloneNote(s.notes[index]), nil
	}
	next := cloneNotes(s.notes)
	next[index].DeletedAt = nil
	next[index].UpdatedAt = nextTimestamp(s.now().UTC(), next[index].UpdatedAt)
	if err := s.persist(next); err != nil {
		return Note{}, err
	}
	s.notes = next
	return cloneNote(next[index]), nil
}

func (s *Store) DeletePermanently(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := indexOf(s.notes, id)
	if index < 0 {
		return ErrNotFound
	}
	if s.notes[index].DeletedAt == nil {
		return ErrNotDeleted
	}
	next := cloneNotes(s.notes)
	next = append(next[:index], next[index+1:]...)
	if err := s.persist(next); err != nil {
		return err
	}
	s.notes = next
	return nil
}

func (s *Store) Stats() Stats {
	s.mu.RLock()
	defer s.mu.RUnlock()
	stats := Stats{Total: len(s.notes)}
	collections := make(map[string]struct{})
	tags := make(map[string]struct{})
	for _, note := range s.notes {
		if note.DeletedAt != nil {
			stats.Trashed++
			continue
		}
		stats.Active++
		stats.Words += note.WordCount
		if note.Favorite {
			stats.Favorites++
		}
		if note.Pinned {
			stats.Pinned++
		}
		if note.Collection != "" {
			collections[strings.ToLower(note.Collection)] = struct{}{}
		}
		for _, tag := range note.Tags {
			tags[strings.ToLower(tag)] = struct{}{}
		}
	}
	stats.Collections = len(collections)
	stats.Tags = len(tags)
	return stats
}

func (s *Store) Collections() []NamedCount {
	return s.namedCounts(false)
}

func (s *Store) Tags() []NamedCount {
	return s.namedCounts(true)
}

func (s *Store) namedCounts(wantTags bool) []NamedCount {
	s.mu.RLock()
	defer s.mu.RUnlock()
	type entry struct {
		name  string
		count int
	}
	counts := make(map[string]entry)
	for _, note := range s.notes {
		if note.DeletedAt != nil {
			continue
		}
		values := []string{note.Collection}
		if wantTags {
			values = note.Tags
		}
		for _, value := range values {
			if value == "" {
				continue
			}
			key := strings.ToLower(value)
			current := counts[key]
			if current.name == "" {
				current.name = value
			}
			current.count++
			counts[key] = current
		}
	}
	result := make([]NamedCount, 0, len(counts))
	for _, current := range counts {
		result = append(result, NamedCount{Name: current.name, Count: current.count})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Count != result[j].Count {
			return result[i].Count > result[j].Count
		}
		return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name)
	})
	return result
}

func (s *Store) persist(notes []Note) error {
	directory := filepath.Dir(s.path)
	if err := os.MkdirAll(directory, 0o755); err != nil {
		return fmt.Errorf("create data directory: %w", err)
	}
	temp, err := os.CreateTemp(directory, ".notes-*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary notes file: %w", err)
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return fmt.Errorf("secure temporary notes file: %w", err)
	}
	encoder := json.NewEncoder(temp)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(diskData{Version: 1, Notes: notes}); err != nil {
		temp.Close()
		return fmt.Errorf("encode notes: %w", err)
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return fmt.Errorf("sync notes: %w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close notes: %w", err)
	}
	if err := os.Rename(tempName, s.path); err != nil {
		return fmt.Errorf("replace notes: %w", err)
	}
	if directoryHandle, err := os.Open(directory); err == nil {
		_ = directoryHandle.Sync()
		_ = directoryHandle.Close()
	}
	return nil
}

func matchesView(note Note, view string) bool {
	switch view {
	case "trash":
		return note.DeletedAt != nil
	case "favorites":
		return note.DeletedAt == nil && note.Favorite
	default:
		return note.DeletedAt == nil
	}
}

func matchesFacet(note Note, tag, collection string) bool {
	if collection != "" && strings.ToLower(note.Collection) != collection {
		return false
	}
	if tag != "" {
		for _, candidate := range note.Tags {
			if strings.ToLower(candidate) == tag {
				return true
			}
		}
		return false
	}
	return true
}

func matchesQuery(note Note, query string) bool {
	if query == "" {
		return true
	}
	fields := []string{note.Title, note.Content, note.Collection, strings.Join(note.Tags, " ")}
	for _, field := range fields {
		if strings.Contains(strings.ToLower(field), query) {
			return true
		}
	}
	return false
}

func indexOf(notes []Note, id string) int {
	for i := range notes {
		if notes[i].ID == id {
			return i
		}
	}
	return -1
}

func cloneNotes(notes []Note) []Note {
	result := make([]Note, len(notes))
	for i, note := range notes {
		result[i] = cloneNote(note)
	}
	return result
}

func cloneNote(note Note) Note {
	note.Tags = append([]string(nil), note.Tags...)
	if note.Tags == nil {
		note.Tags = []string{}
	}
	if note.DeletedAt != nil {
		value := *note.DeletedAt
		note.DeletedAt = &value
	}
	return note
}

func summaryOf(note Note) NoteSummary {
	summary := NoteSummary{
		ID:         note.ID,
		Title:      note.Title,
		Excerpt:    note.Excerpt,
		Tags:       append([]string(nil), note.Tags...),
		Collection: note.Collection,
		Emoji:      note.Emoji,
		Color:      note.Color,
		Pinned:     note.Pinned,
		Favorite:   note.Favorite,
		CreatedAt:  note.CreatedAt,
		UpdatedAt:  note.UpdatedAt,
		WordCount:  note.WordCount,
	}
	if summary.Tags == nil {
		summary.Tags = []string{}
	}
	if note.DeletedAt != nil {
		value := *note.DeletedAt
		summary.DeletedAt = &value
	}
	return summary
}

func nextTimestamp(now, previous time.Time) time.Time {
	if !now.After(previous) {
		return previous.Add(time.Nanosecond)
	}
	return now
}

func randomID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}

func demoNotes(now time.Time) []Note {
	now = now.UTC()
	items := []Note{
		{
			ID: "demo-welcome", Title: "Ласкаво просимо до Shard", Emoji: "✦", Color: "#6C63FF",
			Content: "# Ваш простір для думок\n\nСтворюйте нотатки, додавайте теги й об’єднуйте записи в колекції. Дані зберігаються локально на сервері.",
			Tags:    []string{"старт", "довідка"}, Collection: "Початок", Pinned: true,
			CreatedAt: now.Add(-48 * time.Hour), UpdatedAt: now.Add(-2 * time.Hour),
		},
		{
			ID: "demo-ideas", Title: "Ідеї для наступного проєкту", Emoji: "💡", Color: "#F59E0B",
			Content: "- Зібрати корисні посилання\n- Намалювати швидкий прототип\n- Перевірити ідею з друзями",
			Tags:    []string{"ідеї", "проєкти"}, Collection: "Ідеї", Favorite: true,
			CreatedAt: now.Add(-24 * time.Hour), UpdatedAt: now.Add(-90 * time.Minute),
		},
		{
			ID: "demo-today", Title: "Фокус на сьогодні", Emoji: "✓", Color: "#10B981",
			Content: "1. Завершити важливу задачу\n2. Зробити перерву на прогулянку\n3. Підсумувати день",
			Tags:    []string{"плани"}, Collection: "Щоденник",
			CreatedAt: now.Add(-4 * time.Hour), UpdatedAt: now.Add(-30 * time.Minute),
		},
	}
	for i := range items {
		populateDerived(&items[i])
	}
	return items
}
