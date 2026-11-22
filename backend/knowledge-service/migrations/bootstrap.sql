-- Run once per local Compose startup so existing PostgreSQL volumes also get
-- the knowledge schema. The knowledge role owns only that schema.
\set ON_ERROR_STOP on
SELECT format('CREATE ROLE knowledge_service_user LOGIN PASSWORD %L', :'knowledge_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'knowledge_service_user') \gexec

CREATE SCHEMA IF NOT EXISTS knowledge_service AUTHORIZATION knowledge_service_user;
GRANT ALL PRIVILEGES ON SCHEMA knowledge_service TO knowledge_service_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA knowledge_service GRANT ALL ON TABLES TO knowledge_service_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA knowledge_service GRANT ALL ON SEQUENCES TO knowledge_service_user;
