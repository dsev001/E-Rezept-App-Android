# Lokale TU-Umgebung: App und Integrationstests gegen `*.orb.local`

Branch: `claude/quizzical-knuth-a6c27d`

Diese Doku richtet sich an Entwickler, die das Projekt noch nicht kennen. Sie beschreibt, was dieser Branch ergänzt und wie man es benutzt.

## 1. Zweck

Der Branch ermöglicht zwei Dinge gegen einen **lokalen TU-Stack** (statt der gematik-Cloud):

1. Die Repo-internen Integrationstests (`IdpIntegrationTest`, `TruststoreIntegrationTest`) laufen lassen.
2. Die App im **Android-Emulator** gegen den lokalen Stack betreiben.

Der lokale Stack läuft in OrbStack als Docker-Container. Relevante Hosts:

- `idp-server.ref-idp-server.orb.local` — Referenz-IDP (ref-idp-server).
- `fachdienst.<compose-projekt>.orb.local` — Fachdienst (FD).

Wichtig: `*.orb.local` wird **nur auf dem Host** aufgelöst (OrbStack-Resolver). JVMs (Tests) und der Emulator sehen diesen Resolver nicht. Das ist die zentrale Hürde, die dieser Branch löst.

## 2. Alle Änderungen sind Debug-only

Jede Lockerung ist auf `BuildConfigExtension.isInternalDebug` (= `BuildKonfig.INTERNAL && BuildConfig.DEBUG`) oder den `debug`-Source-Set begrenzt. **Release-Builds sind unverändert.** Der Branch sollte lokal bleiben und nicht nach `master` gemergt werden (er enthält maschinenspezifische Werte und schwächt Sicherheitsmechanismen im Debug-Build ab).

## 3. Voraussetzung: lokale Konfigurationsdateien

Zwei Dateien sind gitignored und müssen lokal vorliegen (aus dem Haupt-Checkout kopieren):

- `local.properties` — Android-SDK-Pfad (`sdk.dir=...`).
- `ci-overrides.properties` — speist über das `overrides()`-Gradle-Delegate die `BuildKonfig`-Werte.

Benötigte Keys in `ci-overrides.properties`:

```properties
# Endpunkte des lokalen Stacks
BASE_SERVICE_URI_TU=https://fachdienst.<projekt>.orb.local/
IDP_SERVICE_URI_TU=https://idp-server.ref-idp-server.orb.local/.well-known/

# TU-Trust-Anchor (aus fd/credentials/ca/rca.crt, siehe Abschnitt 7)
APP_TRUST_ANCHOR_BASE64_TEST=<base64>

# Test-JVM-Truststore (siehe Abschnitt 5)
LOCAL_TEST_TRUSTSTORE=/Users/<user>/.erp-android/local-test-truststore.p12

# DNS-Override für den Emulator (siehe Abschnitt 6)
ORB_LOCAL_DNS_OVERRIDES=fachdienst.<projekt>.orb.local=<ip>;idp-server.ref-idp-server.orb.local=<ip>
```

## 4. Integrationstests aktivierbar machen

Commits: `f94b297d`, `0c74563e`, `4f7dfbdb`, `fbd68de7`.

- **Gradle-Flags statt Hardcode:** `TEST_RUN_WITH_IDP_INTEGRATION` und `TEST_RUN_WITH_TRUSTSTORE_INTEGRATION` sind jetzt `overrides()`-basiert (`common/build.gradle.kts`). Default ist `false`; die Tests bleiben standardmäßig übersprungen (`Assume.assumeTrue`).
- **Aktivierung pro Lauf** über eine Gradle-Property:

```bash
./gradlew :core:testDebugUnitTest \
  --tests "de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest" \
  -PTEST_RUN_WITH_IDP_INTEGRATION=true

./gradlew :app:android:testGoogleTuInternalDebugUnitTest \
  --tests "de.gematik.ti.erp.app.vau.TruststoreIntegrationTest" \
  -PTEST_RUN_WITH_TRUSTSTORE_INTEGRATION=true
```

- **`IdpIntegrationTest`-Mocks repariert** (`fbd68de7`): Der Test war seit dem OCSP-/Trust-Chain-Umbau (Release 1.35.1) kaputt. Angepasst: `loadOcspResponse` gestubbt, `checkIdpCertificate`-Matcher auf 3 Argumente erweitert, echter `AccessTokenDataSource` statt strenger Mock, `IdpDeviceInfoProvider`-Properties gestubbt. Die Assertions sind unverändert.
- `IdpIntegrationTest` führt den vollständigen IDP-Flow mit der virtuellen Gesundheitskarte (`BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_*`) gegen den lokalen IDP aus.

## 5. OrbStack-CA in der Test-JVM

Commits: `0c74563e`, `4f7dfbdb`.

Problem: OrbStack terminiert `*.orb.local`-TLS mit der "OrbStack Development Root CA". Diese liegt nur im macOS-Keychain, nicht im JDK-`cacerts` → JVM-HTTPS scheitert mit PKIX-Fehler.

Lösung: Ein kombinierter PKCS12-Truststore (JDK-`cacerts` + OrbStack-CA) wird in alle Test-JVMs injiziert.

- Einmalig erstellen:

```bash
mkdir -p ~/.erp-android
security find-certificate -p -c "OrbStack Development Root CA" > /tmp/orbstack-ca.pem
JDK=$(dirname "$(dirname "$(readlink -f "$(which java)")")")
keytool -importkeystore -srckeystore "$JDK/lib/security/cacerts" -srcstorepass changeit \
  -destkeystore ~/.erp-android/local-test-truststore.p12 -deststoretype pkcs12 -deststorepass changeit -noprompt
keytool -importcert -noprompt -alias orbstack-dev-root -file /tmp/orbstack-ca.pem \
  -keystore ~/.erp-android/local-test-truststore.p12 -storepass changeit
```

- Verdrahtung: Die beiden Convention-Skripte `scripts/.../base-android-library.gradle.kts` und `base-android-application.gradle.kts` setzen `javax.net.ssl.trustStore` für alle `Test`-Tasks, **wenn** `LOCAL_TEST_TRUSTSTORE` in `ci-overrides.properties` gesetzt ist. Ohne den Key: No-op (für alle anderen unverändert).
- Hinweis: `javax.net.ssl.trustStore` **ersetzt** den Default-Truststore. Die Datei muss daher `cacerts` **plus** die lokale CA enthalten (nicht nur die CA).

## 6. Emulator erreicht `*.orb.local` über HTTPS

Commits: `4de6e459`, `f85b56b1`, `a3efa63e`, `5edfb9c1` (`f1b2bcf8` wurde durch `5edfb9c1` ersetzt).

Im Emulator scheiterte die App beim Start mit `UnknownHostException` auf `GET https://fachdienst.../VAUCertificate`. Drei aufeinander aufbauende Ursachen, drei Fixes — alle `isInternalDebug`-only:

### 6a. DNS
Der Emulator (slirp-NAT) löst `*.orb.local` nicht auf. `BlockingDns` (`app/features/.../di/BlockingDns.kt`) mappt jetzt `*.orb.local`-Hosts auf ihre OrbStack-Bridge-IPs. Die Map kommt aus `BuildKonfig.ORB_LOCAL_DNS_OVERRIDES` (neues Feld in `common/build.gradle.kts`, gespeist aus `ci-overrides.properties`, Format `host=ip;host=ip`). Routing vom Emulator zu den Bridge-IPs (z. B. `192.168.138.15:443`) funktioniert; OrbStack routet per SNI, daher passt das Zertifikat.

### 6b. TLS-Vertrauen
Ein Debug-Override `app/android/src/debug/res/xml/network_security_config.xml` vertraut für die Domain `orb.local` zusätzlich der gebündelten OrbStack-CA (`app/android/src/debug/res/raw/orbstack_dev_ca.pem`) neben den System-CAs.

### 6c. Certificate Transparency (CT)
Die App erzwingt CT an zwei Stellen; beide lehnen das nicht-CT-gelistete OrbStack-Dev-Zertifikat ab:

- **Globaler Provider** `installCertificateTransparencyProvider()` in `ErezeptApp` umhüllt den Trust-Manager. Auf targetSdk 35 ist die dafür nötige Hidden-API `RootTrustManager.isSameTrustConfiguration` **blockiert** → der Per-Domain-Anchor wird nicht angewendet → Fehler "Trust anchor for certification path not found". Eine Host-Exclusion hilft nicht, weil der Chain-Cleaner vorher läuft. Fix: Der globale Provider wird in internal-debug **nicht** installiert.
- **Per-Client-Interceptor** `addCertificateTransparencyInterceptor()` (`ClientBuilderModule.kt`). Die Appmattus-Exclusion `-"*.orb.local"` matcht in Version 2.5.42 keine mehrstufigen Hosts. Fix: In internal-debug `failOnError = false`.

Produktion behält volles CT.

### Verifikation
Emulator → `200 https://fachdienst.<projekt>.orb.local/VAUCertificate`, ohne Host-, Trust- oder CT-Fehler.

## 7. Voraussetzung aus `master`: Debug-Screen Virtual Health Card

Bereits in `master` (Commit `7e3d56ad`, hier nur als Kontext): Die Felder Zertifikat/Private Key im Debug-Screen (Einstellungen → "Nerd control room" → "Secret switches" → Virtual Health Card) werden aus `BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_*` vorbefüllt. Der Login mit virtueller Karte ist der primäre Testpfad für den Auth-Flow.

## 8. Betrieb und Wartung

- **Bridge-IPs ändern sich** bei Container-Neuerstellung. Neu ermitteln und `ORB_LOCAL_DNS_OVERRIDES` (beide ci-overrides-Kopien) aktualisieren, dann App neu installieren:
  ```bash
  dscacheutil -q host -a name fachdienst.<projekt>.orb.local
  ```
- **FD-Hostname ändert sich** mit dem OrbStack-Compose-Projektnamen (der vom Worktree abhängt). `BASE_SERVICE_URI_TU` entsprechend anpassen. Stabil wird es mit einem festen Compose-Projektnamen für den FD.
- **OrbStack-CA neu installiert** (z. B. nach OrbStack-Reinstall): `orbstack_dev_ca.pem` neu exportieren (Befehl aus Abschnitt 5) und Test-Truststore neu bauen.
- **fd-PKI neu generiert** (`fd/credentials/gen-pki.sh`): `APP_TRUST_ANCHOR_BASE64_TEST` neu setzen:
  ```bash
  openssl x509 -in fd/credentials/ca/rca.crt -outform DER | base64 | tr -d '\n'
  ```

## 9. Geänderte Dateien (Überblick)

- `common/build.gradle.kts` — neue Felder `TEST_RUN_WITH_*`, `ORB_LOCAL_DNS_OVERRIDES`.
- `scripts/src/main/kotlin/base-android-library.gradle.kts`, `base-android-application.gradle.kts` — Test-JVM-Truststore.
- `app/features/.../di/BlockingDns.kt` (+ `BlockingDnsTest.kt`) — DNS-Override.
- `app/features/.../di/ClientBuilderModule.kt` — DNS-Verdrahtung, CT-Interceptor.
- `app/features/.../ErezeptApp.kt` — globaler CT-Provider in internal-debug ausgelassen.
- `app/android/src/debug/res/xml/network_security_config.xml`, `res/raw/orbstack_dev_ca.pem` — Debug-CA-Vertrauen.
- `core/.../idp/usecase/IdpIntegrationTest.kt` — Mock-Reparatur.
