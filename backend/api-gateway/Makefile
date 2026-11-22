# API Gateway Makefile
SERVICE_NAME := api-gateway
SERVICE_PORT := 8080

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "Available targets for $(SERVICE_NAME):"
	@echo "  build                 - Build the service"
	@echo "  run                   - Run the service locally"
	@echo "  test                  - Run unit tests"
	@echo "  replay-gate           - Run WireMock offline replay (status + schema + properties)"
	@echo "  replay-lint-invariants - Validate the business-invariants rules file"
	@echo "  clean                 - Clean build artifacts"

.PHONY: build
build:
	@echo "Building $(SERVICE_NAME)..."
	../../gradlew -p ../.. :api-gateway:clean :api-gateway:bootJar

.PHONY: run
run:
	@echo "Running $(SERVICE_NAME) on port $(SERVICE_PORT)..."
	java -jar build/libs/application.jar

.PHONY: test
test:
	@echo "Running tests for $(SERVICE_NAME)..."
	../../gradlew -p ../.. :api-gateway:test

.PHONY: replay-gate
replay-gate:
	../../scripts/replay/replay-service.sh api-gateway

.PHONY: replay-lint-invariants
replay-lint-invariants:
	python3 ../../scripts/replay/property_validate.py --rules ../../tests/replay/services/api-gateway/invariants.json --lint

.PHONY: clean
clean:
	../../gradlew -p ../.. :api-gateway:clean
