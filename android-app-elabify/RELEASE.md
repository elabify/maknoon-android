# Releasing Maknoon (Android)

Two test-distribution channels, both fed from one release signing key:

- **Google Play - Internal testing** (the TestFlight equivalent): upload an
  **AAB**, add up to 100 testers by email or opt-in link, builds go live in
  minutes, and it bypasses the 14-day closed-testing gate that new personal
  Play accounts otherwise hit before production.
- **Direct APK via GitHub Releases + Obtainium** (best for GrapheneOS testers,
  who often have no Play Store): testers paste the repo URL into Obtainium and
  get auto-updates on each release. Fully GMS-free.

> **Signing-compatibility gotcha.** A device that installed from Play (signed by
> Google's Play App Signing key) cannot update from a direct APK (signed by our
> key), and vice-versa: Android refuses the install on a signing-key mismatch.
> Keep each tester on **one** channel.

## One-time: create the upload/release keystore

Generate it once and keep it forever (losing it means you can't update the app).
Run interactively so the passwords never land in shell history:

```sh
cd android-app-elabify
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias maknoon-release \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `android-app-elabify/keystore.properties` (gitignored):

```properties
storeFile=keystore/release.jks
storePassword=<the store password you just set>
keyAlias=maknoon-release
keyPassword=<the key password you just set>
```

`keystore/`, `*.jks`, and `keystore.properties` are all gitignored. Back up
`release.jks` + its passwords somewhere safe (password manager); this single key
is your app's identity on both channels.

## Each release

1. Bump the version in `app/build.gradle.kts` (`versionCode` must strictly
   increase for every upload; `versionName` is the human label, e.g. `0.6.1`).
2. Build the artifacts (needs `JAVA_HOME` on a JDK 17, e.g. Zulu 17):

   ```sh
   export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
   cd android-app-elabify
   ./gradlew bundleRelease     # -> app/build/outputs/bundle/release/app-release.aab  (Play)
   ./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk      (Obtainium/direct)
   ```

   Confirm the APK is signed with the release key (not debug):
   `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`

### Channel A: Google Play internal testing

1. Play Console -> create the app (one-time): name, default language, app/game,
   free/paid, declarations.
2. Enrol in **Play App Signing** (default for new apps) - Google holds the real
   signing key; the `.jks` above is your *upload* key.
3. **Testing -> Internal testing -> Create new release**, upload
   `app-release.aab`, add release notes, roll out.
4. **Testers** tab: add an email list (or your Google Account), copy the opt-in
   URL, share it. Testers open the link, accept, install from Play.
5. Fill the required store-listing / content sections as the Console prompts
   (data safety, privacy policy URL, content rating) - internal testing tolerates
   some of these being in-progress; production does not.

### Channel B: GitHub Releases + Obtainium

1. Create a GitHub Release (tag e.g. `android-v0.6.1`) and attach
   `app-release.apk`.
2. Testers install **Obtainium** (itself from GitHub/Accrescent), then "Add app"
   with the releases repo URL; it tracks new releases and prompts to update.

> Obtainium needs read access to the releases. `elabify/maknoon-android` is private, so
> either: (a) publish the APK to a dedicated **public** releases repo, or
> (b) have testers add a GitHub PAT in Obtainium for the private repo. Decide
> this before sharing the link. (TODO: pick the public releases repo; a CI
> workflow can then build + sign + publish on tag.)

## Notes

- The app is **GMS-free** (no `play-services`/`firebase`); both channels deliver
  a plain APK that runs on GrapheneOS. Play distribution itself still works.
- `isMinifyEnabled = false` for now. R8/minification is a later optimization and
  must be validated against the JNI/native (elabify-core, ledger/trezor) and
  kotlinx.serialization paths before enabling.
- Confirm Play's current **target API** floor in the Console each year; we are at
  `targetSdk 35`.
