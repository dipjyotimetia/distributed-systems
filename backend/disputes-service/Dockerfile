FROM golang:1.27.4-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -trimpath -o /disputes-service ./cmd/disputes

FROM alpine:3.24.4
RUN apk add --no-cache ca-certificates
COPY --from=build /disputes-service /usr/local/bin/disputes-service
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/disputes-service"]
