# Release signing key (CI)

This directory holds the **release signing key used by GitHub Actions** to produce
`release-signed` APKs, plus the `keystore.properties` that points at it.

| File | Purpose |
|---|---|
| `release-keystore.p12` | PKCS12 keystore, RSA 4096, self-signed, valid until 2055-01-01 |
| `keystore.properties` | alias / passwords / storeType consumed by `app/build.gradle.kts` |

Certificate subject: `CN=NativeRunner Release, OU=Release, O=NativeRunner, C=JP`
SHA-256 fingerprint: `81:39:18:80:38:67:13:F8:55:26:C2:20:80:FF:A4:3A:80:0A:B2:8B:75:5C:DA:B9:53:4B:7A:2B:CD:6F:E8:E6`

## Read this before shipping

- This is **not** the upstream GameNative signing key. APKs built here share the
  `app.gamenative` application id but have a different signature, so they **cannot
  update an existing GameNative install** — uninstall the old app first.
- The key lives in the repository, which means anyone with repo access can produce
  builds signed with it. That is fine for a personal/test build channel, not for a
  public release channel.
- To move the key out of the repo: add repository secrets
  `KEYSTORE_BASE64` (base64 of the `.p12`), `KEYSTORE_KEY_ALIAS`,
  `KEYSTORE_KEY_PASSWORD` and `KEYSTORE_STORE_PASSWORD`
  (Settings → Secrets and variables → Actions). The workflow prefers those secrets
  when `KEYSTORE_BASE64` is set and only falls back to this file otherwise.
  After that, delete `release-keystore.p12` and generate a new key.
