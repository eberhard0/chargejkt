#!/usr/bin/env python3
"""Download the raw station data the app ships with: Open Charge Map (needs OCM_API_KEY in the
environment) and OpenStreetMap via Overpass, for Jakarta and roughly 60 km around it.

    python3 tools/snapshot.py app/src/main/assets

Each source is written only when its download succeeds and parses, so a flaky download in CI keeps
the previous snapshot instead of shipping an empty one. The app parses these raw files itself
(Ocm.kt / Osm.kt), the same code it uses when refreshing on the phone.
"""
import json
import os
import socket
import sys
import urllib.parse
import urllib.request

# GitHub runners have no IPv6 route, and Overpass answers on both: stick to IPv4.
_getaddrinfo = socket.getaddrinfo
socket.getaddrinfo = lambda host, port, family=0, *a, **k: _getaddrinfo(host, port, socket.AF_INET, *a, **k)

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets"
UA = "ngecas-snapshot/1.0 (personal EV charger app; github.com/eberhard0/ngecas)"
# Jakarta centre; the bounding box is Jabodetabek plus a margin.
LAT, LON, KM = -6.25, 106.85, 60
BBOX = "-6.80,106.30,-5.75,107.45"

OCM_URL = ("https://api.openchargemap.io/v3/poi/?output=json&countrycode=ID"
           f"&latitude={LAT}&longitude={LON}&distance={KM}&distanceunit=km"
           "&maxresults=5000&compact=false&verbose=false&client=ngecas")
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
QUERY = f"""[out:json][timeout:120];
(
  node["amenity"="charging_station"]({BBOX});
  way["amenity"="charging_station"]({BBOX});
);
out center tags;"""


def fetch(url, headers=None, data=None, timeout=180):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def save(name, text):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {path} ({len(text):,} bytes)")


def main():
    key = os.environ.get("OCM_API_KEY", "").strip()
    if key:
        try:
            text = fetch(OCM_URL, {"X-API-Key": key})
            pois = json.loads(text)
            if not isinstance(pois, list):
                raise ValueError(f"unexpected OCM reply: {text[:200]}")
            print(f"Open Charge Map: {len(pois)} locations")
            save("ocm.json", text)
        except Exception as e:  # keep the committed snapshot
            print(f"Open Charge Map download failed, keeping the old file: {e}")
    else:
        print("OCM_API_KEY not set: keeping the committed ocm.json")

    for endpoint in OVERPASS:
        try:
            text = fetch(endpoint, data=urllib.parse.urlencode({"data": QUERY}).encode())
            elements = json.loads(text).get("elements")
            if elements is None:
                raise ValueError(f"unexpected Overpass reply: {text[:200]}")
            print(f"OpenStreetMap ({endpoint}): {len(elements)} charging stations")
            save("osm.json", text)
            break
        except Exception as e:
            print(f"Overpass {endpoint} failed: {e}")
    else:
        print("All Overpass mirrors failed, keeping the old file")


if __name__ == "__main__":
    main()
