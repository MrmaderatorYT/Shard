package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"errors"
	"flag"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

// staticFiles packages the complete web client into the single server executable.
//
//go:embed all:static
var staticFiles embed.FS

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	defaultAddress := envOr("SHARD_ADDR", ":8080")
	defaultData := envOr("SHARD_DATA_FILE", filepath.Join("data", "notes.json"))
	address := flag.String("addr", defaultAddress, "HTTP listen address")
	dataFile := flag.String("data", defaultData, "path to the JSON notes file")
	flag.Parse()

	store, err := NewStore(*dataFile)
	if err != nil {
		return err
	}
	webFiles, err := fs.Sub(staticFiles, "static")
	if err != nil {
		return err
	}
	if err := requireStore(store); err != nil {
		return err
	}
	handler := NewHandler(store, webFiles)
	authUser, authPassword := os.Getenv("SHARD_AUTH_USER"), os.Getenv("SHARD_AUTH_PASSWORD")
	if (authUser == "") != (authPassword == "") {
		return errors.New("SHARD_AUTH_USER and SHARD_AUTH_PASSWORD must be set together")
	}
	if authUser != "" {
		handler = basicAuth(handler, authUser, authPassword)
	}

	server := &http.Server{
		Addr:              *address,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serveError := make(chan error, 1)
	go func() {
		log.Printf("Shard web is listening on %s (data: %s)", *address, *dataFile)
		serveError <- server.ListenAndServe()
	}()

	select {
	case err := <-serveError:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case <-ctx.Done():
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		return err
	}
	return nil
}

func basicAuth(next http.Handler, expectedUser, expectedPassword string) http.Handler {
	expectedUserHash := sha256.Sum256([]byte(expectedUser))
	expectedPasswordHash := sha256.Sum256([]byte(expectedPassword))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, password, ok := r.BasicAuth()
		userHash := sha256.Sum256([]byte(user))
		passwordHash := sha256.Sum256([]byte(password))
		valid := subtle.ConstantTimeCompare(userHash[:], expectedUserHash[:]) == 1 &&
			subtle.ConstantTimeCompare(passwordHash[:], expectedPasswordHash[:]) == 1
		if !ok || !valid {
			w.Header().Set("WWW-Authenticate", `Basic realm="Shard", charset="UTF-8"`)
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
