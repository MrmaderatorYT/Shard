(() => {
  "use strict";

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const elements = {
    shell: $("#app-shell"),
    sidebar: $("#sidebar"),
    scrim: $("#scrim"),
    search: $("#search-input"),
    list: $("#note-list"),
    listScroll: $("#notes-scroll"),
    loading: $("#notes-loading"),
    empty: $("#empty-state"),
    emptyTitle: $("#empty-title"),
    emptyCopy: $("#empty-copy"),
    listTitle: $("#list-title"),
    listContext: $("#list-context"),
    visibleCount: $("#visible-count"),
    sort: $("#sort-select"),
    activeFilter: $("#active-filter"),
    activeFilterLabel: $("#active-filter-label"),
    connectionStatus: $("#connection-status"),
    collections: $("#collections-list"),
    collectionOptions: $("#collection-options"),
    tags: $("#tag-cloud"),
    editorWelcome: $("#editor-welcome"),
    editor: $("#editor"),
    editorTitle: $("#editor-title"),
    markdown: $("#markdown-editor"),
    preview: $("#markdown-preview"),
    collectionInput: $("#collection-input"),
    tagsInput: $("#tags-input"),
    emojiButton: $("#emoji-button"),
    favoriteButton: $("#favorite-button"),
    pinButton: $("#pin-button"),
    trashBanner: $("#trash-banner"),
    noteMenu: $("#note-menu"),
    noteMenuButton: $("#note-menu-button"),
    deleteMenuItem: $("#delete-note"),
    breadcrumbs: $("#breadcrumbs"),
    saveStatus: $("#save-status"),
    documentMeta: $("#document-meta"),
    updatedTime: $("#updated-time"),
    formatToolbar: $("#format-toolbar"),
    newDialog: $("#new-note-dialog"),
    newForm: $("#new-note-form"),
    newName: $("#new-note-name"),
    newCollection: $("#new-note-collection"),
    newError: $("#new-note-error"),
    confirmDialog: $("#confirm-dialog"),
    confirmForm: $("#confirm-form"),
    confirmTitle: $("#confirm-title"),
    confirmCopy: $("#confirm-copy"),
    confirmAction: $("#confirm-action"),
    toastRegion: $("#toast-region"),
    themeToggle: $("#theme-toggle"),
    themeIcon: $("#theme-icon"),
    themeLabel: $("#theme-label"),
    themeMeta: $('meta[name="theme-color"]'),
  };

  const VIEW_META = {
    all: { title: "Усі нотатки", context: "Моє сховище" },
    favorites: { title: "Улюблені", context: "Обране" },
    pinned: { title: "Закріплені", context: "Швидкий доступ" },
    recent: { title: "Недавні", context: "Остання робота" },
    trash: { title: "Кошик", context: "Видалені нотатки" },
  };

  const NOTE_COLORS = ["#6e8fe8", "#8b7bd8", "#57a98a", "#d98e4b", "#cf6b8e", "#4fa0b0", "#b08968", "#8a8f98"];
  const EMOJIS = ["✦", "📝", "💡", "📌", "🧭", "🌿", "📚", "🧩", "🚀", "☕", "🗂️", "✨"];

  const state = {
    notes: [],
    current: null,
    selectedId: null,
    view: "all",
    tag: "",
    collection: "",
    query: "",
    sort: localStorage.getItem("shard-sort") || "updated-desc",
    stats: {},
    collections: [],
    tags: [],
    loading: true,
    loadError: null,
    detailSequence: 0,
    listSequence: 0,
    changeGeneration: 0,
    dirty: false,
    saveTimer: null,
    savePromise: null,
    facetGeneration: 0,
    savedFacetGeneration: 0,
    savedWordCount: 0,
    editorMode: "edit",
    confirmCallback: null,
    theme: localStorage.getItem("shard-theme") || "system",
  };

  class ApiError extends Error {
    constructor(message, status, code) {
      super(message);
      this.name = "ApiError";
      this.status = status;
      this.code = code || "request_failed";
    }
  }

  const api = {
    async request(path, options = {}) {
      const headers = { Accept: "application/json", ...(options.headers || {}) };
      if (options.body && typeof options.body !== "string") {
        headers["Content-Type"] = "application/json";
        options.body = JSON.stringify(options.body);
      }

      let response;
      try {
        response = await fetch(path, { ...options, headers });
      } catch (error) {
        throw new ApiError("Сервер Shard недоступний", 0, "network_error");
      }

      const type = response.headers.get("content-type") || "";
      let payload = null;
      if (response.status !== 204) {
        try {
          payload = type.includes("json") ? await response.json() : await response.text();
        } catch (_) {
          payload = null;
        }
      }

      if (!response.ok) {
        const detail = payload?.error || payload;
        const message = typeof detail === "string"
          ? detail
          : detail?.message || `Запит завершився з кодом ${response.status}`;
        throw new ApiError(message, response.status, detail?.code);
      }
      return payload;
    },

    list(params = {}) {
      const search = new URLSearchParams();
      Object.entries(params).forEach(([key, value]) => {
        if (value !== "" && value != null) search.set(key, value);
      });
      const suffix = search.size ? `?${search.toString()}` : "";
      return this.request(`/api/notes${suffix}`);
    },

    getNote(id) {
      return this.request(`/api/notes/${encodeURIComponent(id)}`);
    },

    createNote(note) {
      return this.request("/api/notes", { method: "POST", body: note });
    },

    updateNote(id, note) {
      return this.request(`/api/notes/${encodeURIComponent(id)}`, { method: "PUT", body: note });
    },

    deleteNote(id, permanent = false) {
      const suffix = permanent ? "?permanent=true" : "";
      return this.request(`/api/notes/${encodeURIComponent(id)}${suffix}`, { method: "DELETE" });
    },

    restoreNote(id) {
      return this.request(`/api/notes/${encodeURIComponent(id)}/restore`, { method: "POST" });
    },

    stats() {
      return this.request("/api/stats");
    },

    collections() {
      return this.request("/api/collections");
    },

    tags() {
      return this.request("/api/tags");
    },
  };

  function unwrapNote(payload) {
    if (!payload) return null;
    return payload.note || payload.data?.note || payload.data || payload;
  }

  function normalizeDate(value) {
    if (!value) return null;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  function normalizeNote(raw = {}) {
    const tags = Array.isArray(raw.tags)
      ? raw.tags.map((tag) => String(tag).replace(/^#/, "").trim()).filter(Boolean)
      : [];
    return {
      id: String(raw.id ?? ""),
      title: String(raw.title ?? "Без назви"),
      content: typeof raw.content === "string" ? raw.content : null,
      excerpt: String(raw.excerpt ?? ""),
      tags,
      collection: String(raw.collection ?? ""),
      emoji: String(raw.emoji ?? ""),
      color: normalizeColor(raw.color),
      pinned: Boolean(raw.pinned),
      favorite: Boolean(raw.favorite ?? raw.bookmarked),
      createdAt: normalizeDate(raw.createdAt ?? raw.created_at) || new Date().toISOString(),
      updatedAt: normalizeDate(raw.updatedAt ?? raw.updated_at) || normalizeDate(raw.createdAt) || new Date().toISOString(),
      deletedAt: normalizeDate(raw.deletedAt ?? raw.deleted_at),
      wordCount: Number.isFinite(Number(raw.wordCount)) ? Number(raw.wordCount) : countWords(raw.content || raw.excerpt || ""),
    };
  }

  function normalizeColor(value) {
    if (typeof value === "string" && /^#[0-9a-f]{6}$/i.test(value)) return value;
    if (Number.isInteger(value) && value !== 0) {
      return `#${(value >>> 0).toString(16).slice(-6).padStart(6, "0")}`;
    }
    return "";
  }

  function escapeHTML(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  function noteColor(note) {
    if (note.color) return note.color;
    let hash = 0;
    const value = note.title || note.id || "Shard";
    for (let index = 0; index < value.length; index += 1) {
      hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
    }
    return NOTE_COLORS[Math.abs(hash) % NOTE_COLORS.length];
  }

  function countWords(text) {
    return String(text || "").trim().match(/[\p{L}\p{N}]+(?:[’'ʼ-][\p{L}\p{N}]+)*/gu)?.length || 0;
  }

  function plural(number, forms) {
    const n = Math.abs(Number(number));
    const n10 = n % 10;
    const n100 = n % 100;
    if (n10 === 1 && n100 !== 11) return forms[0];
    if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return forms[1];
    return forms[2];
  }

  function formatCount(number) {
    return new Intl.NumberFormat("uk-UA").format(Number(number) || 0);
  }

  function timeAgo(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "нещодавно";
    const seconds = Math.round((date.getTime() - Date.now()) / 1000);
    const abs = Math.abs(seconds);
    if (abs < 45) return "щойно";
    const formatter = new Intl.RelativeTimeFormat("uk-UA", { numeric: "auto" });
    if (abs < 3600) return formatter.format(Math.round(seconds / 60), "minute");
    if (abs < 86400) return formatter.format(Math.round(seconds / 3600), "hour");
    if (abs < 604800) return formatter.format(Math.round(seconds / 86400), "day");
    return new Intl.DateTimeFormat("uk-UA", { day: "numeric", month: "short" }).format(date);
  }

  function cleanExcerpt(note) {
    const source = note.excerpt || "Нотатка без тексту";
    return source
      .replace(/^#{1,6}\s+/gm, "")
      .replace(/\[\[([^\]]+)\]\]/g, "$1")
      .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
      .replace(/[*_`>#-]/g, "")
      .replace(/\s+/g, " ")
      .trim() || "Нотатка без тексту";
  }

  function apiMessage(error, fallback) {
    if (error instanceof ApiError && error.message) return error.message;
    return fallback;
  }

  async function loadNotes({ keepSelection = true } = {}) {
    const sequence = ++state.listSequence;
    state.loading = true;
    state.loadError = null;
    renderLoading();

    const apiView = state.view === "favorites" ? "favorites" : state.view === "trash" ? "trash" : "all";
    try {
      const payload = await api.list({
        view: apiView,
        q: state.query,
        tag: state.tag,
        collection: state.collection,
      });
      if (sequence !== state.listSequence) return;
      const rawNotes = Array.isArray(payload) ? payload : payload?.notes || payload?.data?.notes || [];
      state.notes = rawNotes.map(normalizeNote);
      state.loading = false;
      setConnection("online");
      renderNotes();
      renderNavigation();

      // Keep an open document visible even when a search or facet hides it from
      // the list. Closing it here could discard a still-debouncing edit.
    } catch (error) {
      if (sequence !== state.listSequence) return;
      state.notes = [];
      state.loading = false;
      state.loadError = error;
      setConnection("error");
      renderNotes();
      showToast(apiMessage(error, "Не вдалося завантажити нотатки"), { type: "error", duration: 5000 });
    }
  }

  async function loadSupportingData() {
    const [statsResult, collectionsResult, tagsResult] = await Promise.allSettled([
      api.stats(),
      api.collections(),
      api.tags(),
    ]);

    if (statsResult.status === "fulfilled") {
      state.stats = statsResult.value?.stats || statsResult.value?.data || statsResult.value || {};
    }
    if (collectionsResult.status === "fulfilled") {
      state.collections = normalizeItems(collectionsResult.value);
    }
    if (tagsResult.status === "fulfilled") {
      state.tags = normalizeItems(tagsResult.value);
    }

    if (!state.collections.length) state.collections = deriveItems("collection");
    if (!state.tags.length) state.tags = deriveItems("tags");
    renderNavigation();
  }

  function normalizeItems(payload) {
    const items = Array.isArray(payload) ? payload : payload?.items || payload?.data?.items || [];
    return items
      .map((item) => typeof item === "string" ? { name: item, count: 0 } : { name: String(item.name || ""), count: Number(item.count) || 0 })
      .filter((item) => item.name)
      .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, "uk"));
  }

  function deriveItems(field) {
    const counts = new Map();
    state.notes.forEach((note) => {
      const values = field === "tags" ? note.tags : [note.collection];
      values.filter(Boolean).forEach((value) => counts.set(value, (counts.get(value) || 0) + 1));
    });
    return [...counts].map(([name, count]) => ({ name, count }));
  }

  function renderLoading() {
    elements.loading.hidden = !state.loading;
    elements.list.hidden = state.loading;
    elements.empty.hidden = true;
  }

  function filteredNotes() {
    let notes = [...state.notes];
    if (state.view === "pinned") notes = notes.filter((note) => note.pinned);
    if (state.view === "recent") notes = notes.sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt)).slice(0, 50);

    return notes.sort((a, b) => {
      if (state.sort === "title-asc") return a.title.localeCompare(b.title, "uk", { sensitivity: "base" });
      if (state.sort === "created-desc") return new Date(b.createdAt) - new Date(a.createdAt);
      if (a.pinned !== b.pinned && state.view === "all") return a.pinned ? -1 : 1;
      return new Date(b.updatedAt) - new Date(a.updatedAt);
    });
  }

  function renderNotes() {
    elements.loading.hidden = true;
    elements.list.hidden = false;
    const notes = filteredNotes();
    const meta = VIEW_META[state.view] || VIEW_META.all;
    elements.listTitle.textContent = meta.title;
    elements.listContext.textContent = meta.context;
    elements.visibleCount.textContent = `${formatCount(notes.length)} ${plural(notes.length, ["нотатка", "нотатки", "нотаток"])}`;

    elements.activeFilter.hidden = !state.tag && !state.collection;
    elements.activeFilterLabel.textContent = state.tag ? `# ${state.tag}` : state.collection ? `Колекція: ${state.collection}` : "";

    elements.list.replaceChildren(...notes.map(createNoteCard));
    elements.empty.hidden = notes.length > 0;

    if (notes.length === 0) {
      const copy = emptyStateCopy();
      elements.emptyTitle.textContent = copy.title;
      elements.emptyCopy.textContent = copy.body;
      const action = $("button", elements.empty);
      action.textContent = state.loadError ? "Спробувати ще раз" : state.view === "trash" ? "Повернутися до нотаток" : "Створити нотатку";
      action.dataset.action = state.loadError ? "retry" : state.view === "trash" ? "all" : "create";
    }
  }

  function emptyStateCopy() {
    if (state.loadError) return { title: "Немає зв’язку із сервером", body: "Перевірте, чи запущено сервер Shard, і спробуйте ще раз." };
    if (state.query) return { title: "Нічого не знайдено", body: `Спробуйте інший запит замість «${state.query}» або очистьте пошук.` };
    if (state.tag) return { title: `Немає нотаток із тегом #${state.tag}`, body: "Додайте цей тег до нотатки або оберіть інший." };
    if (state.collection) return { title: "Колекція порожня", body: `Додайте нотатку до колекції «${state.collection}».` };
    if (state.view === "trash") return { title: "Кошик порожній", body: "Видалені нотатки з’являться тут, і їх можна буде відновити." };
    if (state.view === "favorites") return { title: "Улюблених ще немає", body: "Позначте важливу нотатку ромбом — вона з’явиться тут." };
    if (state.view === "pinned") return { title: "Нічого не закріплено", body: "Закріпіть нотатки, до яких повертаєтесь найчастіше." };
    if (state.view === "recent") return { title: "Історія поки порожня", body: "Останні відкриті та змінені нотатки з’являться тут." };
    return { title: "Нотаток ще немає", body: "Створіть першу нотатку — вона зберігатиметься у звичайному Markdown." };
  }

  function createNoteCard(note) {
    const card = document.createElement("button");
    card.type = "button";
    card.className = `note-card${note.id === state.selectedId ? " is-selected" : ""}`;
    card.dataset.id = note.id;
    card.setAttribute("role", "option");
    card.setAttribute("aria-selected", String(note.id === state.selectedId));
    card.style.setProperty("--note-color", noteColor(note));

    const icon = note.emoji || note.title.trim().charAt(0).toLocaleUpperCase("uk-UA") || "✦";
    const flags = [
      note.pinned ? '<span title="Закріплено" aria-label="Закріплено">⌾</span>' : "",
      note.favorite ? '<span title="В улюблених" aria-label="В улюблених">◆</span>' : "",
    ].join("");
    const visibleTags = note.tags.slice(0, 1);
    const tags = visibleTags.map((tag) => `<span class="mini-tag">#${escapeHTML(tag)}</span>`).join("");
    const more = note.tags.length > 1 ? `<span class="mini-tag more">+${note.tags.length - 1}</span>` : "";

    card.innerHTML = `
      <span class="note-icon" aria-hidden="true">${escapeHTML(icon)}</span>
      <span class="note-card-main">
        <span class="note-card-topline">
          <span class="note-card-title">${escapeHTML(note.title || "Без назви")}</span>
          <span class="note-flags">${flags}</span>
        </span>
        <span class="note-card-excerpt">${escapeHTML(cleanExcerpt(note))}</span>
        <span class="note-card-meta">
          <time datetime="${escapeHTML(note.updatedAt)}">${escapeHTML(timeAgo(note.updatedAt))}</time>
          ${note.collection ? `<span aria-hidden="true">·</span><span class="mini-tag">${escapeHTML(note.collection)}</span>` : ""}
          ${tags}${more}
        </span>
      </span>`;
    return card;
  }

  function statValue(...keys) {
    for (const key of keys) {
      const value = state.stats[key];
      if (Number.isFinite(Number(value))) return Number(value);
    }
    return null;
  }

  function renderNavigation() {
    const allCount = statValue("active", "notes", "noteCount", "totalNotes") ?? (state.view === "all" ? state.notes.length : 0);
    const favoriteCount = statValue("favorites", "favoriteCount") ?? state.notes.filter((note) => note.favorite).length;
    const pinnedCount = statValue("pinned", "pinnedCount") ?? (state.view === "all" ? state.notes.filter((note) => note.pinned).length : 0);
    const trashCount = statValue("trashed", "trash", "trashCount", "deleted") ?? (state.view === "trash" ? state.notes.length : 0);
    const words = statValue("words", "wordCount", "totalWords") ?? state.notes.reduce((sum, note) => sum + note.wordCount, 0);

    $("#count-all").textContent = formatCount(allCount);
    $("#count-favorites").textContent = formatCount(favoriteCount);
    $("#count-pinned").textContent = formatCount(pinnedCount);
    $("#count-trash").textContent = formatCount(trashCount);
    $("#stats-notes").textContent = formatCount(allCount);
    $("#stats-words").textContent = formatCount(words);

    $$("[data-view]").forEach((button) => {
      const active = button.dataset.view === state.view && !state.tag && !state.collection;
      button.classList.toggle("is-active", active);
      if (active) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });

    elements.collections.replaceChildren();
    if (!state.collections.length) {
      const empty = document.createElement("p");
      empty.className = "sidebar-empty";
      empty.textContent = "Колекції з’являться після створення нотаток.";
      elements.collections.append(empty);
    } else {
      state.collections.slice(0, 8).forEach((item, index) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = `collection-item${state.collection === item.name ? " is-active" : ""}`;
        button.dataset.collection = item.name;
        button.style.setProperty("--collection-color", NOTE_COLORS[index % NOTE_COLORS.length]);
        button.innerHTML = `<span class="collection-dot" aria-hidden="true"></span><span>${escapeHTML(item.name)}</span><small>${formatCount(item.count)}</small>`;
        elements.collections.append(button);
      });
    }

    elements.collectionOptions.replaceChildren(...state.collections.map((item) => {
      const option = document.createElement("option");
      option.value = item.name;
      return option;
    }));

    elements.tags.replaceChildren();
    if (!state.tags.length) {
      const empty = document.createElement("span");
      empty.className = "sidebar-empty";
      empty.textContent = "Тегів поки немає";
      elements.tags.append(empty);
    } else {
      state.tags.slice(0, 12).forEach((item) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = `tag-filter${state.tag === item.name ? " is-active" : ""}`;
        button.dataset.tag = item.name;
        button.textContent = `#${item.name}`;
        button.title = `${formatCount(item.count)} ${plural(item.count, ["нотатка", "нотатки", "нотаток"])}`;
        elements.tags.append(button);
      });
    }
  }

  function setConnection(status) {
    elements.connectionStatus.classList.toggle("is-offline", status === "offline");
    elements.connectionStatus.classList.toggle("has-error", status === "error");
    elements.connectionStatus.lastChild.textContent = status === "online" ? " синхронізовано" : status === "offline" ? " офлайн" : " помилка зв’язку";
  }

  async function openNote(id, { focusTitle = false } = {}) {
    if (!id) return;
    if (state.current?.id !== id && !await flushSave()) {
      showToast("Не вдалося зберегти поточну нотатку — перехід скасовано", { type: "error" });
      return;
    }
    state.selectedId = id;
    renderNotes();
    elements.shell.classList.add("editor-open");
    elements.noteMenu.hidden = true;

    const sequence = ++state.detailSequence;
    showEditorLoading();
    try {
      const payload = await api.getNote(id);
      if (sequence !== state.detailSequence) return;
      const note = normalizeNote(unwrapNote(payload));
      if (!note.id) note.id = id;
      state.current = note;
      state.selectedId = note.id;
      state.dirty = false;
      state.changeGeneration = 0;
      populateEditor(note);
      updateSummary(note);
      renderNotes();
      if (focusTitle) elements.editorTitle.focus();
    } catch (error) {
      if (sequence !== state.detailSequence) return;
      clearEditor();
      showToast(apiMessage(error, "Не вдалося відкрити нотатку"), { type: "error" });
    }
  }

  function showEditorLoading() {
    elements.editorWelcome.hidden = true;
    elements.editor.hidden = false;
    elements.editorTitle.value = "";
    elements.editorTitle.placeholder = "Завантаження…";
    elements.markdown.value = "";
    setEditorDisabled(true);
    setSaveStatus("saving", "Відкриваємо…");
  }

  function clearEditor() {
    ++state.detailSequence;
    state.current = null;
    state.selectedId = null;
    state.dirty = false;
    state.facetGeneration = 0;
    state.savedFacetGeneration = 0;
    state.savedWordCount = 0;
    elements.editor.hidden = true;
    elements.editorWelcome.hidden = false;
    elements.shell.classList.remove("editor-open");
    document.title = "Shard — нотатки, що пов’язуються";
    renderNotes();
  }

  function populateEditor(note) {
    const deleted = Boolean(note.deletedAt);
    elements.editorWelcome.hidden = true;
    elements.editor.hidden = false;
    elements.editorTitle.placeholder = "Назва нотатки";
    elements.editorTitle.value = note.title;
    elements.markdown.value = note.content || "";
    elements.collectionInput.value = note.collection;
    elements.tagsInput.value = note.tags.join(", ");
    elements.emojiButton.textContent = note.emoji || "✦";
    elements.favoriteButton.setAttribute("aria-pressed", String(note.favorite));
    elements.favoriteButton.setAttribute("aria-label", note.favorite ? "Прибрати з улюблених" : "Додати до улюблених");
    elements.pinButton.setAttribute("aria-pressed", String(note.pinned));
    elements.pinButton.setAttribute("aria-label", note.pinned ? "Відкріпити" : "Закріпити");
    elements.trashBanner.hidden = !deleted;
    elements.deleteMenuItem.hidden = deleted;
    elements.breadcrumbs.innerHTML = `<span>Моє сховище</span><span aria-hidden="true">/</span><strong>${escapeHTML(note.collection || "Без колекції")}</strong>`;
    setEditorDisabled(deleted);
    setSaveStatus("saved", "Збережено");
    updateDocumentMeta();
    elements.updatedTime.textContent = `Змінено ${timeAgo(note.updatedAt)}`;
    state.facetGeneration = 0;
    state.savedFacetGeneration = 0;
    state.savedWordCount = note.wordCount;
    document.title = `${note.title || "Без назви"} — Shard`;
    setEditorMode(deleted ? "preview" : state.editorMode);
  }

  function setEditorDisabled(disabled) {
    [elements.editorTitle, elements.markdown, elements.collectionInput, elements.tagsInput, elements.emojiButton, elements.favoriteButton, elements.pinButton]
      .forEach((control) => { control.disabled = disabled; });
    $$("button", elements.formatToolbar).forEach((button) => { button.disabled = disabled; });
  }

  function updateDocumentMeta() {
    if (!state.current) return;
    const words = countWords(elements.markdown.value);
    const minutes = Math.max(1, Math.ceil(words / 210));
    state.current.wordCount = words;
    elements.documentMeta.textContent = `${formatCount(words)} ${plural(words, ["слово", "слова", "слів"])} · ${minutes} хв читання`;
  }

  function readEditorIntoCurrent() {
    if (!state.current) return;
    state.current.title = elements.editorTitle.value;
    state.current.content = elements.markdown.value;
    state.current.collection = elements.collectionInput.value.trim();
    state.current.tags = [...new Set(elements.tagsInput.value.split(",").map((tag) => tag.trim().replace(/^#/, "")).filter(Boolean))];
    state.current.emoji = elements.emojiButton.textContent === "✦" ? "" : elements.emojiButton.textContent;
    state.current.excerpt = cleanExcerpt({ excerpt: state.current.content.slice(0, 240) });
    state.current.wordCount = countWords(state.current.content);
  }

  function handleEditorInput(event) {
    if (!state.current || state.current.deletedAt) return;
    if (event?.target === elements.collectionInput || event?.target === elements.tagsInput) {
      state.facetGeneration += 1;
    }
    readEditorIntoCurrent();
    state.changeGeneration += 1;
    state.dirty = true;
    setSaveStatus("saving", "Незбережені зміни");
    updateDocumentMeta();
    updateSummary(state.current);
    renderNotes();
    if (state.editorMode === "preview") renderPreview();
    clearTimeout(state.saveTimer);
    state.saveTimer = window.setTimeout(() => saveCurrent(), 700);
  }

  function serializableNote(note) {
    return {
      title: note.title.trim() || "Без назви",
      content: note.content || "",
      tags: note.tags,
      collection: note.collection,
      emoji: note.emoji,
      color: note.color,
      pinned: note.pinned,
      favorite: note.favorite,
    };
  }

  async function saveCurrent() {
    clearTimeout(state.saveTimer);
    state.saveTimer = null;
    if (!state.current || state.current.deletedAt || !state.dirty) return state.current;
    if (state.savePromise) {
      await state.savePromise;
      if (state.dirty) return saveCurrent();
      return state.current;
    }

    readEditorIntoCurrent();
    const target = state.current;
    const targetId = target.id;
    const generation = state.changeGeneration;
    const payload = serializableNote(target);
    const facetGeneration = state.facetGeneration;
    const facetsChanged = facetGeneration !== state.savedFacetGeneration;
    const previousSavedWords = state.savedWordCount;
    setSaveStatus("saving", "Зберігаємо…");

    let savedSuccessfully = false;
    state.savePromise = (async () => {
      try {
        const response = await api.updateNote(targetId, payload);
        savedSuccessfully = true;
        const savedRaw = unwrapNote(response);
        const savedWords = Number.isFinite(Number(savedRaw?.wordCount))
          ? Number(savedRaw.wordCount)
          : countWords(payload.content);
        const wordDelta = savedWords - previousSavedWords;
        if (state.current?.id === targetId) {
          if (savedRaw?.updatedAt || savedRaw?.updated_at) {
            state.current.updatedAt = normalizeDate(savedRaw.updatedAt ?? savedRaw.updated_at) || new Date().toISOString();
          } else {
            state.current.updatedAt = new Date().toISOString();
          }
          state.current.title = payload.title;
          state.savedFacetGeneration = facetGeneration;
          state.savedWordCount = savedWords;
          if (Number.isFinite(Number(state.stats.words))) {
            state.stats.words = Math.max(0, Number(state.stats.words) + wordDelta);
          }
          state.dirty = generation !== state.changeGeneration;
          updateSummary(state.current);
          renderNotes();
          elements.updatedTime.textContent = `Змінено ${timeAgo(state.current.updatedAt)}`;
          setSaveStatus(state.dirty ? "saving" : "saved", state.dirty ? "Є нові зміни" : "Збережено");
          if (facetsChanged) await loadSupportingData();
          else renderNavigation();
        }
        return state.current;
      } catch (error) {
        if (state.current?.id === targetId) {
          state.dirty = true;
          setSaveStatus("error", "Не збережено");
          showToast(apiMessage(error, "Не вдалося зберегти нотатку"), { type: "error", actionLabel: "Повторити", action: () => saveCurrent() });
        }
        throw error;
      } finally {
        state.savePromise = null;
        // Queue a follow-up only when the previous write succeeded and newer
        // keystrokes arrived meanwhile. Validation/network failures wait for an
        // explicit retry or the next edit instead of hammering the server.
        if (savedSuccessfully && state.dirty && state.current?.id === targetId) {
          clearTimeout(state.saveTimer);
          state.saveTimer = window.setTimeout(() => saveCurrent().catch(() => {}), 120);
        }
      }
    })();

    return state.savePromise;
  }

  async function flushSave() {
    clearTimeout(state.saveTimer);
    state.saveTimer = null;
    if (state.savePromise) {
      try { await state.savePromise; } catch (_) { return false; }
    }
    if (state.dirty) {
      try { await saveCurrent(); } catch (_) { return false; }
    }
    return true;
  }

  function updateSummary(note) {
    const index = state.notes.findIndex((item) => item.id === note.id);
    if (index < 0) return;
    const existing = state.notes[index];
    state.notes[index] = {
      ...existing,
      title: note.title || "Без назви",
      excerpt: note.excerpt || cleanExcerpt({ excerpt: note.content?.slice(0, 240) || "" }),
      tags: [...note.tags],
      collection: note.collection,
      emoji: note.emoji,
      color: note.color,
      pinned: note.pinned,
      favorite: note.favorite,
      deletedAt: note.deletedAt,
      updatedAt: note.updatedAt,
      wordCount: note.wordCount,
    };
  }

  function setSaveStatus(kind, text) {
    elements.saveStatus.classList.toggle("is-saving", kind === "saving");
    elements.saveStatus.classList.toggle("has-error", kind === "error");
    elements.saveStatus.lastChild.textContent = ` ${text}`;
  }

  async function createNote(event) {
    event?.preventDefault();
    const title = elements.newName.value.trim();
    if (!title) {
      elements.newError.textContent = "Введіть назву нотатки.";
      elements.newError.hidden = false;
      elements.newName.focus();
      return;
    }

    const submit = $('button[type="submit"]', elements.newForm);
    submit.disabled = true;
    elements.newError.hidden = true;
    try {
      const payload = await api.createNote({
        title,
        content: "",
        tags: [],
        collection: elements.newCollection.value.trim(),
        emoji: "",
        color: "",
        pinned: false,
        favorite: false,
      });
      const created = normalizeNote(unwrapNote(payload));
      elements.newDialog.close();
      elements.newForm.reset();
      state.view = "all";
      state.tag = "";
      state.collection = "";
      state.query = "";
      elements.search.value = "";
      await Promise.all([loadNotes({ keepSelection: false }), loadSupportingData()]);
      await openNote(created.id, { focusTitle: true });
      showToast(`Створено «${created.title}»`);
    } catch (error) {
      elements.newError.textContent = apiMessage(error, "Не вдалося створити нотатку");
      elements.newError.hidden = false;
    } finally {
      submit.disabled = false;
    }
  }

  function openNewDialog({ collection = "" } = {}) {
    closeSidebar();
    if (elements.newDialog.open) return;
    elements.newError.hidden = true;
    elements.newForm.reset();
    elements.newCollection.value = collection;
    elements.newDialog.showModal();
    requestAnimationFrame(() => elements.newName.focus());
  }

  async function toggleProperty(property) {
    if (!state.current || state.current.deletedAt) return;
    state.current[property] = !state.current[property];
    state.changeGeneration += 1;
    state.dirty = true;
    populateActionState();
    updateSummary(state.current);
    renderNotes();
    try {
      await saveCurrent();
      showToast(property === "favorite"
        ? state.current[property] ? "Додано до улюблених" : "Прибрано з улюблених"
        : state.current[property] ? "Нотатку закріплено" : "Нотатку відкріплено");
      loadSupportingData();
    } catch (_) { /* saveCurrent already reports the failure. */ }
  }

  function populateActionState() {
    if (!state.current) return;
    elements.favoriteButton.setAttribute("aria-pressed", String(state.current.favorite));
    elements.favoriteButton.setAttribute("aria-label", state.current.favorite ? "Прибрати з улюблених" : "Додати до улюблених");
    elements.pinButton.setAttribute("aria-pressed", String(state.current.pinned));
    elements.pinButton.setAttribute("aria-label", state.current.pinned ? "Відкріпити" : "Закріпити");
  }

  function askForConfirmation({ title, copy, label, danger = true, action }) {
    state.confirmCallback = action;
    elements.confirmTitle.textContent = title;
    elements.confirmCopy.textContent = copy;
    elements.confirmAction.textContent = label;
    elements.confirmAction.className = danger ? "danger-button" : "primary-button";
    elements.confirmDialog.showModal();
  }

  async function moveCurrentToTrash() {
    if (!state.current) return;
    if (!await flushSave()) {
      showToast("Спочатку потрібно зберегти зміни", { type: "error" });
      return;
    }
    const { id, title } = state.current;
    await api.deleteNote(id, false);
    clearEditor();
    await Promise.all([loadNotes({ keepSelection: false }), loadSupportingData()]);
    showToast(`«${title}» переміщено в кошик`, {
      actionLabel: "Скасувати",
      duration: 7000,
      action: async () => {
        try {
          await api.restoreNote(id);
          await Promise.all([loadNotes({ keepSelection: false }), loadSupportingData()]);
          showToast("Нотатку відновлено");
        } catch (error) {
          showToast(apiMessage(error, "Не вдалося відновити нотатку"), { type: "error" });
        }
      },
    });
  }

  async function restoreCurrent() {
    if (!state.current) return;
    const id = state.current.id;
    try {
      const result = await api.restoreNote(id);
      const restored = normalizeNote(unwrapNote(result));
      state.view = "all";
      state.tag = "";
      state.collection = "";
      await Promise.all([loadNotes({ keepSelection: false }), loadSupportingData()]);
      await openNote(restored.id || id);
      showToast("Нотатку відновлено");
    } catch (error) {
      showToast(apiMessage(error, "Не вдалося відновити нотатку"), { type: "error" });
    }
  }

  async function permanentlyDeleteCurrent() {
    if (!state.current) return;
    const id = state.current.id;
    await api.deleteNote(id, true);
    clearEditor();
    await Promise.all([loadNotes({ keepSelection: false }), loadSupportingData()]);
    showToast("Нотатку остаточно видалено");
  }

  function setView(view) {
    if (!VIEW_META[view]) return;
    state.view = view;
    state.tag = "";
    state.collection = "";
    closeSidebar();
    loadNotes();
    renderNavigation();
  }

  function setCollection(name) {
    state.view = "all";
    state.collection = name;
    state.tag = "";
    closeSidebar();
    loadNotes();
    renderNavigation();
  }

  function setTag(name) {
    state.view = "all";
    state.tag = name;
    state.collection = "";
    closeSidebar();
    loadNotes();
    renderNavigation();
  }

  function clearFilters() {
    state.tag = "";
    state.collection = "";
    state.view = "all";
    loadNotes();
    renderNavigation();
  }

  function openSidebar() {
    elements.shell.classList.add("sidebar-open");
    elements.scrim.hidden = false;
    $("#open-sidebar").setAttribute("aria-expanded", "true");
  }

  function closeSidebar() {
    elements.shell.classList.remove("sidebar-open");
    elements.scrim.hidden = true;
    $("#open-sidebar").setAttribute("aria-expanded", "false");
  }

  function setEditorMode(mode) {
    state.editorMode = mode === "preview" ? "preview" : "edit";
    const previewing = state.editorMode === "preview";
    $("#edit-tab").setAttribute("aria-selected", String(!previewing));
    $("#preview-tab").setAttribute("aria-selected", String(previewing));
    elements.markdown.hidden = previewing;
    elements.preview.hidden = !previewing;
    elements.formatToolbar.hidden = previewing || Boolean(state.current?.deletedAt);
    if (previewing) renderPreview();
  }

  function safeUrl(value) {
    const url = String(value || "").trim();
    if (/^(https?:|mailto:|tel:)/i.test(url) || url.startsWith("/") || url.startsWith("#")) return url;
    return "#";
  }

  function renderInline(source) {
    const tokens = [];
    const token = (html) => {
      const index = tokens.push(html) - 1;
      return `\u0000${index}\u0000`;
    };

    let text = String(source || "")
      .replace(/`([^`\n]+)`/g, (_, code) => token(`<code>${escapeHTML(code)}</code>`))
      .replace(/\[\[([^\]\n]+)\]\]/g, (_, label) => token(`<button class="wiki-link" type="button" data-wiki="${escapeHTML(label.trim())}">${escapeHTML(label.trim())}</button>`))
      .replace(/\[([^\]\n]+)\]\(([^)\n]+)\)/g, (_, label, href) => token(`<a href="${escapeHTML(safeUrl(href))}" target="_blank" rel="noreferrer">${escapeHTML(label)}</a>`));

    text = escapeHTML(text)
      .replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>")
      .replace(/__([^_\n]+)__/g, "<strong>$1</strong>")
      .replace(/~~([^~\n]+)~~/g, "<del>$1</del>")
      .replace(/(^|[\s(])\*([^*\n]+)\*(?=$|[\s).,!?:;])/g, "$1<em>$2</em>")
      .replace(/(^|[\s(])_([^_\n]+)_(?=$|[\s).,!?:;])/g, "$1<em>$2</em>");

    return text.replace(/\u0000(\d+)\u0000/g, (_, index) => tokens[Number(index)] || "");
  }

  function markdownToHTML(markdown) {
    const source = String(markdown || "").replace(/\r\n?/g, "\n");
    if (!source.trim()) return '<p class="empty-preview">У нотатці ще немає тексту.</p>';
    const lines = source.split("\n");
    const html = [];
    let paragraph = [];
    let listType = null;
    let inCode = false;
    let codeLanguage = "";
    let codeLines = [];

    const flushParagraph = () => {
      if (!paragraph.length) return;
      html.push(`<p>${paragraph.map(renderInline).join("<br>")}</p>`);
      paragraph = [];
    };
    const closeList = () => {
      if (!listType) return;
      html.push(`</${listType}>`);
      listType = null;
    };

    lines.forEach((line) => {
      const fence = line.match(/^```\s*([\w+-]*)\s*$/);
      if (fence) {
        if (!inCode) {
          flushParagraph();
          closeList();
          inCode = true;
          codeLanguage = fence[1];
          codeLines = [];
        } else {
          html.push(`<pre${codeLanguage ? ` data-language="${escapeHTML(codeLanguage)}"` : ""}><code>${escapeHTML(codeLines.join("\n"))}</code></pre>`);
          inCode = false;
          codeLanguage = "";
        }
        return;
      }

      if (inCode) {
        codeLines.push(line);
        return;
      }

      if (!line.trim()) {
        flushParagraph();
        closeList();
        return;
      }

      const heading = line.match(/^(#{1,3})\s+(.+)$/);
      if (heading) {
        flushParagraph();
        closeList();
        const level = heading[1].length;
        html.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
        return;
      }

      if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
        flushParagraph();
        closeList();
        html.push("<hr>");
        return;
      }

      const quote = line.match(/^>\s?(.*)$/);
      if (quote) {
        flushParagraph();
        closeList();
        html.push(`<blockquote>${renderInline(quote[1])}</blockquote>`);
        return;
      }

      const task = line.match(/^\s*[-*+]\s+\[([ xX])\]\s+(.+)$/);
      if (task) {
        flushParagraph();
        if (listType !== "ul") {
          closeList();
          html.push("<ul>");
          listType = "ul";
        }
        html.push(`<li class="task-item"><input type="checkbox" disabled${task[1].toLowerCase() === "x" ? " checked" : ""}>${renderInline(task[2])}</li>`);
        return;
      }

      const unordered = line.match(/^\s*[-*+]\s+(.+)$/);
      if (unordered) {
        flushParagraph();
        if (listType !== "ul") {
          closeList();
          html.push("<ul>");
          listType = "ul";
        }
        html.push(`<li>${renderInline(unordered[1])}</li>`);
        return;
      }

      const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
      if (ordered) {
        flushParagraph();
        if (listType !== "ol") {
          closeList();
          html.push("<ol>");
          listType = "ol";
        }
        html.push(`<li>${renderInline(ordered[1])}</li>`);
        return;
      }

      closeList();
      paragraph.push(line);
    });

    if (inCode) html.push(`<pre><code>${escapeHTML(codeLines.join("\n"))}</code></pre>`);
    flushParagraph();
    closeList();
    return html.join("\n");
  }

  function renderPreview() {
    elements.preview.innerHTML = markdownToHTML(elements.markdown.value);
  }

  function applyFormat(format) {
    if (!state.current || state.current.deletedAt) return;
    const editor = elements.markdown;
    const start = editor.selectionStart;
    const end = editor.selectionEnd;
    const selected = editor.value.slice(start, end);
    let replacement = selected;
    let selectStart = start;
    let selectEnd = end;

    const wrap = (before, after = before, placeholder = "текст") => {
      const content = selected || placeholder;
      replacement = `${before}${content}${after}`;
      selectStart = start + before.length;
      selectEnd = selectStart + content.length;
    };
    const prefixLines = (prefix) => {
      const lineStart = editor.value.lastIndexOf("\n", start - 1) + 1;
      const blockEndIndex = editor.value.indexOf("\n", end);
      const lineEnd = blockEndIndex === -1 ? editor.value.length : blockEndIndex;
      const block = editor.value.slice(lineStart, lineEnd) || "текст";
      replacement = block.split("\n").map((line) => `${prefix}${line}`).join("\n");
      editor.setRangeText(replacement, lineStart, lineEnd, "select");
      handleEditorInput();
      return false;
    };

    if (format === "bold") wrap("**");
    else if (format === "italic") wrap("*");
    else if (format === "code") selected.includes("\n") ? wrap("```\n", "\n```", "код") : wrap("`");
    else if (format === "link") wrap("[", "](https://)", "назва посилання");
    else if (format === "wiki") wrap("[[", "]]", "назва нотатки");
    else if (format === "heading" && prefixLines("## ") === false) return;
    else if (format === "bullet" && prefixLines("- ") === false) return;
    else if (format === "check" && prefixLines("- [ ] ") === false) return;
    else if (format === "quote" && prefixLines("> ") === false) return;
    else return;

    editor.setRangeText(replacement, start, end, "end");
    editor.setSelectionRange(selectStart, selectEnd);
    editor.focus();
    handleEditorInput();
  }

  async function handleWikiLink(event) {
    const button = event.target.closest("[data-wiki]");
    if (!button) return;
    const label = button.dataset.wiki.trim();
    const key = label.toLocaleLowerCase("uk-UA");
    let match = state.notes.find((note) => note.title.toLocaleLowerCase("uk-UA") === key);
    if (!match) {
      try {
        const payload = await api.list({ view: "all", q: label });
        const candidates = Array.isArray(payload) ? payload : payload?.notes || payload?.data?.notes || [];
        match = candidates.map(normalizeNote).find((note) => note.title.toLocaleLowerCase("uk-UA") === key);
      } catch (error) {
        showToast(apiMessage(error, "Не вдалося перевірити вікі-посилання"), { type: "error" });
        return;
      }
    }
    if (match) {
      state.view = "all";
      state.tag = "";
      state.collection = "";
      await loadNotes();
      await openNote(match.id);
    } else showToast(`Нотатку «${label}» ще не створено`, {
      actionLabel: "Створити",
      action: () => {
        openNewDialog({ collection: state.current?.collection || "" });
        elements.newName.value = label;
      },
    });
  }

  function showToast(message, { type = "success", duration = 3600, actionLabel = "", action = null } = {}) {
    const toast = document.createElement("div");
    toast.className = `toast${type === "error" ? " is-error" : ""}`;
    toast.setAttribute("role", type === "error" ? "alert" : "status");
    const text = document.createElement("span");
    text.textContent = message;
    toast.append(text);
    if (actionLabel && action) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = actionLabel;
      button.addEventListener("click", async () => {
        toast.remove();
        try { await action(); } catch (error) { showToast(apiMessage(error, "Дію не виконано"), { type: "error" }); }
      });
      toast.append(button);
    }
    elements.toastRegion.append(toast);
    window.setTimeout(() => toast.remove(), duration);
  }

  function copyMarkdown() {
    if (!state.current) return;
    const text = elements.markdown.value;
    navigator.clipboard?.writeText(text).then(
      () => showToast("Markdown скопійовано"),
      () => {
        elements.markdown.select();
        document.execCommand("copy");
        showToast("Markdown скопійовано");
      },
    );
    closeNoteMenu();
  }

  function downloadMarkdown() {
    if (!state.current) return;
    const blob = new Blob([elements.markdown.value], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${slugify(state.current.title) || "note"}.md`;
    link.click();
    URL.revokeObjectURL(url);
    closeNoteMenu();
    showToast("Файл підготовлено до завантаження");
  }

  function slugify(value) {
    return String(value || "")
      .toLocaleLowerCase("uk-UA")
      .normalize("NFKD")
      .replace(/[^\p{L}\p{N}]+/gu, "-")
      .replace(/^-|-$/g, "")
      .slice(0, 80);
  }

  function closeNoteMenu() {
    elements.noteMenu.hidden = true;
    elements.noteMenuButton.setAttribute("aria-expanded", "false");
  }

  function applyTheme(theme) {
    state.theme = theme;
    localStorage.setItem("shard-theme", theme);
    document.documentElement.removeAttribute("data-theme");
    if (theme !== "system") document.documentElement.dataset.theme = theme;
    const labels = {
      system: ["◐", "Системна тема"],
      light: ["☼", "Світла тема"],
      dark: ["☾", "Темна тема"],
    };
    elements.themeIcon.textContent = labels[theme][0];
    elements.themeLabel.textContent = labels[theme][1];
    elements.themeMeta.content = theme === "dark" || (theme === "system" && matchMedia("(prefers-color-scheme: dark)").matches) ? "#191918" : "#f7f7f5";
  }

  function cycleTheme() {
    const themes = ["system", "light", "dark"];
    applyTheme(themes[(themes.indexOf(state.theme) + 1) % themes.length]);
  }

  function bindEvents() {
    $$("[data-create-note]").forEach((button) => button.addEventListener("click", () => openNewDialog()));
    elements.newForm.addEventListener("submit", createNote);
    $$('[data-close-dialog]').forEach((button) => button.addEventListener("click", () => button.closest("dialog")?.close()));

    elements.confirmForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const action = state.confirmCallback;
      if (!action) return elements.confirmDialog.close();
      elements.confirmAction.disabled = true;
      try {
        await action();
        elements.confirmDialog.close();
      } catch (error) {
        showToast(apiMessage(error, "Не вдалося виконати дію"), { type: "error" });
      } finally {
        elements.confirmAction.disabled = false;
        state.confirmCallback = null;
      }
    });

    $$("[data-view]").forEach((button) => button.addEventListener("click", () => setView(button.dataset.view)));
    $$('[data-nav-view]').forEach((link) => link.addEventListener("click", (event) => { event.preventDefault(); setView(link.dataset.navView); }));
    elements.collections.addEventListener("click", (event) => {
      const button = event.target.closest("[data-collection]");
      if (button) setCollection(button.dataset.collection);
    });
    elements.tags.addEventListener("click", (event) => {
      const button = event.target.closest("[data-tag]");
      if (button) setTag(button.dataset.tag);
    });
    $("#clear-filter").addEventListener("click", clearFilters);

    elements.list.addEventListener("click", (event) => {
      const card = event.target.closest("[data-id]");
      if (card) openNote(card.dataset.id);
    });
    elements.list.addEventListener("keydown", (event) => {
      if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      const cards = $$(".note-card", elements.list);
      if (!cards.length) return;
      event.preventDefault();
      const currentIndex = cards.findIndex((card) => card === document.activeElement || card.dataset.id === state.selectedId);
      const nextIndex = event.key === "Home" ? 0 : event.key === "End" ? cards.length - 1 : event.key === "ArrowDown" ? Math.min(cards.length - 1, currentIndex + 1) : Math.max(0, currentIndex - 1);
      cards[nextIndex].focus();
      openNote(cards[nextIndex].dataset.id);
    });

    let searchTimer;
    elements.search.addEventListener("input", () => {
      clearTimeout(searchTimer);
      state.query = elements.search.value.trim();
      searchTimer = window.setTimeout(() => loadNotes(), 240);
    });
    elements.search.addEventListener("search", () => {
      state.query = elements.search.value.trim();
      loadNotes();
    });
    elements.sort.value = state.sort;
    elements.sort.addEventListener("change", () => {
      state.sort = elements.sort.value;
      localStorage.setItem("shard-sort", state.sort);
      renderNotes();
    });

    [elements.editorTitle, elements.markdown, elements.collectionInput, elements.tagsInput].forEach((input) => input.addEventListener("input", handleEditorInput));
    elements.collectionInput.addEventListener("change", handleEditorInput);
    elements.tagsInput.addEventListener("change", handleEditorInput);
    elements.emojiButton.addEventListener("click", () => {
      if (!state.current) return;
      const current = state.current.emoji || "✦";
      const next = EMOJIS[(EMOJIS.indexOf(current) + 1) % EMOJIS.length];
      elements.emojiButton.textContent = next;
      handleEditorInput();
    });

    elements.favoriteButton.addEventListener("click", () => toggleProperty("favorite"));
    elements.pinButton.addEventListener("click", () => toggleProperty("pinned"));
    elements.noteMenuButton.addEventListener("click", () => {
      elements.noteMenu.hidden = !elements.noteMenu.hidden;
      elements.noteMenuButton.setAttribute("aria-expanded", String(!elements.noteMenu.hidden));
      if (!elements.noteMenu.hidden) $("button", elements.noteMenu)?.focus();
    });
    $("#copy-markdown").addEventListener("click", copyMarkdown);
    $("#download-markdown").addEventListener("click", downloadMarkdown);
    elements.deleteMenuItem.addEventListener("click", () => {
      closeNoteMenu();
      askForConfirmation({
        title: "Перемістити нотатку в кошик?",
        copy: "Її можна буде відновити пізніше.",
        label: "Перемістити",
        action: moveCurrentToTrash,
      });
    });
    $("#restore-note").addEventListener("click", restoreCurrent);
    $("#delete-forever").addEventListener("click", () => askForConfirmation({
      title: "Видалити нотатку назавжди?",
      copy: "Цю дію неможливо скасувати.",
      label: "Видалити назавжди",
      action: permanentlyDeleteCurrent,
    }));

    $$("[data-mode]").forEach((button) => button.addEventListener("click", () => setEditorMode(button.dataset.mode)));
    elements.formatToolbar.addEventListener("click", (event) => {
      const button = event.target.closest("[data-format]");
      if (button) applyFormat(button.dataset.format);
    });
    elements.markdown.addEventListener("keydown", (event) => {
      if (event.key === "Tab") {
        event.preventDefault();
        elements.markdown.setRangeText("  ", elements.markdown.selectionStart, elements.markdown.selectionEnd, "end");
        handleEditorInput();
      }
      if ((event.metaKey || event.ctrlKey) && ["b", "i"].includes(event.key.toLowerCase())) {
        event.preventDefault();
        applyFormat(event.key.toLowerCase() === "b" ? "bold" : "italic");
      }
    });
    elements.preview.addEventListener("click", handleWikiLink);

    $("#open-sidebar").addEventListener("click", openSidebar);
    $("#close-sidebar").addEventListener("click", closeSidebar);
    elements.scrim.addEventListener("click", closeSidebar);
    $("#editor-back").addEventListener("click", async () => {
      if (await flushSave()) elements.shell.classList.remove("editor-open");
      else showToast("Зміни не збережено — редактор залишився відкритим", { type: "error" });
    });
    $("#mobile-search").addEventListener("click", () => { elements.search.focus(); elements.search.select(); });
    $("#welcome-search").addEventListener("click", () => { elements.search.focus(); elements.search.select(); });
    $("#focus-tags").addEventListener("click", () => {
      if (state.current && !state.current.deletedAt) {
        closeSidebar();
        elements.collectionInput.focus();
      } else {
        openNewDialog();
        requestAnimationFrame(() => elements.newCollection.focus());
      }
    });
    elements.empty.addEventListener("click", (event) => {
      const action = event.target.closest("button")?.dataset.action;
      if (action === "retry") loadNotes();
      else if (action === "all") setView("all");
      else if (action === "create") openNewDialog({ collection: state.collection });
    });

    elements.themeToggle.addEventListener("click", cycleTheme);
    matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", () => {
      if (state.theme === "system") applyTheme("system");
    });

    document.addEventListener("click", (event) => {
      if (!elements.noteMenu.hidden && !event.target.closest("#note-menu") && !event.target.closest("#note-menu-button")) closeNoteMenu();
    });
    document.addEventListener("keydown", (event) => {
      const command = event.metaKey || event.ctrlKey;
      if (command && event.key.toLowerCase() === "k") {
        event.preventDefault();
        elements.shell.classList.remove("editor-open");
        elements.search.focus();
        elements.search.select();
      }
      if (command && event.key.toLowerCase() === "n") {
        event.preventDefault();
        openNewDialog({ collection: state.collection });
      }
      if (command && event.key.toLowerCase() === "s") {
        event.preventDefault();
        saveCurrent();
      }
      if (event.key === "Escape") {
        closeNoteMenu();
        closeSidebar();
      }
    });

    window.addEventListener("online", () => { setConnection("online"); if (state.loadError) loadNotes(); });
    window.addEventListener("offline", () => setConnection("offline"));
    const persistBeforeLeaving = () => {
      if (state.dirty && state.current) {
        const body = JSON.stringify(serializableNote(state.current));
        void fetch(`/api/notes/${encodeURIComponent(state.current.id)}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body,
          keepalive: true,
        }).catch(() => {});
      }
    };
    window.addEventListener("pagehide", persistBeforeLeaving);
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") persistBeforeLeaving();
    });
  }

  async function init() {
    applyTheme(state.theme);
    bindEvents();
    setConnection(navigator.onLine ? "online" : "offline");
    await Promise.all([loadNotes(), loadSupportingData()]);
  }

  init();
})();
