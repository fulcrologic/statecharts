# Fulcro Routing (`routing.cljc`) — CLAUDE.md

Decisions and gotchas of the statechart routing system (`scr`). Integration-wide notes: `../CLAUDE.md`.

## `routes` generates two extra states
- `:initial` is a transient `...routing.routes-initial.<id ns>/<id name>` state (the id's ns goes in the
  keyword's ns, so `:a.b/c` and `:a/b.c` differ). Its eventless transitions go to `...routes-pending...` when the node
  raised a pending child route on entry (mark `[::raised-pending-route id]`), else (unconditional, last: the testing
  env treats unmocked conds as false) to the real initial. So a module enters its pending route directly.
- Every route transition clears the mark; the pending state's fallback (denied route) goes to the initial route.
  `initial-chain-leaf` follows `::initial-route`. An explicit `initial` element child: no generated states.
- Cross transition 2 with no child session yet (owner entered this macrostep) stores `::pending-child-route`. Route
  transitions carry `::route-kind` (`:direct`, `:enter-owner` = cross 1, `:forward` = cross 2) for the guard.
  Direct ones are external: they exit/re-enter everything under the routes node's nearest compound ancestor (the
  routes node too), so an invoke on a routes node restarts on every route change there.

## URL restore is untrusted
- `url-route-event-data`: per state only its declared `:route/params`/`:route/url-params` (`route-state-index` walks
  the root chart and its istates' charts: `::statechart-id` on the element, `sfro/statechart-id`, `sfro/statechart`),
  never a `routing`-ns key; top level = the leaf path's params (deeper wins), plus `::params-by-state` and
  `::restored-params` (a copy of the top level).
- `state-route-params`: a state's `::params-by-state` entry plus top-level params that differ from
  `::restored-params` (to EVERY state; only storage filters by `:route/params`).
- `:route/url-params` travel like params but `establish-route-params-node` stores only `:route/params`, and
  URLs are built only from `[:routing/parameters]`. Keep it that way: recording them would leak them into URLs.

## URL sync state (app runtime atom, `::url-sync`)
- `:prev-urls`: last URL written/accepted. `replace-route-params!` sets it, and writes the session whose
  `:route/idents` has the target (`route-session-id`: root first, then `:invocation/id` children).
- `:restore-deferred` (`:defer-restore?`): no writes, popstate ignored; `true`, then `{:token t}` from
  `route-sync-from-url!`. `scf` `run-event!` notes the event's `::restore-token` at `::restored-token` in the local
  data of the session that dequeues it (any transition, or none); `:forward` clears its own note
  (`restore-forwarded-ops`); on-save ends the deferral at the save of the session (routing or module) whose note
  matches (a gate outside the routes used to stay deferred). A target that can't save it (no wmem, or step
  superseded/failed) -> `run-event!` sends the token to the routing session (`::restore-dropped`), never a forwarder
  (it could be stopped first: URL sync off forever). Never from the routing session itself (loops on a failing step).

## Busy guard (`busy?`)
- A route event checks only what it exits (`route-change-busy?`): `route-transitions-for` picks the transitions it
  would take, `exited-states` applies SCXML's transition domain. Exited route targets are checked; sessions invoked
  by exited states get the full `deep-busy?` walk; `:forward` recurses into the owner's child session, so the
  routing session denies (its routing-info opens). The guarding session's own actors aren't checked.
- `kept-invocation?` maps a child session to its invoking state by explicit `:id` or the generated
  `<state-id>.<uuid>` id (`:idlocation` invokes get one too). Unmatched sessions are walked (deny if busy).
- Non-route events: `deep-busy?` checks every active route target, child session and invoked session's actors.
- Cost: one `child-sessions` index per check (a scan per visited session was quadratic: 13 ms at 500
  sessions, now <1 ms); `component-busy?` builds props only when there is a busy fn.
- `find-invocation-session-id` takes an id only if its session has a configuration: exited routes keep their
  `:invocation/id` entry (don't delete it on exit: invocation cancel reads it).

## Tests
`routing/headless_app.cljc` (sync tx, `:immediate`, `url-sync-on-save`); `url_sync_replace_spec`, `url_restore_params_spec`,
`cross_chart_entry_spec`, `invocation_lookup_spec`. Test nses capture charts at load: `require :reload` them.
