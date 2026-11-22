# Troubleshooting Guide

This guide helps you debug common issues in the Banking Microservices Application.

## Quick Diagnostics

### Health Checks
Check if all services are running:
```bash
# Published host ports (default Compose)
curl http://localhost:8080/actuator/health  # API Gateway
curl http://localhost:8082/actuator/health  # Accounts Service
curl http://localhost:8083/actuator/health  # Transactions Service
curl http://localhost:3000/api/v1/healthz   # Frontend

# Host port 8081 is mapped by both user-service (8081:8080) and Redpanda
# Schema Registry (8081:8081). Prefer in-container or Compose-network checks:
docker compose exec user-service curl -fsS http://localhost:8080/actuator/health
# Schema Registry: http://redpanda:8081 on banking-network
# KYC gRPC :8091, cards gRPC :8092, fraud gRPC :8086
```

### Database Connection
Test database connectivity:
```bash
# From host machine
psql -h localhost -p 5432 -U postgres -d banking_app

# Check individual service databases
psql -h localhost -p 5432 -U user_service_user -d banking_app
psql -h localhost -p 5432 -U accounts_service_user -d banking_app
psql -h localhost -p 5432 -U transactions_service_user -d banking_app
```

### Service Logs
View logs for debugging:
```bash
# Docker Compose logs
docker compose logs user-service
docker compose logs accounts-service
docker compose logs transactions-service
docker compose logs api-gateway
docker compose logs frontend

# Follow logs in real-time
docker compose logs -f user-service
```

## Common Issues

### 1. Service Won't Start

**Symptom**: Service fails to start with port binding errors
**Solution**:
```bash
# Check if port is already in use
lsof -i :8080  # Replace with actual port
kill -9 <PID> # Kill the process using the port

# Or free the conflicting host port / remap in docker-compose.yml.
# Do not reuse 8091 for user-service — KYC owns host port 8091.
docker compose up user-service
```

### 2. Database Connection Issues

**Symptom**: `Failed to obtain JDBC Connection`
**Root Causes & Solutions**:

- **Database not running**:
  ```bash
  docker compose up -d postgres
  docker compose logs postgres
  ```

- **Wrong credentials**:
  Check environment variables in `docker-compose.yml`

- **Network issues**:
  ```bash
  docker network ls
  docker network inspect distributed-systems_banking-network
  # Project prefix may vary; confirm with: docker compose ps
  ```

### 3. JWT Authentication Problems

**Symptom**: 401 Unauthorized responses
**Debug Steps**:
```bash
# Check JWT token in browser developer tools
# Look for Authorization header in requests

# Verify JWT configuration
grep -r "jwt" backend/*/src/main/resources/application.yml

# Check if user-service is running (it generates tokens).
# Avoid host :8081 (Schema Registry clash); use Compose exec:
docker compose exec user-service curl -fsS http://localhost:8080/actuator/health
```

### 4. Frontend API Connection Issues

**Symptom**: Frontend can't reach backend APIs
**Debug Steps**:
```bash
# Check if API Gateway is accessible
curl http://localhost:8080/actuator/health

# Verify frontend API configuration
cat frontend/.env.local

# Check browser network tab for failed requests
# Look for CORS errors in browser console
```

### 5. Build Failures

**Symptom**: Gradle build fails
**Solutions**:
```bash
# Clean and rebuild
./gradlew clean bootJar

# Check Java version
java -version  # Should be Java 25

# Refresh dependency metadata
./gradlew --refresh-dependencies bootJar

# For specific service
./gradlew :accounts-service:clean :accounts-service:bootJar
```

### 6. OpenTelemetry Issues

**Symptom**: No traces appearing in Jaeger
**Debug Steps**:
```bash
# Check if Jaeger is running
curl http://localhost:16686

# Check the Jaeger container in Compose
docker compose logs --tail=50 jaeger

# Check service environment variables
docker compose exec user-service env | grep OTEL

# Enable debug logging
export OTEL_LOG_LEVEL=DEBUG
```

### Gateway exits during OpenTelemetry startup

If `docker compose logs api-gateway` shows
`NoClassDefFoundError: io/opentelemetry/semconv/ContainerAttributes`, the
gateway failed while loading its OpenTelemetry container resource provider.
The gateway's Gradle dependencies are in
[`backend/api-gateway/build.gradle.kts`](backend/api-gateway/build.gradle.kts).

For local development, save this as `compose.gateway-workaround.yml`:

```yaml
services:
  api-gateway:
    environment:
      OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS: io.opentelemetry.instrumentation.resources.ContainerResourceProvider
```

From the repository root, recreate the gateway with that override:

```bash
docker compose -f docker-compose.yml -f compose.gateway-workaround.yml up -d --no-deps api-gateway
curl -fsS http://localhost:8080/actuator/health
```

The override disables container resource metadata collection on the local
gateway.

## CI Replay Validation

Run `make replay-validation` from the repository root to reproduce the user-service CI check (Postgres on `:5432` plus Docker for WireMock). Run `make replay-gate` for the api-gateway WireMock suite. Failure logs are written under `backend/<service>/replay-results/`.

Checked-in suites live under `tests/replay/services/`. Use a separate working directory for optional traffic captures and internal demos.

## Performance Issues

### Slow Database Queries
```bash
# Enable PostgreSQL query logging
# In postgres container:
echo "log_statement = 'all'" >> /var/lib/postgresql/19/docker/postgresql.conf
echo "log_duration = on" >> /var/lib/postgresql/19/docker/postgresql.conf

# Restart postgres container
docker compose restart postgres

# View query logs
docker compose logs postgres | grep "duration:"
```

### Memory Issues
```bash
# Check container memory usage
docker stats

# Increase JVM heap size
export JAVA_OPTS="-Xmx1g -Xms512m"
docker compose up user-service
```

### Network Latency
```bash
# Test service-to-service communication
docker compose exec user-service curl http://accounts-service:8080/actuator/health
docker compose exec accounts-service curl http://transactions-service:8080/actuator/health
```

## Development Environment

### Running Without Docker

1. **Start only PostgreSQL in Docker**:
   ```bash
   docker compose up -d postgres
   ```

2. **Run services locally**:
   ```bash
   # Terminal 1: User Service
   docker compose up user-service

   # Terminal 2: Accounts Service  
   export DB_HOST=localhost
   export DB_PORT=5432
   # Prefer Compose network DNS; host :8081 collides with Schema Registry
   export USER_SERVICE_URL=http://user-service:8080
   ./gradlew :accounts-service:bootRun
   ```

3. **Debug with IDE**:
   - Set breakpoints in your IDE
   - Configure run configuration with environment variables
   - Start services in debug mode

### Hot Reload Development
```bash
# For a Java backend service
./gradlew :accounts-service:bootRun --debug-jvm

# For frontend (Next.js)
cd frontend
npm run dev  # Automatically reloads on file changes
```

## Configuration Issues

### Environment Variables
```bash
# Check current environment
docker compose config --services

# Override for debugging
echo "LOGGING_LEVEL_ROOT=DEBUG" >> .env
docker compose up user-service
```

### Database Migrations
```bash
# Check migration status
./gradlew :accounts-service:flywayInfo

# Run migrations manually
./gradlew :accounts-service:flywayMigrate

```

## Monitoring and Logs

### Centralized Logging
```bash
# View all service logs together
docker compose logs -f --tail=100

# Filter for errors
docker compose logs | grep ERROR

# Filter by service
docker compose logs user-service accounts-service
```

### Metrics Access
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3001
- **Jaeger**: http://localhost:16686

## Testing

### Integration Tests
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew :accounts-service:test --tests "*AccountsServiceApplicationTests"

# Run with profile
./gradlew test -Dspring.profiles.active=test
```

### API Testing
```bash
# Use httpie for API testing
http POST localhost:8080/api/v1/users/register username=test email=test@test.com password=pass123

# Use curl with proper headers
curl -X POST http://localhost:8080/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","email":"test@test.com","password":"pass123"}'
```

## Getting Help

### Log Analysis Commands
```bash
# Find errors in the last hour
docker compose logs --since=1h | grep -i error

# Count error occurrences
docker compose logs | grep -c ERROR

# Find specific error patterns
docker compose logs | grep -E "(OutOfMemory|Connection.*failed|Timeout)"
```

### Useful Debug Information
When reporting issues, include:
```bash
# System information
docker --version
docker compose --version
java -version
node --version
npm --version

# Service status
docker compose ps
docker stats --no-stream

# Recent logs
docker compose logs --tail=50 user-service
```

## Rebuild the local stack

```bash
docker compose down
docker compose up -d --build
```

Compose retains named database volumes during this rebuild.
