# Core API

## Overview
Fulcro's Core API centers around manipulating the graph database. Most operations are CLJC and work in headless environments, independent of rendering.

## Denormalization

### `db->tree` Function
Primary algorithm in `com.fulcrologic.fulcro.algorithms.denormalize`

**Purpose**: Run EQL query against normalized Fulcro database

### Basic Usage
```clojure
(def sample-db
  {:people    [[:person/id 1] [:person/id 2]]
   :person/id {1 {:person/name "Bob"}
               2 {:person/name "Judy"}}})

(fdn/db->tree [{:people [:person/name]}] sample-db sample-db)
=> {:people [#:person{:name "Bob"} #:person{:name "Judy"}]}
```

### Key Points
1. **EQL drives recursive walk** of database
2. **Main task**: Replace idents with maps via `(get-in db ident)`
3. **Stops at EQL boundaries**: Only resolves what query requests

### Two Usage Contexts
1. **From root**: Starting from database root
2. **From node**: Starting from arbitrary database entity

### Examples

**Simple property access:**
```clojure
(fdn/db->tree [:people] sample-db sample-db)
=> {:people [[:person/id 1] [:person/id 2]]}
```

**Table access:**
```clojure
(fdn/db->tree [:person/id] sample-db sample-db)
=> #:person{:id {1 #:person{:name "Bob"}, 2 #:person{:name "Judy"}}}
```

**As advanced select-keys:**
```clojure
(let [entity {:person/name "Joe" :person/age 42}]
  (fdn/db->tree [:person/name] entity {}))
=> #:person{:name "Joe"}
```

### Idents in Queries
EQL allows idents as query elements to "jump" to specific entities:

```clojure
(fdn/db->tree [[:person/id 1]] {} sample-db)
=> {[:person/id 1] #:person{:name "Bob"}}

;; With joins
(fdn/db->tree [{[:person/id 1] [:person/name]}] {} sample-db)
=> {[:person/id 1] #:person{:name "Bob"}}
```

### Component Refresh Pattern
```clojure
;; How Fulcro refreshes specific components
(let [starting-entity (get-in sample-db [:person/id 1])]
  (fdn/db->tree [:person/name] starting-entity sample-db))
=> #:person{:name "Bob"}
```

## Normalization

### `tree->db` Function
Located in `com.fulcrologic.fulcro.algorithms.normalize`

**Purpose**: Convert arbitrary tree of data into normalized form

### Core Questions
1. **Should** this map be normalized?
2. **Where** should it be normalized to?

### Determining When to Normalize
Use the component's query to indicate intent:
- **Props**: Keep as opaque data
- **Joins**: Normalize and create idents

### Determining Target Location
Components provide ident functions that specify where data should be stored:

```clojure
(defsc Person [this props]
  {:ident :person/id  ; or [:person/id :person/id] or (fn [] [...])
   :query [:person/id :person/name]})
```

### Query Metadata
`get-query` adds component metadata for normalization:
```clojure
(meta (comp/get-query Person))
=> {:component Person-class-with-ident-function}
```

### Normalization Process
1. **Walk tree** parallel with EQL
2. **At joins**: Extract component from metadata
3. **Get ident**: Run component's ident function on data
4. **Normalize**: Place data at ident location, replace with reference

### Example
```clojure
(fnorm/tree->db Root {:root/people {:person/id 1 :person/name "Bob"}} true)
=> {:root/people [:person/id 1], :person/id {1 #:person{:id 1, :name "Bob"}}}
```

## Initial State

### Purpose
Solves the "empty database" problem at application startup.

### Problem
- New Fulcro app has empty database
- UI query expects data
- Manual database construction is error-prone and doesn't refactor well

### Solution: Co-located Initial State
```clojure
(defsc Person [this props]
  {:query         [:person/id :person/name]
   :ident         :person/id
   :initial-state (fn [{:keys [id name]}] {:person/id id :person/name name})})

(comp/get-initial-state Person {:id 1 :name "Bob"})
=> #:person{:id 1, :name "Bob"}
```

### Composition
```clojure
(defsc Root [this props]
  {:query         [{:root/people (comp/get-query Person)}]
   :initial-state (fn [_] {:root/people [(comp/get-initial-state Person {:id 1 :name "Bob"})]})})

(comp/get-initial-state Root)
=> #:root{:people [#:person{:id 1, :name "Bob"}]}
```

### Template vs Lambda Forms

**Template (concise):**
```clojure
{:initial-state {:person/id :param/id :person/name :param/name}}
```

**Lambda (flexible):**
```clojure
{:initial-state (fn [{:keys [id name]}] {:person/id id :person/name name})}
```

### Application Initialization
Fulcro initialization is essentially:
```clojure
(let [data-tree (comp/get-initial-state Root)
      normalized-tree (fnorm/tree->db Root data-tree true)]
  ;; Reset app state to normalized-tree
  )
```

## Understanding Rendering

### Core Rendering Logic
Fulcro's rendering is surprisingly simple:
```clojure
(let [current-state   {...normalized-database...}
      denormalized-tree (fdn/db->tree (comp/get-query Root) current-state current-state)
      root-factory (comp/factory Root)]
  (js/ReactDOM.render (root-factory denormalized-tree) dom-node))
```

### Key Insight
Rendering is **literal reification** of normalized database as UI. Most complexity is in optimizations (targeted re-renders).

## Evolving the Graph

### Primary Task
Update state database so next render frame shows desired UI.

### Common Operations
1. **Manual manipulation**: `assoc-in`, etc. within mutations
2. **Structured addition**: Using `merge-component!` or `load!`

### Merge Operations
```clojure
;; Add data to normalized database
(merge/merge-component! app Person {:person/id 3 :person/name "Sally"})

;; With targeting
(merge/merge-component! app Person person-data
  :append [:root/people])
```

### Targeting Options
```clojure
:replace [:root/edge]                    ; Replace single reference
:append [:root/people]                   ; Add to end of vector
:prepend [:root/people]                  ; Add to beginning
(targeting/append-to [:person/id 1 :person/spouse]) ; Specific location
```

### Mutation Return Values
```clojure
(defmutation save-person [params]
  (remote [env]
    (-> env
      (m/returning Person)              ; Merge result using Person component
      (m/with-target [:current-user])))) ; Target to specific location
```

### Database Paths
Remember: any node reachable in ≤3 levels:
- `[:table id field]` - Entity property
- `[:table id]` - Entire entity  
- `[:root-prop]` - Root property

### Development Pattern
1. **Understand current data shape** (via Fulcro Inspect)
2. **Determine desired data shape**
3. **Write mutation/load** to transform current → desired
4. **Verify UI updates** correctly