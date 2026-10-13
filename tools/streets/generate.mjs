#!/usr/bin/env node

/**
 * Writes Leipzig's streets, and the house numbers along them, for "Neue
 * Bestellung": core/src/main/resources/de/hamzabistro/printstation/core/streets.json.
 *
 * From OpenStreetMap, with one Overpass query: the named streets, every
 * address that has a house number, and the districts. Each street gets its
 * postcodes (the commonest first), the district most of it is in, and
 * whether it lies inside the delivery area; each house number its own
 * postcode and delivery ring, since a long street has several of both.
 *
 *   node tools/streets/generate.mjs
 *   node tools/streets/generate.mjs --area ../hamza-bistro-web/src/app/delivery-area.ts
 *
 * The rings are the website's (src/app/delivery-area.ts), copied into
 * delivery-area.json beside this script. With --area the copy is taken
 * again first: do that whenever the delivery area changes, then commit both
 * files. StreetDirectoryTest checks the result.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const AREA_FILE = path.join(here, 'delivery-area.json');
const TARGET = path.resolve(here, '../../core/src/main/resources/de/hamzabistro/printstation/core/streets.json');

// The main instance, its mirrors, then two other public ones: any one of them is often busy.
const OVERPASS_ENDPOINTS = [
  'https://overpass-api.de/api/interpreter',
  'https://lz4.overpass-api.de/api/interpreter',
  'https://z.overpass-api.de/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
];
const ROUNDS = 3;
const PAUSE_SECONDS = 30;
const USER_AGENT = 'HamzaBistroStreetPicker/1.0 (kontakt@hamzabistro.de)';
const TIMEOUT_SECONDS = 180;

/** The rings from the inside out, and the letter each house number carries for its own. */
const ZONES = [
  ['inner', 'i'],
  ['near', 'n'],
  ['far', 'f'],
  ['edge', 'e'],
];
const OUTSIDE = 'x';

// ---------------------------------------------------------------------------
// The delivery area
// ---------------------------------------------------------------------------

/** INNER_, NEAR_, FAR_ and EDGE_AREA_POLYGON out of the website's delivery-area.ts, as [lon, lat] pairs. */
function ringsFromWebsite(file) {
  const source = fs.readFileSync(file, 'utf8');
  const rings = {};
  for (const [zone] of ZONES) {
    const declared = new RegExp(`export const ${zone.toUpperCase()}_AREA_POLYGON[^=]*=\\s*\\[([\\s\\S]*?)\\n\\];`).exec(source);
    if (!declared) throw new Error(`${file} has no ${zone.toUpperCase()}_AREA_POLYGON`);
    rings[zone] = [...declared[1].matchAll(/\[\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\]/g)].map((m) => [Number(m[1]), Number(m[2])]);
    if (rings[zone].length < 4) throw new Error(`${zone}: only ${rings[zone].length} points read`);
  }
  return rings;
}

function loadRings() {
  const at = process.argv.indexOf('--area');
  if (at >= 0) {
    const file = process.argv[at + 1];
    if (!file) throw new Error('--area needs the path of delivery-area.ts');
    const rings = ringsFromWebsite(file);
    fs.writeFileSync(AREA_FILE, JSON.stringify(rings) + '\n', 'utf8');
    console.log(`Copied the rings from ${file}: ${ZONES.map(([z]) => `${z} ${rings[z].length}`).join(', ')} points`);
  }
  return JSON.parse(fs.readFileSync(AREA_FILE, 'utf8'));
}

/** Ray casting, as the website's containsPoint does it. */
function pointInPolygon(lon, lat, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [lonI, latI] = ring[i];
    const [lonJ, latJ] = ring[j];
    if (latI > lat === latJ > lat) continue;
    const lonAtLat = lonI + ((lat - latI) / (latJ - latI)) * (lonJ - lonI);
    if (lon < lonAtLat) inside = !inside;
  }
  return inside;
}

/** The ring a point is in, by its letter: the innermost that holds it, as zoneForLocation has it. */
function zoneOf(lon, lat, rings) {
  for (const [zone, letter] of ZONES) if (pointInPolygon(lon, lat, rings[zone])) return letter;
  return OUTSIDE;
}

// ---------------------------------------------------------------------------
// OpenStreetMap
// ---------------------------------------------------------------------------

async function queryOverpass(ql) {
  let lastError;
  for (let round = 1; round <= ROUNDS; round++) {
    for (const endpoint of OVERPASS_ENDPOINTS) {
      try {
        console.log(`Asking ${endpoint} ...`);
        const res = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': USER_AGENT },
          body: 'data=' + encodeURIComponent(ql),
        });
        if (!res.ok) {
          console.warn(`  HTTP ${res.status}`);
          continue;
        }
        return await res.json();
      } catch (err) {
        console.warn(`  ${err.message}`);
        lastError = err;
      }
    }
    // All busy at once happens; a moment later one of them is not.
    if (round < ROUNDS) await new Promise((resolve) => setTimeout(resolve, PAUSE_SECONDS * 1000));
  }
  throw lastError || new Error('No Overpass instance answered');
}

/** The kinds of way that are a street somebody lives on: no service lanes, footpaths or tracks. */
const HIGHWAYS = new Set([
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

/** A name that is a street's: not one ending in a house number, a bus stop's note or a car park's lane. */
function isStreetName(name) {
  if (!name || name.trim().length < 2) return false;
  const n = name.trim();
  if (/\d$/.test(n)) return false;
  if (/[:()]/.test(n)) return false;
  if (/^[„"'«»]/.test(n) || /[“"'«»]$/.test(n)) return false;
  if (/^(Parkhaus|Tiefgarage|Parkplatz|Alarmausfahrt|Aufstellfläche|Feuerwehrausfahrt)\b/i.test(n)) return false;
  return true;
}

/** Leipzig's own postcodes, 04103 to 04357: one from the next town on a boundary street is not ours. */
function isPostcode(pc) {
  return !!pc && /^04[123]\d{2}$/.test(pc.trim());
}

/**
 * The house numbers one address stands for, as they are typed: "9", "9a",
 * and for a building that spans several ("12-16", "3;5", "7a-7c") each of
 * them. Nothing for what is no house number ("Halle 3", "o. Nr.").
 */
function houseNumbers(raw) {
  const numbers = new Set();
  for (const part of raw.toLowerCase().replace(/\s+/g, '').split(/[;,/]/)) {
    const one = /^(\d{1,4})([a-z]?)$/.exec(part);
    if (one) {
      numbers.add(one[1] + one[2]);
      continue;
    }
    const range = /^(\d{1,4})([a-z]?)[-–](\d{1,4})([a-z]?)$/.exec(part);
    if (!range) continue;
    const [, a, aLetter, b, bLetter] = range;
    const from = Number(a);
    const to = Number(b);
    numbers.add(a + aLetter);
    numbers.add(b + bLetter);
    if (from === to && aLetter && bLetter) {
      // "7a-7c": the letters between.
      for (let c = aLetter.charCodeAt(0); c <= bLetter.charCodeAt(0); c++) numbers.add(a + String.fromCharCode(c));
    } else if (!aLetter && !bLetter && to > from && to - from <= 40) {
      // "12-16" is one side of the street: 12, 14, 16. "12-15" is all four.
      const step = (to - from) % 2 === 0 ? 2 : 1;
      for (let n = from; n <= to; n += step) numbers.add(String(n));
    }
  }
  return [...numbers];
}

/** House numbers in their order on the street: by number, then by letter. */
function byHouseNumber(a, b) {
  return parseInt(a, 10) - parseInt(b, 10) || a.localeCompare(b);
}

/** "1 2 3 4 4a 6 7 8" as "1-4 4a 6-8": a run of three whole numbers or more is written as its ends. */
function compress(numbers) {
  const sorted = [...numbers].sort(byHouseNumber);
  const out = [];
  for (let i = 0; i < sorted.length; ) {
    let j = i;
    if (/^\d+$/.test(sorted[i])) {
      while (j + 1 < sorted.length && /^\d+$/.test(sorted[j + 1]) && Number(sorted[j + 1]) === Number(sorted[j]) + 1) j++;
    }
    if (j - i >= 2) out.push(`${sorted[i]}-${sorted[j]}`);
    else for (let k = i; k <= j; k++) out.push(sorted[k]);
    i = j + 1;
  }
  return out.join(' ');
}

function nearest(lat, lon, places) {
  let best = null;
  let bestDistance = Infinity;
  for (const place of places) {
    const d = (lat - place.lat) ** 2 + (lon - place.lon) ** 2;
    if (d < bestDistance) {
      bestDistance = d;
      best = place;
    }
  }
  return best;
}

function commonest(counts) {
  return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([key]) => key);
}

// ---------------------------------------------------------------------------

async function main() {
  const rings = loadRings();
  const started = Date.now();
  const data = await queryOverpass(`
    [out:json][timeout:${TIMEOUT_SECONDS}];
    area["name"="Leipzig"]["boundary"="administrative"]["admin_level"="6"]->.leipzig;
    (
      way["highway"]["name"](area.leipzig);
      nwr["addr:street"]["addr:housenumber"](area.leipzig);
      nwr["addr:street"]["addr:postcode"](area.leipzig);
      node["place"~"^(suburb|quarter)$"](area.leipzig);
    );
    out tags center;
  `);
  console.log(`${data.elements?.length || 0} elements in ${Math.round((Date.now() - started) / 1000)} s`);

  const suburbs = [];
  const addressesWithPostcode = [];
  const usesAsAddress = new Map(); // street key -> how many addresses name it
  const isHighway = new Set(); // street keys seen on a real street
  const at = (el) => ({ lat: el.lat ?? el.center?.lat, lon: el.lon ?? el.center?.lon });

  for (const el of data.elements) {
    const tags = el.tags || {};
    const { lat, lon } = at(el);
    if ((tags.place === 'suburb' || tags.place === 'quarter') && tags.name && lat && lon) suburbs.push({ name: tags.name.trim(), lat, lon });
    if (isPostcode(tags['addr:postcode']) && lat && lon) addressesWithPostcode.push({ pc: tags['addr:postcode'].trim(), lat, lon });
    // Counted with a postcode only: the paths of an allotment garden have plot numbers, and none.
    if (tags['addr:street'] && tags['addr:postcode'] && isStreetName(tags['addr:street'])) {
      const key = tags['addr:street'].trim().toLowerCase();
      usesAsAddress.set(key, (usesAsAddress.get(key) || 0) + 1);
    }
    if (tags.highway && HIGHWAYS.has(tags.highway) && isStreetName(tags.name)) isHighway.add(tags.name.trim().toLowerCase());
  }
  console.log(`${suburbs.length} districts, ${addressesWithPostcode.length} addresses with a postcode`);

  // One street per name, whatever its case: "schwartzestraße" is Schwartzestraße.
  const streets = new Map();
  function street(name) {
    const key = name.toLowerCase();
    if (!streets.has(key)) streets.set(key, { name, postcodes: new Map(), suburbs: new Map(), points: [], houses: new Map() });
    const s = streets.get(key);
    if (/^[a-zäöü]/.test(s.name) && /^[A-ZÄÖÜ]/.test(name)) s.name = name;
    return s;
  }

  for (const el of data.elements) {
    const tags = el.tags || {};
    const { lat, lon } = at(el);

    if (tags.highway && HIGHWAYS.has(tags.highway) && isStreetName(tags.name)) {
      const s = street(tags.name.trim());
      if (lat && lon) s.points.push([lon, lat]);
      if (isPostcode(tags.postal_code)) s.postcodes.set(tags.postal_code.trim(), (s.postcodes.get(tags.postal_code.trim()) || 0) + 1);
    }

    const named = tags['addr:street']?.trim();
    if (!named || !isStreetName(named)) continue;
    // A name only addresses carry, and only one of them: a typo, not a street.
    if (!isHighway.has(named.toLowerCase()) && (usesAsAddress.get(named.toLowerCase()) || 0) < 2) continue;

    const s = street(named);
    const pc = isPostcode(tags['addr:postcode']) ? tags['addr:postcode'].trim() : null;
    if (pc) s.postcodes.set(pc, (s.postcodes.get(pc) || 0) + 1);
    // The district from addresses that are filled in properly: one with a postcode names its own part of town more reliably.
    const suburb = tags['addr:postcode'] ? tags['addr:suburb']?.trim() : null;
    if (suburb) s.suburbs.set(suburb, (s.suburbs.get(suburb) || 0) + 1);
    if (lat && lon) s.points.push([lon, lat]);

    if (!tags['addr:housenumber'] || !lat || !lon) continue;
    for (const number of houseNumbers(tags['addr:housenumber'])) {
      // The same number twice on one street name: another street of that name, in another postcode. Both are kept.
      const houses = s.houses.get(number) || [];
      houses.push({ pc, zone: zoneOf(lon, lat, rings), lat, lon });
      s.houses.set(number, houses);
    }
  }

  const results = [];
  let houseCount = 0;
  let twice = 0;
  for (const s of streets.values()) {
    let lat = 0;
    let lon = 0;
    for (const [pLon, pLat] of s.points) {
      lon += pLon / s.points.length;
      lat += pLat / s.points.length;
    }

    // A street nobody has put a postcode on takes the one of the address nearest to it.
    if (s.postcodes.size === 0 && s.points.length > 0) {
      const near = nearest(lat, lon, addressesWithPostcode);
      if (near) s.postcodes.set(near.pc, 1);
    }
    const postcodes = commonest(s.postcodes);
    // The district its addresses name, or else the one whose centre is nearest to the street's.
    const district = s.suburbs.size > 0 ? commonest(s.suburbs)[0] : s.points.length > 0 ? (nearest(lat, lon, suburbs)?.name ?? null) : null;

    const inside = s.points.filter(([pLon, pLat]) => pointInPolygon(pLon, pLat, rings.edge)).length;
    const delivery = s.points.length > 0 && inside === s.points.length ? 'inside' : inside > 0 ? 'partly' : 'outside';

    // The house numbers, grouped by what is theirs alone: the postcode and the ring.
    // None for a street nobody rides to: "keine Lieferung" says all there is to say about its houses.
    const groups = new Map();
    for (const [number, houses] of delivery === 'outside' ? [] : s.houses) {
      const seen = new Set();
      for (const house of houses) {
        // No postcode on the address itself: the one nearest to it has the same, on all but a boundary.
        const pc = house.pc || nearest(house.lat, house.lon, addressesWithPostcode)?.pc || postcodes[0];
        if (!pc) continue;
        const key = pc + house.zone;
        if (seen.has(key)) continue;
        seen.add(key);
        if (!groups.has(key)) groups.set(key, new Set());
        groups.get(key).add(number);
      }
      houseCount++;
      if (new Set([...seen].map((key) => key.slice(0, 5))).size > 1) twice++;
    }
    const houses = [...groups.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([key, numbers]) => `${key}:${compress(numbers)}`)
      .join(';');

    results.push({ name: s.name, postcodes, district, delivery, ...(houses ? { houses } : {}) });
  }

  results.sort((a, b) => a.name.localeCompare(b.name, 'de'));
  const json = JSON.stringify(results);
  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  fs.writeFileSync(TARGET, json, 'utf8');
  const withHouses = results.filter((r) => r.houses).length;
  console.log(`${results.length} streets, ${withHouses} of them with house numbers: ${houseCount} numbers, ${twice} of them in two postcodes`);
  console.log(`${(Buffer.byteLength(json, 'utf8') / 1024).toFixed(0)} KB -> ${TARGET}`);
}

main().catch((err) => {
  console.error('Not written:', err);
  process.exit(1);
});
