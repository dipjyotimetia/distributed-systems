-- Run once per local Compose startup so existing PostgreSQL volumes also get
-- the disputes schema. The disputes role owns only that schema.
\set ON_ERROR_STOP on
SELECT format('CREATE ROLE disputes_service_user LOGIN PASSWORD %L', :'disputes_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'disputes_service_user') \gexec

CREATE SCHEMA IF NOT EXISTS disputes AUTHORIZATION disputes_service_user;
SET ROLE disputes_service_user;
\ir schema.sql
