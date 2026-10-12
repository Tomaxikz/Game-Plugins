# Rust item images for the panel: research

## Current state (code read)
- `frontend/src/gameIcons.ts`: for `game === 'rust'` returns one URL, `https://wiki.rustclash.com/img/items180/<shortname>.png`. The browser loads it per view. No server cache, no skin handling. Fallback is a generic cube icon.
- `frontend/src/GameIcon.tsx`: `<img>` with `onError` stepping through sources, then a FontAwesome fallback.
- `src/items.rs`: correction to the brief. It is a Minecraft item-spec builder (`minecraft:` ids, SNBT components). It has no Rust or RustClash image logic.

## Findings
- **rustlabs.com is now RustClash.** `https://rustlabs.com` returns 301 to `https://wiki.rustclash.com/`. So options 1 and 2 are the same operator and the same host.
- **No published image terms found.** `https://wiki.rustclash.com/` returned HTTP 403 to our fetcher, so its terms could not be read. A uMod thread describes the `img/items180/` path and says new items are added per patch: https://umod.org/community/image-library/47513-solution-to-images-not-showing
- **Facepunch fan guidelines** (https://facepunch.com/legal/ugc/): fan content is permitted "within reason and if it follows these guidelines", and must be "clear that it's unofficial". Commercial use beyond platform revenue needs "written approval". The guidelines say nothing about asset reuse or third-party sites. "All rights and other matters not in these guidelines are reserved to us."
- **Facepunch ToS** (https://facepunch.com/legal/tos): Facepunch "owns or licences the Facepunch Services in their entirety" (4.1). Users may not use Facepunch names or logos "for commercial purposes without permission" (3.1(vii)). Users may not "reverse engineer, decompile or attempt to obtain or use source code" (3.1(iii)).
- **Facepunch modding terms** (https://facepunch.com/legal/modding): "you can't distribute the underlying Game or other Facepunch property." Facepunch still owns "everything in them (e.g. game name, setting, gameplay and appearance)".
- **Facepunch IP policy** (https://facepunch.com/legal/ip): DMCA takedown route, legal@facepunch.com. No fan-use statement.
- **Steam Subscriber Agreement** (https://store.steampowered.com/agreement): Valve bars use "as part of content aggregation networks, websites or services, without the prior written consent of Valve". Fan art is "solely on a non-commercial basis". Valve says it "does not screen such third-party content".
- **Steam ItemDef schema** (https://partner.steamgames.com/doc/features/inventory/schema): the schema has `icon_url` (recommended 200x200) and `icon_url_large`. Valve's page recommends self-hosting these images. It contains no licence grant.
- **Steam Web API** (https://partner.steamgames.com/doc/webapi/IEconService): no rate-limit statement. The IEconItems_252490 page (https://partner.steamgames.com/doc/webapi/IEconItems_252490) has no entry for `GetItemDefArchive`, so that method is unverified.
- **Steam branding** (https://store.steampowered.com/publishing/branding): no rule on third-party use of game art.
- **Community icon packs on GitHub**: none found for Rust item art. Open-source licences would not cover art owned by Facepunch.
- **Local extraction tools**: AssetStudio is MIT-licensed (https://github.com/Perfare/AssetStudio). The licence covers the tool, not the Rust art inside the client. Extraction conflicts with ToS 3.1(iii) and ToS 3.1(ii) ("copy, rent, steal, sell...").

## Options
| # | Source | Legal / ToS | Reliability | Coverage / new items | Skins | Rate limits | Hotlink or cache | Maintenance |
|---|---|---|---|---|---|---|---|---|
| 1 | RustClash `img/items180` | No terms readable (403). Third-party art, no grant | Unknown, single operator | Claims per-patch additions (uMod) | Not known | Unknown | Must cache server-side | Low, but breaks if path changes |
| 2 | rustlabs.com | Redirects to RustClash, same as 1 | Same as 1 | Same as 1 | Same as 1 | Same as 1 | Same as 1 | Same as 1 |
| 3 | Facepunch hosts / Steam Market images | No Facepunch icon endpoint found. Steam forbids aggregation without consent | Not verified | Not verified | Not verified | Not verified | Do not hotlink for art | Medium |
| 4 | Steam ItemDef schema / Web API | Metadata usable. Images under Valve terms, no grant | Official API, undocumented methods | Schema gives names and ids, not guaranteed art | Skin ids via schema are plausible, not verified | No stated limit found | Schema: cache metadata. Images: self-host | Medium |
| 5 | GitHub icon packs | None found for Rust | n/a | n/a | n/a | n/a | n/a | n/a |
| 6 | Local extraction from owner's client | Conflicts with ToS 3.1(iii) and 3.1(ii). Redistribution barred by modding terms | Works offline | Full, per client build | Yes, if extracted | None | Never redistribute | High: per patch |
| 7 | Own generated category icons | Always legal (our own art) | Ours | Full, by category | Generic only | None | Serve from our backend | Low |

## Recommendation
1. **Primary:** cache RustClash `items180/<shortname>.png` server-side on first successful fetch, keyed by shortname. Serve from our own backend. Never hotlink per view. Request Facepunch or RustClash permission in writing (legal@facepunch.com) before shipping.
2. **Fallback 1:** our own generated category SVG (weapon, ammo, medical, clothing, tool, etc.), following the existing `hytaleCategoryIcon` pattern in `gameIcons.ts`.
3. **Fallback 2:** a text badge with the shortname, so unknown items stay readable.
- **Cache vs hotlink:** cache all art server-side with a fetch timestamp and a 404 negative cache. Hotlink nothing. Use the Steam schema for names and skin ids only.
- **Skins:** show the base item icon plus a skin-id badge. Do not fetch Steam Market or Workshop art. Only add skin art if written permission is granted.
- **Must NOT:** ship extracted client art or commit it to the repo; hotlink Steam or Facepunch CDNs for art; scrape the Steam Market at runtime; call the Steam API on each page view; use the Facepunch or Rust logo or name in ways that imply official status; monetise the panel with Facepunch art without written approval.
