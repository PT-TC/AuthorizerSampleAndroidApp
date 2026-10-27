# AuthorizerSampleAndroidApp

Minimal PoC app to test passkey sign-in against the local Authorizer IdP (`~/authorizer-local`).

Flow: button → Chrome Custom Tab → Authorizer `/authorize` (OIDC code + PKCE S256) → passkey sign-in →
redirect to an app-owned **loopback listener** (`http://127.0.0.1:<port>/callback`, RFC 8252; Authorizer rejects custom-scheme redirects) →
app exchanges the code at `/oauth/token` → shows `id_token` claims. Client: `local-passkey-poc` (public, PKCE, no secret).

## Run
```bash
# 1. Authorizer must allow the loopback redirect (port is ignored for loopback)
cd ~/authorizer-local && docker rm -f authorizer-local
docker run -d --name authorizer-local -p 8080:8080 -p 9091:9091 -v "$PWD/data:/data" quay.io/authorizer/authorizer:latest \
  --database-type=sqlite --database-url=/data/authorizer.db --jwt-type=RS256 \
  --jwt-private-key="$(cat priv.pem)" --jwt-public-key="$(cat pub.pem)" \
  --admin-secret=admin --encryption-key=local-dev-encryption-key-not-prod \
  --client-id=local-passkey-poc --client-secret=local-passkey-poc-secret \
  --allowed-origins=http://localhost:8080 --url=http://localhost:8080 --env=development \
  --redirect-uris=http://127.0.0.1/callback,http://localhost:8080/app

# 2. Device -> host port forward, build, install, launch
adb reverse tcp:8080 tcp:8080
cd /$HOME/AuthorizerSampleAndroidApp
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.pt.authorizersample/.MainActivity
```

## Test
1. Tap **Sign in with Authorizer**. Custom Tab opens `http://localhost:8080/...` (served over `adb reverse`).
2. First time: **Sign Up** → "Set up MFA" → **Passkey** → *Add a passkey* → confirm with fingerprint/PIN.
3. Tap **Return to app** (or it auto-returns): app shows `SIGNED IN ✅` + claims.
4. Repeat; this time use **Sign in with a passkey** (no password).

## Requirements / notes
- Device needs a screen lock and a passkey provider (Google Password Manager) with Chrome as default browser. Sanity check by opening `http://localhost:8080/app/` in Chrome first.
- RP ID = `localhost`. For a tunnel (https host), change the base-URL field in the app and restart Authorizer with `--url`/`--allowed-origins` set to it.
- Cleartext is allowed only for `localhost` (`network_security_config.xml`). PoC only; no secrets in the app.
