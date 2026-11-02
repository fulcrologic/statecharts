# Fulcro Integration — CLAUDE.md

Connects statecharts to Fulcro UI framework. This is the most complex subsystem with **10-20% test coverage — critical gap**.

## File Map
- `fulcro.cljc` main API (`start!`, actors, event queue/holds); `fulcro_impl.cljc` data model, WM store
- `fulcro/hooks.cljc` React hooks; `fulcro/operations.cljc` load/remote ops; `fulcro/routing.cljc` the routing API,
  `routing_options.cljc`, `routing/url_codec*.cljc`, `routing/url_history.cljc`
- Deprecated: `fulcro/rad_integration.cljc` (successor `com.fulcrologic/fulcro-rad-statecharts`),
  `route_history.cljc`, `ui_routes.cljc` (`ui_routes_options.cljc` is shared)

Data model: Fulcro app state; `:fulcro/state-map` paths reach the whole state, actor paths go through the ident.

## Expression Arity (CRITICAL)
`install-fulcro-statecharts!` always uses `{:explode-event? true}`: every expression fn (`:expr`, `:cond`, `:srcexpr`,
invoke `:params` values, `entry-fn`/`exit-fn`/`script-fn`) gets `(env data event-name event-data)`. **Always write
`(fn [env data & _] ...)` or the full 4-arity.** 2-arity throws `ArityException` in CLJ only (CLJS ignores extra args);
`run-expression!` catches and logs it, and kaocha's output capture hides the log.
- Audit 2026-10-31: `application-chart`'s 2-arity `:done.invoke.*` script leaked finished sessions (fixed).
- Component-option fns (`initial-props`, `busy?`, `actors`) are called by library code, not the execution model.

## Op Ordering Guarantee
An expression's ops land in vector order in every tx mode, in or out of a transaction: state ops (incl. `apply-action`)
`swap!` at once, then `update!` schedules one render; `load`/`invoke-remote` submit a transaction. Never route a state
op through `transact!`/`transact!!`: sync tx processing queues one submitted inside a running tx until it ends (the old
`apply-action`). It now reports to Inspect as `do-apply-action`. Tests: `fulcro/apply_action_order_spec`.

## Event Processing Guard
- One `processing?` flag per app (queue `receive-events!`): a nested processing call returns at once, its events wait.
  Never hold it while a promise is pending (holding it across an async `start!` entry froze every session).
- Holds (`holds`: sid -> generation): a held session's events STAY in the mpq (`::session-held?`), so `cancel!`
  reaches them. `run-event!` holds the target for its step (and its promise) ONLY if unheld (`try-hold!`), else
  returns `::mpq/requeue` (joining a restart's hold made the old step "current"). No hold counts.
- `::defer-processing sid f` (`start!`, invocations) is a restart: new generation replaces the hold; the old
  session's self-sent queued events are cancelled (others' go to the new one). `(f current? superseded?)`. Known gap
  (needs generation-stamped events): one already taken off when the restart lands still reaches the new session.
- Stale releases are ignored; a superseded step/start skips its own sid's save (`guarded-working-memory-store`). Its
  env has `::superseded?`: true while it RUNS but lost its hold (restart, stop); queue `send!`/`cancel!` and
  invocation calls made WITH THAT ENV are then dropped (`scf/send!`/`scr/route-to!` with the app still go out).
  `running` resets before the release. Not after it's over: RAD needs a restarted report's old load result (by id),
  so `fop/load` results drop the key (`fulcro_impl`), like mutations.
- Handlers (`process-events!`, `cael`) must use the store from the env they get, and return nil or the processor's
  promise, never the save's value (an `:on-save` promise held the session).
- `:immediate` drain loop: repeats while unheld due events exist (max 100); `released?` covers a release on
  another thread after its last check (a sync start's too). An async parent's step awaits its invocation's start.

## Known Issues
- A superseded or stopped session's pending step still runs its expressions when its promise settles: their data
  ops write local data and Fulcro state directly (the execution model holds the data model). Sends, cancels and
  invocation calls made with its env are dropped and the wmem save is skipped, but writes, and sends/routes
  made with the app, are not gated (deliberate; documented as a KNOWN LIMITATION in the `:async?` docstring).
- A restart (`start!` of a live id) doesn't stop the old session's invoked children; they keep running.
- `fulcro_impl.cljc` duplicates `dissoc-in` (`data_model/`); `ui_routes.cljc` FIXMEs at lines 231 and 376.
- Option docstrings disagree with callers: `ro/actors`/`sfro/actors` say `(fn [])` but get `(env data)`;
  `sfro/busy?` says `(fn [env data])` but `routing/check-component-busy?` calls `(busy-fn app props)`
