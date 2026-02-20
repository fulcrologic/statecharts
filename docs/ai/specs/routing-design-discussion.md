# Routing System Design Discussion

## Date: 2026-02-17

## Context

Analysis of the current `ui_routes.cljc` routing system and the routing demo, leading to
a design for composable, code-splittable statechart-driven routing with externalized history.

## Current System Analysis

### Two Approaches Exist Today

**1. `ui_routes.cljc` (Fulcro-integrated routing)**

Full Fulcro integration with idents, dynamic queries, and normalized state.

Ident resolution in `initialize-route!` follows this cascade:
- `ro/initial-props` option → called as `(initial-props env data)`, result used with `rc/get-ident`
- Form auto-detection → pulls `{id-key id}` from event data
- Fallback → `(rc/get-initial-state Target event-data)` and derives ident

Parameters stored via `establish-route-params-node`:
- Checks event data for declared `:route/params` keys
- Falls back to URL state params
- Stores at `[:routing/parameters state-id]` in data model

Internal storage locations (in statechart data model):

| Data | Location | Purpose |
|---|---|---|
| Route idents | `[:route/idents <registry-key>]` | Fulcro join targets |
| Route params | `[:routing/parameters <state-id>]` | Per-state parameters |
| Failed route | `::failed-route-event` | Force-continue on denied routes |
| Invocation IDs | `[:invocation/id <target-key>]` | Child session tracking |

Also mutates Fulcro state externally:
- `merge/merge-component!` — initializes component in Fulcro state atom
- `rc/set-query!` + `swap! state-atom` — rewires parent query joins

**2. Routing Demo (simple approach)**

No `rstate`/`istate`. Stores everything flat in the data model:
- `[:ROOT :route/params]` — all event data
- `[:ROOT :current-event]`, `[:ROOT :current-day]`, `[:ROOT :current-menu]`

Renders by reading directly from `::sc/local-data` in Fulcro state. No idents, no dynamic
queries, no `merge-component!`. Statechart data model is the single source of truth.

### Global State in `ui_routes.cljc`

Three module-level singletons:
- `history` (volatile!) — HTML5 history integration instance
- `route-table-atom` (atom) — precomputed route table for URL matching
- `session-id` (constant `::session`) — not parameterized

This means only ONE routing statechart can exist per JVM/browser context.

## Design: Externalized History

### Principle

The statechart is the **source of truth**. The URL is a **projection** of the chart's state.
User editing the URL (back button, literal edit) is a **request** to the chart, not a command.
If the chart refuses, the external sync system auto-fixes the URL to match.

This eliminates a huge class of SPA bugs.

### What Moves Out of `ui_routes`

- `establish-route-params-node` URL-reading logic
- `undo-url-change` entirely
- `apply-external-route` entirely
- `state-for-path` and the route table
- `history` atom and `route-table-atom`
- The `route->url` / `url->route` fns in `start-routing!`

### What Stays

- `initialize-route!` — ident resolution, Fulcro merge
- `update-parent-query!` — dynamic query patching
- `establish-route-params-node` — reduced to just `ops/assign` from event data
- `busy?` / `record-failed-route!` / `override-route!` — all event-based already
- `routing-info-state` — the denied-route modal state machine
- `routes` / `routing-regions` — chart structure helpers
- All render helpers (`ui-current-subroute`, `ui-parallel-route`)

### The Parameter Story Gets Simpler

Currently `establish-route-params-node` has awkward branching:
```
event has params? -> use event data
else URL has params? -> use URL params
else -> empty
```

With externalization, this collapses to: **always use event data**. The external layer
reads the URL and puts params into the event data before sending. The chart node just does
`(ops/assign [:routing/parameters id] (select-keys event-data params))`.

### Login/Auth Redirect Flow

The failed-route mechanism is already event-based, not URL-based:

1. App loads. Chart starts, enters initial state.
2. External layer reads URL, sends routing event into chart
3. Auth guard rejects -> `record-failed-route!` stores the **event** (not URL)
4. Chart transitions to login state
5. External layer observes configuration = login, updates URL
6. User logs in -> chart fires `override-route!` -> re-sends stored event
7. Chart enters target state with params
8. External layer observes new configuration, updates URL

The chart never touches the URL. The external layer watches configuration and maps bidirectionally.

## Design: Composable Routing with Code Splitting

### Problem

A programmer wants `(route-to! this UltimateTarget)` — not manual sequencing through
chart hierarchies. And child charts should support dynamic code-split module loading.

### Solution: Declared Reachable Targets

Instead of passing the child chart to `istate` at build time (which defeats code splitting),
declare the **symbols** of reachable targets as metadata:

```clojure
(istate {:route/target    :my.ns/AdminPanel
         :route/reachable #{:my.ns/dashboard :my.ns/users :my.ns/user-detail}}
  ...)
```

**Coupling direction is correct:**
- Parent -> child: only knows symbols (keywords). No code dependency.
- Child -> parent: can import parent's declaration and validate against it.

### How `routes` Uses Reachable Sets

`routes` reads `:route/reachable` to auto-generate cross-chart transitions:

```clojure
;; Direct targets (current behavior)
(transition {:event :route-to.my.ns/main :target :my.ns/main})

;; Composed targets (new — auto-generated from :route/reachable)
(transition {:event :route-to.my.ns/users :target :my.ns/admin-panel}
  (script {:expr (fn [_ _ _ event-data]
    [(ops/assign ::pending-child-route
       {:event :route-to.my.ns/users :data event-data})])}))
```

### Cascade Through Invoke Params

1. Top-level receives `:route-to.my.ns/users`
2. Transitions to `:my.ns/admin-panel`, stores pending child route
3. `istate` reads pending route, passes through invoke params
4. Child chart starts, reads invoke params, self-routes to `:my.ns/users`

With the async processor, this entire cascade completes within one `process-event!` call.
Parking ensures each level's async on-entry (data loading etc.) completes before the next.

### Recursive Composition

```
Parent chart
  istate {:route/target :my.ns/AdminPanel
          :route/reachable #{:my.ns/dashboard :my.ns/users :my.ns/user-profile}}
    Child chart (admin-panel)
      rstate :my.ns/dashboard
      rstate :my.ns/users
      istate {:route/target :my.ns/UserManager
              :route/reachable #{:my.ns/user-profile}}
        Grandchild chart
          rstate :my.ns/user-profile
```

Each istate declares the **full transitive set** reachable through it. Each level only
needs to know "which of MY istates can reach this target?" — a simple lookup.

### Validation

Child chart validates parent's declaration at startup:

```clojure
(defn validate-reachable! [parent-declared actual-chart]
  (let [actual (find-all-route-targets actual-chart)]
    (when-not (= parent-declared actual)
      (log/error "Route reachable mismatch!"
        {:missing-from-parent (set/difference actual parent-declared)
         :stale-in-parent     (set/difference parent-declared actual)}))))
```

Dev-time helper generates the set:

```clojure
(defn reachable-targets [chart]
  (find-all-route-targets (chart/statechart-normalize chart)))
```

### `route-to!` Stays Simple

```clojure
(route-to! this :my.ns/user-profile {:user-id 42})
```

One call, one event to the top-level chart. The cascade handles the rest.

## External History Layer: Observation Model

The history layer has **full read access** to all sessions (via `::sc/local-data` in the
Fulcro state atom) and **controlled write access** (only sends events to the top-level chart).

**Read direction (state -> URL):**
Walk the chain: read parent configuration -> find active istates -> read their child
session IDs (from `[:invocation/id target-key]`) -> read child configurations -> repeat.
The full active leaf path plus all `[:routing/parameters state-id]` at each level gives
everything needed to construct the URL.

**Write direction (URL -> state):**
Always send to the top-level chart. The cascade handles reaching the target.
If the chart refuses (busy guard), configuration doesn't change, history layer sees
mismatch, pushes URL back to match the chart's actual state.

The parent chart doesn't need to proxy anything. Same relationship a debugger has to a
running program: full read access, controlled write access through a defined interface.

## Route Table Precomputation

The current `route-table-atom` exists solely for URL-to-state reverse lookup. With
externalized history, this moves to the external layer. Even without externalization,
it's an optimization not a necessity — `state-for-path` already does a linear scan as
first attempt, and realistic route counts (dozens, not thousands) make the scan fine.

## Key Insights

1. Chart-building functions (`rstate`, `istate`, `routes`) are already session-agnostic
2. The runtime API layer needs parameterized session IDs (trivial change)
3. The failed-route mechanism is already event-based, not URL-based
4. Invoke params naturally bridge parent-child chart boundaries
5. Async parking ensures cascading route entry completes within one `process-event!`
6. Declared reachable sets enable code splitting while maintaining composability
7. Validation catches staleness — child verifies parent's declarations at startup
