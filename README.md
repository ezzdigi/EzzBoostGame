# EzzGameBOOST

Android gaming toolkit by **EzzDigi** (ایزی دیجی) — https://ezzdigi.com

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
