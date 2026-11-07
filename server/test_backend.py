import concurrent.futures
import hmac
import http.client
import json
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path

from server.http import Server
from server.store import ApiError, Store


class BackendTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.tmp.name) / "db.sqlite3")
        self.clock = [int(time.time())]
        self.store = Store(self.path, clock=lambda: self.clock[0])
        self.login = self.store.sandbox_login("alice", "car-a")
        self.p = self.store.auth(self.login["accessToken"])
        self.start_trip()

    def tearDown(self):
        self.tmp.cleanup()

    def start_trip(self, trip="trip-a", boot="boot-a", odo=10000, elapsed=1000, key="trip-key-0001"):
        body={"schemaVersion":1,"installationId":"car-a","tripId":trip,"bootId":boot,"odometerMeters":odo,"elapsedMs":elapsed}
        return self.store.start_trip(self.p,key,body)

    def chunk(self, seq=0, distance=1000, start_odo=10000, start=1000, trip="trip-a", duration=10000):
        return {"schemaVersion":1,"installationId":"car-a","tripId":trip,"sequence":seq,
                "startElapsedMs":start,"endElapsedMs":start+duration,"distanceMeters":distance,
                "odometerStartMeters":start_odo,"odometerEndMeters":start_odo+distance}

    def chunk_call(self, body, key):
        return self.store.distance_chunk(self.p,key,body)

    def ticket(self):
        return self.chunk_call(self.chunk(),"chunk-key-0001")["tickets"][0]["id"]

    def ad(self, ticket, key="ad-key-000001"):
        session=self.store.create_ad_session(self.p,key,{"schemaVersion":1,"rewardTicketId":ticket,"placement":"PARKED_REWARDED"})
        self.store.start_ad_session(self.p,key.replace("ad-key","start-key"),{"schemaVersion":1,"adSessionId":session["id"]})
        return session

    def proof(self, session, tx="tx-1"):
        body={"adSessionId":session,"providerTransactionId":tx,"occurredAt":str(self.store.now())}
        return json.dumps(body,separators=(",",":")).encode(),body

    def claim(self,ticket,session,key="claim-key-0001"):
        return self.store.claim(self.p,key,{"schemaVersion":1,"rewardTicketId":ticket,"adSessionId":session,"userConfirmed":True})

    def test_fractional_carry_and_chunk_exact_dedupe(self):
        first=self.chunk(distance=600)
        a=self.chunk_call(first,"chunk-key-0001")
        self.assertEqual(a["fractionalMeters"],600)
        self.assertEqual(self.chunk_call(first,"chunk-key-0001"),a)
        second=self.chunk(1,400,10600,11000)
        b=self.chunk_call(second,"chunk-key-0002")
        self.assertEqual(len(b["tickets"]),1)
        self.assertEqual(b["fractionalMeters"],0)
        with self.assertRaisesRegex(ApiError,"mismatch"):
            self.chunk_call(self.chunk(distance=999),"chunk-key-0001")

    def test_new_trip_rebaselines_after_reboot_without_reward_and_carries_fraction(self):
        out=self.chunk_call(self.chunk(distance=600),"chunk-key-0001")
        self.assertEqual(out["fractionalMeters"],600)
        # New boot has reset elapsed time and an odometer discontinuity. The new baseline earns nothing.
        self.start_trip("trip-b","boot-b",500,0,"trip-key-0002")
        with self.assertRaisesRegex(ApiError,"trip_baseline_not_current"):
            self.chunk_call(self.chunk(1,400,10600,11000),"chunk-key-old-trip")
        body=self.chunk(0,400,500,0,"trip-b")
        earned=self.chunk_call(body,"chunk-key-0002")
        self.assertEqual(earned["fractionalMeters"],0)
        self.assertEqual(len(earned["tickets"]),1)
        with self.assertRaisesRegex(ApiError,"trip_baseline_required"):
            self.chunk_call(self.chunk(trip="unknown"),"chunk-key-0003")

    def test_trip_gap_requires_explicit_new_baseline(self):
        # A 16 minute gap inside one trip is rejected, but a new baseline can start at a new odometer/boot.
        long_gap=self.chunk(start=1_000_001)
        with self.assertRaisesRegex(ApiError,"trip_gap_requires_rebaseline"):
            self.chunk_call(long_gap,"chunk-key-0001")
        self.start_trip("trip-b","boot-a",900000,2_000_000,"trip-key-0002")
        self.assertEqual(self.chunk_call(self.chunk(0,1000,900000,2_000_000,"trip-b"),"chunk-key-0002")["tickets"][0]["points"],10)

    def test_odometer_gaps_sequence_and_rate_rejected(self):
        self.chunk_call(self.chunk(),"chunk-key-0001")
        with self.assertRaisesRegex(ApiError,"rollback_or_gap"):
            self.chunk_call(self.chunk(1,1000,12000,11000),"chunk-key-0002")
        with self.assertRaisesRegex(ApiError,"out_of_order"):
            self.chunk_call(self.chunk(3,1000,11000,11000),"chunk-key-0003")
        fast=self.chunk(1,1000,11000,11000,duration=1)
        with self.assertRaisesRegex(ApiError,"rate_exceeded"):
            self.chunk_call(fast,"chunk-key-0004")

    def test_strict_identifiers_and_schema_types(self):
        with self.assertRaisesRegex(ApiError,"invalid_distance_chunk"):
            self.chunk_call({**self.chunk(),"tripId":[]},"chunk-key-0001")
        with self.assertRaisesRegex(ApiError,"Expected"):
            self.chunk_call({**self.chunk(),"schemaVersion":True},"chunk-key-0001")
        with self.assertRaisesRegex(ApiError,"invalid_trip_baseline"):
            self.start_trip("",key="trip-key-0002")

    def test_wrong_account_cannot_read_or_claim_ticket(self):
        ticket=self.ticket(); ad=self.ad(ticket)
        bob=self.store.sandbox_login("bob","car-b")
        bp=self.store.auth(bob["accessToken"])
        with self.assertRaisesRegex(ApiError,"ticket_not_found"):
            self.store.claim(bp,"claim-key-0002",{"schemaVersion":1,"rewardTicketId":ticket,"adSessionId":ad["id"],"userConfirmed":True})
        self.assertEqual(self.store.tickets(bp)["tickets"],[])

    def test_confirmation_is_bound_to_exact_ad_session(self):
        ticket=self.ticket(); old=self.ad(ticket)
        self.assertEqual(self.claim(ticket,old["id"])["status"],"PENDING_PROOF")
        self.clock[0]=old["expiresAt"]+1
        new=self.ad(ticket,"ad-key-000002")
        raw,body=self.proof(new["id"],"new-session-tx")
        self.store.receive_proof(raw,body)
        self.assertEqual(self.store.wallet(self.p)["confirmedPoints"],0)
        # Old confirmation does not transfer to the replacement ad session.
        self.claim(ticket,new["id"],"claim-key-0002")
        self.assertEqual(self.store.wallet(self.p)["confirmedPoints"],10)

    def test_proof_and_user_confirmation_work_in_either_order_exactly_once(self):
        ticket=self.ticket(); ad=self.ad(ticket); raw,body=self.proof(ad["id"])
        proof_first=self.store.receive_proof(raw,body)
        self.assertIsNone(proof_first["ledgerEntry"])
        paid=self.claim(ticket,ad["id"])
        self.assertEqual(paid["status"],"CREDITED")
        self.assertEqual(self.claim(ticket,ad["id"])["ledgerEntry"],paid["ledgerEntry"])
        self.assertEqual(self.store.wallet(self.p)["confirmedPoints"],10)

    def test_concurrent_claims_from_separate_connections_credit_once(self):
        ticket=self.ticket(); ad=self.ad(ticket); raw,body=self.proof(ad["id"])
        self.store.receive_proof(raw,body)
        barrier=threading.Barrier(8)
        def run(i):
            p=self.store.auth(self.login["accessToken"]); barrier.wait()
            return self.store.claim(p,"claim-key-0001",{"schemaVersion":1,"rewardTicketId":ticket,"adSessionId":ad["id"],"userConfirmed":True})
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            rows=list(pool.map(run,range(8)))
        self.assertTrue(all(x["status"]=="CREDITED" for x in rows))
        self.assertEqual(len({x["ledgerEntry"]["id"] for x in rows}),1)
        self.assertEqual(self.store.wallet(self.p)["confirmedPoints"],10)

    def test_transaction_reuse_and_different_claim_key_conflict(self):
        ticket=self.ticket(); ad=self.ad(ticket); raw,body=self.proof(ad["id"],"global-tx")
        self.store.receive_proof(raw,body); self.claim(ticket,ad["id"])
        ticket2=self.chunk_call(self.chunk(1,1000,11000,11000),"chunk-key-0002")["tickets"][0]["id"]
        with self.assertRaisesRegex(ApiError,"claim_already_started"):
            self.claim(ticket,ad["id"],"claim-key-0002")
        ad2=self.ad(ticket2,"ad-key-000002"); raw2,body2=self.proof(ad2["id"],"global-tx")
        with self.assertRaisesRegex(ApiError,"reused"):
            self.store.receive_proof(raw2,body2)

    def test_durable_reopen_and_expired_session_keeps_ticket(self):
        ticket=self.ticket(); ad=self.ad(ticket)
        with self.store.connect() as db:
            db.execute("UPDATE ad_sessions SET expires_at=? WHERE id=?",(self.store.now()-1,ad["id"]))
        reopened=Store(self.path,clock=lambda:self.clock[0])
        self.assertEqual(reopened.tickets(self.p)["tickets"][0]["id"],ticket)
        next_ad=reopened.create_ad_session(self.p,"ad-key-000002",{"schemaVersion":1,"rewardTicketId":ticket,"placement":"PARKED_REWARDED"})
        self.assertNotEqual(next_ad["id"],ad["id"])

    def test_live_http_full_sandbox_flow_logout_and_hmac(self):
        secret=b"s"*32
        server=Server(("127.0.0.1",0),self.store,True,secret)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        conn=http.client.HTTPConnection("127.0.0.1",server.server_port)
        def request(method,path,body=None,headers=None):
            data=body if isinstance(body,bytes) else (json.dumps(body,separators=(",",":")).encode() if body is not None else b"{}")
            conn.request(method,path,data,{"Content-Type":"application/json",**(headers or {})})
            response=conn.getresponse(); payload=response.read()
            return response.status,(json.loads(payload) if payload else {})
        try:
            status,login=request("POST","/v1/sandbox/login",{"accountAlias":"http-user","installationId":"http-car"}); self.assertEqual(status,201)
            auth={"Authorization":"Bearer "+login["accessToken"]}
            baseline={"schemaVersion":1,"installationId":"http-car","tripId":"http-trip","bootId":"http-boot","odometerMeters":1_000,"elapsedMs":10}
            self.assertEqual(request("POST","/v1/trips",baseline,{**auth,"Idempotency-Key":"http-trip-key"})[0],200)
            chunk={"schemaVersion":1,"installationId":"http-car","tripId":"http-trip","sequence":0,"startElapsedMs":10,"endElapsedMs":10_010,"distanceMeters":1_000,"odometerStartMeters":1_000,"odometerEndMeters":2_000}
            status,accepted=request("POST","/v1/distance-chunks",chunk,{**auth,"Idempotency-Key":"http-chunk-key"}); self.assertEqual(status,200)
            ticket=accepted["tickets"][0]["id"]
            status,ad=request("POST","/v1/ad-sessions",{"schemaVersion":1,"rewardTicketId":ticket,"placement":"PARKED_REWARDED"},{**auth,"Idempotency-Key":"http-ad-key"}); self.assertEqual(status,200)
            self.assertEqual(request("POST","/v1/ad-sessions/start",{"schemaVersion":1,"adSessionId":ad["id"]},{**auth,"Idempotency-Key":"http-start-key"})[0],200)
            status,_=request("POST","/v1/sandbox/ad-complete",{"schemaVersion":1,"adSessionId":ad["id"]},{**auth,"Idempotency-Key":"http-complete-key"}); self.assertEqual(status,425)
            self.clock[0]+=5
            status,completed=request("POST","/v1/sandbox/ad-complete",{"schemaVersion":1,"adSessionId":ad["id"]},{**auth,"Idempotency-Key":"http-complete-key"}); self.assertEqual(status,200)
            self.assertEqual(completed["source"],"NON_PROVIDER_SANDBOX")
            self.assertEqual(request("POST","/v1/reward-claims",{"schemaVersion":1,"rewardTicketId":ticket,"adSessionId":ad["id"],"userConfirmed":True},{**auth,"Idempotency-Key":"http-claim-key"})[0],200)
            self.assertEqual(request("GET","/v1/wallet",headers=auth)[1]["confirmedPoints"],10)
            proof={"adSessionId":"absent","providerTransactionId":"tx","occurredAt":str(self.store.now())}
            raw=json.dumps(proof,separators=(",",":")).encode()
            self.assertEqual(request("POST","/v1/sandbox/ad-proof",raw,{"X-Sandbox-Signature":"bad"})[0],401)
            sig=hmac.new(secret,raw,"sha256").hexdigest()
            self.assertEqual(request("POST","/v1/sandbox/ad-proof",raw,{"X-Sandbox-Signature":sig})[0],404)
            self.assertEqual(request("POST","/v1/logout",{},auth)[0],200)
            self.assertEqual(request("GET","/v1/wallet",headers=auth)[0],401)
        finally:
            conn.close(); server.shutdown(); server.server_close(); thread.join()

    def test_sandbox_completion_minimum_is_measured_from_ad_start(self):
        ticket=self.ticket()
        session=self.store.create_ad_session(self.p,"ad-key-000010",{"schemaVersion":1,"rewardTicketId":ticket,"placement":"PARKED_REWARDED"})
        self.clock[0]+=20
        self.store.start_ad_session(self.p,"start-key-000010",{"schemaVersion":1,"adSessionId":session["id"]})
        body={"schemaVersion":1,"adSessionId":session["id"]}
        self.clock[0]+=4
        with self.assertRaisesRegex(ApiError,"sandbox_minimum_watch_time_not_met"):
            self.store.sandbox_ad_complete(self.p,"complete-key-000010",body)
        self.clock[0]+=1
        response=self.store.sandbox_ad_complete(self.p,"complete-key-000010",body)
        self.assertEqual(response["source"],"NON_PROVIDER_SANDBOX")

    def test_http_rejects_nonfinite_json_and_truncated_request_body(self):
        server=Server(("127.0.0.1",0),self.store,True,b"")
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            # JSON's nonstandard NaN constant must not enter the request object.
            conn=http.client.HTTPConnection("127.0.0.1",server.server_port)
            conn.request("POST","/v1/sandbox/login",b'{"accountAlias":NaN,"installationId":"x"}',{"Content-Type":"application/json"})
            response=conn.getresponse(); self.assertEqual(response.status,400); response.read(); conn.close()
            # A client that closes after sending fewer bytes than Content-Length gets a bounded 400.
            sock=socket.create_connection(("127.0.0.1",server.server_port),timeout=2)
            sock.sendall(b"POST /v1/sandbox/login HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 20\r\nConnection: close\r\n\r\n{}")
            sock.shutdown(socket.SHUT_WR)
            chunks=[]
            while True:
                part=sock.recv(4096)
                if not part: break
                chunks.append(part)
            sock.close()
            self.assertIn(b"400 Bad Request",b"".join(chunks))
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_closed_mode_rejects_all_routes(self):
        server=Server(("127.0.0.1",0),self.store,False,b"")
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            conn=http.client.HTTPConnection("127.0.0.1",server.server_port)
            conn.request("GET","/v1/wallet",headers={"Authorization":"Bearer "+self.login["accessToken"]})
            response=conn.getresponse(); self.assertEqual(response.status,404); response.read(); conn.close()
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == "__main__":
    unittest.main()
