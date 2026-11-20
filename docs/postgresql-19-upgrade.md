# Upgrade an existing PostgreSQL 18 installation to 19

New installations initialize PostgreSQL 19 on new storage. Existing Compose
volumes and Kubernetes claims containing PostgreSQL 18 data must be retained
until the restored installation is verified.

1. Record the old deployment revision, storage names, connection settings, and
   database list. Stop all application writers, including Temporal, the
   disputes and statement workers, and the payments and clearing workers.
   Keep PostgreSQL 18 available for backup.
2. From a secured administrative client, back up every PostgreSQL instance
   separately. Supply connection settings through the environment or a
   protected libpq service file. Keep the dumps in restricted storage because
   they contain application data and may contain authentication material.

   ```sh
   umask 077
   pg_dumpall --globals-only > globals.sql
   pg_dump --format=custom --create --dbname=banking_app --file=banking_app.dump
   ```

   Repeat the database dump for every application database, including
   `temporal`, `temporal_visibility`, `payments`, and `clearing` where present.
   Back up any other databases reported by the source instance.
3. Start a fresh PostgreSQL 19 instance with its own storage and no application
   initialization scripts. Use a temporary bootstrap administrator whose name
   does not appear in the source roles. Restore roles before databases and
   stop on errors. Run these commands using the PostgreSQL 19 client with its
   connection settings pointed at the destination.

   ```sh
   psql -X --set=ON_ERROR_STOP=1 --dbname=postgres --file=globals.sql
   pg_restore --exit-on-error --create --dbname=postgres banking_app.dump
   ```

   Repeat for each database. If a source application uses the maintenance
   database itself, restore it into an empty database of the same name without
   passing `--create`. Verify that required extensions are present before
   restoring. After connecting as the restored administrator, remove the
   temporary role once its owned objects have been reassigned.
4. For Compose, mount the restored storage as the matching `postgres_data_19`,
   `payments_postgres_data_19`, or `clearing_postgres_data_19` volume. For
   Kubernetes, restore into the new claims and scale the previous payments and
   clearing StatefulSets to zero before starting their versioned replacements.
   This keeps both versions from serving traffic behind the same Services.
   Keep bootstrap jobs and application workloads stopped until restore is done.
5. Check `SHOW server_version`, record counts, role ownership and grants,
   Flyway history, representative account balances and payment records, and
   Temporal readiness. Restart PostgreSQL and verify persistence before
   resuming writers. Run a banking smoke test after reopening traffic.

To roll back before accepting writes on 19, stop the new workloads, restore
the previous deployment configuration and reconnect to the retained 18
storage. If PostgreSQL 19 has accepted writes, stop writers and reconcile
those changes before returning to 18.
