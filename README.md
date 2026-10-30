# Galt Promo

A single-page ClojureScript presence demo. A Macchiato server streams user snapshots over SSE, and a re-frame/Bulma UI renders each user's generated alliterative name, browser, IP address, and connection status.

Disconnected users remain visible with a red status dot for 10 seconds, then disappear. The server sets an HTTP-only `client-id` cookie and stores each user's last name in SQLite, so returning browsers keep the same name even across server restarts.

## Setup

Requires Node 22.13 or newer (the server uses the built-in `node:sqlite` module, which is only available without `--experimental-sqlite` from 22.13).

```sh
npm install
```

## Develop

Start the shadow-cljs watches:

```sh
npm run dev
```

In another terminal, run the server:

```sh
npm start
```

Open <http://localhost:3000>. Open additional isolated browser windows or profiles to see multiple users. Set `PORT` to change the server port and `DATABASE_PATH` to change the SQLite file (default `galt-promo.db`).

The **List** and **Map** tabs show the same presence data, with **Map** open by default. Pins are green while online, red during the disconnect grace period, and blue for your own marker. Click your own name on the map or in a user list to rename yourself inline; the name is saved and reused on your next visit. The responsive, full-screen globe uses real-time day/night shading, a blue atmosphere, and a six-second opening rotation that finishes at the current user's location. New online users shoot green arcs to the other users; disconnecting users shoot red arcs. The globe and marker clusters resize with the viewport.

Opening the app asks for browser location permission; if allowed, precise coordinates replace the IP-based location for that user. If permission is denied, IP geolocation is used. Groups of two or more markers that would overlap at the current zoom collapse into a numbered marker; click it to see the users in a table.

Browsers only permit precise location on `localhost` or an HTTPS origin. IP geolocation uses the local GeoLite database bundled by `geoip-lite`, so IP addresses are not sent to a third-party service. Private or unresolvable IPs (such as `127.0.0.1`) have no marker until browser location succeeds. To refresh the database, follow the `geoip-lite` update instructions with a MaxMind license key.

The globe loads its Earth textures from jsDelivr, so the first map view requires internet access.

The UI watch rebuilds `resources/public/js/main.js`; the server watch rebuilds `target/server.js`. Restart `npm start` after server changes.

## REPL

Start a browser-connected ClojureScript REPL while the UI watch and app are running:

```sh
npx shadow-cljs cljs-repl ui
```

For the server build:

```sh
npx shadow-cljs cljs-repl server
```

Editors can instead connect to the shadow-cljs nREPL server and select the `ui` or `server` build. Use `shadow-cljs watch` output for the nREPL port when it is chosen dynamically.

## Test and build

```sh
npm test
npm run build
npm start
```

Tests cover name generation, the 10-second presence boundary, live HTTP/SSE connect and disconnect behavior, cookie sessions, SQLite name persistence and renaming, the index route, location validation and lookup, marker clustering, online/offline arc transitions, and re-frame state/subscriptions. `npm run build` produces release builds for both application targets.

## Edit names

The bundled adjective/noun dictionary is in `src/galt_promo/names.cljs`. Names are unique among users currently connected or in their disconnect grace period.
