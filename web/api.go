package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"mime"
	"net/http"
	pathpkg "path"
	"strings"
	"time"
)

const maxRequestBody = 2 << 20 // 2 MiB including JSON overhead.

type API struct {
	store  *Store
	static fs.FS
}

type errorEnvelope struct {
	Error apiError `json:"error"`
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func NewHandler(store *Store, static fs.FS) http.Handler {
	api := &API{store: store, static: static}
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", api.handleHealth)
	mux.HandleFunc("GET /api/health", api.handleHealth)
	mux.HandleFunc("GET /api/notes", api.handleNotesList)
	mux.HandleFunc("POST /api/notes", api.handleNotesCreate)
	mux.HandleFunc("GET /api/search", api.handleNotesList)
	mux.HandleFunc("GET /api/notes/{id}", api.handleNoteGet)
	mux.HandleFunc("PUT /api/notes/{id}", api.handleNoteUpdate)
	mux.HandleFunc("PATCH /api/notes/{id}", api.handleNoteUpdate)
	mux.HandleFunc("DELETE /api/notes/{id}", api.handleNoteDelete)
	mux.HandleFunc("POST /api/notes/{id}/trash", api.handleNoteTrash)
	mux.HandleFunc("POST /api/notes/{id}/restore", api.handleNoteRestore)
	mux.HandleFunc("GET /api/stats", api.handleStats)
	mux.HandleFunc("GET /api/collections", api.handleCollections)
	mux.HandleFunc("GET /api/tags", api.handleTags)
	mux.HandleFunc("OPTIONS /api/{rest...}", api.handleOptions)
	mux.HandleFunc("/api/", api.handleAPINotFound)
	mux.Handle("/", api.spaHandler())

	return apiHeaders(gzipResponses(recoverPanics(mux)))
}

func (api *API) handleAPINotFound(w http.ResponseWriter, _ *http.Request) {
	writeError(w, http.StatusNotFound, "not_found", "API endpoint not found")
}

func (api *API) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func (api *API) handleNotesList(w http.ResponseWriter, r *http.Request) {
	view := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("view")))
	if view == "" {
		view = "all"
	}
	if view != "all" && view != "favorites" && view != "trash" {
		writeError(w, http.StatusBadRequest, "invalid_view", "view must be one of: all, favorites, trash")
		return
	}
	notes := api.store.List(NoteFilter{
		Query:      r.URL.Query().Get("q"),
		Tag:        r.URL.Query().Get("tag"),
		Collection: r.URL.Query().Get("collection"),
		View:       view,
	})
	writeJSON(w, http.StatusOK, map[string]any{"notes": notes, "total": len(notes)})
}

func (api *API) handleNotesCreate(w http.ResponseWriter, r *http.Request) {
	var input CreateNoteInput
	if !decodeJSON(w, r, &input) {
		return
	}
	note, err := api.store.Create(input)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	w.Header().Set("Location", "/api/notes/"+note.ID)
	writeJSON(w, http.StatusCreated, note)
}

func (api *API) handleNoteGet(w http.ResponseWriter, r *http.Request) {
	id, ok := noteID(w, r)
	if !ok {
		return
	}
	note, err := api.store.Get(id)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, note)
}

func (api *API) handleNoteUpdate(w http.ResponseWriter, r *http.Request) {
	id, ok := noteID(w, r)
	if !ok {
		return
	}
	var input UpdateNoteInput
	if !decodeJSON(w, r, &input) {
		return
	}
	note, err := api.store.Update(id, input)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, note)
}

func (api *API) handleNoteDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := noteID(w, r)
	if !ok {
		return
	}
	permanent := r.URL.Query().Get("permanent")
	if permanent != "" && permanent != "true" && permanent != "false" {
		writeError(w, http.StatusBadRequest, "invalid_query", "permanent must be true or false")
		return
	}
	if permanent == "true" {
		if err := api.store.DeletePermanently(id); err != nil {
			api.writeStoreError(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	note, err := api.store.Trash(id)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, note)
}

func (api *API) handleNoteTrash(w http.ResponseWriter, r *http.Request) {
	id, ok := noteID(w, r)
	if !ok {
		return
	}
	note, err := api.store.Trash(id)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, note)
}

func (api *API) handleNoteRestore(w http.ResponseWriter, r *http.Request) {
	id, ok := noteID(w, r)
	if !ok {
		return
	}
	note, err := api.store.Restore(id)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, note)
}

func (api *API) handleStats(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, api.store.Stats())
}

func (api *API) handleCollections(w http.ResponseWriter, _ *http.Request) {
	items := api.store.Collections()
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (api *API) handleTags(w http.ResponseWriter, _ *http.Request) {
	items := api.store.Tags()
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (api *API) handleOptions(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Allow", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
	w.WriteHeader(http.StatusNoContent)
}

func (api *API) writeStoreError(w http.ResponseWriter, err error) {
	var validation *ValidationError
	switch {
	case errors.As(err, &validation):
		writeError(w, http.StatusUnprocessableEntity, "validation_error", validation.Error())
	case errors.Is(err, ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "note not found")
	case errors.Is(err, ErrAlreadyDeleted):
		writeError(w, http.StatusConflict, "note_in_trash", "restore the note before editing it")
	case errors.Is(err, ErrNotDeleted):
		writeError(w, http.StatusConflict, "note_not_in_trash", "note is not in trash")
	default:
		log.Printf("api storage error: %v", err)
		writeError(w, http.StatusInternalServerError, "storage_error", "the note could not be saved")
	}
}

func (api *API) spaHandler() http.Handler {
	if api.static == nil {
		return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			http.NotFound(w, nil)
		})
	}
	files := http.FileServer(http.FS(api.static))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
			return
		}
		requested := strings.TrimPrefix(pathpkg.Clean("/"+r.URL.Path), "/")
		if requested == "." || requested == "" {
			requested = "index.html"
		}
		if info, err := fs.Stat(api.static, requested); err == nil && !info.IsDir() {
			setStaticCacheHeaders(w, requested)
			files.ServeHTTP(w, r)
			return
		}
		index, err := fs.ReadFile(api.static, "index.html")
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		http.ServeContent(w, r, "index.html", time.Time{}, bytes.NewReader(index))
	})
}

func setStaticCacheHeaders(w http.ResponseWriter, name string) {
	// Asset names are intentionally human-readable rather than content-hashed,
	// so clients must revalidate after a server update instead of keeping an old
	// JavaScript bundle beside a new API binary.
	w.Header().Set("Cache-Control", "no-cache")
	if extension := pathpkg.Ext(name); extension != "" {
		if mediaType := mime.TypeByExtension(extension); mediaType != "" {
			w.Header().Set("Content-Type", mediaType)
		}
	}
}

func noteID(w http.ResponseWriter, r *http.Request) (string, bool) {
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" || len(id) > 128 || strings.ContainsAny(id, "/\\") {
		writeError(w, http.StatusBadRequest, "invalid_id", "invalid note id")
		return "", false
	}
	return id, true
}

func decodeJSON(w http.ResponseWriter, r *http.Request, destination any) bool {
	contentType := r.Header.Get("Content-Type")
	if contentType != "" && !strings.HasPrefix(strings.ToLower(contentType), "application/json") {
		writeError(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/json")
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBody)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "body_too_large", "request body exceeds 2 MiB")
			return false
		}
		if errors.Is(err, io.EOF) {
			writeError(w, http.StatusBadRequest, "invalid_json", "request body must contain a JSON object")
			return false
		}
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid JSON: "+safeJSONError(err))
		return false
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_json", "request body must contain exactly one JSON object")
		return false
	}
	return true
}

func safeJSONError(err error) string {
	message := err.Error()
	if len(message) > 180 {
		message = message[:180]
	}
	return message
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("encode response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorEnvelope{Error: apiError{Code: code, Message: message}})
}

func apiHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "same-origin")
		w.Header().Set("Cross-Origin-Resource-Policy", "same-origin")
		w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'")
		if strings.HasPrefix(r.URL.Path, "/api/") || r.URL.Path == "/healthz" {
			w.Header().Set("Cache-Control", "no-store")
		}
		next.ServeHTTP(w, r)
	})
}

type gzipResponseWriter struct {
	http.ResponseWriter
	writer *gzip.Writer
}

func (w *gzipResponseWriter) WriteHeader(status int) {
	w.Header().Del("Content-Length")
	w.ResponseWriter.WriteHeader(status)
}

func (w *gzipResponseWriter) Write(data []byte) (int, error) {
	w.Header().Del("Content-Length")
	return w.writer.Write(data)
}

func gzipResponses(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.Header.Get("Range") != "" || !strings.Contains(strings.ToLower(r.Header.Get("Accept-Encoding")), "gzip") || alreadyCompressedPath(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Content-Encoding", "gzip")
		w.Header().Add("Vary", "Accept-Encoding")
		writer := gzip.NewWriter(w)
		defer writer.Close()
		next.ServeHTTP(&gzipResponseWriter{ResponseWriter: w, writer: writer}, r)
	})
}

func alreadyCompressedPath(path string) bool {
	switch strings.ToLower(pathpkg.Ext(path)) {
	case ".png", ".jpg", ".jpeg", ".gif", ".webp", ".zip", ".gz", ".pdf", ".woff", ".woff2":
		return true
	default:
		return false
	}
}

func recoverPanics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				log.Printf("panic serving %s %s: %v", r.Method, r.URL.Path, recovered)
				writeError(w, http.StatusInternalServerError, "internal_error", "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func requireStore(store *Store) error {
	if store == nil {
		return fmt.Errorf("store is required")
	}
	return nil
}
