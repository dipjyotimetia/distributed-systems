FROM golang:1.27.4-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -trimpath -o /kyc-service ./cmd/kyc

FROM alpine:3.24.4
RUN apk add --no-cache ca-certificates
COPY --from=build /kyc-service /usr/local/bin/kyc-service
USER 65532:65532
EXPOSE 8091 9092
ENTRYPOINT ["/usr/local/bin/kyc-service"]
