# Relay Worker

A small Cloudflare Worker that gives Huginn and Muninn short-lived TURN relay logins.
The Cloudflare TURN token stays inside Cloudflare, never inside the apps.

Files:

- `worker.js`: the Worker, and the `RelayLimits` object that keeps the daily counts.
- `wrangler.toml`: the Worker's name, the `LIMITS` binding and the storage setup, for `wrangler deploy`.
- `test.mjs`: a local check. Run `node test.mjs` in this folder. It should end with `44 tests passed`.
- `package.json` and `package-lock.json`: the exact wrangler version the deploy installs (`npm ci`).

## How it gets to Cloudflare

Each release (a push to main that changes `code/VERSION`) deploys this folder to the Worker `huginn-relay`
(`https://huginn-relay.ravenor907.workers.dev`), in its own job after the release is out. Test builds and betas
never touch it. The deploy job can only read the repository, installs wrangler from `package-lock.json` and keeps the
Worker's secrets. Without the two GitHub secrets below, or if the deploy fails, the release still stands and the
run shows a warning.

## Setup steps

Do these in order. One action per step. Button names are from the Cloudflare dashboard at
dash.cloudflare.com and from GitHub. If a button has a slightly different name, pick the closest match.

### A. Make the TURN key

1. Log in to dash.cloudflare.com.
2. In the left sidebar, click **Realtime**.
3. Click **TURN Server**.
4. Click **Create**.
5. Type the name `huginn-relay`.
6. Click **Create**.
7. Copy the **Turn Token ID** into a note. This is your `TURN_KEY_ID`.
8. Copy the **API Token** into the same note. This is your `TURN_KEY_API_TOKEN`. It is shown only once.

### B. Make the app secret

9. Open a terminal.
10. Run `openssl rand -hex 32`.
11. Copy the long line it prints into your note. This is your `APP_SECRET`.

### C. Get the Cloudflare account ID

12. In the left sidebar, click **Workers & Pages**.
13. Find **Account ID** on the right side of the page.
14. Click it to copy it into your note. This is your `CLOUDFLARE_ACCOUNT_ID`.

### D. Make the deploy token

It may only edit Workers on your one account, nothing else.

15. Click the person icon at the top right, then **My Profile**.
16. Click **API Tokens**, then **Create Token**.
17. Next to **Create Custom Token**, click **Get started**.
18. Name it `huginn-relay deploy`.
19. Under **Permissions**, pick **Account**, **Workers Scripts**, **Edit**. Add no other rows.
20. Under **Account Resources**, pick **Include** and your account.
21. Click **Continue to summary**, then **Create Token**.
22. Copy the token into your note. This is your `CLOUDFLARE_API_TOKEN`. It is shown only once.

### E. Add the two GitHub secrets

23. Open the repository on github.com and click **Settings**.
24. Click **Secrets and variables**, then **Actions**.
25. Click **New repository secret**. Name `CLOUDFLARE_API_TOKEN`, paste its value, click **Add secret**.
26. Click **New repository secret** again. Name `CLOUDFLARE_ACCOUNT_ID`, paste its value, click **Add secret**.
27. The next release deploys the Worker.

### F. Add the three Worker secrets

Do this once the Worker exists (after the first release with the GitHub secrets).

28. In Cloudflare, click **Workers & Pages**, then `huginn-relay`.
29. Click **Settings**.
30. Find **Variables and Secrets** and click **Add**.
31. Set **Type** to **Secret**, name `TURN_KEY_ID`, paste its value from your note.
32. Add a Secret named `TURN_KEY_API_TOKEN` with its value.
33. Add one more Secret named `APP_SECRET` with its value.
34. Click **Deploy**.

### G. Check it works

35. Open `https://huginn-relay.ravenor907.workers.dev/health` in a browser.
36. You should see `{"ok":true}`.
37. The Worker address and `APP_SECRET` go into the apps' constants files (see `code/shared/docs/relay.md`).

### H. Turn on the usage alert (required)

TURN data is billed per GB relayed. This alert is how you hear about unexpected traffic.

38. In Cloudflare, click **Notifications** (left sidebar or account menu).
39. Click **Add**.
40. Pick **Usage Based Billing**.
41. Pick **Realtime** (TURN) as the product, and a limit you are happy to pay each month.
42. Add your email, then click **Save**.

## Good to know

- Limits (details in `code/shared/docs/relay.md`, "Limits"): every budget counts minutes of relay login handed out.
  New app builds get 10 minute logins, ask for one only when a call needs the relay (not on Wi-Fi, Bluetooth or a
  direct path), and renew only while the relay carries the call, so the minutes are time spent relaying; older builds
  get 30 minute logins, which count 3 times as much. Each day: 900 minutes per app install (counted by its install
  token, never by its address, so a carrier's shared addresses or a school never block anyone), 1440 per address group
  (an IPv4 /24 or an IPv6 /48) shared only by installs registered in the last 2 days, and 50,000 for everyone, at most
  a quarter of that in any one hour (about 208 people relaying at once). 50,000 minutes of sound is about 30 GB a day,
  under the free 1,000 GB a month of TURN; video over the relay costs about 20 times as much a minute.
  Logins without an install token (very old apps, or anyone who pulled `APP_SECRET` out of an app) get far less: 60
  minutes per install, 120 per address group and 900 for all of them together, so they can never use up the others.
  Past 60% of the day's or hour's budget the relay is "busy": only installs registered at least 2 days earlier still
  get logins. When the relay says no, calls go on over Wi-Fi, Bluetooth and direct paths. The day resets at midnight UTC.
- Optional Worker variables (Type: Text, not Secret): `DAILY_MINUTES` sets the day's budget for everyone (pick it from
  what you are willing to pay for TURN; 50,000 minutes is about 833 hours of relaying). `LEGACY_UNTIL`, a Unix time in seconds:
  from then on logins without a token are refused. Every app since 1.36 has a token, so this can be set now.
- Junk requests: everything that reaches the Worker counts against the free plan's 100,000 requests a day. The Worker
  turns junk away before it costs anything else (no storage request, no TURN login), but only a rule in front of it can
  keep junk from counting. That needs a domain on this Cloudflare account; `wrangler.toml` and relay.md ("Junk
  requests") say how. Until then a flood can, at worst, stop the relay until the next UTC day.
- The counts live in one small storage object at Cloudflare (`RelayLimits`, bound as `LIMITS`). Each login
  is checked and counted in one step inside it, so logins at the same moment can't go over a limit.
- Addresses are never stored or logged. The per-address count is kept under a keyed hash (HMAC) of the address group
  and the day. The key is the Worker's `TURN_KEY_API_TOKEN` secret, so the stored value can't be turned back
  into an address by trying them all. All counts from earlier days are deleted when a new day starts.
- If the storage object is missing or not working, the Worker refuses every request (answers 503).
  That is on purpose: the limits are what protect the TURN account.
- Free plan budget: with every limit used up, about 24,200 storage rows written a day (and as many deleted the next
  day), against the free 100,000. No new secret is needed: tokens are signed with a key made from `TURN_KEY_API_TOKEN`
  (or a `TOKEN_KEY` secret, if you add one).
- A request body over 1 KB is refused (413) before it is read.
- TURN data is billed by Cloudflare per GB relayed. Check **Realtime** in the dashboard for usage; step H's alert
  emails you when usage passes the limit you chose.
- To deploy by hand from this folder: `npm ci`, then `npx wrangler deploy` (needs Node 22 or newer and a
  Cloudflare login).
