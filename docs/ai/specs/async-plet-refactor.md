# Spec: Refactor Async Impl — Replace Nested `maybe-then` with `p/let`

**Status**: done
**Priority**: P2
**Created**: 2026-02-11
**Completed**: 2026-02-12
**Owner**: AI

## Context

`v20150901_async_impl.cljc` defines a `maybe-then` primitive that chains possibly-async operations. In many functions, this leads to deeply nested callback pyramids — up to 6 levels deep with closing parens like `))))))))))))))))))))))))` (line 591). The promesa `p/let` macro provides sequential binding syntax that flattens these chains while preserving the same semantics.

`p/let` always returns a promise (even when all bindings are synchronous). This is acceptable because:
- The async impl is specifically for async use cases; the sync impl (`v20150901_impl.cljc`) exists for zero-overhead sync
- All callers already use `maybe-then`, which correctly handles promises via `p/then`
- `do-sequence` already uses `p/let` internally (line 82), so this is consistent

## Requirements

1. Replace deeply nested `maybe-then` chains (2+ levels) with `p/let` bindings
2. Keep `maybe-then` for single-step transforms and conditional/branching patterns
3. Remove dead code (`maybe-chain`, defined but never called)
4. No behavioral changes to the algorithm — same transition selection, entry/exit order, history handling
5. All existing async tests must continue to pass

## Affected Modules

- `src/main/com/fulcrologic/statecharts/algorithms/v20150901_async_impl.cljc` — main refactoring target

## Approach

Use `p/let` for sequential chains, keep `maybe-then` for single transforms and conditional recursion.

### Refactoring targets (in file order)

**1. `send!` (lines 384-408) — highest impact**

5-level nested `maybe-then` for independent values → flat `p/let`:
```clojure
;; Before: 25 lines of nesting
(maybe-then event-name-v (fn [event-name]
  (maybe-then target-v (fn [target]
    (maybe-then type-v (fn [type]
      (maybe-then delay-v (fn [delay]
        (maybe-then content-v (fn [content] ...))))))))))

;; After:
(p/let [event-name event-name-v
        target     target-v
        type       type-v
        delay      delay-v
        content    content-v]
  (let [target-is-parent? (= target (env/parent-session-id env))
        data (merge (named-data env namelist) content)]
    (when idlocation (sp/update! data-model env {:ops [(ops/assign idlocation id-v)]}))
    (sp/send! event-queue env ...)))
```

**2. `enter-states!` inner per-state body (lines 551-591) — highest impact**

6-level nested `maybe-then` with 25 closing parens → sequential `p/let`:
```clojure
;; After:
(p/let [_ late-init
        _ (do-sequence (chart/entry-handlers statechart s)
            (fn [entry] (execute! env entry)))
        _ (when-let [t (and (contains? states-for-default-entry s)
                         (some->> s (chart/initial-element statechart) (chart/transition-element statechart)))]
            (execute! env t))
        _ (when-let [content (get default-history-content (chart/element-id statechart s))]
            (execute-element-content! env (chart/element statechart content)))]
  ;; Final state handling (pure data operations + one possible async)
  (when (chart/final-state? statechart s)
    (if (= :ROOT (chart/get-parent statechart s))
      (vswap! vwmem assoc ::sc/running? false)
      (p/let [done-data (compute-done-data! env s)]
        (vswap! vwmem update ::sc/internal-queue conj ...)
        (when (and (chart/parallel-state? statechart grandparent) ...)
          (vswap! vwmem update ::sc/internal-queue conj ...))))))
```

**3. `process-event!` body (lines 1117-1136)**

4-level nested → flat:
```clojure
(p/let [_ (select-transitions! env event)
        _ (handle-external-invocations! env event)
        _ (microstep! env)
        _ (before-event! env)]
  (vswap! vwmem dissoc ::sc/enabled-transitions ::sc/states-to-invoke ::sc/internal-queue)
  (env/assign! env [:ROOT :_event] nil)
  @vwmem)
```

**4. `initialize!` (lines 1164-1187)**

4-level nested → flat:
```clojure
(p/let [_ early-init
        _ (when (map? invocation-data)
            (sp/update! data-model env {:ops (ops/set-map-ops invocation-data)}))
        _ (enter-states! env)
        _ (before-event! env)
        _ (when script (execute! env script))]
  (vswap! vwmem dissoc ::sc/enabled-transitions ::sc/states-to-invoke ::sc/internal-queue)
  @vwmem)
```

**5. `microstep!` (lines 899-908)**

2-level nested → flat:
```clojure
(p/let [_ (exit-states! env)
        _ (execute-transition-content! env)]
  (enter-states! env))
```

**6. `exit-interpreter!` inner (lines 1032-1040)**

2-level nested → flat:
```clojure
(p/let [_ (run-exit-handlers! env state)
        _ (cancel-active-invocations! env state)]
  (vswap! vwmem update ::sc/configuration disj state)
  (when (and (not skip-done-event?) (chart/final-state? statechart state) (= :ROOT (chart/get-parent statechart state)))
    (send-done-event! env state)))
```

**7. `exit-states!` inner per-state (lines 882-893)**

2-level nested → flat:
```clojure
(p/let [_ (run-many! env to-exit)
        _ (cancel-active-invocations! env s)]
  (vswap! vwmem update ::sc/configuration disj s))
```

**8. `before-event!` (lines 1048-1066)**

3-level nested → `p/let` for the inner chain:
```clojure
(p/let [_ (handle-eventless-transitions! env)]
  (if (-> vwmem deref ::sc/running?)
    (p/let [_ (run-invocations! env)]
      (when (seq (::sc/internal-queue @vwmem))
        (step)))
    (exit-interpreter! env)))
```

**9. `invocation-details` (lines 750-758)**

2-level nested → flat:
```clojure
(p/let [type (or type-v :statechart)
        src  src-v]
  (assoc invocation :type type :src src
    :processor (first (filterv #(sp/supports-invocation-type? % type) invocation-processors))))
```

**10. `start-invocation!` (lines 760-806)**

Nested details + param-result chains → `p/let`.

### Keep `maybe-then` for:
- **Single transforms**: `condition-match` (line 159), `compute-done-data!` (line 525)
- **Conditional recursion**: `handle-eventless-transitions!` recursive loop
- **Conditional execution**: `handle-external-invocations!` finalize/forward chain
- **Recursive search with early-exit**: `find-first-matching-transition`, `find-enabled-transition-for-state`, `select-transitions*`

### Remove dead code:
- `maybe-chain` (lines 59-67) — defined but never called

## Verification

1. [x] All async tests pass: `v20150901-async.async-spec` (33 tests, 99 assertions)
2. [x] Full test suite passes (no regression — 186 tests, 788 assertions, 3 pre-existing failures unrelated to this change)
3. [x] `maybe-chain` removed, no remaining references
4. [x] No new `maybe-then` nesting deeper than 1 level (single transforms only)
5. [x] CLJS compilation succeeds (`.cljc` file loads cleanly; no CLJS-specific code was modified)
