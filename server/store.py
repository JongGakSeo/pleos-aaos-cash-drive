"""Small transactional SQLite domain store for the local Cash Drive sandbox."""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import time
import uuid
from contextlib import closing
from pathlib import Path
from typing import Any, Callable


class ApiError(Exception):
    def __init__(self, status: int, code: str, detail: str = "") -> None:
        super().__init__(detail or code)
        self.status, self.code, self.detail = status, code, detail or code


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, path: str | Path, clock: Callable[[], float] = time.time) -> None:
        self.path = str(path)
        self.clock = clock
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._init()

    def connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=10000")
        return db

    def _init(self) -> None:
        with closing(self.connect()) as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
            CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, alias TEXT NOT NULL UNIQUE);
            CREATE TABLE IF NOT EXISTS installations(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS tokens(token_hash TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id),
              installation_id TEXT NOT NULL REFERENCES installations(id), created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS idempotency(
              account_id TEXT NOT NULL REFERENCES accounts(id), key TEXT NOT NULL, body_hash TEXT NOT NULL,
              response_json TEXT NOT NULL, operation TEXT, PRIMARY KEY(account_id,key));
            CREATE TABLE IF NOT EXISTS distance_state(
              installation_id TEXT PRIMARY KEY REFERENCES installations(id), fractional_m INTEGER NOT NULL DEFAULT 0,
              last_odometer_m INTEGER, last_end_elapsed_ms INTEGER);
            CREATE TABLE IF NOT EXISTS trips(
              installation_id TEXT NOT NULL REFERENCES installations(id), trip_id TEXT NOT NULL, boot_id TEXT NOT NULL,
              baseline_odometer_m INTEGER NOT NULL, last_odometer_m INTEGER NOT NULL, baseline_elapsed_ms INTEGER NOT NULL,
              last_elapsed_ms INTEGER NOT NULL, next_sequence INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
              status TEXT NOT NULL DEFAULT 'ACTIVE',
              PRIMARY KEY(installation_id,trip_id));
            CREATE TABLE IF NOT EXISTS chunks(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), installation_id TEXT NOT NULL,
              trip_id TEXT NOT NULL, sequence INTEGER NOT NULL, payload_hash TEXT NOT NULL, distance_m INTEGER NOT NULL,
              UNIQUE(installation_id,trip_id,sequence));
            CREATE TABLE IF NOT EXISTS trip_sequences(
              installation_id TEXT NOT NULL REFERENCES installations(id), trip_id TEXT NOT NULL, next_sequence INTEGER NOT NULL,
              PRIMARY KEY(installation_id,trip_id));
            CREATE TABLE IF NOT EXISTS tickets(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), installation_id TEXT NOT NULL,
              points INTEGER NOT NULL CHECK(points > 0), status TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS ad_sessions(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), ticket_id TEXT NOT NULL REFERENCES tickets(id),
              created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL, started_at INTEGER);
            CREATE TABLE IF NOT EXISTS proofs(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), session_id TEXT NOT NULL UNIQUE REFERENCES ad_sessions(id),
              transaction_id TEXT NOT NULL UNIQUE, body_hash TEXT NOT NULL, verified_at INTEGER NOT NULL,
              source TEXT NOT NULL DEFAULT 'SIGNED_SANDBOX_WEBHOOK');
            CREATE TABLE IF NOT EXISTS claims(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), ticket_id TEXT NOT NULL REFERENCES tickets(id),
              ad_session_id TEXT NOT NULL REFERENCES ad_sessions(id),
              idem_key TEXT NOT NULL, body_hash TEXT NOT NULL, confirmed_at INTEGER NOT NULL, status TEXT NOT NULL,
              UNIQUE(account_id,idem_key), UNIQUE(ticket_id,ad_session_id));
            CREATE TABLE IF NOT EXISTS ledger(
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), ticket_id TEXT NOT NULL UNIQUE REFERENCES tickets(id),
              ad_transaction_id TEXT NOT NULL UNIQUE, points INTEGER NOT NULL, created_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS ticket_owner_idx ON tickets(account_id,created_at);
            """)
            idem_columns = {row["name"] for row in db.execute("PRAGMA table_info(idempotency)")}
            if "operation" not in idem_columns:
                db.execute("ALTER TABLE idempotency ADD COLUMN operation TEXT")
            columns = {row["name"] for row in db.execute("PRAGMA table_info(distance_state)")}
            if "last_end_elapsed_ms" not in columns:
                db.execute("ALTER TABLE distance_state ADD COLUMN last_end_elapsed_ms INTEGER")
            trip_columns={row["name"] for row in db.execute("PRAGMA table_info(trips)")}
            if "status" not in trip_columns:
                db.execute("ALTER TABLE trips ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
            proof_columns = {row["name"] for row in db.execute("PRAGMA table_info(proofs)")}
            if "source" not in proof_columns:
                db.execute("ALTER TABLE proofs ADD COLUMN source TEXT NOT NULL DEFAULT 'SIGNED_SANDBOX_WEBHOOK'")
            ad_columns={row["name"] for row in db.execute("PRAGMA table_info(ad_sessions)")}
            if "started_at" not in ad_columns:
                db.execute("ALTER TABLE ad_sessions ADD COLUMN started_at INTEGER")
            claim_sql = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='claims'").fetchone()["sql"]
            if "ticket_id TEXT NOT NULL UNIQUE" in claim_sql:
                db.execute("ALTER TABLE claims RENAME TO claims_legacy")
                db.execute("""CREATE TABLE claims(
                  id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), ticket_id TEXT NOT NULL REFERENCES tickets(id),
                  ad_session_id TEXT NOT NULL REFERENCES ad_sessions(id), idem_key TEXT NOT NULL, body_hash TEXT NOT NULL,
                  confirmed_at INTEGER NOT NULL, status TEXT NOT NULL, UNIQUE(account_id,idem_key), UNIQUE(ticket_id,ad_session_id))""")
                db.execute("""INSERT INTO claims(id,account_id,ticket_id,ad_session_id,idem_key,body_hash,confirmed_at,status)
                  SELECT c.id,c.account_id,c.ticket_id,s.id,c.idem_key,c.body_hash,c.confirmed_at,c.status
                  FROM claims_legacy c JOIN ad_sessions s ON s.ticket_id=c.ticket_id
                  JOIN proofs p ON p.session_id=s.id JOIN ledger l ON l.ticket_id=c.ticket_id AND l.ad_transaction_id=p.transaction_id
                  WHERE s.account_id=c.account_id ORDER BY s.created_at DESC""")
                db.execute("DROP TABLE claims_legacy")

    def now(self) -> int:
        return int(self.clock())

    @staticmethod
    def _json(value: Any) -> str:
        return json.dumps(value, sort_keys=True, separators=(",", ":"))

    @staticmethod
    def _identifier(value: Any, limit: int = 100) -> bool:
        return isinstance(value, str) and 0 < len(value) <= limit and "\x00" not in value

    def logout(self, token: str) -> None:
        with closing(self.connect()) as db:
            db.execute("DELETE FROM tokens WHERE token_hash=?", (digest(token),))

    def sandbox_login(self, alias: str, installation_id: str) -> dict[str, Any]:
        if not isinstance(alias, str) or not isinstance(installation_id, str) or not alias or len(alias) > 80 or not installation_id or len(installation_id) > 100:
            raise ApiError(400, "invalid_identity")
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT id FROM accounts WHERE alias=?", (alias,)).fetchone()
            if row is None:
                account = "acct_" + uuid.uuid4().hex
                db.execute("INSERT INTO accounts VALUES(?,?)", (account, alias))
            else:
                account = row["id"]
            inst = db.execute("SELECT account_id FROM installations WHERE id=?", (installation_id,)).fetchone()
            if inst and inst["account_id"] != account:
                raise ApiError(409, "installation_bound_to_other_account")
            if not inst:
                db.execute("INSERT INTO installations VALUES(?,?,?)", (installation_id, account, self.now()))
            db.execute("INSERT OR IGNORE INTO distance_state(installation_id) VALUES(?)", (installation_id,))
            token = secrets.token_urlsafe(32)
            db.execute("INSERT INTO tokens VALUES(?,?,?,?)", (digest(token), account, installation_id, self.now()))
            db.commit()
            return {"accountId": account, "installationId": installation_id, "accessToken": token, "mode": "sandbox"}
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def auth(self, token: str) -> dict[str, str]:
        with closing(self.connect()) as db:
            row = db.execute("SELECT account_id,installation_id FROM tokens WHERE token_hash=?", (digest(token),)).fetchone()
            if not row:
                raise ApiError(401, "invalid_bearer_token")
            return {"accountId": row["account_id"], "installationId": row["installation_id"]}

    @staticmethod
    def _legacy_operation(response: Any) -> str | None:
        """Recognize only known cached response shapes from the unscoped sandbox DB."""
        if not isinstance(response, dict):
            return None
        matches = [name for name, matched in (
            ("trip_baseline", response.get("baselineStatus") == "UNREWARDED_CLIENT_REPORTED" and "tripId" in response),
            ("distance_chunk", response.get("evidenceStatus") == "CLIENT_REPORTED_UNVERIFIED" and "tickets" in response),
            ("ad_create", response.get("provider") == "sandbox" and response.get("status") == "CREATED" and "ticketId" in response),
            ("ad_start", response.get("status") == "STARTED" and "adSessionId" in response and "startedAt" in response),
            ("sandbox_complete", response.get("source") == "NON_PROVIDER_SANDBOX" and response.get("accepted") is True and "proofId" in response and "providerTransactionId" in response),
            ("reward_claim", response.get("status") in ("PENDING_PROOF", "CREDITED") and "ledgerEntry" in response),
        ) if matched]
        return matches[0] if len(matches) == 1 else None

    def _idem_get(self, db: sqlite3.Connection, account: str, key: str, body: dict[str, Any], operation: str) -> dict[str, Any] | None:
        row = db.execute("SELECT body_hash,response_json,operation FROM idempotency WHERE account_id=? AND key=?", (account,key)).fetchone()
        if not row:
            return None
        response = json.loads(row["response_json"])
        stored_operation = row["operation"] or self._legacy_operation(response)
        if stored_operation is None:
            raise ApiError(409, "idempotency_key_operation_unknown")
        if stored_operation != operation:
            raise ApiError(409, "idempotency_key_operation_mismatch")
        if row["body_hash"] != digest(self._json(body)):
            raise ApiError(409, "idempotency_key_body_mismatch")
        if row["operation"] is None:
            db.execute("UPDATE idempotency SET operation=? WHERE account_id=? AND key=?", (operation,account,key))
        return response

    def _idem_put(self, db: sqlite3.Connection, account: str, key: str, body: dict[str, Any], response: dict[str, Any], operation: str) -> None:
        db.execute("INSERT INTO idempotency(account_id,key,body_hash,response_json,operation) VALUES(?,?,?,?,?)", (account,key,digest(self._json(body)),self._json(response),operation))

    def start_trip(self, principal: dict[str,str], key: str, body: dict[str,Any]) -> dict[str,Any]:
        operation = "trip_baseline"
        required={"schemaVersion","installationId","tripId","bootId","odometerMeters","elapsedMs"}
        if set(body)!=required or type(body.get("schemaVersion")) is not int or body["schemaVersion"]!=1:
            raise ApiError(400,"invalid_trip_baseline")
        if not self._identifier(body["installationId"]) or not self._identifier(body["tripId"]) or not self._identifier(body["bootId"]):
            raise ApiError(400,"invalid_trip_baseline")
        if any(type(body[k]) is not int or body[k]<0 for k in ("odometerMeters","elapsedMs")):
            raise ApiError(400,"invalid_trip_baseline")
        if body["installationId"]!=principal["installationId"]:
            raise ApiError(403,"installation_mismatch")
        db=self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cached=self._idem_get(db,principal["accountId"],key,body,operation)
            if cached is not None: db.commit(); return cached
            existing=db.execute("SELECT * FROM trips WHERE installation_id=? AND trip_id=?",(body["installationId"],body["tripId"])).fetchone()
            if existing:
                raise ApiError(409,"trip_id_already_baselined")
            db.execute("UPDATE trips SET status='CLOSED' WHERE installation_id=? AND status='ACTIVE'",(body["installationId"],))
            db.execute("INSERT INTO trips(installation_id,trip_id,boot_id,baseline_odometer_m,last_odometer_m,baseline_elapsed_ms,last_elapsed_ms,next_sequence,created_at,status) VALUES(?,?,?,?,?,?,?,?,?,'ACTIVE')",(body["installationId"],body["tripId"],body["bootId"],body["odometerMeters"],body["odometerMeters"],body["elapsedMs"],body["elapsedMs"],0,self.now()))
            response={"accepted":True,"tripId":body["tripId"],"bootId":body["bootId"],"baselineStatus":"UNREWARDED_CLIENT_REPORTED"}
            self._idem_put(db,principal["accountId"],key,body,response,operation); db.commit(); return response
        except Exception: db.rollback(); raise
        finally: db.close()

    def distance_chunk(self, principal: dict[str,str], key: str, body: dict[str,Any]) -> dict[str,Any]:
        operation = "distance_chunk"
        required = {"schemaVersion","installationId","tripId","sequence","startElapsedMs","endElapsedMs","distanceMeters","odometerStartMeters","odometerEndMeters"}
        if set(body) != required or type(body.get("schemaVersion")) is not int or body["schemaVersion"] != 1:
            raise ApiError(400,"invalid_distance_chunk","Expected the documented schemaVersion 1 fields")
        ints = [body[k] for k in required - {"installationId","tripId"}]
        if any(type(x) is not int or x < 0 for x in ints) or not self._identifier(body["tripId"]):
            raise ApiError(400,"invalid_distance_chunk")
        if not self._identifier(body["installationId"]): raise ApiError(400,"invalid_distance_chunk")
        if body["installationId"] != principal["installationId"]:
            raise ApiError(403,"installation_mismatch")
        seq, start, end, distance = (body[k] for k in ("sequence","startElapsedMs","endElapsedMs","distanceMeters"))
        odo0, odo1 = body["odometerStartMeters"], body["odometerEndMeters"]
        if end <= start or end - start > 900_000 or distance <= 0 or odo1 - odo0 != distance or distance > 100_000:
            raise ApiError(422,"invalid_distance_evidence")
        if distance * 1000 > (end-start) * 100:  # above 360 km/h
            raise ApiError(422,"distance_rate_exceeded")
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cached = self._idem_get(db,principal["accountId"],key,body,operation)
            if cached is not None:
                db.commit(); return cached
            account, installation, trip = principal["accountId"], principal["installationId"], body["tripId"]
            exists = db.execute("SELECT id FROM chunks WHERE installation_id=? AND trip_id=? AND sequence=?",(installation,trip,seq)).fetchone()
            if exists:
                raise ApiError(409,"chunk_sequence_already_used")
            announced = db.execute("SELECT * FROM trips WHERE installation_id=? AND trip_id=?",(installation,trip)).fetchone()
            if not announced: raise ApiError(409,"trip_baseline_required")
            if announced["status"]!="ACTIVE": raise ApiError(409,"trip_baseline_not_current")
            if start < announced["last_elapsed_ms"] or end <= start:
                raise ApiError(422,"elapsed_time_out_of_order")
            if start-announced["last_elapsed_ms"]>900_000:
                raise ApiError(422,"trip_gap_requires_rebaseline")
            if odo0 != announced["last_odometer_m"]:
                raise ApiError(422,"odometer_rollback_or_gap")
            if announced["next_sequence"] != seq:
                raise ApiError(409,"sequence_out_of_order")
            state=db.execute("SELECT fractional_m FROM distance_state WHERE installation_id=?",(installation,)).fetchone()
            if state is None: raise ApiError(409,"installation_not_bound")
            fractional = state["fractional_m"] + distance
            count, remain = divmod(fractional,1000)
            tickets = []
            for _ in range(count):
                tid = "ticket_" + uuid.uuid4().hex
                db.execute("INSERT INTO tickets VALUES(?,?,?,?,?,?)",(tid,account,installation,10,"PENDING",self.now()))
                tickets.append({"id":tid,"points":10,"status":"PENDING"})
            db.execute("INSERT INTO chunks VALUES(?,?,?,?,?,?,?)",("chunk_"+uuid.uuid4().hex,account,installation,trip,seq,digest(self._json(body)),distance))
            db.execute("UPDATE trips SET next_sequence=?,last_odometer_m=?,last_elapsed_ms=? WHERE installation_id=? AND trip_id=?",(seq+1,odo1,end,installation,trip))
            db.execute("UPDATE distance_state SET fractional_m=? WHERE installation_id=?",(remain,installation))
            response = {"accepted":True,"distanceMeters":distance,"fractionalMeters":remain,"tickets":tickets,"evidenceStatus":"CLIENT_REPORTED_UNVERIFIED"}
            self._idem_put(db,account,key,body,response,operation); db.commit(); return response
        except Exception:
            db.rollback(); raise
        finally:
            db.close()

    def create_ad_session(self, principal: dict[str,str], key: str, body: dict[str,Any]) -> dict[str,Any]:
        operation = "ad_create"
        if set(body) != {"schemaVersion","rewardTicketId","placement"} or type(body.get("schemaVersion")) is not int or body.get("schemaVersion") != 1 or body.get("placement") != "PARKED_REWARDED" or not self._identifier(body.get("rewardTicketId")):
            raise ApiError(400,"invalid_ad_session")
        db=self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cached=self._idem_get(db,principal["accountId"],key,body,operation)
            if cached is not None: db.commit(); return cached
            t=db.execute("SELECT * FROM tickets WHERE id=? AND account_id=?",(body["rewardTicketId"],principal["accountId"])).fetchone()
            if not t: raise ApiError(404,"ticket_not_found")
            if t["status"] in ("AD_PLAYING","AWAITING_CONFIRMATION"):
                active=db.execute("SELECT * FROM ad_sessions WHERE ticket_id=? AND status IN ('CREATED','COMPLETED') ORDER BY created_at DESC LIMIT 1",(t["id"],)).fetchone()
                if active and self.now() > active["expires_at"]:
                    db.execute("UPDATE ad_sessions SET status='EXPIRED' WHERE id=?",(active["id"],))
                    db.execute("UPDATE tickets SET status='PENDING' WHERE id=?",(t["id"],))
                    t=db.execute("SELECT * FROM tickets WHERE id=?",(t["id"],)).fetchone()
            if t["status"] not in ("PENDING",): raise ApiError(409,"ticket_not_ad_eligible")
            now=self.now(); sid="ad_"+uuid.uuid4().hex; expiry=now+600
            db.execute("INSERT INTO ad_sessions(id,account_id,ticket_id,created_at,expires_at,status,started_at) VALUES(?,?,?,?,?,?,NULL)",(sid,principal["accountId"],t["id"],now,expiry,"CREATED"))
            db.execute("UPDATE tickets SET status='AD_PLAYING' WHERE id=?",(t["id"],))
            response={"id":sid,"ticketId":t["id"],"provider":"sandbox","expiresAt":expiry,"status":"CREATED"}
            self._idem_put(db,principal["accountId"],key,body,response,operation); db.commit(); return response
        except Exception: db.rollback(); raise
        finally: db.close()

    def start_ad_session(self, principal:dict[str,str], key:str, body:dict[str,Any]) -> dict[str,Any]:
        operation = "ad_start"
        if set(body)!={"schemaVersion","adSessionId"} or type(body.get("schemaVersion")) is not int or body["schemaVersion"]!=1 or not self._identifier(body.get("adSessionId")):
            raise ApiError(400,"invalid_ad_start")
        db=self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cached=self._idem_get(db,principal["accountId"],key,body,operation)
            if cached is not None: db.commit(); return cached
            session=db.execute("SELECT * FROM ad_sessions WHERE id=? AND account_id=?",(body["adSessionId"],principal["accountId"])).fetchone()
            if not session: raise ApiError(404,"ad_session_not_found")
            if session["status"]!="CREATED" or self.now()>session["expires_at"]: raise ApiError(410,"ad_session_expired")
            started=session["started_at"] or self.now()
            db.execute("UPDATE ad_sessions SET started_at=? WHERE id=?",(started,session["id"]))
            response={"adSessionId":session["id"],"startedAt":started,"status":"STARTED"}
            self._idem_put(db,principal["accountId"],key,body,response,operation); db.commit(); return response
        except Exception: db.rollback(); raise
        finally: db.close()

    def _accept_proof(self, db: sqlite3.Connection, session_id: str, transaction_id: str, body_hash: str, occurred: int, source: str) -> dict[str,Any]:
        old=db.execute("SELECT body_hash FROM proofs WHERE transaction_id=?",(transaction_id,)).fetchone()
        if old:
            if old["body_hash"]!=body_hash: raise ApiError(409,"provider_transaction_reused")
            return {"accepted":True,"duplicate":True,"source":source}
        s=db.execute("SELECT * FROM ad_sessions WHERE id=?",(session_id,)).fetchone()
        now=self.now()
        if not s or s["status"]!="CREATED": raise ApiError(404,"ad_session_not_found")
        if s["started_at"] is None: raise ApiError(409,"ad_session_not_started")
        if now>s["expires_at"]:
            db.execute("UPDATE ad_sessions SET status='EXPIRED' WHERE id=?",(session_id,))
            db.execute("UPDATE tickets SET status='PENDING' WHERE id=?",(s["ticket_id"],))
            raise ApiError(410,"ad_session_expired")
        if not s["started_at"]<=occurred<=now: raise ApiError(422,"proof_time_invalid")
        pid="proof_"+uuid.uuid4().hex
        db.execute("INSERT INTO proofs(id,account_id,session_id,transaction_id,body_hash,verified_at,source) VALUES(?,?,?,?,?,?,?)",(pid,s["account_id"],session_id,transaction_id,body_hash,now,source))
        db.execute("UPDATE ad_sessions SET status='COMPLETED' WHERE id=?",(session_id,))
        db.execute("UPDATE tickets SET status='AWAITING_CONFIRMATION' WHERE id=?",(s["ticket_id"],))
        ledger=self._credit_if_ready(db,s["ticket_id"],session_id)
        return {"accepted":True,"duplicate":False,"proofId":pid,"source":source,"ledgerEntry":ledger}

    def receive_proof(self, raw_body: bytes, body: dict[str,Any]) -> dict[str,Any]:
        if set(body)!={"adSessionId","providerTransactionId","occurredAt"} or not all(self._identifier(body[k],200) for k in body):
            raise ApiError(400,"invalid_sandbox_proof")
        db=self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            bh=digest(raw_body.decode("utf-8"))
            try: occurred=int(body["occurredAt"])
            except ValueError: raise ApiError(400,"invalid_sandbox_proof")
            response=self._accept_proof(db,body["adSessionId"],body["providerTransactionId"],bh,occurred,"SIGNED_SANDBOX_WEBHOOK")
            db.commit(); return response
        except Exception: db.rollback(); raise
        finally: db.close()

    def sandbox_ad_complete(self, principal:dict[str,str], key:str, body:dict[str,Any]) -> dict[str,Any]:
        operation = "sandbox_complete"
        if set(body)!={"schemaVersion","adSessionId"} or type(body.get("schemaVersion")) is not int or body["schemaVersion"]!=1 or not self._identifier(body.get("adSessionId")):
            raise ApiError(400,"invalid_sandbox_completion")
        db=self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cached=self._idem_get(db,principal["accountId"],key,body,operation)
            if cached is not None: db.commit(); return cached
            session=db.execute("SELECT * FROM ad_sessions WHERE id=? AND account_id=?",(body["adSessionId"],principal["accountId"])).fetchone()
            if not session: raise ApiError(404,"ad_session_not_found")
            now=self.now()
            if session["started_at"] is None: raise ApiError(409,"ad_session_not_started")
            if now-session["started_at"]<5: raise ApiError(425,"sandbox_minimum_watch_time_not_met")
            transaction="sandbox_tx_"+uuid.uuid4().hex
            signature_body=self._json({"adSessionId":session["id"],"providerTransactionId":transaction,"occurredAt":str(now),"source":"NON_PROVIDER_SANDBOX"})
            response=self._accept_proof(db,session["id"],transaction,digest(signature_body),now,"NON_PROVIDER_SANDBOX")
            response["providerTransactionId"]=transaction
            self._idem_put(db,principal["accountId"],key,body,response,operation); db.commit(); return response
        except Exception: db.rollback(); raise
        finally: db.close()

    def _credit_if_ready(self, db:sqlite3.Connection, ticket_id:str, session_id:str) -> dict[str,Any] | None:
        c=db.execute("SELECT c.*,t.points,t.account_id,p.transaction_id FROM claims c JOIN tickets t ON t.id=c.ticket_id JOIN ad_sessions s ON s.id=c.ad_session_id JOIN proofs p ON p.session_id=s.id WHERE c.ticket_id=? AND c.ad_session_id=? AND c.account_id=t.account_id AND s.account_id=t.account_id AND p.account_id=t.account_id AND s.id=?",(ticket_id,session_id,session_id)).fetchone()
        if not c: return None
        old=db.execute("SELECT * FROM ledger WHERE ticket_id=?",(ticket_id,)).fetchone()
        if old: return {"id":old["id"],"points":old["points"],"status":"CREDITED"}
        lid="ledger_"+uuid.uuid4().hex
        db.execute("INSERT INTO ledger VALUES(?,?,?,?,?,?)",(lid,c["account_id"],ticket_id,c["transaction_id"],c["points"],self.now()))
        db.execute("UPDATE tickets SET status='CREDITED' WHERE id=?",(ticket_id,)); db.execute("UPDATE claims SET status='CREDITED' WHERE ticket_id=? AND ad_session_id=?",(ticket_id,session_id))
        return {"id":lid,"points":c["points"],"status":"CREDITED"}

    def claim(self, principal:dict[str,str], key:str, body:dict[str,Any]) -> dict[str,Any]:
        operation = "reward_claim"
        if set(body)!={"schemaVersion","rewardTicketId","adSessionId","userConfirmed"} or type(body.get("schemaVersion")) is not int or body.get("schemaVersion")!=1 or body.get("userConfirmed") is not True or not self._identifier(body.get("rewardTicketId")) or not self._identifier(body.get("adSessionId")):
            raise ApiError(400,"explicit_confirmation_required")
        db=self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cached=self._idem_get(db,principal["accountId"],key,body,operation)
            if cached is not None:
                ledger=db.execute("SELECT id,points FROM ledger WHERE ticket_id=?",(body["rewardTicketId"],)).fetchone()
                if ledger: cached={"status":"CREDITED","ledgerEntry":{"id":ledger["id"],"points":ledger["points"],"status":"CREDITED"}}
                db.commit(); return cached
            t=db.execute("SELECT * FROM tickets WHERE id=? AND account_id=?",(body["rewardTicketId"],principal["accountId"])).fetchone()
            if not t: raise ApiError(404,"ticket_not_found")
            session=db.execute("SELECT * FROM ad_sessions WHERE id=? AND ticket_id=? AND account_id=?",(body["adSessionId"],t["id"],principal["accountId"])).fetchone()
            if not session: raise ApiError(404,"ad_session_not_found")
            if session["status"] not in ("CREATED","COMPLETED") or self.now()>session["expires_at"]:
                raise ApiError(410,"ad_session_expired")
            if session["started_at"] is None: raise ApiError(409,"ad_session_not_started")
            existing=db.execute("SELECT * FROM claims WHERE ticket_id=? AND ad_session_id=?",(t["id"],session["id"])).fetchone()
            if existing and existing["idem_key"]!=key: raise ApiError(409,"claim_already_started")
            if t["status"]=="CREDITED": raise ApiError(409,"ticket_already_credited")
            claimid=existing["id"] if existing else "claim_"+uuid.uuid4().hex
            if not existing:
                db.execute("INSERT INTO claims VALUES(?,?,?,?,?,?,?,?)",(claimid,principal["accountId"],t["id"],session["id"],key,digest(self._json(body)),self.now(),"PENDING"))
            ledger=self._credit_if_ready(db,t["id"],session["id"])
            response={"status":"CREDITED" if ledger else "PENDING_PROOF","ledgerEntry":ledger}
            self._idem_put(db,principal["accountId"],key,body,response,operation); db.commit(); return response
        except Exception: db.rollback(); raise
        finally: db.close()

    def wallet(self, principal:dict[str,str]) -> dict[str,Any]:
        with closing(self.connect()) as db:
            row=db.execute("SELECT COALESCE(SUM(points),0) AS total FROM ledger WHERE account_id=?",(principal["accountId"],)).fetchone()
            return {"accountId":principal["accountId"],"confirmedPoints":row["total"]}

    def tickets(self, principal:dict[str,str]) -> dict[str,Any]:
        with closing(self.connect()) as db:
            rows=db.execute("SELECT id,points,status,created_at FROM tickets WHERE account_id=? ORDER BY created_at,id",(principal["accountId"],)).fetchall()
            return {"tickets":[dict(r) for r in rows]}
