# Relay Worker

A small Cloudflare Worker that gives Huginn and Muninn short-lived TURN relay logins.
The Cloudflare TURN token stays inside Cloudflare, never inside the apps.

Files:

- `worker.js`: the Worker, and the `RelayLimits` object that keeps the daily counts.
- `wrangler.toml`: the Worker's name, the `LIMITS` binding and the storage setup, for `wrangler deploy`.
- `test.mjs`: a local check. Run `node test.mjs` in this folder. It should end with `21 tests passed`.
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

- Limits: each app install gets at most 30 logins a day, each internet address at most 60 a day (a home
  shares one address, so this is a few installs' worth). Since 1.4 each install registers once (a second or less of
  work on the device, at most 6 new installs a day per address and 2000 for everyone) and gets a token that each login
  renews. Logins with a token share a cap of 12,000 a day; logins without one (older apps, or anyone who pulled
  `APP_SECRET` out of an app) share 1000 a day of their own, so they can never use up the others.
  An IPv6 address counts by its /48, the most one home or phone line is usually given, so one customer can't
  get around the cap by moving between their own addresses. The day resets at midnight UTC. When a limit is hit the Worker
  answers 429.
- The counts live in one small storage object at Cloudflare (`RelayLimits`, bound as `LIMITS`). Each login
  is checked and counted in one step inside it, so logins at the same moment can't go over a limit.
- Addresses are never stored or logged. The per-address count is kept under a keyed hash (HMAC) of the address
  and the day. The key is the Worker's `TURN_KEY_API_TOKEN` secret, so the stored value can't be turned back
  into an address by trying them all. All counts from earlier days are deleted when a new day starts.
- If the storage object is missing or not working, the Worker refuses every request (answers 503).
  That is on purpose: the caps are what protect the TURN account.
- Free plan budget, per login: 1 Worker request, 1 storage object request, 3 storage rows written (and the
  same 3 or fewer deleted the next day); per registration 1 of each and 2 rows. The free plan allows 100,000 Worker
  requests, 100,000 storage object requests and 100,000 rows written a day. With every cap full that is at most
  15,000 requests of each kind and 86,000 rows written and deleted, so it stays free. No new secret is needed: tokens
  are signed with a key made from `TURN_KEY_API_TOKEN` (or a `TOKEN_KEY` secret, if you add one).
- Each login lasts 30 minutes. The apps ask for a fresh one for a call when less than 5 minutes are left.
- A request body over 1 KB is refused (413) before it is read.
- TURN data is billed by Cloudflare per GB relayed. Check **Realtime** in the dashboard for usage; step H's alert
  emails you when usage passes the limit you chose.
- To deploy by hand from this folder: `npm ci`, then `npx wrangler deploy` (needs Node 22 or newer and a
  Cloudflare login).
