# Pluggable URL Codec

| Field    | Value          |
|----------|----------------|
| Status   | Done           |
| Priority | P2             |
| Created  | 2026-02-19     |
| Completed| 2026-02-19     |
| Owner    | Claude         |

## Context

URL encoding is currently hardcoded in `route_url.cljc`. The encode path
(`configuration->url` / `deep-configuration->url`) always produces
`/Seg1/Seg2?_p=<transit+percent-encoded>`. The decode path
(`url->route-target`) always expects that exact format. This makes every URL
opaque — fine for apps, terrible for SEO, bookmarkability, and human
readability.

The goal is to make encode/decode **pluggable** so that:
1. The default codec changes to transit → base64 → uri-encode (base64 uses
   `=` and `+` which are URI-significant, so the base64 string itself must be
   uri-encoded).
2. Advanced users can write codecs that produce SEO-friendly URLs like
   `/users/42/posts?sort=date&page=3` by reading annotations on statechart
   elements.

## Current Data Flow

### Encode (state → URL)

```
configuration + data-model
        │
        ├─ leaf-route-path-segments  →  ["AdminPanel" "UserDetail"]
        │     walks :route/target up to :routing/root
        │
        └─ params-from-configuration →  {:user-detail {:user-id 42}}
              reads [:routing/parameters state-id] from data-model

        ╰── configuration->url joins them:
              "/AdminPanel/UserDetail?_p=<transit+pct-encoded>"
```

### Decode (URL → state)

```
"/AdminPanel/UserDetail?_p=..."
        │
        └─ url->route-target  →  {:leaf-name "UserDetail"
                                   :segments  ["AdminPanel" "UserDetail"]
                                   :params    {:user-detail {:user-id 42}}}
```

### Element annotations available today

On `rstate` / `istate` elements:
- `:route/target`  — keyword (component registry key)
- `:route/segment` — optional string override for the URL segment
- `:route/params`  — set of keywords naming the route parameters
- `:route/reachable` — (istate only) set of keywords reachable through child chart

## Requirements

### R1 — URLCodec protocol

A protocol with two functions: encode and decode. The encode function receives
a **rich context map** (not just raw bytes) so the codec can make structural
decisions. The decode function receives the URL string plus the same element
metadata so it can reverse the encoding.

### R2 — Encoding context (what the encoder sees)

The encoder needs enough information to produce any reasonable URL shape. The
context map passed to `encode` should contain:

```clojure
{;; State IDs of the active route path, in parent→leaf order.
 ;; The string segment for each is derivable: look up the ID in :route-elements,
 ;; then use :route/segment or (name :route/target).
 :segments [:admin-panel :user-detail]

 ;; Parameters keyed by state-id, each value is a map of param-key → value.
 ;; These are the values the encoder must somehow embed in the URL.
 :params {:user-detail {:user-id 42 :tab "settings"}}

 ;; Route elements from the active path, indexed by state ID for easy lookup.
 ;; Each value is the full element map from elements-by-id, so the encoder
 ;; can read any annotation it needs (including :route/encoding — see R4).
 :route-elements {:admin-panel {:id :admin-panel :route/target :AdminPanel ...}
                  :user-detail {:id :user-detail :route/target :UserDetail
                                :route/params #{:user-id :tab}
                                :route/encoding {:path-params [:user-id]
                                                 :query-aliases {:tab "t"}} ...}}}
```

**Why `:route-elements`?** The encoder author may want to:
- Positionally embed certain params as path segments (e.g., `/users/42`)
- Rename query params for brevity (`tab` → `t`)
- Omit segments entirely when embedding params inline
- Use completely custom URL patterns

The elements give the encoder full access to the statechart author's intent
without the framework needing to understand every possible annotation.

### R3 — Decoding contract

`decode` receives a URL string and the `route-elements` map (the same active
route elements the encoder saw). It must return:

```clojure
{:leaf-id :user-detail                              ;; state ID of the leaf route target
 :params  {:user-detail {:user-id 42 :tab "settings"}}}  ;; params keyed by state ID
```

The decoder is responsible for resolving the URL all the way to a leaf state
ID — it has `route-elements` to do the lookup. The framework then uses the
leaf ID directly to raise the routing event with the params. No intermediate
segment strings needed.

### R4 — `:route/encoding` annotation on elements

Arbitrary **optional, opaque** keys MAY be placed on `rstate`/`istate` elements. The framework
ignores these — but because the codec has the elements, it can use them.
I.e. The codec author defines them.

Example annotations an SEO codec might use:

```clojure
;; Embed :user-id directly in the path: /users/42
(rstate {:route/target :UserDetail
         :route/params #{:user-id :tab}
         :route/encoding {:path-params [:user-id]     ;; positional in path
                          :query-aliases {:tab "t"}}} ;; ?t=settings
  ...)

;; Custom segment pattern
(rstate {:route/target :BlogPost
         :route/params #{:year :slug}
         :route/encoding {:pattern "blog/:year/:slug"}}  ;; /blog/2026/hello-world
  ...)
```

### R5 — State ID is always derived; collisions are errors

The `:id` of an `rstate`/`istate` is **always** `(coerce-to-keyword target)`.
Passing an explicit `:id` to `rstate` or `istate` must be a compile-time error
(throw from the function itself during chart construction).

This eliminates the latent bug where auto-generated transitions use
`:route/target` as the SCXML transition target — since `:id` always equals
`:route/target`, they are guaranteed to match.

**Runtime collision detection**: When `deep-configuration->url` (or any
flattening step) collects route elements across the invocation tree, it must
check for duplicate state IDs and throw if any are found. This catches the
case where the same component appears as a leaf in multiple places in the
composed tree. The fix for the (rare) user who needs this is to create a
thin wrapper component with a distinct registry key.

### R6 — Default codec: transit + base64 + uri-encode

Replace the current `encode-params`/`decode-params` with a default codec that:

1. **Encode**: transit-clj→str → base64-encode → uri-encode
   (base64 alphabet includes `+`, `/`, `=` which are URI-significant)
2. **Decode**: uri-decode → base64-decode → transit-str→clj

The URL shape stays `/Seg1/Seg2?_p=<encoded>`. This is a drop-in replacement
for the current encoding — just changes the wire format of `_p`.

### R7 — Codec injection point

The codec must be injectable at `install-url-sync!` time (or wherever the
URL sync machinery is initialized). When no codec is supplied, the default
transit+base64 codec is used.

Use parameter: `:url-codec` on the options map passed to `install-url-sync!`.

### R8 — Round-trip invariant

For any codec `c` and encoding context `ctx`:

```
(let [url    (encode-url c ctx)
      result (decode-url c url (:route-elements ctx))]
  (= (:leaf-id result) (last (:segments ctx)))   ;; resolves to same leaf
  (= (:params result) (:params ctx)))             ;; params survive round-trip
```

## Approach

### Phase 1 — Extract the protocol and default codec

1. Define `URLCodec` protocol in `route_url.cljc`:
   ```clojure
   (defprotocol URLCodec
     (encode-url [this context]
       "Given an encoding context map, return a URL path string (with query if needed).")
     (decode-url [this href route-elements]
       "Given a URL string and route-elements map, return {:leaf-id <state-id> :params <map>}."))
   ```

2. Implement `TransitBase64Codec` as the default:
   - Encode: derive segment strings from IDs via route-elements, join with `/`,
     append `?_p=<transit→base64→uri-encode>` if params
   - Decode: split path on `/`, match segments to route-elements to find leaf ID,
     extract `_p`, uri-decode→base64-decode→transit

3. Wire codec into the encode/decode call sites:
   - `configuration->url` and `deep-configuration->url` call `encode-url`
   - `url->route-target` calls `decode-url`
   - The codec instance is stored in the URL sync runtime state

### Phase 2 — Build the encoding context

1. Modify `configuration->url` (and deep variant) to build the context map:
   - Collect `:route-elements` by walking the same path as `leaf-route-path-segments`
   - Include the full element maps (already available from `elements-by-id`)
   - Bundle `:segments` and `:params` as before

2. Any custom keys the user puts on `rstate`/`istate` elements are already
   preserved on the element map — no framework changes needed for annotations.

### Phase 3 — Injection and wiring

1. Add `:url-codec` option to `install-url-sync!`
2. Store in the URL sync runtime state alongside the history provider
3. Thread through to all encode/decode call sites

## Non-goals

- Writing an SEO codec (that's a user-space concern; we just enable it)
- Changing the path segment derivation logic (still walks `:route/target`)
- Breaking existing URL sync behavior (default codec produces same URL shape)

## Verification

- [ ] Default codec round-trips: `(= params (-> (encode ...) (decode ...)))`
- [ ] `_p` value is now base64+pct-encoded (not raw transit+pct-encoded)
- [ ] Custom keys on elements are visible in the encoding context's `:route-elements`
- [ ] Custom codec can be injected and used end-to-end
- [ ] All existing `route_url_history_spec` and `url_sync_headless_spec` tests pass
- [ ] Passing `:id` to `rstate`/`istate` throws at chart construction time
- [ ] State ID collision across invocation tree throws at runtime
- [ ] No regressions in routing-demo2

## Resolved Questions

1. **Encoder receives only the active route elements** (`:route-elements` map),
   not the full `elements-by-id`. Same for decoder — it gets the route elements
   only. This is sufficient since it covers the full active path.

2. **One codec call per URL**. `deep-configuration->url` flattens all segments
   and params across the invocation tree first, then calls the codec once.
   There is only one route/URL.

3. **Custom element annotations are opaque**. The framework does not define or
   validate any annotation key (like `:route/encoding`). Codec authors put
   whatever they want on elements — the framework just passes the elements
   through.

4. **State ID = route/target, always**. Passing explicit `:id` to `rstate` or
   `istate` is a compile-time error. Collisions across the composed invocation
   tree are a runtime error. Users needing the same component in multiple tree
   positions must create a thin wrapper with a distinct registry key (rare).
