"""Loopback-only JSON HTTP adapter for the local sandbox."""
from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from .store import ApiError, Store

MAX_BODY = 1_048_576
KEY_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")


class Handler(BaseHTTPRequestHandler):
    server_version = "CashDriveSandbox/1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, fmt: str, *args: object) -> None:
        # Request paths can include sensitive identifiers; keep logs quiet.
        return

    def _send(self, status: int, value: dict) -> None:
        data = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> tuple[bytes, dict]:
        if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
            raise ApiError(415, "json_required")
        try:
            size = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            size = -1
        if size < 0 or size > MAX_BODY:
            raise ApiError(413 if size > MAX_BODY else 400, "invalid_content_length")
        try:
            raw = self.rfile.read(size)
        except socket.timeout:
            raise ApiError(408, "request_body_timeout")
        if len(raw) != size:
            raise ApiError(400, "truncated_request_body")
        try:
            body = json.loads(raw.decode("utf-8"), object_pairs_hook=self._strict_object, parse_constant=self._reject_constant)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            raise ApiError(400, "invalid_json")
        if not isinstance(body, dict):
            raise ApiError(400, "json_object_required")
        return raw, body

    @staticmethod
    def _strict_object(pairs: list[tuple[str, object]]) -> dict:
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate json key")
            result[key] = value
        return result

    @staticmethod
    def _reject_constant(value: str) -> None:
        raise ValueError("non-finite JSON number")

    def _principal(self) -> dict[str, str]:
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or len(auth) > 256:
            raise ApiError(401, "bearer_token_required")
        return self.server.store.auth(auth[7:])  # type: ignore[attr-defined]

    def _key(self) -> str:
        key = self.headers.get("Idempotency-Key", "")
        if not KEY_RE.fullmatch(key):
            raise ApiError(400, "idempotency_key_required")
        return key

    def do_GET(self) -> None:
        try:
            if not self.server.sandbox: raise ApiError(404, "route_not_found")  # type: ignore[attr-defined]
            path = urlparse(self.path).path
            principal = self._principal()
            if path == "/v1/wallet": result = self.server.store.wallet(principal)  # type: ignore[attr-defined]
            elif path == "/v1/tickets": result = self.server.store.tickets(principal)  # type: ignore[attr-defined]
            else: raise ApiError(404, "route_not_found")
            self._send(200, result)
        except ApiError as exc: self._send(exc.status, {"error": exc.code, "detail": exc.detail})
        except Exception: self._send(500, {"error": "internal_error"})

    def do_POST(self) -> None:
        try:
            if not self.server.sandbox: raise ApiError(404, "route_not_found")  # type: ignore[attr-defined]
            path = urlparse(self.path).path
            raw, body = self._body()
            if path == "/v1/sandbox/login":
                if not self.server.sandbox: raise ApiError(404, "route_not_found")  # type: ignore[attr-defined]
                if set(body) != {"accountAlias", "installationId"} or not isinstance(body["accountAlias"],str) or not isinstance(body["installationId"],str): raise ApiError(400, "invalid_identity")
                result = self.server.store.sandbox_login(body["accountAlias"], body["installationId"])  # type: ignore[attr-defined]
                self._send(201, result); return
            if path == "/v1/sandbox/ad-proof":
                if not self.server.sandbox: raise ApiError(404, "route_not_found")  # type: ignore[attr-defined]
                signature = self.headers.get("X-Sandbox-Signature", "")
                expected = hmac.new(self.server.webhook_secret, raw, "sha256").hexdigest()  # type: ignore[attr-defined]
                if not hmac.compare_digest(signature, expected): raise ApiError(401, "invalid_proof_signature")
                self._send(200, self.server.store.receive_proof(raw, body)); return  # type: ignore[attr-defined]
            principal = self._principal()
            if path == "/v1/logout":
                auth = self.headers.get("Authorization", "")
                self.server.store.logout(auth[7:])  # type: ignore[attr-defined]
                self._send(200, {"loggedOut":True}); return
            key = self._key()
            if path == "/v1/trips": result = self.server.store.start_trip(principal,key,body)  # type: ignore[attr-defined]
            elif path == "/v1/distance-chunks": result = self.server.store.distance_chunk(principal,key,body)  # type: ignore[attr-defined]
            elif path == "/v1/ad-sessions": result = self.server.store.create_ad_session(principal,key,body)  # type: ignore[attr-defined]
            elif path == "/v1/ad-sessions/start": result = self.server.store.start_ad_session(principal,key,body)  # type: ignore[attr-defined]
            elif path == "/v1/reward-claims": result = self.server.store.claim(principal,key,body)  # type: ignore[attr-defined]
            elif path == "/v1/sandbox/ad-complete": result = self.server.store.sandbox_ad_complete(principal,key,body)  # type: ignore[attr-defined]
            else: raise ApiError(404, "route_not_found")
            self._send(200, result)
        except ApiError as exc: self._send(exc.status, {"error": exc.code, "detail": exc.detail})
        except Exception: self._send(500, {"error": "internal_error"})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, store: Store, sandbox: bool, webhook_secret: bytes) -> None:
        super().__init__(address, Handler)
        self.store, self.sandbox, self.webhook_secret = store, sandbox, webhook_secret


def main() -> None:
    parser = argparse.ArgumentParser(description="Cash Drive loopback sandbox backend")
    parser.add_argument("--sandbox", action="store_true", help="enable sandbox login and signed sandbox proof endpoints")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default=str(Path(__file__).parent / "data" / "cashdrive.sqlite3"))
    args = parser.parse_args()
    if args.host not in ("127.0.0.1", "::1", "localhost"):
        parser.error("this build is local sandbox only and must bind to loopback")
    secret = os.environ.get("CASHDRIVE_SANDBOX_WEBHOOK_SECRET", "").encode()
    if args.sandbox and len(secret) < 32:
        parser.error("set CASHDRIVE_SANDBOX_WEBHOOK_SECRET to at least 32 random bytes in local config")
    server = Server((args.host,args.port), Store(args.db), args.sandbox, secret)
    print(f"Cash Drive sandbox API listening on http://{args.host}:{args.port}; mode={'sandbox' if args.sandbox else 'closed'}")
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == "__main__":
    main()
