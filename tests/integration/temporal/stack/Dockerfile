FROM golang:1.27.4 AS build
WORKDIR /src
COPY tests/integration/temporal/cmd/provider-fixtures/ ./
RUN --mount=type=tmpfs,target=/tmp --mount=type=tmpfs,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -ldflags="-s -w" -o /provider-fixtures .
FROM scratch
COPY --from=build /provider-fixtures /provider-fixtures
ENTRYPOINT ["/provider-fixtures"]
