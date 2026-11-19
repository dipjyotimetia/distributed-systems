FROM golang:1.27.4-alpine AS builder

WORKDIR /app

COPY go.mod go.sum* ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /fraud-service ./cmd/server

# ---- runtime ----
FROM alpine:3.24.4

RUN addgroup -g 10001 -S fraud && adduser -u 10001 -S fraud -G fraud

COPY --from=builder /fraud-service /usr/local/bin/fraud-service

USER fraud

EXPOSE 8080 9091

ENTRYPOINT ["fraud-service"]
