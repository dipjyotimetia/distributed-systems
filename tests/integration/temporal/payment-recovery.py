"""Exercise payment recovery on a disposable Compose project or Kind cluster."""
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date
from contextlib import contextmanager


def eventually(check, message, timeout=75):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(1)
    raise AssertionError(message)


class Journey:
    def __init__(self, compose):
        self.kind = compose == ["--kind"]
        self.compose = compose
        self.kubectl = ["kubectl", "-n", "banking-app"]
        self.frontend = os.environ["MICROSVC_FRONTEND"]
        self.notifications = os.environ["MICROSVC_NOTIFICATIONS"]
        username = "recovery" + secrets.token_hex(5)
        password = "T3st!" + secrets.token_hex(16)
        self.customer = self.request(self.frontend, "/api/v1/users/register", 201, {
            "username": username, "email": username + "@example.test", "password": password,
        })["id"]
        login = self.request(self.frontend, "/api/v1/users/login", 200, {
            "usernameOrEmail": username, "password": password,
        })
        self.auth = {"Authorization": "Bearer " + login["token"]}

    @staticmethod
    def request(base, path, expected, payload=None, headers=None):
        request = urllib.request.Request(base + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=6) as response:
                assert response.status == expected, (path, response.status, expected)
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise AssertionError(f"{path} returned HTTP {error.code}, expected {expected}") from None

    def sql(self, statement):
        target = ([*self.kubectl, "exec", "-i", "banking-payments-postgres-19-0", "--"] if self.kind
                  else [*self.compose, "exec", "-T", "payments-postgres"])
        return subprocess.run([*target, "psql", "-Atq", "-U", "payments", "-d", "payments", "-v", "ON_ERROR_STOP=1"],
            input=statement, text=True, capture_output=True, check=True, timeout=15).stdout.strip()

    def engine(self, *args):
        subprocess.run([*self.compose, *args], check=True, timeout=55)

    @contextmanager
    def interrupt(self, service):
        if self.kind:
            label, workload = {
                "clearing-mq": ("banking-clearing-mq", "statefulset/banking-clearing-mq"),
                "redpanda": ("banking-kafka", "deployment/banking-kafka"),
            }[service]
            subprocess.run([*self.kubectl, "scale", workload, "--replicas=0"], check=True, timeout=15)
            try:
                def no_pods():
                    result = subprocess.run([*self.kubectl, "get", "pods", "-l", "app=" + label, "-o", "name"],
                        capture_output=True, text=True, check=True, timeout=10)
                    return not result.stdout.strip()
                eventually(no_pods, f"{service} did not stop", timeout=50)
                yield
            finally:
                subprocess.run([*self.kubectl, "scale", workload, "--replicas=1"], check=True, timeout=15)
                subprocess.run([*self.kubectl, "rollout", "status", workload, "--timeout=90s"], check=True, timeout=95)
        elif service == "redpanda":
            # Restart the broker so Kafka clients must reconnect, as in Kind.
            self.engine("stop", service)
            try:
                yield
            finally:
                self.engine("start", service)
        else:
            self.engine("pause", service)
            try:
                yield
            finally:
                self.engine("unpause", service)

    def replace_worker(self):
        if self.kind:
            worker = "deployment/banking-payments-worker"
            subprocess.run([*self.kubectl, "rollout", "restart", worker], check=True, timeout=15)
        else:
            self.engine("up", "-d", "--no-deps", "--force-recreate", "payments-worker")

    @contextmanager
    def withhold_ack_record(self, destination):
        assert destination in ("MQ", "KAFKA")
        eligible = "TRUE" if destination == "MQ" else "convert_from(NEW.body, 'UTF8')::jsonb->>'status' = 'SETTLED'"
        self.sql(f"""
            CREATE FUNCTION payments.test_reject_{destination.lower()}_ack() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.destination = '{destination}' AND NEW.published_at IS NOT NULL THEN
                    IF {eligible} THEN
                        RAISE EXCEPTION 'integration fixture withheld outbox acknowledgement';
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$;
            CREATE TRIGGER test_outbox_ack BEFORE UPDATE OF published_at ON payments.outbox
            FOR EACH ROW EXECUTE FUNCTION payments.test_reject_{destination.lower()}_ack();
        """)
        try:
            yield
        finally:
            self.sql(f"""
                DROP TRIGGER test_outbox_ack ON payments.outbox;
                DROP FUNCTION payments.test_reject_{destination.lower()}_ack();
            """)

    def submit(self):
        account = self.request(self.frontend, "/api/v1/accounts", 201,
            {"accountType": "CHECKING", "currency": "AUD", "initialBalance": 100}, self.auth)["id"]
        payment = self.request(self.frontend, "/api/v1/payments", 202,
            {"accountId": account, "destination": "DEST00000001", "amountMinor": 2500},
            {**self.auth, "Idempotency-Key": str(uuid.uuid4())})
        return account, str(uuid.UUID(payment["id"]))

    def state(self, payment):
        return self.request(self.frontend, "/api/v1/payments/" + payment, 200, headers=self.auth)["status"]

    def balance(self, account):
        result = self.request(self.frontend, f"/api/v1/accounts/{account}/balance", 200, headers=self.auth)
        return round(result["balance"] * 100)

    def notices(self, payment):
        rows = self.request(self.notifications, f"/api/v1/notifications/{self.customer}", 200) or []
        return [row for row in rows if row.get("payment_id") == payment]

    def pending(self, payment, destination):
        assert destination in ("MQ", "KAFKA")
        return int(self.sql(f"""
            SELECT count(*) FROM payments.outbox WHERE payment_id = '{payment}'
              AND destination = '{destination}' AND published_at IS NULL;
        """))

    def booking(self, account, payment):
        eventually(lambda: self.state(payment) == "SETTLED", "payment did not settle")
        eventually(lambda: not self.pending(payment, "KAFKA"), "payment events stayed in the outbox")
        eventually(lambda: bool(self.notices(payment)), "settlement notification did not arrive")
        assert len(self.notices(payment)) == 1
        assert self.balance(account) == 7500
        assert self.sql(f"""
            SELECT count(*) FROM payments.transition WHERE payment_id = '{payment}' AND status = 'SETTLED';
        """) == "1"

    def worker_and_mq(self):
        with self.interrupt("clearing-mq"):
            account, payment = self.submit()
            eventually(lambda: self.pending(payment, "MQ") == 1, "payment did not acquire a hold")
            assert self.balance(account) == 10000
            self.replace_worker()
        self.booking(account, payment)

    def redpanda(self):
        with self.interrupt("redpanda"):
            account, payment = self.submit()
            eventually(lambda: self.state(payment) == "SETTLED", "settlement waited for Kafka")
            assert self.pending(payment, "KAFKA") > 0
            assert not self.notices(payment)
            assert self.balance(account) == 7500
        self.booking(account, payment)

    def mq_ack_gap(self):
        with self.withhold_ack_record("MQ"):
            account, payment = self.submit()
            eventually(lambda: self.sql(f"""
                SELECT count(*) FROM payments.clearing_inbox WHERE payment_id = '{payment}' AND kind = 'ACCEPTED';
            """) == "1", "clearing did not acknowledge the MQ instruction")
            assert self.pending(payment, "MQ") == 1
        self.booking(account, payment)
        eventually(lambda: not self.pending(payment, "MQ"), "MQ acknowledgement was not retried")
        assert self.sql(f"""
            SELECT count(*) FROM payments.outbox WHERE payment_id = '{payment}' AND destination = 'MQ';
        """) == "1"

    def kafka_ack_gap(self):
        with self.withhold_ack_record("KAFKA"):
            account, payment = self.submit()
            eventually(lambda: self.state(payment) == "SETTLED", "payment did not settle with Kafka ack fault")
            eventually(lambda: bool(self.notices(payment)), "Kafka delivery never reached notifications")
            assert self.pending(payment, "KAFKA") == 1
            assert self.balance(account) == 7500
        self.booking(account, payment)


    def reconciliation_discrepancies(self):
        fixture_base = os.environ["MICROSVC_PAYMENT_FIXTURES"]
        with open(os.environ["MICROSVC_PAYMENT_EVIDENCE"], encoding="utf-8") as stream:
            browser = json.load(stream)
        date_value = date(2000, 1, 1)
        original = str(uuid.UUID(browser["paymentId"]))
        _, missing = self.submit()
        eventually(lambda: self.state(missing) == "SETTLED", "fixture payment did not settle")
        # The test fixture places the synthetic booking in a separate closed clearing date.
        self.sql(f"""
            UPDATE payments.payment SET settlement_business_date = '{date_value}' WHERE id = '{missing}';
        """)
        amount = int(browser["amountMinor"]) + 1
        reference = browser["clearingReference"]
        record = f"01S{original}AUD{amount:012d}{reference}"
        payload = f"01H{date_value:%Y%m%d}999999AUD\n{record}\n01T000001{amount:018d}\n"
        upload = urllib.request.Request(f"{fixture_base}/settlements-test/settlement/aud/{date_value}.dat",
            data=payload.encode("ascii"), method="PUT", headers={"If-None-Match": "*"})
        with urllib.request.urlopen(upload, timeout=5) as response:
            assert response.status == 200

        if self.kind:
            job = "payment-reconciliation-discrepancies"
            spec = subprocess.run([*self.kubectl, "create", "job", job,
                "--from=cronjob/banking-payments-reconciliation", "--dry-run=client", "-o", "yaml"],
                capture_output=True, text=True, check=True, timeout=15).stdout
            configured = subprocess.run([*self.kubectl, "set", "env", "--local", "-f", "-",
                f"SETTLEMENT_BUSINESS_DATE={date_value}", "-o", "yaml"], input=spec,
                capture_output=True, text=True, check=True, timeout=15).stdout
            subprocess.run([*self.kubectl, "create", "-f", "-"], input=configured, text=True, check=True, timeout=15)
            subprocess.run([*self.kubectl, "wait", "--for=condition=complete", "job/" + job, "--timeout=90s"],
                check=True, timeout=95)
            subprocess.run([*self.kubectl, "set", "env", "deployment/banking-payments",
                f"PAYMENTS_OPERATOR_IDS={self.customer}"], check=True, timeout=15)
            subprocess.run([*self.kubectl, "rollout", "status", "deployment/banking-payments", "--timeout=90s"],
                check=True, timeout=95)
        else:
            self.engine("run", "--rm", "-e", f"SETTLEMENT_BUSINESS_DATE={date_value}", "payments-reconciliation")
            os.environ["BANKING_PAYMENTS_OPERATOR_IDS"] = str(self.customer)
            self.engine("up", "-d", "--no-deps", "--force-recreate", "payments-service")

        def operator_rows():
            try:
                return self.request(self.frontend, "/api/v1/operations/payments/reconciliation", 200, headers=self.auth)
            except (AssertionError, OSError):
                return None

        rows = eventually(operator_rows, "operator could not read reconciliation discrepancies")
        actual = {(row["paymentId"], row["outcome"], row["expectedAmountMinor"], row["observedAmountMinor"])
                  for row in rows if row["businessDate"] == str(date_value)}
        assert actual == {(original, "AMOUNT_MISMATCH", amount - 1, amount),
                          (missing, "MISSING_RECORD", 2500, None)}, actual


def main(command):
    journey = Journey(command)
    for scenario in (journey.worker_and_mq, journey.redpanda, journey.mq_ack_gap, journey.kafka_ack_gap,
                     journey.reconciliation_discrepancies):
        scenario()
        print(f"Payment recovery passed: {scenario.__name__}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
