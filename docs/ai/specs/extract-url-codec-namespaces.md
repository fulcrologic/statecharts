# Extract URL Codec into Dedicated Namespaces

| Field    | Value          |
|----------|----------------|
| Status   | Done           |
| Priority | P2             |
| Created  | 2026-02-19     |
| Completed| 2026-02-19     |
| Owner    | Claude         |

## Context

`route_url.cljc` mixes two distinct concerns: URL history management (protocols, browser/simulated history, navigation) and URL encoding/decoding (codec protocol, transit+base64 encoding, configuration-to-URL conversion). Extract the codec system into two new namespaces to separate the protocol from its default implementation.

All files live under `src/main/com/fulcrologic/statecharts/integration/fulcro/`.

## New Namespace: `url_codec.cljc`

**Purpose**: URLCodec protocol + codec-agnostic functions that convert statechart configuration into URLs.

**Namespace**: `com.fulcrologic.statecharts.integration.fulcro.url-codec`

**Requires**: `clojure.set`, `clojure.string`, `com.fulcrologic.statecharts` (as-alias), `com.fulcrologic.statecharts.protocols`

**Functions to move here from `route_url.cljc`** (preserve exact implementations):

| Function | Visibility | Current lines | Notes |
|----------|-----------|---------------|-------|
| `URLCodec` protocol | public | 202-208 | `encode-url`, `decode-url` |
| `element-segment` | public | 181-187 | Used by codec, path gen, and `find-target-by-leaf-name*` in route_url |
| `leaf-route-path-segments` | private | 309-348 | Calls `element-segment` |
| `path-from-configuration` | public | 350-359 | Calls `leaf-route-path-segments` |
| `params-from-configuration` | public | 361-381 | Pure data extraction |
| `leaf-route-path-elements` | private | 383-417 | Used by `configuration->url` |
| `configuration->url` | public | 419-432 | **Remove 3-arity overload** — only keep `[elements-by-id configuration data-model codec]` |
| `deep-configuration->url` | public | 434-488 | **Remove 4-arity overload** — only keep `[state-map registry session-id local-data-path-fn codec]` |

**Key decision**: `configuration->url` and `deep-configuration->url` lose their defaulting arities (the ones that call `(transit-base64-codec)`). This prevents `url_codec` from depending on `url_codec_transit`. All current callers in `ui_routes.cljc` already pass the codec explicitly.

## New Namespace: `url_codec_transit.cljc`

**Purpose**: Default `URLCodec` implementation using transit+base64 encoding.

**Namespace**: `com.fulcrologic.statecharts.integration.fulcro.url-codec-transit`

**Requires**: `clojure.string`, `com.fulcrologic.fulcro.algorithms.transit`, `com.fulcrologic.statecharts.integration.fulcro.url-codec` (for protocol + `element-segment`), `taoensso.timbre`

**CLJ imports**: `java.net.URI`, `java.util.Base64`

**Functions to move here from `route_url.cljc`**:

| Function | Visibility | Current lines | Notes |
|----------|-----------|---------------|-------|
| `base64-encode` | private | 214-218 | Cross-platform Base64 |
| `base64-decode` | private | 220-224 | Cross-platform Base64 |
| `encode-params-base64` | public | 230-237 | transit→base64 |
| `decode-params-base64` | public | 239-249 | base64→transit |
| `extract-query-param` | private | 189-196 | CLJ query string helper, used by `parse-href-parts` |
| `parse-href-parts` | private | 255-268 | Cross-platform href parser for decode |
| `TransitBase64Codec` | public record | 270-297 | Implements `URLCodec`; calls `element-segment` from url-codec, `encode-params-base64`, `decode-params-base64` |
| `transit-base64-codec` | public | 299-303 | Constructor |

**Internal reference updates**: `TransitBase64Codec` currently calls `element-segment` — after the move, this becomes `url-codec/element-segment` (or just `element-segment` if referred).

## Modified: `route_url.cljc`

**Remove**: Everything listed in the two tables above (lines 181-488 minus the `find-target-by-*` functions at 494-525).

**Add require**: `[com.fulcrologic.statecharts.integration.fulcro.url-codec :as url-codec]`

**Remove from requires** (no longer needed):
- `clojure.set`
- `com.fulcrologic.fulcro.algorithms.transit`
- `com.fulcrologic.statecharts.protocols`
- `taoensso.timbre`

**Remove from CLJ imports**: `java.util.Base64` (keep `java.net.URI` — used by `current-url-path`)

**Update internal references**:
- `find-target-by-leaf-name` (line 501) calls `element-segment` → change to `url-codec/element-segment`
- `find-target-by-leaf-name-deep` (line 518) calls `find-target-by-leaf-name` → no change (still local)

**Update docstring**: Remove mention of URLCodec/TransitBase64Codec. Describe as "URL history management for statechart-driven routing."

**Stays in route_url.cljc** (lines to keep):
- Lines 20-40: `current-url`, `current-url-path`, `push-url!`, `replace-url!`
- Lines 42-175: `URLHistoryProvider`, `BrowserURLHistory`, `SimulatedURLHistory`, inspection helpers
- Lines 494-525: `find-target-by-leaf-name`, `find-target-by-leaf-name-deep`
- Lines 527-542: `new-url-path`

## Modified: `ui_routes.cljc`

**Add requires**:
```clojure
[com.fulcrologic.statecharts.integration.fulcro.url-codec :as url-codec]
[com.fulcrologic.statecharts.integration.fulcro.url-codec-transit :as url-codec-transit]
```

**Reference changes** (line → old → new):

| Line | Old | New |
|------|-----|-----|
| 180 | `route-url/element-segment` | `url-codec/element-segment` |
| 931 | `route-url/decode-url` | `url-codec/decode-url` |
| 967 | docstring: `route-url/transit-base64-codec` | `url-codec-transit/transit-base64-codec` |
| 974 | `route-url/transit-base64-codec` | `url-codec-transit/transit-base64-codec` |
| 1037 | `route-url/deep-configuration->url` | `url-codec/deep-configuration->url` |
| 1044 | `route-url/configuration->url` | `url-codec/configuration->url` |
| 1175 | `route-url/transit-base64-codec` | `url-codec-transit/transit-base64-codec` |

**All other `route-url/` references stay** (history provider methods on lines 930, 942, 945, 976, 1047, 1092, 1093, 1110, 1112, 1121, 1127, 1129, 1138, 1152, 1158, 1164, 1183, 1191).

## Modified: Test Files

### `route_url_test.cljc`

**Add require**: `[com.fulcrologic.statecharts.integration.fulcro.url-codec :as uc]`

**Changes**:
- `ru/element-segment` → `uc/element-segment` (3 calls in "element-segment" spec)
- `ru/path-from-configuration` → `uc/path-from-configuration` (3 calls in "path-from-configuration" spec)
- `ru/find-target-by-leaf-name` stays `ru/` (it lives in route_url)
- `ru/current-url-path` stays `ru/`

### `route_url_history_spec.cljc`

**Add requires**:
```clojure
[com.fulcrologic.statecharts.integration.fulcro.url-codec :as uc]
[com.fulcrologic.statecharts.integration.fulcro.url-codec-transit :as uct]
```

**Changes in "TransitBase64Codec" specification** (lines 324-416):
- `ru/transit-base64-codec` → `uct/transit-base64-codec`
- `ru/encode-url` → `uc/encode-url`
- `ru/decode-url` → `uc/decode-url`

**Changes in "Base64 param encoding" specification** (lines 422-455):
- `ru/encode-params-base64` → `uct/encode-params-base64`
- `ru/decode-params-base64` → `uct/decode-params-base64`

**All SimulatedURLHistory tests stay `ru/`**.

### `url_sync_headless_spec.cljc`

**Add requires**:
```clojure
[com.fulcrologic.statecharts.integration.fulcro.url-codec :as uc]
[com.fulcrologic.statecharts.integration.fulcro.url-codec-transit :as uct]
```

**Changes in "URLCodec injection" specification** (lines 901-1023):
- `ru/transit-base64-codec` → `uct/transit-base64-codec` (lines ~905, 926, 988)
- `ru/URLCodec` → `uc/URLCodec` (line ~944, custom codec `reify`)

**All other `ru/` references stay** (history provider, navigation helpers).

## Verification

1. Kaocha (CLJ): `route-url-test`, `route-url-history-spec`, `url-sync-headless-spec` — all 17 tests, 190 assertions pass
2. CLJS compile: `(shadow/compile :ci-tests)` — no new warnings
