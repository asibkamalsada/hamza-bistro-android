#!/usr/bin/env node

/**
 * Generates the compact street list asset for Leipzig.
 * Queries the public Overpass API for named streets, address postcodes, and Ortsteile,
 * checks each street against the bistro's delivery area ('edge' polygon),
 * and writes the result to core/src/main/resources/de/hamzabistro/printstation/core/streets.json.
 *
 * Usage:
 *   node tools/streets/generate.mjs
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Endpoints in order of preference: primary overpass-api.de, then lz4/z mirrors
const OVERPASS_ENDPOINTS = [
  'https://overpass-api.de/api/interpreter',
  'https://lz4.overpass-api.de/api/interpreter',
  'https://z.overpass-api.de/api/interpreter',
];

const USER_AGENT = 'HamzaBistroStreetPicker/1.0 (kontakt@hamzabistro.de)';
const TIMEOUT_SECONDS = 180;

/**
 * Outer boundary of the delivery area: a copy of EDGE_AREA_POLYGON in
 * hamza-bistro-web/src/app/delivery-area.ts. When that changes, copy it
 * here again and regenerate. GeoJSON [lon, lat] pairs.
 */
const EDGE_RING = [
  [12.32858, 51.39364], [12.30733, 51.38878], [12.31682, 51.38253], [12.30826, 51.37921],
  [12.28865, 51.38153], [12.29687, 51.37653], [12.29058, 51.37331], [12.27309, 51.37953],
  [12.28558, 51.36739], [12.25258, 51.37304], [12.24447, 51.36341], [12.26471, 51.36353],
  [12.23972, 51.35553], [12.24258, 51.34833], [12.22434, 51.35053], [12.25187, 51.33853],
  [12.24343, 51.33438], [12.25173, 51.32838], [12.24153, 51.32748], [12.25171, 51.3234],
  [12.23132, 51.30953], [12.25169, 51.31763], [12.25328, 51.29553], [12.26158, 51.29407],
  [12.28458, 51.3027],  [12.29045, 51.28453], [12.31058, 51.27122], [12.31343, 51.27968],
  [12.33512, 51.28599], [12.33358, 51.29267], [12.36163, 51.28653], [12.36754, 51.28853],
  [12.36058, 51.29968], [12.37058, 51.29152], [12.37958, 51.30081], [12.38675, 51.29436],
  [12.3904,  51.30871], [12.41678, 51.31653], [12.4093,  51.32381], [12.42058, 51.32549],
  [12.4256,  51.34453], [12.41519, 51.34853], [12.41276, 51.37553], [12.35358, 51.38316],
  [12.35558, 51.3753],  [12.34345, 51.38039], [12.343,   51.39095], [12.33558, 51.38218],
  [12.32858, 51.39364],
];

/** Ray casting point-in-polygon test, matching hamza-bistro-web's containsPoint. */
function pointInPolygon(lon, lat, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [lonI, latI] = ring[i];
    const [lonJ, latJ] = ring[j];
    const straddles = (latI > lat) !== (latJ > lat);
    if (!straddles) continue;
    const lonAtLat = lonI + ((lat - latI) / (latJ - latI)) * (lonJ - lonI);
    if (lon < lonAtLat) inside = !inside;
  }
  return inside;
}

function distSq(lat1, lon1, lat2, lon2) {
  const dLat = lat1 - lat2;
  const dLon = lon1 - lon2;
  return dLat * dLat + dLon * dLon;
}

async function queryOverpass(ql) {
  let lastError;
  for (const endpoint of OVERPASS_ENDPOINTS) {
    try {
      console.log(`Querying Overpass at ${endpoint}...`);
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'User-Agent': USER_AGENT,
        },
        body: 'data=' + encodeURIComponent(ql),
      });
      if (!res.ok) {
        const text = await res.text();
        console.warn(`Endpoint ${endpoint} returned HTTP ${res.status}: ${text.slice(0, 120)}`);
        continue;
      }
      return await res.json();
    } catch (err) {
      console.warn(`Endpoint ${endpoint} failed: ${err.message}`);
      lastError = err;
    }
  }
  throw lastError || new Error('All Overpass endpoints failed');
}

/**
 * Whitelist of highway types that represent real, named streets.
 * Excludes service roads, parking aisles, emergency routes, footways, tracks, etc.
 */
const HIGHWAY_WHITELIST = new Set([
  'residential',
  'living_street',
  'pedestrian',
  'primary',
  'primary_link',
  'secondary',
  'secondary_link',
  'tertiary',
  'tertiary_link',
  'unclassified',
  'trunk',
  'trunk_link',
]);

function isValidStreetName(name) {
  if (!name || name.trim().length < 2) return false;
  const trimmed = name.trim();
  // Reject names ending in a digit (e.g. "Hammerstraße8", "Pötzschker Weg 2 - 4", "Straße B01/B02", "Gewerbehof - Mockauer Straße 15")
  if (/\d$/.test(trimmed)) return false;
  // Reject names containing ':' or '(' or ')' (e.g. "Modul 15: Wasserdurchfahrt", "Permoser-/Torgauer Straße (SEV)")
  if (/[:()]/.test(trimmed)) return false;
  // Reject names starting or ending with quotes (e.g. '„Stadtplatz Gohlis“')
  if (/^[„"'«»]/.test(trimmed) || /[“"'«»]$/.test(trimmed)) return false;
  // Reject parking and service entries
  if (/^(Parkhaus|Tiefgarage|Parkplatz|Alarmausfahrt|Aufstellfläche|Feuerwehrausfahrt)\b/i.test(trimmed)) return false;
  return true;
}

function isValidPostcode(pc) {
  if (!pc) return false;
  // Keep only 04xxx postcodes; Leipzig city postcodes strictly match 04103..04357 (041xx, 042xx, 043xx).
  // Drops foreign postcodes like 04758 (Oschatz).
  return /^04[123]\d{2}$/.test(pc.trim());
}

async function main() {
  const startTime = Date.now();

  const overpassQuery = `
    [out:json][timeout:${TIMEOUT_SECONDS}];
    area["name"="Leipzig"]["boundary"="administrative"]["admin_level"="6"]->.leipzig;
    (
      way["highway"]["name"](area.leipzig);
      nwr["addr:street"]["addr:postcode"](area.leipzig);
      node["place"~"^(suburb|quarter)$"](area.leipzig);
    );
    out tags center;
  `;

  const data = await queryOverpass(overpassQuery);
  console.log(`Received ${data.elements?.length || 0} elements in ${Date.now() - startTime} ms`);

  // 1. Collect Ortsteile / Suburbs, address points, and address counts
  const suburbs = [];
  const addressPoints = [];
  const addressCounts = new Map(); // lowercase name -> count of addr:street uses
  const highwayNames = new Set(); // lowercase name -> true if seen on a whitelisted highway

  for (const el of data.elements) {
    if (el.tags?.place && (el.tags.place === 'suburb' || el.tags.place === 'quarter')) {
      const name = el.tags.name?.trim();
      const lat = el.lat ?? el.center?.lat;
      const lon = el.lon ?? el.center?.lon;
      if (name && lat && lon) suburbs.push({ name, lat, lon });
    }

    const pc = el.tags?.['addr:postcode']?.trim();
    if (isValidPostcode(pc)) {
      const lat = el.lat ?? el.center?.lat;
      const lon = el.lon ?? el.center?.lon;
      if (lat && lon) addressPoints.push({ pc, lat, lon });
    }

    if (el.tags?.['addr:street']) {
      const name = el.tags['addr:street'].trim();
      if (isValidStreetName(name)) {
        const key = name.toLowerCase();
        addressCounts.set(key, (addressCounts.get(key) || 0) + 1);
      }
    }

    if (el.tags?.highway && el.tags?.name) {
      if (HIGHWAY_WHITELIST.has(el.tags.highway)) {
        const name = el.tags.name.trim();
        if (isValidStreetName(name)) {
          highwayNames.add(name.toLowerCase());
        }
      }
    }
  }
  console.log(`Found ${suburbs.length} Ortsteile / suburbs, ${addressPoints.length} address points`);

  // 2. Aggregate streets deduplicating case-insensitively
  const streets = new Map(); // lowercase name -> street object

  function getOrCreate(name) {
    const key = name.toLowerCase();
    if (!streets.has(key)) {
      streets.set(key, {
        name,
        postcodes: new Map(),
        suburbCounts: new Map(),
        coords: [],
      });
    }
    const s = streets.get(key);
    // Prefer capitalized canonical name (e.g. Schwartzestraße over schwartzestraße)
    if (/^[a-zäöü]/.test(s.name) && /^[A-ZÄÖÜ]/.test(name)) {
      s.name = name;
    }
    return s;
  }

  for (const el of data.elements) {
    // Whitelisted highway ways
    if (el.tags?.highway && el.tags?.name) {
      const hw = el.tags.highway;
      if (!HIGHWAY_WHITELIST.has(hw)) continue;
      const name = el.tags.name.trim();
      if (!isValidStreetName(name)) continue;

      const s = getOrCreate(name);
      const lat = el.lat ?? el.center?.lat;
      const lon = el.lon ?? el.center?.lon;
      if (lat && lon) s.coords.push([lon, lat]);
      const pc = el.tags['postal_code']?.trim();
      if (isValidPostcode(pc)) {
        s.postcodes.set(pc, (s.postcodes.get(pc) || 0) + 1);
      }
    }

    // Addresses (nwr)
    if (el.tags?.['addr:street']) {
      const name = el.tags['addr:street'].trim();
      if (!isValidStreetName(name)) continue;
      const key = name.toLowerCase();

      // Require at least 2 uses for names that only come from addresses
      if (!highwayNames.has(key) && (addressCounts.get(key) || 0) < 2) {
        continue;
      }

      const s = getOrCreate(name);
      const pc = el.tags['addr:postcode']?.trim();
      if (isValidPostcode(pc)) {
        s.postcodes.set(pc, (s.postcodes.get(pc) || 0) + 1);
      }
      const sub = el.tags['addr:suburb']?.trim();
      if (sub) {
        s.suburbCounts.set(sub, (s.suburbCounts.get(sub) || 0) + 1);
      }
      const lat = el.lat ?? el.center?.lat;
      const lon = el.lon ?? el.center?.lon;
      if (lat && lon) s.coords.push([lon, lat]);
    }
  }

  console.log(`Processed ${streets.size} unique streets after filtering and case-deduping`);

  // 3. Build output entries
  const results = [];
  for (const [key, s] of streets) {
    let centerLat = 0, centerLon = 0;
    if (s.coords.length > 0) {
      for (const [lon, lat] of s.coords) {
        centerLon += lon;
        centerLat += lat;
      }
      centerLon /= s.coords.length;
      centerLat /= s.coords.length;
    }

    // Fall back to nearest addr:postcode if street has no postcode
    if (s.postcodes.size === 0 && centerLat !== 0 && centerLon !== 0 && addressPoints.length > 0) {
      let bestDist = Infinity;
      let bestPc = null;
      for (const ap of addressPoints) {
        const d = distSq(centerLat, centerLon, ap.lat, ap.lon);
        if (d < bestDist) {
          bestDist = d;
          bestPc = ap.pc;
        }
      }
      if (bestPc) {
        s.postcodes.set(bestPc, 1);
      }
    }

    const postcodes = Array.from(s.postcodes.entries())
      .sort((a, b) => b[1] - a[1])
      .map(([pc]) => pc);

    let district = null;
    if (s.suburbCounts.size > 0) {
      district = Array.from(s.suburbCounts.entries()).sort((a, b) => b[1] - a[1])[0][0];
    } else if (centerLat !== 0 && suburbs.length > 0) {
      let bestDist = Infinity;
      let bestSub = null;
      for (const sub of suburbs) {
        const d = distSq(centerLat, centerLon, sub.lat, sub.lon);
        if (d < bestDist) {
          bestDist = d;
          bestSub = sub.name;
        }
      }
      district = bestSub;
    }

    let delivery = 'outside';
    if (s.coords.length > 0) {
      let inCount = 0;
      for (const [lon, lat] of s.coords) {
        if (pointInPolygon(lon, lat, EDGE_RING)) inCount++;
      }
      if (inCount === s.coords.length) {
        delivery = 'inside';
      } else if (inCount > 0) {
        delivery = 'partly';
      } else {
        delivery = 'outside';
      }
    }

    results.push({
      name: s.name,
      postcodes,
      district,
      delivery,
    });
  }

  // Sort alphabetically according to German locale
  results.sort((a, b) => a.name.localeCompare(b.name, 'de'));

  const jsonContent = JSON.stringify(results);
  const sizeKb = (Buffer.byteLength(jsonContent, 'utf8') / 1024).toFixed(1);
  console.log(`Generated ${results.length} streets (${sizeKb} KB)`);

  // Target path: keep ONE asset in core resources (loaded by StreetDirectory)
  const target = path.resolve(__dirname, '../../core/src/main/resources/de/hamzabistro/printstation/core/streets.json');
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, jsonContent, 'utf8');
  console.log(`Wrote ${target}`);
}

main().catch((err) => {
  console.error('Generation failed:', err);
  process.exit(1);
});
