# Cash Drive 로컬 백엔드 샌드박스

Python 3.9+ 표준 라이브러리와 SQLite만 사용한다. 루프백 전용 HTTP API로 계정 귀속, 거리 후보, 광고권, 확인 요청, 원장 흐름을 재현한다. 샌드박스 로그인은 OAuth가 아니며 거리 odometer 값은 클라이언트 보고값이라 차량에서 검증되지 않는다. 광고 공급자/실제 SSV, Pleos UXR, 지급처 연동은 없다.

## 실행

프로젝트 루트에서:

```sh
export CASHDRIVE_SANDBOX_WEBHOOK_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
python3 -m server.http --sandbox
```

기본 주소는 `http://127.0.0.1:8787`, SQLite 기본 파일은 `server/data/cashdrive.sqlite3`이다. 파일 경로와 포트는 `--db`, `--port`로 바꿀 수 있다. 외부 인터페이스 bind는 거부한다. `--sandbox` 없이 실행하면 모든 API가 닫힌 상태로 404를 반환한다. 테스트:

```sh
python3 -m unittest server.test_backend -v
```

## API 계약

로그인 helper는 `POST /v1/sandbox/login`에 `{"accountAlias":"alice","installationId":"car-a"}`를 보내고 응답의 임의 bearer를 로컬에서 사용한다. 같은 alias는 같은 계정으로 복구되며 설치 ID는 다른 계정에 재바인딩할 수 없다. 토큰 원문은 저장하지 않고 SHA-256만 저장한다. 이 helper는 개발용 가짜 identity다.

보호된 API는 `Authorization: Bearer …`가 필요하다. 모든 쓰기 API는 8~128자 `Idempotency-Key`를 받는다. 키는 계정 내에서 하나의 API 작업과 본문에 귀속된다. 같은 키를 다른 API에 쓰면 본문이 같더라도 `409 idempotency_key_operation_mismatch`, 같은 API에서 본문을 바꾸면 `409 idempotency_key_body_mismatch`다. 시작/완료/확인에는 각각 다른 키를 사용하고, 같은 작업의 응답 손실 재시도에만 원래 키를 유지한다.

기존 4열 idempotency 테이블은 operation 열을 추가하고 알려진 기존 응답 형식으로 원래 API 작업을 식별해 같은 작업의 재시도를 보존한다. 형식이 불명확한 과거 응답은 `409 idempotency_key_operation_unknown`으로 닫는다. 이 경우 DB·원장을 지우거나 새 키로 무작정 재전송하지 말고 기존 응답과 원장을 대조해 명시적으로 마이그레이션해야 한다.

완료 증명의 occurredAt은 세션 생성 시각이 아니라 실제 시작 표식 startedAt 이후부터 수신 시각까지의 범위여야 한다. 시작 전 시각의 증명은 `422 proof_time_invalid`로 거절하며, 먼저 제출한 사용자 확인만으로 원장에 적립하지 않는다.

| Method / path | 핵심 body | 결과 |
|---|---|---|
| `POST /v1/trips` | `{"schemaVersion":1,"installationId":"car-a","tripId":"trip-1","bootId":"boot-1","odometerMeters":12000,"elapsedMs":500}` | 새 trip/boot의 무보상 odometer·elapsed baseline 설정 |
| `POST /v1/distance-chunks` | architecture schemaVersion 1 body, 사전 등록한 tripId | 청크 승인 응답, 매 1,000m마다 10P 티켓 생성, 나머지 m 설치별 이월 |
| `POST /v1/ad-sessions` | `{"schemaVersion":1,"rewardTicketId":"…","placement":"PARKED_REWARDED"}` | 10분 sandbox 세션 |
| `POST /v1/ad-sessions/start` | `{"schemaVersion":1,"adSessionId":"…"}` | 로그인된 계정이 재생 직전에 호출하는 세션 시작 표식 |
| `POST /v1/sandbox/ad-proof` | `adSessionId`, `providerTransactionId`, epoch seconds 문자열 `occurredAt` | 원문 body의 HMAC-SHA256 `X-Sandbox-Signature` 검증 |
| `POST /v1/sandbox/ad-complete` | `{"schemaVersion":1,"adSessionId":"…"}` | 로그인된 동일 계정의 started 세션에서 5초 이상 경과 후 synthetic proof. source는 `NON_PROVIDER_SANDBOX` |
| `POST /v1/reward-claims` | `{"schemaVersion":1,"rewardTicketId":"…","adSessionId":"…","userConfirmed":true}` | proof가 아직 없으면 `PENDING_PROOF`; 같은 세션의 명시 확인과 proof가 모두 있어야 단일 ledger credit |
| `POST /v1/logout` | `{}` | 현재 bearer 폐기 |
| `GET /v1/wallet` | — | 원장에서 계산한 확정 포인트 |
| `GET /v1/tickets` | — | 해당 계정의 티켓 목록 |

trip baseline 요청 자체는 보상을 만들지 않는다. reboot, 긴 gap, 또는 odometer 재기준화 뒤에는 새 tripId/bootId로 baseline을 등록해야 하며, 등록 시 이전 active trip은 닫혀 이후 청크를 받지 않는다. 이전 trip의 종료 odometer와 새 baseline 사이 거리는 보상하지 않으며, 미완성 1km 잔여분은 설치별로 다음 trip에 이월한다. 청크는 trip별 0부터 순차 sequence, baseline/이전 청크 odometer 끝과 다음 시작의 연속성, elapsed 순서, 청크와 gap 각각 15분 이하, 100km 이하, 360km/h 이하를 확인한다. 완료된 주행 데이터가 아니라 요청상 단조성만 검사하므로 응답에 `CLIENT_REPORTED_UNVERIFIED`를 표시한다. seq/key 오류는 보상 생성 전 거절한다.

claim은 특정 adSessionId에 묶인다. Android 데모는 광고를 열기 직전에 `/v1/ad-sessions/start`를 호출하고, 실제 provider 연결 전 완료 시뮬레이션에는 `/v1/sandbox/ad-complete`를 쓴다. 세션이 만료되면 티켓을 보존하고 재광고 세션과 새 사용자 확인을 요구한다. 확인을 이전 세션에서 제출해도 새 세션의 proof와 결합되지 않는다. `/v1/sandbox/ad-complete`는 모바일 데모에서 HMAC secret 없이 동작하도록 만든 로컬 합성 완료 도구다. 5초 대기는 광고 시청 증거가 아니며 실제 광고 공급자의 SSV를 모사한 것으로 취급하지 않는다.

Webhook HMAC는 `CASHDRIVE_SANDBOX_WEBHOOK_SECRET`로 검증한다. 로컬 요청 서명 예시(실제 광고 증명 아님):

```sh
python3 -c 'import hashlib,hmac,json,os; b=json.dumps({"adSessionId":"ad_ID","providerTransactionId":"demo-tx-1","occurredAt":"1760000000"},separators=(",",":")).encode(); print(hmac.new(os.environ["CASHDRIVE_SANDBOX_WEBHOOK_SECRET"].encode(),b,hashlib.sha256).hexdigest())'
```

실제 사용 때 body의 `adSessionId`는 API 응답의 세션 ID로 바꾸고 `occurredAt`은 세션 생성 이후 현재까지의 epoch seconds로 지정한다. DB는 WAL, foreign key, unique constraint, `BEGIN IMMEDIATE` write transaction을 사용한다. 요청 body 상한은 1 MiB, 중복 JSON 키와 미지원 field는 거절한다.

## 보안/미구현 경계

이 서버는 sandbox 전용이며 production identity/provider/SSV adapter를 제공하지 않는다. 계정 alias는 실제 로그인 자격이 아니고 HMAC secret도 로컬 개발용이다. 거리 데이터와 합성 광고 완료는 실제 차량·광고 사업자 증명으로 승격되지 않는다. Pairing QR, provider OAuth, 차량 attestation, 지급/교환, production deployment는 미구현이다.
