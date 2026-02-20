# Spec: Routing2 Demo Application

**Status**: backlog
**Priority**: P1
**Created**: 2026-02-17
**Owner**: conductor
**Depends on**: ui-routing2-core (Phase 1 + Phase 2 + Phase 3)

## Context

A complete working demo application that exercises all features of the new `ui-routing2`
system. The demo must prove that the architecture works end-to-end with:

- Login/auth as a routing constraint
- Nested `istate`s with `rstate`s
- Async parking for data loading
- Bookmark save/restore across login
- The statechart as single source of truth

This is NOT a toy. It should be a realistic enough app structure that we can identify
design problems before committing to the API.

## Requirements

### App Structure

1. A mock Fulcro app with a mock server remote (pathom-based, in-memory) that simulates:
   - Authentication (login/logout with configurable delay)
   - Data loading (entities with async delay)
   - Session resume check on app start

2. Statechart hierarchy:
   ```
   :state/root (initial → :state/initializing)
     :state/initializing     — check for existing session
     :state/login            — login form
     :state/logged-in        — wrapper for all authenticated routes
       routes (routing-regions)
         :state/dashboard    — rstate, simple landing
         :state/projects     — rstate, list view with async load
           :state/project    — rstate, detail with async load (nested under projects)
         :state/admin        — istate, invokes admin-panel chart
           admin-panel chart:
             :state/users    — rstate, user list
             :state/user     — rstate, user detail (nested)
             :state/settings — rstate, app settings
     :state/error            — error recovery
   ```

3. The admin panel is a separate chart invoked via `istate` with `:route/reachable`
   declaring all its targets. This demonstrates cross-chart composition.

### Login/Auth Flow

4. On app start, the chart enters `:state/initializing` which runs an async "check session"
   call. If valid session exists → `:state/logged-in`. If not → `:state/login`.

5. When not authenticated, ANY `route-to.*` event targeting a route inside `:state/logged-in`
   must be caught by the route guard, saved as a bookmark, and the user sent to login.

6. After successful login, the saved bookmark is automatically replayed — the user lands
   on the deep route they originally requested.

7. Demonstrate the full flow: user requests `/admin/users/42` while logged out →
   redirected to login → logs in → automatically navigated to user 42 detail in the
   admin panel (cascading through istate invoke + child routing).

### Async Parking

8. Each data-loading `on-entry` returns a promise (simulated delay).
   The async processor parks until it resolves before entering the next state.

9. Navigating to `:state/project` (which is inside `:state/projects`) should:
   - Enter `:state/projects`, async load project list, park
   - Enter `:state/project`, async load project detail, park
   - All within one `process-event!` call

10. Failed async loads raise error events handled by the chart (similar to routing demo).

### Bookmark Mechanism

11. The bookmark is the generalized "save and replay" from ui-routing2-core's Phase 3.
    It uses the `:route/guard` system — the guard returns `:not-authenticated`,
    which triggers `record-failed-route!`.

12. The `:state/logged-in` on-entry includes a script that checks for a saved bookmark
    and replays it (calls `override-route!` or equivalent).

13. If no bookmark exists, `:state/logged-in` enters its default child (dashboard).

### UI

14. Simple but functional Fulcro components — enough to visually verify routing works.
    Each screen shows:
    - Current statechart configuration
    - Loaded data for current route
    - Navigation buttons/links
    - Login/logout controls

15. A "state inspector" panel that shows:
    - Active configuration for all running charts (parent + invoked)
    - Data model contents
    - Whether a bookmark is saved

### What We Skip

- Code splitting / dynamic module loading (future concern)
- Actual URL/history integration (external concern, not part of this demo)
- Production styling

## Affected Modules

- **NEW**: `src/routing-demo2/` — demo app source tree
  - `chart.cljc` — top-level statechart definition
  - `admin_chart.cljc` — admin panel child chart
  - `model.cljc` — async data loaders (mock)
  - `mock_server.cljc` — mock pathom remote for auth + data
  - `data.cljc` — seed data
  - `ui.cljs` — Fulcro UI components
  - `app.cljs` — app setup and startup
- Shadow-cljs build config for the demo

## Approach

### Step 1: Mock Server

Build the mock pathom remote with:
- `(login! {:username :password})` → returns session token after delay
- `(check-session! {:token})` → returns user info or nil after delay
- `(logout! {:token})` → clears session
- Entity resolvers: projects, users, settings (with configurable delay)

### Step 2: Top-Level Chart

Build the statechart with the hierarchy described above. Key design points:
- `:state/initializing` does async session check, transitions to login or logged-in
- `:state/logged-in` contains `routing-regions` with `routes`
- Route guard on `routes` checks authentication state in data model
- Bookmark replay in `:state/logged-in` on-entry

### Step 3: Admin Panel Chart

Separate chart with its own `routes`/`rstate` definitions. Invoked via `istate` from
parent chart with `:route/reachable #{:admin/users :admin/user :admin/settings}`.

### Step 4: UI Components

Fulcro `defsc` components for each route target. Each manages its own ident and query.
The root component renders based on statechart configuration.

### Step 5: Integration Test Scenarios

Write test scenarios (can be REPL-driven or fulcro-spec) that verify:
1. Cold start → initializing → login (no session)
2. Cold start → initializing → logged-in (valid session)
3. Deep route while logged out → login → bookmark restore
4. Navigate dashboard → projects → project detail (async cascade)
5. Navigate to admin/users/42 (cross-chart, async cascade)
6. Logout while in deep route → login screen
7. Login after logout → bookmark replays last deep route
8. Error during async load → error state

## Verification

1. [ ] Mock server handles login/logout/session-check with async delays
2. [ ] App starts, checks session, routes to login or dashboard
3. [ ] Login flow works: enter credentials → async auth → enter logged-in
4. [ ] Deep route while logged out saves bookmark and redirects to login
5. [ ] After login, bookmark automatically navigates to saved deep route
6. [ ] Cross-chart routing: `(route-to! app :admin/user {:user-id 42})` works
7. [ ] Async parking: entering nested route cascades loads sequentially
8. [ ] Failed async load triggers error handling
9. [ ] Admin panel invoked chart has independent routing within itself
10. [ ] State inspector shows all active configurations and data
11. [ ] Logout clears state and returns to login
12. [ ] All test scenarios pass
