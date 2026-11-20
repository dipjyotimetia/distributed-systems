# Transactions Service Makefile
# Service configuration
SERVICE_NAME := transactions-service
SERVICE_PORT := 8083

# Default target
.DEFAULT_GOAL := help

# Help target
.PHONY: help
help:
	@echo "Available targets for $(SERVICE_NAME):"
	@echo "  build                 - Build the service"
	@echo "  run                   - Run the service locally"
	@echo "  test                  - Run tests"
	@echo "  clean                 - Clean build artifacts"

# Build target
.PHONY: build
build:
	@echo "Building $(SERVICE_NAME)..."
	../../gradlew -p ../.. :transactions-service:clean :transactions-service:bootJar

# Run target
.PHONY: run
run:
	@echo "Running $(SERVICE_NAME) on port $(SERVICE_PORT)..."
	java -jar build/libs/application.jar

# Test target
.PHONY: test
test:
	@echo "Running tests for $(SERVICE_NAME)..."
	../../gradlew -p ../.. :transactions-service:test

# Clean target
.PHONY: clean
clean:
	@echo "Cleaning $(SERVICE_NAME)..."
	../../gradlew -p ../.. :transactions-service:clean
