# Hamza Bistro – Druckstation (Android)

Prints every accepted order on the shop's Bluetooth receipt printer from the
tablet beside it — with Chrome closed, the app in the background and the
screen off. It does what the site's print station does on a page
(`src/app/services/print-station.service.ts` in
[hamza-bistro-web](https://github.com/asibkamalsada/hamza-bistro-web)), from
an Android foreground service, which is what a web page cannot be
([hamza-bistro-web#62](https://github.com/asibkamalsada/hamza-bistro-web/issues/62)).

It is installed from an APK on the shop's own tablets, never through Google
Play. Accepting or refusing orders stays with the website and Telegram.

## How it works

```
 order accepted (phone, tablet, Telegram)
        │
        ▼
 Supabase ── Realtime: "an accepted order changed" ──►  PrintStationService
    ▲    ◄── every 25 s: accepted orders not printed ──   (foreground service,
    │    ◄── print_station_seen, at least once a minute     connectedDevice,
    │    ◄── claim_print(order) ─────────── "claimed" ──►   started on boot)
    │    ◄── print-ticket edge function ── ESC/POS bytes ─►       │
    │    ◄── finish_print(order, true | false)                   │ BLE, 20-byte writes
    │                                                            ▼
    │                                                   CY-BX58D receipt printer
```

- **The same protocol as every printing device**
  (`supabase/migrations/20260930150000_order_printing.sql`): the station
  registers with `print_station_seen` (label `Android-App`, a random id kept
  on the tablet), takes each order with `claim_print` before printing it —
  only `claimed` prints, `printed` and `moved` are never retried, `busy` is
  looked at again next time — and says how it went with `finish_print`.
  With the site's station switched on somewhere too, each order still prints
  exactly once. Switching printing off in the app calls `print_station_off`,
  which takes it off "Druckstationen" on `/orders/settings`.
- **The same ticket as the site.** The bytes come from the `print-ticket`
  edge function, which builds them with the site's own `receipt.ts`. The app
  has no ticket layout of its own, so changing the ticket is a deploy of the
  function, not a new APK on every tablet.
- **Printer off:** the ticket is given back (`finish_print(…, false)`), the
  round stops, and the next look — Realtime or the 25-second poll — tries
  again, so switching the printer on is all it takes. Meanwhile the site's
  "Bon nicht gedruckt" push goes out after two minutes, saying the station
  is running.
- **Printed but not recorded** (the network went at the wrong moment): kept
  in a small file on the tablet and reported at the next look, never printed
  twice.
- **Switching on** does not print the whole evening again: what is accepted
  at that moment is left to others, as with the site's switch.

### The code

- [`core/`](core) — plain Kotlin, tested on the JVM: Supabase Auth
  (`SupabaseAuth`, `SessionManager`), the queue and the protocol
  (`SupabasePrintBackend`), Realtime (`OrdersRealtime`), and the loop that
  decides what to print (`PrintStation`).
- [`app/`](app) — what only Android can do: the foreground service and the
  boot receiver (`station/`), the BLE printer and the scan (`printer/`), the
  Keystore-sealed session (`security/`), and the setup screen (`ui/`).

## Security

The app runs unattended on a tablet that lies on a counter all evening, so it
is built to give away as little as possible if the tablet, or the app, falls
into the wrong hands:

- **A print account, not a staff account.** It signs in as an account in
  `public.print_accounts` (`20260930180000_print_accounts.sql` in
  hamza-bistro-web). That account can read the accepted orders nobody has
  printed yet — and only while they are — and take part in printing. It
  cannot open `/orders`, accept or cancel anything, change the menu, read
  older orders, or take other print stations off the list. It never holds
  the service-role key; every call carries the account's own token and the
  database's policies decide.
- **No password on the device.** The password is typed once and sent once;
  what is kept is the refresh token, sealed with AES-256-GCM under a key in
  the Android Keystore (StrongBox where the tablet has it). The key never
  leaves the hardware, so a copy of the app's files is worth nothing on any
  other device. Signing out ends the session on the server as well.
- **Nothing leaves in a backup.** Backups and device-to-device transfer are
  off for all of the app's data.
- **HTTPS only**, trusting Android's own certificate authorities and none a
  user or a profile added, so a proxy with its own CA on the tablet cannot
  read the traffic.
- **Least surface.** Nothing is exported but the launcher screen; the boot
  receiver only hears the system. Bluetooth scanning is declared as never
  used for location. The setup screen cannot be screenshotted and ignores
  taps through other apps' overlays. Releases are minified with debug
  logging stripped, and logs never carry a token or anything off a ticket.
- **The captcha.** The site's sign-in is protected by Cloudflare Turnstile,
  and Supabase refuses a password sign-in without its token. The app shows
  the same widget in a WebView that shows one fixed page, cannot navigate,
  cannot call into the app (the app reads the token out) and is wiped when
  it closes. Refreshing a session needs no captcha, so it is shown only at
  sign-in.
- **Supply chain.** Dependencies come only from the repository that owns
  their group, Dependabot proposes updates, and CI's actions are pinned to
  commits with a read-only token.

The Supabase URL, its publishable key and the Turnstile site key in
[`gradle.properties`](gradle.properties) are the public values the website
ships in its own code; none of them is a secret.

## Setting it up

### 1. The server side (once)

In hamza-bistro-web, on the branch that brings the Android station:

1. Merge it, so `20260930180000_print_accounts.sql` reaches the database.
2. Deploy the ticket function (with JWT verification, the default):

   ```
   npx supabase functions deploy print-ticket --project-ref <your-project-ref>
   ```

3. Make the print account. Sign up on the website with an address of its
   own and confirm it — a Gmail "plus" address of the shop's mailbox works
   (`<shop-mailbox>+<alias>@gmail.com` arrives in the shop's inbox) —
   then, in the Supabase SQL editor:

   ```sql
   insert into public.print_accounts (user_id, label)
   select id, 'Tablet an der Kasse' from auth.users
   where email = '<shop-mailbox>+<alias>@gmail.com' and email_confirmed_at is not null;
   ```

   Do not add it to `staff`. To take the right away, delete the row (or the
   account).

### 2. The tablet

1. Install the APK: on the tablet, download `druckstation-….apk` from the
   latest of the repository's
   [releases](https://github.com/asibkamalsada/hamza-bistro-android/releases/latest)
   (see "Building" below), open it and allow installing from that source
   when Android asks. A newer release installs the same way, over the old
   one, and keeps the sign-in and the printer.
2. Open **Druckstation**, sign in with the print account (wait for the
   captcha's tick first).
3. Switch the printer on, **Drucker suchen**, and tap the printer — its
   model name, `CY-BX58D-…`. Allow "Geräte in der Nähe" when asked.
4. **Testdruck**: umlauts as umlauts, one full line of digits, and a QR code
   a phone can scan.
5. Switch on **Jede angenommene Bestellung drucken** and allow notifications.
   The permanent notification "Druckstation läuft" appears.
6. **Hintergrundbetrieb erlauben**, and confirm. On Samsung and Xiaomi also
   follow [dontkillmyapp.com](https://dontkillmyapp.com) for the model
   (Samsung: *Settings → Battery → Background usage limits → Never sleeping
   apps*, add Druckstation; Xiaomi: *Autostart* on and *Battery saver: No
   restrictions*), or those makers stop the app anyway.
7. In Chrome on the same tablet, switch **"Jede angenommene Bestellung
   drucken"** off on `/orders/settings`: the printer takes one connection at
   a time, and the two would fight over it.

After a reboot the station starts by itself once the tablet has been
unlocked for the first time — before that, Android keeps the app's
encrypted data locked. A tablet without a screen lock gets there by itself.

## Building

The APK is built by CI ([`.github/workflows/android.yml`](.github/workflows/android.yml))
on every push: the debug APK is attached to each run.

For the release APK, add the release key to the repository's secrets once:

| Secret | What |
| --- | --- |
| `HB_KEYSTORE_BASE64` | the keystore, `base64 -w0 release.jks` |
| `HB_KEYSTORE_PASSWORD` | its password |
| `HB_KEY_ALIAS` | the key's alias |
| `HB_KEY_PASSWORD` | the key's password |

Every push to `main` — a merged pull request too — then builds a signed
release APK and publishes it as a GitHub release: tag `v0.1.<run>`, the APK
as `druckstation-0.1.<run>.apk`, and the pull requests merged since the
release before as its notes. `<run>` is the workflow's run number, which is
also the APK's `versionCode`, so each release installs over the one before;
the tablet shows the same `0.1.<run>` under App info. The `0.1` is set in
[`app/build.gradle.kts`](app/build.gradle.kts). Without the key, `main`
builds an unsigned APK that no tablet installs, publishes nothing, and says
so in a warning on the run.

Make the key once and keep it safe outside the repository — Android installs
an update only when it is signed with the same key:

```
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias druckstation
```

Locally, with the Android SDK: `./gradlew :core:test :app:assembleDebug`,
or with the environment variables `HB_KEYSTORE_FILE`, `HB_KEYSTORE_PASSWORD`,
`HB_KEY_ALIAS` and `HB_KEY_PASSWORD` set, `./gradlew :app:assembleRelease`.

Requirements: Android 12 or later on the tablet; JDK 17+ to build.
