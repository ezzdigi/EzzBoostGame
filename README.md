# EzzGameBOOST

Android gaming toolkit

- RAM boost, live phone stats (RAM, battery, storage, thermal)
- Real DNS switching (Radar Game, Electro, Shecan, Begzar, Shelter, Cloudflare, Google, Quad9, AdGuard) through a local DNS-only VPN
- One-tap "find fastest DNS and connect", auto-connect when launching a game, notification and Quick Settings tile
- Ping lab with saved game servers and hourly quality history
- Game library with play time, favorites, home-screen widget
- Import your own WireGuard `.conf` files and connect (real tunnel)
- Custom DNS: enter your own IPv4 / IPv6 servers, test them and connect
- 27+ Iranian and public DNS providers, reliable one-tap disconnect
- Persian / English UI

## Build
Push to `main` and download the APK from **Actions → Artifacts**.
Create a **Release** on GitHub and the signed-or-debug-key release APK is attached automatically.

Optional own signing key: add repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Online DNS list, game profiles, updates
- The app downloads `dns.json` from this repository (raw GitHub URL) every 12 hours, so DNS servers can be updated by editing that file, without a new APK.
- Per-game profiles: choose a DNS or WireGuard config, RAM cleanup and keep-screen-on for each game.
- Gaming Server tab (formerly VPN): WireGuard and V2Ray tunnels work for games only, with no whole-phone mode. Other apps (Telegram, Instagram, YouTube, browsers) connect directly. If no game is found it refuses to connect.
- "Get WireGuard gaming servers" downloads `wireguard.json` from this repo (same idea as `dns.json` / `v2ray.json`). Format: `{"version":1,"configs":[{"name":"Game1","conf":"[Interface]\n..."},{"name":"Game2","url":"https://github.com/USER/REPO/blob/main/configs/game2.conf"}]}`. You can also paste your own GitHub link (a `.conf` file, a folder or a repo) in the field first.
- In-app update check uses the latest GitHub Release. Publish releases with tags like `v2.2.0`; CI sets the app version from the tag.
- V2Ray/Xray: add share links or a subscription URL (clipboard / file), test the server and connect directly inside the app (vmess, vless, trojan, ss) using the embedded Xray core (libv2ray.aar from 2dust/AndroidLibXrayLite, downloaded by CI). hysteria2/tuic open in an external client. A public list can be published by committing `v2ray.json` to the repo root.

- Online lists (`dns.json`, `v2ray.json`, `wireguard.json`) are read from the repo root. The repo is configurable in the app (Gaming Server tab → ONLINE LISTS) and several mirrors are tried (raw.githubusercontent.com, jsDelivr), because raw GitHub is blocked on some networks. Errors show the reason (HTTP 404, timeout, ...).

- Every detected game is a favorite by default (added once; removing a favorite sticks). DNS addresses are hidden in the DNS tab. Items that first appear in an online list (DNS, V2Ray, gaming servers) show "🆕 جدید" for 24 hours; the first sync of a list is the baseline. `wireguard.json` is also synced automatically every 12 hours.

- Subscription card (Gaming Server tab): "Buy" opens `SUB_BUY_URL` in the browser, the user pastes the code from your site and taps Activate. Set `SUB_BUY_URL` and `SUB_API` in `Model.kt`. With an active code the app tries `SUB_API/<code>/dns.json | v2ray.json | wireguard.json` first (server returns free + premium lists); an expired or invalid code gets an HTTP error and the app falls back to the free GitHub lists. `SUB_API/<code>/status.json` must return `{"active":true,"expires":"2026-12-31"}`. `server/worker.js` is an untested Cloudflare Worker example of that API.

- `server/` has the full subscription server (Cloudflare Worker): purchase page, codes, premium lists. See `server/SUBSCRIPTION_SETUP_FA.md`. `subscription-preview.html` is a UI preview.

- V2Gaming also pulls public text lists automatically: `V2_SOURCES` in `Model.kt` (default: iboxz/free-v2ray-collector vless.txt) and an optional `"sources": [...]` array in `v2ray.json`. Only encrypted links (tls / reality / vmess / ss) are kept, duplicates are dropped, and at most `V2_SOURCE_MAX` (20) per source are added, shown as "Public • ...".

- Public V2 sources now include `vless.txt` and `vmess.txt` from iboxz/free-v2ray-collector. Extracted servers are named `Game Gaming Pubg EzzDns <n>` (`V2_AUTO_NAME`), and the V2Gaming list refreshes every 2 hours while the app is open (`V2_REFRESH_HOURS`).

- V2Gaming: after connecting, the app checks the server end to end through a local SOCKS inbound and shows "✅ سرور جواب می‌دهد" or a warning. Extracted public servers are tested with a TCP connect first (dead ones are dropped; up to 20 live per source from 60 candidates) and named `Gaming <n>` (`V2_AUTO_NAME`).
- Theme: "📱 خودکار" (default) follows the phone's dark/light mode; dark and light can also be forced in the Tools tab. The subscription card shows "به زودی...".

- Accounts: the Gaming Server tab has a per-user login ("اشتراک ویژه"). Each user gets their own WireGuard (and optionally V2Ray) config from your hosting (PHP + MySQL). Set `SUB_BASE` in `Model.kt`. The server folder `ezzsub` is a separate download; do not commit it (or its config.php) to a public repo. The old Cloudflare Worker version was removed.
