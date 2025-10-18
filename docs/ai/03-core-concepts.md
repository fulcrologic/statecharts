# Core Concepts

## Immutable Data Structures

### Benefits of Immutability
Fulcro's compelling features are enabled by persistent data structures being first-class in the language.

### Problems with Mutable State
```java
Person p = new Person();
doSomethingOnAnotherThread(p);
p.fumble(); // Did p change? Race condition?
```

### Immutable Solution
```java
Person p = new Person();
doSomethingOnAnotherThread(p);
Person q = p.fumble(); // p unchanged, q could be different
```

### Key Advantages
- **Reasoning**: Other threads see data exactly as when locally reasoned about
- **Structural sharing**: New versions reference unchanged parts of old versions
- **Performance**: Adding to 1M item list is still constant time
- **Fulcro benefits**:
  - Time-travel UI history with minimal space
  - Efficient change detection (ref comparison vs data comparison)
  - Pure rendering without hidden variables

## Pure Rendering

### React's Model
1. **Render function**: Generates Virtual DOM (VDOM) data structure
2. **First frame**: Real DOM matches VDOM
3. **Subsequent frames**: New VDOM compared to cached version, changes applied

### Pure Rendering Concept
Complete snapshot of application state → function → screen "looks right"

Like 2D game: redraw screen based on "state of the world"

### Example: Nested Checkboxes

**Imperative Problems:**
- Mutable state in objects
- Event handling nightmares
- "Check all" logic complexity
- Infinite loops and edge cases

**Pure Rendering Solution:**
```clojure
(def state {:items [{:id :a :checked? true} {:id :b :checked? false}]})

;; Check-all state is computed
(let [all-checked (every? :checked? (get state :items))]
  (dom/input {:checked all-checked}))

;; State changes create new world
(def next-state (assoc-in state [:items 0 :checked?] false))
```

**Benefits:**
- No mutable state in UI components
- React enforces correct display
- Simple logic for both directions
- Entire UI re-renders, React optimizes

## Data-Driven Architecture

### Evolution from REST
- **REST limitations**: Not ideal query or update language
- **GraphQL/Falcor approach**: Clients specify exact data needs
- **Abstract expressions**: "Walking a graph" of related data

### Example Query Concept
Instead of `/person/3`, say:
"Person 3's name, age, and billing info, but only billing zip code"

### Graph Visualization
```
[person: age? name?] → [billing info: zip?]
```

### Mutations as Data
```clojure
'(change-person {:id 3 :age 44})
```

**Key insight**: Encode as data structure, process locally AND transmit over wire

## Graph Database

### Structure
The client-side database is a persistent map representing a graph:
- **Root node**: Top-level map
- **Tables**: Hold component/entity data
- **Naming convention**: Namespaced keywords (`:person/id`, `:account/email`)

### Database Format
```clojure
{:person/id {4 {:person/id 4 :person/name "Joe"}}}
```

### Idents
**Definition**: Tuples `[TABLE ID]` that uniquely identify graph nodes

**Example**: `[:person/id 4]`

**Usage**:
```clojure
(update-in state-db [:person/id 4] assoc :person/age 33)
(get-in state-db [:person/id 4])
```

### Relationships
```clojure
{:person/id
 {1 {:person/id 1 :person/name "Joe"
     :person/spouse [:person/id 2]           ; to-one
     :person/children [[:person/id 3]
                       [:person/id 4]]}      ; to-many
  2 {:person/id 2 :person/name "Julie"
     :person/spouse [:person/id 1]}}}
```

### Graph Capabilities
- **Loops supported**: Joe and Julie can point to each other
- **Compact representation**: Arbitrary nodes and edges
- **Root properties**: Special storage at top level

### Naming Conventions
- **UI-only Properties**: `:ui/name` (ignored by server queries)
- **Tables**: `:entity-type/index-indicator` (e.g., `:person/id`)
- **Root properties**: `:root/prop-name`
- **Node properties**: `:entity-type/property-name`
- **Singleton Components**: `[:component/id ::Component]`

### Database Operations
The graph database is central to Fulcro operation:
- **UI**: Pure function transforming database → UI
- **Mutations**: Evolve database to new version
- **Data manipulation**: Making new "state of the world"
- **Simplicity**: Properties/nodes at most 2-3 levels deep

```clojure
;; Example manipulation
(swap! state (fn [s]
              (-> s
                (assoc :root/people [[:person/id 1] [:person/id 2]])
                (assoc-in [:person/id 2 :person/name] "George")
                (assoc-in [:person/id 2 :person/age] 33))))
```