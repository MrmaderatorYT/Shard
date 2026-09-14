# Shard Web

Оптимізована вебверсія Shard: один Go-бінарник містить REST API та адаптивний
клієнт без npm, CDN і зовнішньої бази даних.

## Запуск

Потрібен Go 1.22 або новіший.

```bash
cd web
go run .
```

Відкрийте `http://localhost:8080`. Під час першого запуску сервер створить
`data/notes.json` і додасть три демонстраційні нотатки.

Для production-збірки:

```bash
go build -trimpath -ldflags="-s -w" -o shard-web .
./shard-web
```

## Налаштування

| Змінна / прапорець | Типове значення | Призначення |
| --- | --- | --- |
| `SHARD_ADDR` / `-addr` | `:8080` | Адреса HTTP-сервера |
| `SHARD_DATA_FILE` / `-data` | `data/notes.json` | Файл сховища |
| `SHARD_AUTH_USER` | порожньо | Необов’язковий логін Basic Auth |
| `SHARD_AUTH_PASSWORD` | порожньо | Необов’язковий пароль Basic Auth |

Логін і пароль задаються лише разом. Для доступу через інтернет ставте сервер
за HTTPS reverse proxy; Basic Auth не шифрує трафік самостійно.

## Дані та API

Записи зберігаються атомарно через тимчасовий файл і `rename`, права файлу —
`0600`. Для резервної копії достатньо скопіювати `notes.json`.

Основні маршрути:

- `GET /api/notes` — компактні картки без повного Markdown-тексту;
- `GET /api/notes/{id}` — повна нотатка;
- `POST /api/notes`, `PUT /api/notes/{id}` — створення й автозбереження;
- `DELETE /api/notes/{id}` — переміщення в кошик;
- `POST /api/notes/{id}/restore` — відновлення;
- `DELETE /api/notes/{id}?permanent=true` — остаточне видалення лише з кошика;
- `GET /api/stats`, `/api/collections`, `/api/tags`, `/healthz`.

## Перевірка

```bash
go test ./...
go test -race ./...
go vet ./...
```

