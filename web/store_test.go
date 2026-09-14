package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestStoreSeedsOnlyMissingOrEmptyFiles(t *testing.T) {
	path := filepath.Join(t.TempDir(), "data", "notes.json")
	store, err := NewStore(path)
	if err != nil {
		t.Fatalf("NewStore(missing): %v", err)
	}
	if got := store.Stats().Total; got != 3 {
		t.Fatalf("seed count = %d, want 3", got)
	}

	empty := diskData{Version: 1, Notes: []Note{}}
	encoded, err := json.Marshal(empty)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, encoded, 0o600); err != nil {
		t.Fatal(err)
	}
	store, err = NewStore(path)
	if err != nil {
		t.Fatalf("NewStore(valid empty database): %v", err)
	}
	if got := store.Stats().Total; got != 0 {
		t.Fatalf("valid empty database was seeded, count = %d", got)
	}

	if err := os.WriteFile(path, []byte(" \n\t"), 0o600); err != nil {
		t.Fatal(err)
	}
	store, err = NewStore(path)
	if err != nil {
		t.Fatalf("NewStore(empty file): %v", err)
	}
	if got := store.Stats().Total; got != 3 {
		t.Fatalf("empty-file seed count = %d, want 3", got)
	}
}

func TestStoreRejectsCorruptData(t *testing.T) {
	path := filepath.Join(t.TempDir(), "notes.json")
	if err := os.WriteFile(path, []byte(`{"version":1,"notes":`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewStore(path); err == nil {
		t.Fatal("NewStore(corrupt) succeeded, want error")
	}
}

func TestStoreConcurrentCreatesPersistEveryNote(t *testing.T) {
	store, path := newEmptyTestStore(t)
	const count = 32
	errorsChannel := make(chan error, count)
	var wait sync.WaitGroup
	for i := 0; i < count; i++ {
		wait.Add(1)
		go func(i int) {
			defer wait.Done()
			_, err := store.Create(CreateNoteInput{
				Title:   fmt.Sprintf("Note %d", i),
				Content: "concurrent write",
			})
			errorsChannel <- err
		}(i)
	}
	wait.Wait()
	close(errorsChannel)
	for err := range errorsChannel {
		if err != nil {
			t.Fatalf("Create: %v", err)
		}
	}

	if got := store.Stats().Total; got != count {
		t.Fatalf("in-memory total = %d, want %d", got, count)
	}
	reopened, err := NewStore(path)
	if err != nil {
		t.Fatalf("reopen store: %v", err)
	}
	if got := reopened.Stats().Total; got != count {
		t.Fatalf("persisted total = %d, want %d", got, count)
	}
	matches, err := filepath.Glob(filepath.Join(filepath.Dir(path), ".notes-*.tmp"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != 0 {
		t.Fatalf("temporary files left behind: %v", matches)
	}
}

func TestStoreLifecycleAndFilters(t *testing.T) {
	store, path := newEmptyTestStore(t)
	created, err := store.Create(CreateNoteInput{
		Title:      "  Roadmap  ",
		Content:    "Alpha beta gamma",
		Tags:       []string{"#Go", "go", " Backend "},
		Collection: " Work ",
		Favorite:   true,
		Pinned:     true,
		Color:      "#6C63FF",
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if created.ID == "" || created.CreatedAt.IsZero() || created.UpdatedAt.IsZero() {
		t.Fatalf("server fields not populated: %+v", created)
	}
	if created.Title != "Roadmap" || created.Collection != "Work" {
		t.Fatalf("fields were not normalized: %+v", created)
	}
	if created.WordCount != 3 || len(created.Tags) != 2 {
		t.Fatalf("derived/tag fields = %+v", created)
	}

	if notes := store.List(NoteFilter{Query: "beta", Tag: "go", Collection: "work", View: "favorites"}); len(notes) != 1 {
		t.Fatalf("filtered notes = %d, want 1", len(notes))
	}

	content := "Revised content with four words"
	favorite := false
	updated, err := store.Update(created.ID, UpdateNoteInput{Content: &content, Favorite: &favorite})
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if updated.WordCount != 5 || !updated.UpdatedAt.After(created.UpdatedAt) {
		t.Fatalf("updated derived fields/timestamp = %+v", updated)
	}

	trashed, err := store.Trash(created.ID)
	if err != nil {
		t.Fatalf("Trash: %v", err)
	}
	if trashed.DeletedAt == nil || len(store.List(NoteFilter{View: "trash"})) != 1 {
		t.Fatalf("note not listed in trash: %+v", trashed)
	}
	if _, err := store.Update(created.ID, UpdateNoteInput{Content: &content}); !errors.Is(err, ErrAlreadyDeleted) {
		t.Fatalf("Update(trashed) error = %v, want ErrAlreadyDeleted", err)
	}
	restored, err := store.Restore(created.ID)
	if err != nil {
		t.Fatalf("Restore: %v", err)
	}
	if restored.DeletedAt != nil {
		t.Fatalf("restored DeletedAt = %v", restored.DeletedAt)
	}
	if err := store.DeletePermanently(created.ID); !errors.Is(err, ErrNotDeleted) {
		t.Fatalf("DeletePermanently(active) error = %v, want ErrNotDeleted", err)
	}
	if _, err := store.Trash(created.ID); err != nil {
		t.Fatal(err)
	}
	if err := store.DeletePermanently(created.ID); err != nil {
		t.Fatalf("DeletePermanently: %v", err)
	}
	if _, err := store.Get(created.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Get(deleted) error = %v, want ErrNotFound", err)
	}
	reopened, err := NewStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.Stats().Total != 0 {
		t.Fatalf("permanent deletion was not persisted: %+v", reopened.Stats())
	}
}

func newEmptyTestStore(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "data", "notes.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(diskData{Version: 1, Notes: []Note{}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, encoded, 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := NewStore(path)
	if err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, time.August, 27, 12, 0, 0, 0, time.UTC)
	var clockMu sync.Mutex
	store.now = func() time.Time {
		clockMu.Lock()
		defer clockMu.Unlock()
		base = base.Add(time.Millisecond)
		return base
	}
	return store, path
}
