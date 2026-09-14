package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func TestAPINoteLifecycle(t *testing.T) {
	store, path := newEmptyTestStore(t)
	handler := NewHandler(store, nil)

	response := performJSON(t, handler, http.MethodPost, "/api/notes", `{
		"title":"API note",
		"content":"searchable private full content",
		"tags":["Go","API"],
		"collection":"Work",
		"emoji":"🧪",
		"color":"#123ABC",
		"favorite":true
	}`)
	if response.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, body = %s", response.Code, response.Body.String())
	}
	var created Note
	decodeResponse(t, response, &created)
	if created.ID == "" || created.WordCount != 4 || created.Content == "" {
		t.Fatalf("created note = %+v", created)
	}

	response = performJSON(t, handler, http.MethodGet, "/api/notes?q=private&tag=go&collection=work&view=favorites", "")
	if response.Code != http.StatusOK {
		t.Fatalf("list status = %d, body = %s", response.Code, response.Body.String())
	}
	var list struct {
		Notes []map[string]any `json:"notes"`
		Total int              `json:"total"`
	}
	decodeResponse(t, response, &list)
	if list.Total != 1 || len(list.Notes) != 1 {
		t.Fatalf("list = %+v", list)
	}
	if _, leaked := list.Notes[0]["content"]; leaked {
		t.Fatalf("list summary leaked full content: %+v", list.Notes[0])
	}
	if list.Notes[0]["excerpt"] == "" {
		t.Fatalf("list summary has no excerpt: %+v", list.Notes[0])
	}

	response = performJSON(t, handler, http.MethodGet, "/api/notes/"+created.ID, "")
	var fetched Note
	decodeResponse(t, response, &fetched)
	if fetched.Content != created.Content {
		t.Fatalf("GET content = %q, want %q", fetched.Content, created.Content)
	}

	response = performJSON(t, handler, http.MethodPut, "/api/notes/"+created.ID, `{"content":"updated words","pinned":true}`)
	if response.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, body = %s", response.Code, response.Body.String())
	}
	var updated Note
	decodeResponse(t, response, &updated)
	if updated.WordCount != 2 || !updated.Pinned || !updated.UpdatedAt.After(created.UpdatedAt) {
		t.Fatalf("updated note = %+v", updated)
	}

	response = performJSON(t, handler, http.MethodDelete, "/api/notes/"+created.ID, "")
	if response.Code != http.StatusOK {
		t.Fatalf("trash status = %d, body = %s", response.Code, response.Body.String())
	}
	var trashed Note
	decodeResponse(t, response, &trashed)
	if trashed.DeletedAt == nil {
		t.Fatal("DELETE without permanent did not trash note")
	}

	response = performJSON(t, handler, http.MethodPost, "/api/notes/"+created.ID+"/restore", "")
	if response.Code != http.StatusOK {
		t.Fatalf("restore status = %d, body = %s", response.Code, response.Body.String())
	}
	response = performJSON(t, handler, http.MethodDelete, "/api/notes/"+created.ID+"?permanent=true", "")
	if response.Code != http.StatusConflict {
		t.Fatalf("permanent active status = %d, want 409; body = %s", response.Code, response.Body.String())
	}
	_ = performJSON(t, handler, http.MethodDelete, "/api/notes/"+created.ID, "")
	response = performJSON(t, handler, http.MethodDelete, "/api/notes/"+created.ID+"?permanent=true", "")
	if response.Code != http.StatusNoContent || response.Body.Len() != 0 {
		t.Fatalf("permanent status/body = %d/%q", response.Code, response.Body.String())
	}

	reopened, err := NewStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.Stats().Total != 0 {
		t.Fatalf("API deletion not persisted: %+v", reopened.Stats())
	}
}

func TestAPIStatsAndFacets(t *testing.T) {
	store, _ := newEmptyTestStore(t)
	handler := NewHandler(store, nil)
	first := createViaAPI(t, handler, `{"title":"One","content":"one two","tags":["go","api"],"collection":"Work","favorite":true}`)
	createViaAPI(t, handler, `{"title":"Two","content":"three","tags":["go"],"collection":"Work","pinned":true}`)
	_ = performJSON(t, handler, http.MethodDelete, "/api/notes/"+first.ID, "")

	response := performJSON(t, handler, http.MethodGet, "/api/stats", "")
	var stats Stats
	decodeResponse(t, response, &stats)
	if stats.Total != 2 || stats.Active != 1 || stats.Trashed != 1 || stats.Words != 1 || stats.Collections != 1 || stats.Tags != 1 {
		t.Fatalf("stats = %+v", stats)
	}

	response = performJSON(t, handler, http.MethodGet, "/api/tags", "")
	var tags struct {
		Items []NamedCount `json:"items"`
	}
	decodeResponse(t, response, &tags)
	if len(tags.Items) != 1 || tags.Items[0] != (NamedCount{Name: "go", Count: 1}) {
		t.Fatalf("tags = %+v", tags.Items)
	}
	response = performJSON(t, handler, http.MethodGet, "/api/collections", "")
	var collections struct {
		Items []NamedCount `json:"items"`
	}
	decodeResponse(t, response, &collections)
	if len(collections.Items) != 1 || collections.Items[0].Name != "Work" || collections.Items[0].Count != 1 {
		t.Fatalf("collections = %+v", collections.Items)
	}
}

func TestAPIValidationAndBodyLimit(t *testing.T) {
	store, _ := newEmptyTestStore(t)
	handler := NewHandler(store, nil)
	tests := []struct {
		name   string
		body   string
		status int
	}{
		{name: "malformed", body: `{"title":`, status: http.StatusBadRequest},
		{name: "unknown field", body: `{"title":"x","id":"client-id"}`, status: http.StatusBadRequest},
		{name: "bad color", body: `{"title":"x","color":"red"}`, status: http.StatusUnprocessableEntity},
		{name: "content validation", body: `{"title":"x","content":"` + strings.Repeat("a", maxContentBytes+1) + `"}`, status: http.StatusUnprocessableEntity},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := performJSON(t, handler, http.MethodPost, "/api/notes", test.body)
			if response.Code != test.status {
				t.Fatalf("status = %d, want %d; body = %s", response.Code, test.status, response.Body.String())
			}
			if !strings.Contains(response.Header().Get("Content-Type"), "application/json") {
				t.Fatalf("error Content-Type = %q", response.Header().Get("Content-Type"))
			}
		})
	}

	oversized := bytes.NewBufferString(`{"title":"x","content":"` + strings.Repeat("a", maxRequestBody) + `"}`)
	request := httptest.NewRequest(http.MethodPost, "/api/notes", oversized)
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized status = %d, want 413; body = %s", response.Code, response.Body.String())
	}

	response = performJSON(t, handler, http.MethodGet, "/api/notes?view=wrong", "")
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid view status = %d, want 400", response.Code)
	}
	response = performJSON(t, handler, http.MethodGet, "/api/does-not-exist", "")
	if response.Code != http.StatusNotFound || !strings.Contains(response.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("unknown API response = %d %q", response.Code, response.Header().Get("Content-Type"))
	}
}

func TestHealthAndSPAFallback(t *testing.T) {
	store, _ := newEmptyTestStore(t)
	assets := fstest.MapFS{
		"index.html":    {Data: []byte("<!doctype html><title>Shard</title>")},
		"assets/app.js": {Data: []byte("console.log('shard')")},
	}
	handler := NewHandler(store, assets)

	response := performJSON(t, handler, http.MethodGet, "/healthz", "")
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"status":"ok"`) {
		t.Fatalf("health response = %d %s", response.Code, response.Body.String())
	}
	response = performJSON(t, handler, http.MethodGet, "/notes/client-route", "")
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "<title>Shard</title>") {
		t.Fatalf("SPA fallback = %d %s", response.Code, response.Body.String())
	}
	response = performJSON(t, handler, http.MethodGet, "/assets/app.js", "")
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "console.log") {
		t.Fatalf("asset response = %d %s", response.Code, response.Body.String())
	}
}

func TestStaticCompressionAndSecurityHeaders(t *testing.T) {
	store, _ := newEmptyTestStore(t)
	assets := fstest.MapFS{
		"index.html": {Data: []byte("<!doctype html><title>Shard</title>")},
		"app.js":     {Data: []byte("console.log('compressed shard client')")},
	}
	handler := NewHandler(store, assets)
	request := httptest.NewRequest(http.MethodGet, "/app.js", nil)
	request.Header.Set("Accept-Encoding", "gzip")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Content-Encoding") != "gzip" {
		t.Fatalf("compressed response = %d encoding=%q", response.Code, response.Header().Get("Content-Encoding"))
	}
	reader, err := gzip.NewReader(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(decoded), "compressed shard client") {
		t.Fatalf("decoded body = %q", decoded)
	}
	if policy := response.Header().Get("Content-Security-Policy"); !strings.Contains(policy, "script-src 'self'") {
		t.Fatalf("Content-Security-Policy = %q", policy)
	}
}

func TestBasicAuth(t *testing.T) {
	protected := basicAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}), "shard", "correct horse battery staple")

	unauthorized := httptest.NewRecorder()
	protected.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodGet, "/", nil))
	if unauthorized.Code != http.StatusUnauthorized || unauthorized.Header().Get("WWW-Authenticate") == "" {
		t.Fatalf("unauthorized response = %d challenge=%q", unauthorized.Code, unauthorized.Header().Get("WWW-Authenticate"))
	}

	request := httptest.NewRequest(http.MethodGet, "/", nil)
	request.SetBasicAuth("shard", "correct horse battery staple")
	authorized := httptest.NewRecorder()
	protected.ServeHTTP(authorized, request)
	if authorized.Code != http.StatusNoContent {
		t.Fatalf("authorized status = %d", authorized.Code)
	}
}

func createViaAPI(t *testing.T, handler http.Handler, body string) Note {
	t.Helper()
	response := performJSON(t, handler, http.MethodPost, "/api/notes", body)
	if response.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, body = %s", response.Code, response.Body.String())
	}
	var note Note
	decodeResponse(t, response, &note)
	return note
}

func performJSON(t *testing.T, handler http.Handler, method, target, body string) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	request := httptest.NewRequest(method, target, reader)
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func decodeResponse(t *testing.T, response *httptest.ResponseRecorder, destination any) {
	t.Helper()
	if err := json.Unmarshal(response.Body.Bytes(), destination); err != nil {
		t.Fatalf("decode response: %v; body = %s", err, response.Body.String())
	}
}
