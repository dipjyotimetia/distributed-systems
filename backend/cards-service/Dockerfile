# Build context is backend/ so the local fraud and KYC modules can be replaced in.
FROM golang:1.27.4-alpine AS build
WORKDIR /src
COPY fraud-service /src/fraud-service
COPY kyc-service /src/kyc-service
COPY cards-service /src/cards-service
WORKDIR /src/cards-service
RUN go mod download
RUN CGO_ENABLED=0 go build -trimpath -o /cards-service ./cmd/cards

FROM alpine:3.24.4
RUN apk add --no-cache ca-certificates
COPY --from=build /cards-service /usr/local/bin/cards-service
USER 65532:65532
EXPOSE 8092 9093
ENTRYPOINT ["/usr/local/bin/cards-service"]
