# Native sandbox HTTP client

`SandboxApi` is a blocking `HttpURLConnection` client for the local Python server. It has no executor of its own: call methods from the app's worker executor. Every request rejects main-thread use. API errors throw `SandboxApiException(httpStatus, errorCode, serverDetail)`; transport, response-size, and response-shape failures throw `IOException`.

```kotlin
val api = SandboxApi(baseUrl = "http://10.0.2.2:8787", sandboxEnabled = true)
val login = api.loginSandbox(SandboxIdentity(accountAlias, installationId))
tokenVault.save(login)

api.startTrip(login.accessToken, TripBaseline(installationId, tripId, bootId, odometerMeters, elapsedMs), tripKey)
api.submitDistanceChunk(login.accessToken, chunk, chunkKey)
val wallet = api.wallet(login.accessToken) // server ledger total, not a client-side calculation
val tickets = api.tickets(login.accessToken)
val ad = api.createAdSession(login.accessToken, ticketId, createAdKey)
api.startAdSession(login.accessToken, ad.id, startKey) // call immediately before starting the ad surface
val completion = api.completeSandboxAd(login.accessToken, ad.id, completeKey)
val claim = api.claimReward(login.accessToken, ticketId, ad.id, claimKey)
api.logout(login.accessToken, tokenVault) // revokes remotely and clears local credentials even on failure
```

Each retry of a write must reuse its caller-owned idempotency key and the same request body. Generate a fresh key for a new action/session. `completeSandboxAd` asks the server to create a local proof after five seconds from the explicit session start; it does not send or contain the server HMAC secret and returns `source=NON_PROVIDER_SANDBOX`.

`SandboxApi` accepts only an explicitly enabled debug build and the fixed loopback addresses `10.0.2.2`, `127.0.0.1`, or `localhost` on port `8787`. It has no TLS/production mode and never follows redirects. Request and response bodies are capped at 1 MiB; connect/read timeouts are five seconds. Bearer credentials are sent only in `Authorization`, and no request logging is performed.

`TokenVault` encrypts the active `SandboxLogin` JSON with an AES-GCM key held by Android Keystore. SharedPreferences contain only the IV and ciphertext. The decrypted record binds the token to its account and installation; corruption/key invalidation clears the saved value and throws `TokenVaultException` so the caller can leave the user signed out. `SandboxLogin.toString()` redacts every field. Callers must not log credentials or exception causes.

The current manifest disables general cleartext networking. The debug emulator needs a narrowly scoped Android network-security exception for `10.0.2.2` to reach the loopback-only Python server; keep that exception debug-only and do not enable cleartext for release builds.
