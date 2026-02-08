# Spec: Add Missing SCXML System Variables

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-08
**Owner**: conductor

## Context

The W3C SCXML spec (Section 5.7) requires several system variables that this library doesn't expose in the data model:

. **`_sessionid`** - Session ID bound at load time. Currently tracked internally (`::sc/session-id`) but not in the data model. Accessible via `env/session-id` function but not as a data model variable.

. **`In()` predicate** - Section 5.9 requires all data models support this. The functionality exists (`runtime/current-configuration`) but isn't exposed as a predicate in the execution environment. Additionally, `environment.cljc` has an `In` alias for `is-in-state?` but it requires the env parameter — not the same as the spec's zero-context `In()`. Write an `In(state)` helper that returns a function. Put it in the elements namespace.

. **`_name`** - Statechart name attribute. Exists on chart but not exposed in data model.

Be careful of circular references. You might need to simply destructure data manually.

## Requirements

. Bind `_sessionid` in data model during initialization (`v20150901_impl.cljc:820`)
. Provide `In` predicate accessible from expressions without explicit env
. Optionally bind `_name` (low priority)
. None of these should be breaking changes

## Affected Modules

- `src/main/com/fulcrologic/statecharts/algorithms/v20150901_impl.cljc` - Initialization
- `src/main/com/fulcrologic/statecharts/execution_model/lambda.cljc` - `In()` predicate
- `src/main/com/fulcrologic/statecharts/data_model/` - Variable storage

## Approach

### Slice 1: _sessionid

Assign `[:ROOT :_sessionid]` during `initialize!` after session-id is established.

### Slice 2: In() Predicate

Add `In` function to the data passed into elements.cljc

```clojure
(defn In
  "Can be used as a shorthand as the sole value of the `:cond` (e.g. on if/transition elements)
   when using the lambda execution model."
  [state-id]
  (fn [{::sc/keys [vwmem] :as _env} & _]
    (boolean
      (some-> vwmem deref ::sc/configuration (contains? state-id)))))
```


### Slice 3: _name (optional)
Lower priority, implement if straightforward.

## Verification

. [ ] `:_sessionid` accessible from expression lambdas via data model
. [ ] `:_name` accessible from expression lambdas via data model
. [ ] `In(:state-id)` works in condition expressions
. [ ] Existing tests still pass (no regression)
. [ ] New tests verify variable availability
