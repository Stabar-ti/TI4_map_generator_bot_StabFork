# Preserve-Map Milty: zero-global-state refactor

## Context

Branch `preload-miilty` adds a "Preserve Map" milty-draft mode: instead of wiping the map and applying a stock template, draft positions are derived from color-coded placeholder tiles already on the board (`red1`, `blueblank`, …). The feature works, but its publication mechanism has two defects:

1. **Restart NPE**: the derived template is registered only in `Mapper`'s in-memory map under alias `virtual_<game>`, while that alias is persisted in the game save. After a bot restart every draft action NPEs (`Mapper.getMapTemplate` → null → `template.getTemplateTiles()`).
2. **Global leak**: registration goes into the shared `mapTemplates` map, so `virtual_*` templates leak into template pickers/autocomplete of unrelated games.

User constraint: **touch existing systems as little as possible, minimize leak surface**. This refactor eliminates global registration entirely — no `Mapper` changes, no `GameLoadService` changes, no save-format changes. All template resolution for preserve-mode happens per-game inside the milty subsystem, backed by already-persisted storage.

Verified facts this design rests on:
- The classic milty flow resolves the template alias in exactly **5 places**, all in the milty subsystem:
  - `MiltyDraftHelper.java:60` (`generateImage`, has `Game`)
  - `MiltyDraftHelper.java:227` (`sliceImageWithPlayerInfo` — private, only called from `generateImage:90`, which already resolved the model)
  - `MapTemplateHelper.buildPartialMapFromMiltyData` (~:225, has `Game`)
  - `MapTemplateHelper.buildMapFromMiltyData:75` (has `Game`)
  - `FinishDraftService.java:53` → `getPlayerHomeSystemLocation(picks, alias)` (resolves at `MapTemplateHelper:138`)
- `MiltyDraftHelper:416`/`:433` only read the alias string and pass it into the game-aware `MapTemplateHelper` methods; `MiltyDraftManager` never resolves the alias (only stores/saves/loads it); `MiltyDraftDisplayService` renders via `generateImage(game)`.
- `MiltyDraftManager.mapTemplate` is already persisted in its super-save string (save :603, load :663); any delimiter-free string round-trips.
- `game.setStoredValue`/`getStoredValue` is already persisted and fully escape-safe (`StringHelper.escape` handles `,;|:-_ \n`).
- `specs.template` is only read after the preserve branch by `GenerateSlicesService:30`, which runs **only in the non-preset path** — preserve mode requires preset slices, so it's never hit.
- `mapTemplateID` is a plain Lombok field on `GameProperties:30`; `game.mapTemplateID = null` is safe — save writes literal `"null"`, load restores it, and `SetMapTemplate` explicitly handles the `"null"` string (state exists in production games).
- Slice-image geometry (`squareSliceImageSize`, `tileDisplayCoords`) uses `sliceEmulateTiles` defaults, independent of `templateTiles` — works with a derived model.
- Placeholder round-trip is 1-based and correct (`playerNumber` indexes `DRAFT_PLACEHOLDER_COLORS` directly; `maxPlayerNumber` == real player count). No off-by-one.
- Lazy re-derivation from the map is NOT viable: home placeholders are consumed as factions get picked, but `FinishDraftService` needs home positions at draft end → serialized storage is required.
- `getPlayerHomeSystemLocation`'s other 7 callers are all in the new draft system using the `(Integer, String)` signature, which stays byte-identical (resolve-then-delegate extract).

## Step 0 — Save memory (deferred from plan mode)

User said "remember the above". Write a memory file `preserve-map-milty-design.md` (type: project) at `/Users/ericvg/.claude/projects/-Users-ericvg-Documents-GitHub-TI4-map-generator-bot-StabFork/memory/` capturing: branch purpose, the 5 resolution points, the two defects of transient global registration, the verified facts above, and the zero-global-state design below. Add pointer line to `MEMORY.md`.

## Design

**Sentinel alias**: constant `"miltyPreservedMap"` (no `|`/`;` — safe in manager save string). Stored in `draftManager.setMapTemplate(...)` → persists via existing manager save string.

**Tile data**: serialized into `game.setStoredValue("preservedMapTemplate", ...)` → persists via existing storedValue map. Compact format: tiles joined by `;`, each `pos,playerNumber,miltyTileIndex` with literal `H` instead of index for home tiles. `playerCount` = max playerNumber on deserialize.

**Resolver** (new, in `MapTemplateHelper`):
```java
public static MapTemplateModel resolveTemplate(Game game, String alias) {
    MapTemplateModel model = Mapper.getMapTemplate(alias);
    if (model == null && PRESERVED_MAP_TEMPLATE_ALIAS.equals(alias))
        model = deserializeTemplate(game.getStoredValue(PRESERVED_MAP_STORE_KEY));
    return model;
}
```
Resource templates always win; for every existing alias behavior is identical.

## Changes by file

### src/main/java/ti4/image/Mapper.java
**Revert to master.** Delete `registerTransientMapTemplate`. Zero net touch. (Kills leak #3 by construction — no global registry exists.)

### src/main/java/ti4/helpers/MapTemplateHelper.java (feature home — mostly new code)
- Keep: `DRAFT_PLACEHOLDER_COLORS` hoist, `deriveTemplateFromGameMap` (drop the `virtual_<game>` alias — use the sentinel constant; keep `setPlayerCount(maxPlayerNumber)`, it's correct).
- Add: `PRESERVED_MAP_TEMPLATE_ALIAS`, `PRESERVED_MAP_STORE_KEY` constants; `serializeTemplate(model)`, `deserializeTemplate(str)`, `resolveTemplate(game, alias)` (~25 lines).
- Modify (1 line each): `buildPartialMapFromMiltyData` and `buildMapFromMiltyData` — swap `Mapper.getMapTemplate(mapTemplate)` → `resolveTemplate(game, mapTemplate)`.
- Extract overload: `getPlayerHomeSystemLocation(Integer speakerPosition, MapTemplateModel template)` holding the existing loop body; existing `(Integer, String)` variant resolves then delegates — behavior identical for untouched callers (`AndcatReferenceCardsDraftable`, `BaseGameMiniMiltyService`, `SeatDraftable`, `SpeakerOrderDraftable`).

### src/main/java/ti4/service/milty/MiltyService.java (feature home)
Rewrite the preserve branch (~:176-186):
```java
MapTemplateModel derivedTemplate = MapTemplateHelper.deriveTemplateFromGameMap(game);
// guard: no placeholder tiles found -> return error message
// guard: derivedTemplate.getPlayerCount() < specs.playerIDs.size() -> return error message
game.setStoredValue(PRESERVED_MAP_STORE_KEY, MapTemplateHelper.serializeTemplate(derivedTemplate));
draftManager.setMapTemplate(MapTemplateHelper.PRESERVED_MAP_TEMPLATE_ALIAS);
game.setMapTemplateID(null); // honest "no standard template" state, well-supported
```
Remove `Mapper.registerTransientMapTemplate(...)` and `specs.template = derivedTemplate` (nothing reads it in the preset path). Note: guards must run **before** the irreversible `clearTileMap()`-vs-preserve fork; place them with the existing preset-slice validation at the top of `startFromSpecs` if possible (derive needs only `game`), else before the startMsg block.
Keep the guard `preserveMap requires presetSlices` (:87-90).

### src/main/java/ti4/service/milty/MiltyDraftHelper.java (2 file-internal touches)
- :60 `generateImage`: `Mapper.getMapTemplate(manager.getMapTemplate())` → `MapTemplateHelper.resolveTemplate(game, manager.getMapTemplate())`.
- `sliceImageWithPlayerInfo` (private, :225): add `MapTemplateModel` parameter passed from its only caller (:90); delete the redundant re-resolution at :227.
- `buildPartialMap`/`buildMap` (:413-444): **no changes** — they pass the alias through to `MapTemplateHelper`, which now resolves game-aware. Sentinel is non-null so the default-template fallback stays inert.

### src/main/java/ti4/service/milty/FinishDraftService.java (1 line)
- :53 → resolve via `MapTemplateHelper.resolveTemplate(game, manager.getMapTemplate())` and call a `(PlayerDraft, MapTemplateModel)` overload mirroring the existing `(PlayerDraft, String)` one (keeps the `picks.getFaction() == null` short-circuit). The resulting `pos` feeds player home-system setup (:80, :91) unchanged.

### Unchanged (previously touched or considered)
- `SliceGenerationSettings.java`, `MiltyDraftSpec.java` — keep branch's additive changes as-is.
- `Game.java`, `GameProperties.java`, `GameLoadService.java`, `GameSaveService.java`, `MiltyDraftManager.java` — untouched.

## Net footprint on pre-existing lines

| File | Touched pre-existing lines |
|---|---|
| Mapper.java | 0 (reverted to master) |
| MiltyDraftHelper.java | 2 (one public, one private-signature) |
| FinishDraftService.java | 1 |
| MapTemplateHelper.java | 3 (two 1-line resolver swaps + extract-overload) |
| Core pipelines (save/load/Game/Mapper) | 0 |

Leak surface: none — no static state is ever written; a re-draft in the same game overwrites its own storedValue; other games can never observe it.

## Resource & regression analysis

- **Static/JVM memory: zero; net reduction vs current branch** (which grows Mapper's static map one entry per preserve-game, retained for JVM lifetime).
- **Per-game storage**: one storedValue entry (~500 B for 6p), only in games using the feature — same mechanism as existing features (`keleresFlavorPreset`, `queuedMiltyPick`).
- **Compute**: `resolveTemplate` for normal games = one HashMap lookup, identical to today; deserialize fallback (~37 tokens, µs) runs only for preserve-games, once per pick/slice-image/finish. No caching needed. One redundant resolution per slice image is removed.
- **Regressions**: touched lines return the same object instance for every existing alias (resource map wins first; fallback dead unless alias == sentinel, impossible for existing games). `getPlayerHomeSystemLocation(Integer, String)` unchanged for other callers. Old saves lack the new key → nothing new executes. `mapTemplateID = null` only in preserve-games; "null template" is a pre-existing handled state.
- **Caveat**: games that started a preserve-draft under the current branch build (persisted `virtual_*` alias) would be orphaned — expected empty set (unreleased branch); verify no live test game.

## Verification

1. `mvn compile` (or the project's build) — clean compile.
2. Unit-level: round-trip `serializeTemplate(deriveTemplateFromGameMap(game))` → `deserializeTemplate` equality on pos/playerNumber/miltyTileIndex/home/playerCount (scratch test or temporary main).
3. Flow simulation (no Discord needed): construct a `Game`, place placeholder tiles (`blue1`.., `blueblank`, second color set), run `MiltyService.startFromSpecs` with preset slices + preserveMap → assert non-placeholder tiles survive, storedValue present, manager alias == sentinel, `Mapper.getMapTemplate(sentinel)` is null while `resolveTemplate` returns the model (proves nothing static is written).
4. Guards: start with preserveMap but no placeholders → friendly error; fewer placeholder colors than players → friendly error; preserveMap without preset slices → existing error.
5. Restart-path check: after simulated picks (placeholders partially consumed), `resolveTemplate` still returns full template incl. consumed home positions (reads storedValue, not map).
