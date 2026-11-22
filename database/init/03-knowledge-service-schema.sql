-- Knowledge service schema (also bootstrapped on existing volumes via knowledge-schema job)
CREATE SCHEMA IF NOT EXISTS knowledge_service;

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'knowledge_service_user') THEN
    CREATE USER knowledge_service_user WITH PASSWORD 'knowledge_service_pass';
  END IF;
END
$$;

GRANT ALL PRIVILEGES ON SCHEMA knowledge_service TO knowledge_service_user;
GRANT USAGE ON SCHEMA public TO knowledge_service_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA knowledge_service GRANT ALL ON TABLES TO knowledge_service_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA knowledge_service GRANT ALL ON SEQUENCES TO knowledge_service_user;
