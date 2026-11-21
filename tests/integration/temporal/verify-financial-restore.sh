#!/usr/bin/env bash
# Called with the database containers from a quiesced, disposable payment acceptance project.
set -euo pipefail
umask 077
banking="$1"
payments="$2"
report="$3"
broker="${4:-}"
work="$(mktemp -d)"
restore="payment-restore-$(openssl rand -hex 6)"
started="$(date +%s)"
cleanup() {
  docker rm -f "$restore" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Require both source containers to belong to this disposable test project.
sources=("$banking" "$payments")
if [[ -n "$broker" ]]; then sources+=("$broker"); fi
for source in "${sources[@]}"; do
  label="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$source")"
  [[ "$label" == payment-test-* ]]
  [[ -z "${project:-}" || "$project" == "$label" ]]
  project="$label"
done

dump() {
  docker exec "$1" pg_dump --format=custom --no-owner --no-acl -U "$2" -d "$3"
}
dump "$banking" postgres banking_app > "$work/banking.dump"
dump "$payments" payments payments > "$work/payments.dump"
backup_docker exec -i "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d banking_app \
  < "$(dirname "$0")/legacy-ledger-audit.sql" > "${report%.json}-legacy-audit.json"

completed="$(date +%s)"

image="$(docker inspect -f '{{.Image}}' "$banking")"
# Isolate the restore network and use local socket access only.
docker run --detach --network none --name "$restore" -e POSTGRES_HOST_AUTH_METHOD=trust "$image" >/dev/null
for ((attempt = 0; attempt < 60; attempt++)); do
  if docker exec "$restore" pg_isready --host localhost --username postgres --quiet; then break; fi
  sleep 1
done
for database in banking_app payments; do
  docker exec "$restore" createdb -U postgres "$database"
  input="$work/payments.dump"
  if [[ "$database" == banking_app ]]; then input="$work/banking.dump"; fi
  docker exec -i "$restore" pg_restore --exit-on-error --no-owner --no-acl \
    -U postgres -d "$database" < "$input"
done

digest() {
  local container="$1"
  local user="$2"
  local target_db="$3"
  shift 3
  for table in "$@"; do
    docker exec "$container" psql -qAt -v ON_ERROR_STOP=1 -U "$user" -d "$target_db" -c "
      SELECT '$table', count(*), md5(COALESCE(string_agg(entry, '' ORDER BY entry), ''))
      FROM (SELECT to_jsonb(stored)::text AS entry FROM $table AS stored) entries
    "
  done
}

accounts_tables=(
  accounts_service.accounts accounts_service.movement_reservations accounts_service.account_postings
  transactions_service.customer_movements transactions_service.movement_decisions transactions_service.event_outbox
  transactions_service.movement_screening_audit
)
payments_tables=(payments.payment payments.transition payments.outbox payments.clearing_inbox payments.review_resolution)
digest "$banking" postgres banking_app "${accounts_tables[@]}" > "$work/before-accounts"
digest "$payments" payments payments "${payments_tables[@]}" > "$work/before-payments"
digest "$restore" postgres banking_app "${accounts_tables[@]}" > "$work/after-accounts"
digest "$restore" postgres payments "${payments_tables[@]}" > "$work/after-payments"
cmp "$work/before-accounts" "$work/after-accounts"
cmp "$work/before-payments" "$work/after-payments"

# Reconcile every settled payment to both its debit and stable settlement event.
docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d payments -c "
  SELECT id || '|' || account_id || '|' || amount_minor
  FROM payments.payment WHERE status = 'SETTLED' ORDER BY id
" > "$work/settled"
docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d banking_app -c "
  SELECT operation_id || '|' || account_id || '|' || (-amount * 100)::bigint
  FROM accounts_service.account_postings WHERE source = 'OUTBOUND_PAYMENT' ORDER BY operation_id
" > "$work/debits"
cmp "$work/settled" "$work/debits"
[[ -s "$work/settled" ]]
docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d payments -c "
  SELECT count(*) FROM payments.payment p
  WHERE p.status = 'SETTLED' AND (
    SELECT count(*) FROM payments.outbox o
    WHERE o.payment_id = p.id AND o.destination = 'KAFKA'
      AND (CASE WHEN o.destination = 'KAFKA' THEN convert_from(o.body, 'UTF8')::jsonb->>'status' END) = 'SETTLED'
  ) <> 1
" | grep -qx 0

docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d payments -c "
  SELECT id || '|' || account_id || '|' || amount_minor FROM payments.payment
  WHERE status = 'REVIEW_REQUIRED' AND reserved_at IS NOT NULL ORDER BY id
" > "$work/reviews"
docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d banking_app -c "
  SELECT operation_id || '|' || from_account_id || '|' || (amount * 100)::bigint
  FROM accounts_service.movement_reservations WHERE state = 'RESERVED' ORDER BY operation_id
" > "$work/holds"
awk -F '|' 'NR==FNR { wanted[$1]=1; next } $1 in wanted' "$work/reviews" "$work/holds" > "$work/review-holds"
cmp "$work/reviews" "$work/review-holds"

replayed=0
if [[ -n "$broker" ]]; then
  docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d banking_app -c "
    SELECT payload FROM transactions_service.event_outbox WHERE topic = 'movement-lifecycle-v1' ORDER BY id
  " > "$work/events"
  docker exec "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d payments -c "
    SELECT convert_from(body,'UTF8') FROM payments.outbox WHERE destination = 'KAFKA' ORDER BY created_at,id
  " >> "$work/events"
  replayed="$(wc -l < "$work/events" | tr -d ' ')"
  [[ "$replayed" -gt 0 ]]
  docker exec "$broker" rpk topic create "$restore" -X brokers=redpanda:9092 >/dev/null
  docker exec -i "$broker" rpk topic produce "$restore" -X brokers=redpanda:9092 < "$work/events" >/dev/null
  timeout 45 docker exec "$broker" rpk topic consume "$restore" -X brokers=redpanda:9092 \
    -o start --num "$replayed" --format '%v\n' > "$work/replayed"
  cmp "$work/events" "$work/replayed"
fi

docker exec -i "$restore" psql -qAt -v ON_ERROR_STOP=1 -U postgres -d banking_app \
  < "$(dirname "$0")/legacy-ledger-audit.sql" > "${report%.json}-legacy-audit.json"

completed="$(date +%s)"
printf '{"backup_restore_seconds":%s,"restore_seconds":%s,"lost_compared_rows":0,"settled_payments_reconciled":%s,"event_payloads_replayed":%s}\n' \
  "$((completed - started))" "$((completed - backup_completed))" "$(wc -l < "$work/settled" | tr -d ' ')" "$replayed" > "$report"
printf 'Financial restore reconciliation passed. Report %s\n' "$report"
