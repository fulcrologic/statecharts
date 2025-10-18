# Data Loading

## Loading Overview

### Core Function: `load!`
Primary mechanism for loading data from servers into normalized client database.

### Three Basic Scenarios

**IMPORTANT**: `load!` has NO auto-targeting. Placement in the tree requires explicit `:target` option.

1. **Load to ROOT** (keyword): Normalizes and places at database root
   - `(df/load! app :friends Person)` → `{:friends [[:person/id 1] [:person/id 2]]}` at ROOT
2. **Load by ident**: Only normalizes into tables, does NOT place ident anywhere
   - `(df/load! app [:person/id 3] Person)` → Updates `{:person/id {3 {...}}}` only
3. **Load with :target**: Normalizes AND places idents at specified location
   - `(df/load! app :friends Person {:target [...]})` → Normalizes + places at target

## Server Setup for Loading

### Remote Configuration
```clojure
(ns app.application
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]))

(defonce app (app/fulcro-app
               {:remotes {:remote (http/fulcro-http-remote {})}}))
```

### Pathom Resolvers
```clojure
(ns app.resolvers
  (:require
    [com.wsscode.pathom.connect :as pc]))

;; Entity resolver
(pc/defresolver person-resolver [env {:person/keys [id]}]
  {::pc/input  #{:person/id}
   ::pc/output [:person/name :person/age]}
  (get people-table id))

;; List resolver  
(pc/defresolver list-resolver [env {:list/keys [id]}]
  {::pc/input  #{:list/id}
   ::pc/output [:list/label {:list/people [:person/id]}]}
  (when-let [list (get list-table id)]
    (assoc list :list/people (mapv (fn [id] {:person/id id}) (:list/people list)))))

;; Root resolvers
(pc/defresolver friends-resolver [env input]
  {::pc/output [{:friends [:list/id]}]}
  {:friends {:list/id :friends}})
```

## Loading Patterns

### 1. Loading to Root
```clojure
;; Load root-level properties
(df/load! app :friends PersonList)
(df/load! app :enemies PersonList)
```

Generates query: `[{:friends (comp/get-query PersonList)}]`

### 2. Loading Specific Entities (by ident)
```clojure
;; Load specific person - only normalizes, doesn't place in tree!
(df/load! this [:person/id 3] Person)
;; Result: {:person/id {3 {:person/id 3 :person/name "..."}}}
;; The ident [:person/id 3] is NOT placed anywhere in the tree!

;; To place it in the tree, use :target
(df/load! this [:person/id 3] Person
  {:target [:component/id :main-panel :current-person]})
;; Now [:person/id 3] is placed at the target location
```

Generates query: `[{[:person/id 3] (comp/get-query Person)}]`

### 3. Loading with Targeting
```clojure
;; Load and append to existing list
(df/load! this [:person/id 3] Person 
  {:target (targeting/append-to [:list/id :friends :list/people])})

;; Load and replace single reference
(df/load! this [:person/id 3] Person
  {:target [:current-user]})

;; Load and prepend to list
(df/load! this [:person/id 3] Person
  {:target (targeting/prepend-to [:root/people])})
```

## Targeting Options

### The Three-Element Rule

Because of normalization, targeting NEVER requires deep paths. Maximum depth is 3 elements: `[table-name id field]`

```clojure
;; ❌ You DON'T need deep paths like this:
{:target [:component/id :root :main-panel :user-profile :friends-list :friends]}

;; ✅ You only need this:
{:target [:component/id :friends-list :friends]}

;; Why? Because :friends-list is already a normalized reference!
```

### Available Targeting Functions
```clojure
(require '[com.fulcrologic.fulcro.algorithms.data-targeting :as targeting])

;; Replace single value
:target [:path :to :location]

;; Append to vector (if not already present)
:target (targeting/append-to [:path :to :vector])

;; Prepend to vector
:target (targeting/prepend-to [:path :to :vector])

;; Replace entire vector
:target (targeting/replace-at [:path :to :vector])
```

### Multiple Targets
```clojure
(df/load! this [:person/id 3] Person
  {:target [(targeting/append-to [:list/id :friends :list/people])
            [:current-selection]]})
```

## Loading Parameters

### Query Parameters
```clojure
(df/load! this :all-people Person 
  {:params {:limit 10 :offset 20}})
```

Server receives: `[(:all-people {:limit 10 :offset 20})]`

### Focus and Without
```clojure
;; Load only specific fields
(df/load! this [:person/id 3] Person
  {:focus [:person/name]})

;; Load without certain fields  
(df/load! this [:person/id 3] Person
  {:without #{:person/age}})
```

## Loading Indicators

### Marker Configuration

Load markers require a link query in the component to work properly:

```clojure
(defsc PersonList [this {:keys [people] :as props}]
  {:query [{:people (comp/get-query Person)}
           [df/marker-table '_]]  ; ← Required for load markers!
   :ident (fn [] [:component/id :person-list])}

  (let [marker (get props [df/marker-table :people-loading])
        loading? (df/loading? marker)]
    (dom/div
      (when loading?
        (dom/div "Loading..."))
      (if (seq people)
        (map ui-person people)
        (dom/button
          {:onClick #(df/load! this :people Person
                       {:marker :people-loading
                        :target [:component/id :person-list :people]})}
          "Load People")))))
```

**Key Points**:
- Link query `[df/marker-table '_]` must be in component query
- Marker is accessed from props: `(get props [df/marker-table :marker-name])`
- Pass marker to `df/loading?` to check state

## Error Handling

### Error Callbacks
```clojure
(df/load! this :people Person
  {:error-action (fn [{:keys [error]}]
                   (log/error "Load failed:" error))})
```

### Post-Load Actions
```clojure
(df/load! this :people Person
  {:post-action (fn [env]
                  (log/info "Load completed"))})
```

## Advanced Loading

### Refresh After Mutation
```clojure
(defmutation save-person [params]
  (action [env] ...)
  (remote [env] (m/returning Person)))
```

### User-Triggered Loading

**IMPORTANT**: Do not use React lifecycle methods (like `componentDidMount`) to trigger loads. Instead, trigger loads in response to user events:

```clojure
(defsc PersonList [this {:keys [people]}]
  {:query [{:people (comp/get-query Person)}]
   :ident (fn [] [:component/id :person-list])}

  (dom/div
    (if (seq people)
      (map ui-person people)
      (dom/button
        {:onClick #(df/load! this :people Person
                     {:target [:component/id :person-list :people]})}
        "Load People"))))

### Parallel Loading
```clojure
;; Multiple loads execute in parallel
(df/load! app :friends PersonList)
(df/load! app :enemies PersonList)
(df/load! app :current-user Person)
```

## Load State Management

### Automatic Normalization

When data arrives, Fulcro automatically:
1. **Normalizes** the tree using component idents (always happens)
2. **Merges** into existing database (always happens)
3. **Places at ROOT** (if loading with keyword) OR **Updates targeted locations** (if `:target` provided)
4. **Triggers** UI refresh

**Key Point**: Normalization is automatic, but placement in tree requires:
- Loading with keyword (goes to ROOT)
- OR explicit `:target` option (goes to specified location)
- Loading by ident only normalizes, does NOT place ident in tree without `:target`

### Manual Merge Alternative
```clojure
;; Instead of load!, manually merge data
(merge/merge-component! app Person person-data 
  :append [:list/id :friends :list/people])
```

## Loading Best Practices

### User-Triggered Lazy Loading

Load additional data in response to user actions, not lifecycle events:

```clojure
(defsc PersonRow [this {:person/keys [id name]}]
  {:query [:person/id :person/name]
   :ident :person/id}

  (dom/div
    {:onClick #(comp/transact! this [(select-person {:person/id id})])}
    name))

(defmutation select-person [{:person/id id}]
  (action [{:keys [state app]}]
    ;; Set selection and conditionally load details
    (swap! state assoc-in [:component/id :main-panel :current-person]
      [:person/id id])
    (let [person (get-in @state [:person/id id])
          has-details? (contains? person :person/age)]
      (when-not has-details?
        (df/load! app [:person/id id] PersonDetail))))
  (remote [_] false))
```

### Conditional Loading
```clojure
;; Load only if data is stale or missing
(when (> (- (js/Date.now) last-refresh) 300000) ; 5 minutes
  (df/load! this :current-data SomeComponent))
```

### Loading with Dependencies
```clojure
;; Load person, then their addresses
(df/load! this [:person/id person-id] Person
  {:post-action 
   (fn [{:keys [app]}]
     (df/load! app :addresses Address 
       {:params {:person-id person-id}}))})
```