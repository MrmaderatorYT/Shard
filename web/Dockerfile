FROM golang:1.26-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY *.go ./
COPY static ./static
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/shard-web . \
    && mkdir -p /out/data \
    && chown -R 65532:65532 /out/data

FROM scratch
WORKDIR /app
COPY --from=build /out/shard-web /shard-web
COPY --from=build --chown=65532:65532 /out/data /data
USER 65532:65532
EXPOSE 8080
VOLUME ["/data"]
ENV SHARD_ADDR=:8080
ENV SHARD_DATA_FILE=/data/notes.json
ENTRYPOINT ["/shard-web"]
