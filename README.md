# Hamza Team (Android) – the shop's tablet, and the drivers' phones

The shop's own app for working the orders: on **the one tablet in the
shop**, where orders are accepted and from which they print, and if wanted
on the drivers' phones. No cart — everything the staff pages of
[hamza-bistro-web](https://github.com/asibkamalsada/hamza-bistro-web) had
(the queue, the history, what is sold out, the delivery hours, who hears
about orders), and the one thing a web page cannot do:

**an alarm that rings until somebody answers the order.** In a loop, on
the alarm stream, over the lock screen with the screen switched on, like an
incoming call — through "silent", and through Do Not Disturb as long as
alarms are allowed there (Android's default), with the volume turned up
while it rings if somebody turned it down. It stops on
every device the moment the order is accepted or declined anywhere: in the
app, on the website, in Telegram.

It is also the print station it grew out of: the device beside the
Bluetooth receipt printer prints every accepted order with the screen off,
and says out loud when a ticket did not come out.

Installed from an APK, never through Google Play.

## Why an app, after the PWA

The installed `/orders` (the PWA) gets a Web Push per order and one every
30 seconds while it waits. In practice that was not enough:

- **A push is a notification, not an alarm.** It plays once, on the
  ringer, and a phone on silent or in an apron pocket over a fryer does not
  make it heard. The reminders help, but each is still one ping.
- **Whether it arrives at all depends on the phone.** Web Push travels
  through Chrome and Google's push service, and each maker's battery saver
  has its own idea about both. Some devices were reliable, some were not.
- **Sound needs a tap** ("Schicht starten") and stops when the page sleeps.

The app does not depend on a push: while a device is on shift, a
foreground service keeps the queue in view itself — Supabase Realtime,
with a 25-second poll underneath, as `/orders` does — and rings from the
device. No Firebase, no Google services, no new secrets on the server.

## One tablet in the shop

There is **one tablet in the shop, for the kitchen and the counter alike**,
and that is enough — no separate device for the kitchen, the till or
anybody else. It is where it happens:

- **orders are accepted (or declined) on it** — it rings until somebody
  does;
- **it prints** every accepted order, on the Bluetooth printer beside it;
- it moves them on, "Unterwegs" / "Abholbereit", then "Geliefert · Bar /
  Karte" or "Abgeholt · Bar / Karte": delivered and how it was paid, in one
  tap, for the Kassensturz.

It signs in with a staff account of its own (see Security), stays on
shift, stays plugged in and keeps its screen on.

**A driver's phone** can have the app as well, with the driver's own staff
account: for the "Unterwegs" list with the route and the phone number, and
for "Bar" / "Karte" at the door. It is optional — the tablet does not need it —
and a driver will usually want the alarm set to "once" or off, so that the
tablet stays the place orders are taken.

| Device                          | Signs in as                               | Does                                                                                  |
| ------------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------- |
| The tablet in the shop          | a staff account of its own                | Accepts orders, loud alarm until answered, prints every accepted order, screen on    |
| A driver's phone (optional)     | the driver's own staff account            | The queue, route, call, "Bar" / "Karte"; alarm "once" or off; own night window      |

Which one a device is follows from the account: staff get the queue. A
print account (`public.print_accounts`) gets the printer setup and nothing
else — what the tablet ran on as the Druckstation, and still possible for a
tablet that should only print; the shop's tablet now needs the staff
account, because it accepts the orders.

### The queue

What `/orders` shows, under the same three headings — **Neu** (accept or
decline), **In der Küche**, **Unterwegs & abholbereit** — read by the same
rules, ported to Kotlin with the site's own test cases
([`StaffQueue`](core/src/main/kotlin/de/hamzabistro/printstation/core/StaffQueue.kt),
[`Eta`](core/src/main/kotlin/de/hamzabistro/printstation/core/Eta.kt)):

- one tap accepts, with the minutes on the button; the filled one is the
  estimate from the dishes and the delivery ring, as on the site;
- a pre-order is accepted for its time; declining asks why, and the
  customer's email says it;
- "fällig 18:32 · noch 12 Min.", red once late; "kochen ab 18:05" on a
  pre-order;
- every step waits out the undo window (5 s by default) and only lands if
  the order is still where this device saw it;
- **+10 Min.** and **+20 Min.** on every accepted card, for a kitchen or a
  driver running behind: through the same undo window, then `delay_order`,
  which adds the minutes in the database, so two phones tapping at once
  both count. The card then reads "fällig 18:45 · verschoben +10 Min.", a
  pre-order's "kochen ab" moves with it, and a customer who asked for
  updates gets an email
  ([`20261002170000_order_delay.sql`](https://github.com/asibkamalsada/hamza-bistro-web/blob/main/supabase/migrations/20261002170000_order_delay.sql));
- the phone number opens the dialler, the address opens the route in the
  chosen map app, the note for the door has a box of its own;
- "Bon gedruckt 18:05" on an accepted order, "Bon drucken" on a device with
  the printer;
- held sideways on a tablet, the three headings stand side by side, like
  a kitchen pass.

### Everything else the website's staff pages had

The website still has `/orders`, `/orders/settings`, `/orders/hours` and
`/menu-admin`, but links to none of them any more: they are a fallback,
reached by typing the address. Everything they do is here, behind the
queue's top bar (**Mehr** on a phone):

- **Letzte Bestellungen** — the last 50 orders whatever became of them,
  and what today's delivered orders came to, for the call about yesterday's
  order and for cashing up. Read when opened, not polled; the cards are for
  reading (the phone number and the route still open). A delivered order
  that was delayed says so: "fällig 18:45 · verschoben +10 Min."; a
  delivered one says how it was paid: "Bezahlt: Bar / Karte / unbekannt".
- **Kassensturz** — for today or a day picked: per driver (account and
  device name), how many orders, cash, card and unknown, with the order
  numbers behind each sum a tap away, and **Teilen** as plain text through
  the share sheet. "Bar" or "Karte" taken back within the undo window
  records nothing; after it, a correction is made in the Supabase
  dashboard only.
- **Auswertung** — this week, last week, this month, last month or a
  range picked (at most 366 Leipzig days): Umsatz, Bestellungen,
  Ø Warenkorb and Verspätet % on top; per day, Lieferung vs Abholung, the
  rings, the best sellers (top 10, all a tap away, by Menge or Umsatz), the
  busy hours as a weekday × hour grid, median and p90 of each step's time,
  cancellations by who and why, discounts and payments. **Teilen** sends
  the summary as text; **CSV** sends the days, the best sellers and the
  rings as three files for German Excel (`;`, decimal comma). Counts and
  sums only, from `staff_report()`.
- **Speisekarte** — the whole menu, in five tabs. What is sold out, as on
  `/menu-admin`: a dish; a single choice, which goes off in every dish that
  offers it; an ingredient, which takes everything made of it off at once
  and, switched back on, puts back only what was not sold out on its own.
  Behind **Bearbeiten** a dish's name, description, price, **Pfand**,
  pickup discount, prep time, Füllmenge, **Zusatzstoffe**, category,
  labels, allergens, photo and its groups of choices (in order); **+
  Gericht** in a category makes a new one from the same form, starting
  from what most of the category's dishes have. **Archivieren** asks
  first, takes the dish off the menu and out of customers' sight (past
  orders keep it), and **Archiviert** lists what was archived, to restore.
  **Reihenfolge** puts dishes, choices and categories in order with arrows.
  On **Optionen** a group is made (exactly one or any number of choices),
  given choices with their extra charge and Zusatzstoffe, and ticked onto
  dishes. **Kategorien** adds, renames, orders and photographs the
  sections; **Angebote** is the week as seven rows, each a category or one
  dish so much cheaper, or none. Everything goes through the staff
  functions of `20261003110000_menu_editing.sql` (hamza-bistro-web#116);
  their refusals read as "Nicht gefunden", "Name schon vergeben" (with an
  offer to restore the archived dish of that name), "Wert nicht erlaubt",
  "Erst wiederherstellen" and "Erst die Gruppe von den Gerichten nehmen".
  A photo picked in a form is cropped square, shrunk to 256 px WebP on the
  device and uploaded to the `menu` bucket, and lands on the menu when the
  form is saved. What an ingredient is used in is set behind the count
  beside it. **Allergens** (LMIV, the 14 lettered a–n) are ticked in the
  dish's form, and per choice behind **Allergene** on the choices tab;
  nothing ticked stays "not stated" (the menu says "Angaben folgen"), and
  only the separate **Keines der 14** saves "none". Each row shows its
  letters or a red "noch nicht angegeben", and both tabs count what is
  still missing with a filter to work through it. A drink's row shows
  "0,33 l · 6,52 €/l", the price per litre without the Pfand, as the menu
  prints it.
- **Lieferzeiten** — the open/closed switch, **Unbeantwortete Bestellungen
  ablehnen** (off, 5, 10 — recommended —, 15, 20 or 30 minutes; saved on
  tap, for every device and the website), closures planned ahead (a
  holiday, a day off; also open-ended), and the week's delivery hours, saved
  all at once, on the quarter hour.
- **Einstellungen**, besides this device's own settings: **Wer von
  Bestellungen erfährt** (the devices on shift with this app, with how they
  ring and when they were last heard from — a lost one can be removed — and
  the phones and browsers still getting the website's pushes), the
  **Druckstationen**, and the **Adressprüfung** with its live test. A
  failing address check also puts one line above the queue.

### Open, paused, closed

Above the queue, the same line as on `/orders`: whether customers can order
right now, and the switch for it
([`ShopHours`](core/src/main/kotlin/de/hamzabistro/printstation/core/ShopHours.kt),
`ui/ShopSwitch.kt`).

- **Open**: "Bestellungen werden angenommen – bis 20:00 Uhr", and
  **Pausieren …** — 30 minutes, an hour, the rest of the day, or until
  further notice. Two taps, the second saying for how long. Beside it,
  **Viel los …** — busy mode: +15, +30 or +45 minutes on every promise,
  for 30 minutes, an hour or the rest of the day; the line then reads
  "· +30 Min. bis 20:15 Uhr", and **Wieder normal** ends it. The
  pre-selected accept button adds the minutes (`Eta.estimate(...,
  busyMinutes)`, after the rounding, as the site's `estimateEtaMinutes`);
  when a pre-order has to go on does not. Busy mode ends by itself at its
  time, here on the device's clock too (`shop_busy` in
  [`20261002160000_busy_mode.sql`](https://github.com/asibkamalsada/hamza-bistro-web/blob/main/supabase/migrations/20261002160000_busy_mode.sql)).
  Below it, **Alle +15 Min.**, while there is an accepted order for right
  away to move: busy mode lengthens the promises still to be made, this
  one the promises already made (`delay_open_orders`). It asks first, with
  how many orders, because every one of those customers who asked for
  updates gets an email.
- **Closed**: in red, until when, and who closed it, with **Wieder öffnen**
  one tap away.
- **Outside the hours**: when orders come in again; pre-orders still do.

Closing is the website's (`shop_close` in
[`20261001150000_shop_hours.sql`](https://github.com/asibkamalsada/hamza-bistro-web/blob/main/supabase/migrations/20261001150000_shop_hours.sql)):
the checkout stops taking orders for that time, for right now and for
later, and the database refuses them too. Orders already in the queue are
not touched; closing says how many were booked for a time inside it, so
somebody accepts or declines them. The line is read with the queue, every
25 seconds, so a closure made on another phone or on the website shows
here within one poll.

The week's delivery hours and closures planned ahead (a holiday, a day
off) are set under **Lieferzeiten**, which the line also opens. Without that
migration the line is simply not there.

### The alarm

[`AlarmPolicy`](core/src/main/kotlin/de/hamzabistro/printstation/core/AlarmPolicy.kt)
decides, every few seconds and on every change of the queue:

- **A new order rings in a loop** — the first hour after it came in, and a
  pre-order still waiting in the hour before its time, the same windows as
  the server's reminders. Accepted or declined anywhere, it stops
  everywhere, because it leaves the queue every device reads.
- **"Stumm"** silences what is ringing for a minute (settable); an order
  still waiting after that rings again, and one that arrives meanwhile rings
  at once.
- **Per device**: ring in a loop, say so once like a message, or nothing;
  the sound (beeps, bell, siren — the site's three, synthesised — or the
  device's own alarm tone); full volume while ringing; vibration; a night
  window (22:00–09:00 to start with) in which an order is a silent
  notification instead.
- **The tablet's chimes**, once each: an accepted pre-order that has to go
  on now; this device's printer not printing a ticket (after half a
  minute, again every three while it lasts — the server's push about it
  comes after two); the queue unreadable for two minutes, which is a
  device that would not hear about the next order.
- **Auto-decline**: with it switched on under **Lieferzeiten**, the
  database declines an order nobody answers in time
  ([`20261002150000_auto_decline.sql`](https://github.com/asibkamalsada/hamza-bistro-web/blob/main/supabase/migrations/20261002150000_auto_decline.sql)).
  A waiting card counts down to that moment ("wird in 6 Min. automatisch
  abgelehnt", red for the last two; a pre-order more than an hour off says
  when instead), read from the server's own `auto_decline_at`. Two minutes
  before, the alarm **escalates once**: a warble none of the alarm sounds
  is, over the loop and through "Stumm", and a harder vibration while it
  keeps ringing ([`AutoDecline`](core/src/main/kotlin/de/hamzabistro/printstation/core/AutoDecline.kt)).
  Not on a device set to stay silent about new orders. **Letzte
  Bestellungen** shows such an order as "keine Antwort — automatisch
  abgelehnt", for a call back.
- **A ticket that printed nowhere**: on every device that does not print
  itself, an accepted order with no ticket two minutes on chimes once,
  while a print station registered before it was accepted is meant to
  print it — the rule of the "nicht gedruckte Bons" push the server sends
  the phones. The print stations are read every two minutes.

The full-screen alarm shows the order number, what to cook and the total
— never the customer's name, phone or address, which stay behind the lock
screen — and the accept buttons, so an order can be taken without
unlocking.

### Who hears about orders

A device on shift says so once a minute
([`staff_app_seen`](https://github.com/asibkamalsada/hamza-bistro-web/blob/main/supabase/migrations/20261001120000_staff_app_devices.sql)),
so **Einstellungen → Wer von Bestellungen erfährt** on every device (and
`/orders/settings` on the website) lists it, with how it rings and when it
was last heard from, and says in red when nobody would hear about the next
order. A device not heard from for three minutes is shown as not
listening. Ending the shift takes it off the list; any staff device can
remove a lost one.

## How it works

```
                 Supabase (the site's project)
   ┌──────────────────────────────────────────────────────────┐
   │ orders ── Realtime ──┐        staff_app_seen  (1/min)  ◄─┼── on shift
   │   ▲                  │        print_station_seen (1/min)◄─┼── printing
   │   │ PATCH status     │        claim_print / finish_print◄─┼──┐
   │   │ (from status)    ▼        print-ticket function ─────┼──┤ ESC/POS
   └───┼──────────────────┼───────────────────────────────────┘  │
       │                  │                                       │
   ┌───┴──────────────────┴────── ShiftService (foreground) ─────┴──┐
   │ OrderQueue ── QueueState ──► AlarmController ──► AlarmPlayer     │
   │   (Realtime + 25 s poll)       (AlarmPolicy)     (alarm stream,  │
   │                                     │             loop/chime)    │
   │                                     └─► full-screen notification │
   │ PrintStation ─────────────────────────────► BlePrinter ──► CY-BX58D
   └──────────────────────────────────────────────────────────────────┘
        ▲                        ▲
   QueueScreen / AlarmActivity: the same queue, the same steps (PendingSteps)
```

One foreground service, one permanent notification ("Schicht läuft · 2 neu
· 3 in der Küche"), with either or both parts:

- **the shift** — type `specialUse` — while a staff account is on shift;
- **printing** — type `connectedDevice`, which Android 14 requires of a
  service holding a Bluetooth link — while printing is switched on and a
  printer chosen.

It starts again by itself after a reboot and after an update, and holds
the CPU awake while it runs, so an order arriving with the screen off rings
in seconds.

### Printing

Unchanged from the print station, and the same protocol as every printing
device (`supabase/migrations/20260930150000_order_printing.sql` in the
site): the station registers with `print_station_seen` (label
`Android-App`), takes each order with `claim_print` before printing it and
reports with `finish_print`, so with the site printing somewhere too each
order still prints exactly once. The bytes come from the `print-ticket`
edge function, built with the site's own `receipt.ts`, so changing the
ticket is a deploy of the function, not a new APK. Printer off: the ticket
is given back and tried again at the next look; printed but not recorded:
kept in a small file and reported later, never printed twice.

**Tütenzettel nach jedem Bon** (Einstellungen → Bondrucker, per device,
off by default) asks `print-ticket` with `"bagSlip": true`: the answer is
the ticket and then the bag slip, one job for the printer, so it is still
one claim and one finish per order — for automatic and by-hand prints. A
`print-ticket` from before the slip ignores the flag and prints the ticket
alone; one that refuses it (a 400) is asked again for the plain ticket, and
the app logs it.

### The code

- [`core/`](core) — plain Kotlin, tested on the JVM: Supabase Auth
  (`SupabaseAuth`, `SessionManager`), the queue (`StaffOrder`,
  `StaffQueue`, `Eta`, `SupabaseStaffBackend`, `OrderQueue`), the history
  and takings (`History`), the Kassensturz (`CashUp`), the Auswertung (`Report`), opening and closing the shop, closures and the
  week (`ShopHours`, `SupabaseShopBackend`), the menu, choices and
  ingredients and editing them (`Menu`, `MenuEditing`, `Allergens`, `DrinkVolume`, `PhotoCrop`), who hears about orders, the print
  stations and the address check (`Devices`), the alarm's rules
  (`AlarmPolicy`), Realtime (`OrdersRealtime`), printing
  (`SupabasePrintBackend`, `PrintStation`), and the check for a newer
  release (`AppUpdate`).
- [`app/`](app) — what only Android can do: the service and the boot
  receiver (`station/`), the alarm's sound and screen (`alarm/`), the undo
  window (`queue/`), the BLE printer (`printer/`), the Keystore-sealed
  session (`security/`), and the screens (`ui/`).

The package is still `de.hamzabistro.printstation`: it is the application
id, and changing it would make the new app a second install beside the
old one instead of an update of it.

## Security

The app runs on devices that lie on a counter all evening and on phones in
pockets, so it gives away as little as it can:

- **Each person signs in with their own account**, the one they use on
  the website. What an account may do is decided by the database, exactly
  as for `/orders`: staff read and move orders (`is_staff()` and RLS), a
  print account reads only the accepted orders nobody has printed and
  prints them. A driver taken off the `staff` table can no longer read the
  queue and drops off the website's list at once.
- **The shop's tablet** needs a staff account to accept orders and ring,
  so it can read what `/orders` on that tablet could already read. Give it
  an account of its own, added to `staff` — not somebody's personal one,
  so it can be taken away without locking a person out — with an address
  that is not written down anywhere public and a long, random password.
- **No password on the device.** It is typed once and sent once; what is
  kept is the refresh token, sealed with AES-256-GCM under a key in the
  Android Keystore (StrongBox where there is one). Signing out ends the
  session on the server as well.
- **Nothing leaves in a backup**; backups and device transfer are off.
- **HTTPS only**, trusting Android's own certificate authorities and none a
  user or a profile added.
- **What shows where.** A lock screen and a notification show the order
  number, what to cook and the total — never a name, phone number or
  address. The app's screens cannot be screenshotted or shown in the
  recent-apps picture, and ignore taps through other apps' overlays.
- **Least surface.** Nothing is exported but the launcher; the boot
  receiver and the "Stumm" receiver hear only the system and the app's own
  notification. Releases are minified with debug logging stripped, and
  logs never carry a token or anything about a customer.
- **The captcha.** The site's sign-in is behind Cloudflare Turnstile; the
  app shows the same widget in a WebView that cannot navigate or call into
  the app, wiped when it closes.
- **Supply chain.** Dependencies come only from the repository that owns
  their group, Dependabot proposes updates, and CI's actions are pinned to
  commits with a read-only token.

The Supabase URL, its publishable key and the Turnstile site key in
[`gradle.properties`](gradle.properties) are the public values the website
ships in its own code; none of them is a secret.

**This repository and its releases are public**, and nothing above rests
on them being secret: anybody can read the code and install the APK, and
what they then get is decided by the database for the account they sign
in with. An account that is neither staff nor a print account is signed
straight out again. The release key and its password live only in the
repository's secrets, which pull requests from forks never see. Keep
account addresses, names and passwords out of the repository, its issues
and its releases — add people on the dashboard, as the site's README says.

## Setting it up

### 1. The server side (once)

In hamza-bistro-web:

1. Merge the branch with `20261001120000_staff_app_devices.sql`, so the
   website lists the app's devices. Without it the app works all the same
   — it rings, prints and moves orders — but the website does not know
   about it, and `/orders` keeps saying in red that nobody hears about
   orders once the phones' pushes are off.
2. For printing: `print-ticket` deployed — see "The Android print
   station" in the site's README.
3. The tablet's own account in `staff` (see Security), and each driver who
   uses the app with their own, as for `/orders`.
4. For opening and closing the shop from the app:
   `20261001150000_shop_hours.sql`. Without it the line above the queue is
   not shown, and everything else works as before.

### 2. Each device

1. Install the APK: on the device, download `hamza-team-….apk` from the
   latest of the repository's
   [releases](https://github.com/asibkamalsada/hamza-bistro-android/releases/latest),
   open it and allow installing from that source when Android asks. A newer
   release installs the same way, over the old one — and over the old
   **Druckstation**, keeping its sign-in, printer and printing.
2. Open **Hamza Team** and sign in (wait for the captcha's tick first).
3. **Schicht beginnen**, and allow notifications.
4. Under **Einstellungen → Damit es klingelt**, fix whatever is listed:
   notifications, "Vollbild über dem Sperrbildschirm" (Android 14 and
   later), and **Hintergrundbetrieb erlauben**. On Samsung and Xiaomi also
   follow [dontkillmyapp.com](https://dontkillmyapp.com) for the model
   (Samsung: _Settings → Battery → Background usage limits → Never
   sleeping apps_, add Hamza Team; Xiaomi: _Autostart_ on and _Battery
   saver → No restrictions_), or those makers stop the app anyway.
5. Give the device a name ("Tablet", "Fahrer 1"), choose how it rings, and
   press **Probehören**.
6. Check under **Einstellungen → Wer von Bestellungen erfährt** (on this
   device or another): the device is there, "im Dienst seit …".
7. On a phone that had the installed `/orders` with pushes on, switch the
   pushes off there ("Dieses Gerät" on `/orders/settings`), or it hears
   about every order twice.

**The tablet** additionally, under **Einstellungen → Bondrucker**: switch
the printer on, **Drucker suchen**, tap the printer (`CY-BX58D-…`, allow
"Geräte in der Nähe"), **Testdruck**, and switch on **Jede angenommene
Bestellung drucken**. In Chrome on the same tablet, switch the website's
own "Jede angenommene Bestellung drucken" off: the printer takes one
connection at a time. **Tütenzettel nach jedem Bon** adds a strip for the
bag after each ticket. Leave the alarm on "Laut klingeln" and the shift on
for good; keep it plugged in; **Bildschirm anlassen** keeps the queue on
screen.

**A driver** will usually want **Einmal Bescheid geben** rather than the
loop, and to end the shift (**Im Dienst** off) at the end of the evening,
which takes the phone off the website's list at once.

After a reboot the service starts by itself once the device has been
unlocked for the first time — before that, Android keeps the app's
encrypted data locked.

### Newer versions

The app does not update itself. It reads the latest release from GitHub's
API (unauthenticated: the repository is public) when the queue or the
settings are opened, at most once an hour, and once a day while on shift;
a release whose tag's run number (`v0.2.<run>`) is above the installed
`versionCode` shows **Update verfügbar: 0.2.… · Installieren** above the
queue and under **Einstellungen → App**. **Installieren** opens the APK's
download in the browser; Android's installer puts it over the old version,
keeping the sign-in and the printer. A GitHub that does not answer changes
nothing: the check runs beside the queue and the alarm, never in their way,
and only the settings say that it failed.

### Updating from the Druckstation

The app is the same application, signed with the same key, so the release
installs over it. A tablet signed in with a print account carries on
printing as before, after the update as after a reboot. The first time the
app is opened afterwards it finds out once whether the account is staff;
for a print account nothing changes on screen.

To make the shop's tablet the place orders are accepted: **Abmelden** (which
switches printing off and takes the station off the website's list),
sign in with the tablet's staff account, **Schicht beginnen**, and under
**Einstellungen → Bondrucker** switch **Jede angenommene Bestellung
drucken** on again — the printer stays chosen. The print account is then
no longer needed; delete its row in `print_accounts` (or the account).

## Building

The APK is built by CI ([`.github/workflows/android.yml`](.github/workflows/android.yml))
on every push: the debug APK is attached to each run.

For the release APK, add the release key to the repository's secrets once:

| Secret                 | What                                |
| ---------------------- | ----------------------------------- |
| `HB_KEYSTORE_BASE64`   | the keystore, `base64 -w0 release.jks` |
| `HB_KEYSTORE_PASSWORD` | its password                        |
| `HB_KEY_ALIAS`         | the key's alias                     |
| `HB_KEY_PASSWORD`      | the key's password                  |

Every push to `main` — a merged pull request too — then builds a signed
release APK and publishes it as a GitHub release: tag `v0.2.<run>`, the APK
as `hamza-team-0.2.<run>.apk`, and the pull requests merged since the
release before as its notes. `<run>` is the workflow's run number, which is
also the APK's `versionCode`, so each release installs over the one before;
the device shows the same `0.2.<run>` under App info. The `0.2` is set in
[`app/build.gradle.kts`](app/build.gradle.kts). Without the key, `main`
builds an unsigned APK that no device installs, publishes nothing, and says
so in a warning on the run.

Make the key once and keep it safe outside the repository — Android installs
an update only when it is signed with the same key:

```
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias druckstation
```

Locally, with the Android SDK: `./gradlew :core:test :app:lintDebug
:app:assembleDebug`, or with the environment variables `HB_KEYSTORE_FILE`,
`HB_KEYSTORE_PASSWORD`, `HB_KEY_ALIAS` and `HB_KEY_PASSWORD` set,
`./gradlew :app:assembleRelease`.

Requirements: Android 12 or later; JDK 17+ to build.
