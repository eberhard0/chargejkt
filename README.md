# ChargeJKT

Public EV chargers in Jakarta (and about 60 km around it) on a map and in a nearest-first list, for
the Fold. Tap a charger for its plugs, power, cost, hours and notes, then **Navigate** (Google Maps
or any map app) or **Waze**.

**Data** is public and community-maintained, so it can lag reality and never shows live availability:

- [Open Charge Map](https://openchargemap.org) (CC BY 4.0) — most Jakarta chargers, many credited to PLN.
  Reading it needs a free API key: sign in → My Profile → My Apps → register an app.
- [OpenStreetMap](https://www.openstreetmap.org) `amenity=charging_station` via Overpass (ODbL), for
  anything Open Charge Map lacks (an OSM point within 80 m of an OCM one is dropped as a duplicate).

The APK ships with a snapshot of both (`app/src/main/assets/ocm.json`, `osm.json`, refreshed by CI
with `tools/snapshot.py`); **Settings → Refresh now** downloads fresh copies onto the phone. Both the
snapshot and a refresh are parsed by the same Kotlin code (`Ocm.kt`, `Osm.kt`).

A **Guide** tab explains how charging works in Jakarta (apps, sign-up, payment, prices per operator as of November 2026), and each charger's sheet shows its operator's app and price with a Get app button.

Filters: DC fast only, plug type (CCS2, Type 2, GB/T DC, GB/T AC, CHAdeMO), saved chargers. Amber
dots are DC fast chargers, blue dots are AC. Map tiles from OpenFreeMap.

## Build

GitHub Actions (`.github/workflows/android.yml`) builds every push and attaches signed builds to
`v*` tag releases. Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`
(signing) and `OCM_API_KEY` (Open Charge Map; also compiled in so the app can refresh by itself).
