# EQL - The Query and Mutation Language

## Overview

EQL (EDN Query Language) is Fulcro's query and mutation language, a subset of Datomic's pull query syntax with extensions for unions and mutations. All data in Fulcro is queried and manipulated from the UI using EQL.

### Key Concepts

- **Graph Walk**: A query is a graph walk with relative notation that must start at some specific spot
- **Network Agnostic**: Mutations are intended to be network agnostic - the UI doesn't need to know if operations are local-only or remote
- **Data as Code**: Both queries and mutations are simply data that can be interpreted locally or sent over the wire

### Query Types

Queries can be either:
- **Vector**: Regular component query `[:a :b]`
- **Map of vectors**: Union query for handling multiple possible node types

## Properties

The simplest EQL queries are for properties "right here" in the current graph node. Properties are queried using keywords.

### Basic Property Query

```clojure
[:a :b]
```

This queries for properties `:a` and `:b` at the current node in the graph traversal. Property values can be any scalar data serializable in EDN.

## Joins

Joins represent traversal of an edge to another node in the graph. The notation is a map with a single key (the local property holding the "pointer") whose value is the query for the remainder of the graph walk.

### Basic Join Syntax

```clojure
[{:children (comp/get-query Child)}]
```

### Key Points About Joins

- The query itself cannot specify to-one vs to-many - this is determined by the database data structure
- If the join property leads to a vector of links, it's to-many
- If it leads to a single link, it's to-one
- Always use `get-query` to properly annotate sub-queries for normalization
- Auto-normalization for to-many only occurs with vectors, not lists or seqs

## Unions

Unions represent a map of queries where only one applies at a given graph edge. This enables dynamic queries that adjust based on actual data linkage.

### Union Component Definition

```clojure
(defsc PersonPlaceOrThingUnion [this props]
  ; lambda form required for unions
  {:query (fn [] {:person/id (comp/get-query Person)
                  :place/id (comp/get-query Place)
                  :thing/id (comp/get-query Thing)})}
  ...)
```

### Parent Component Using Union

```clojure
(defsc Parent [this props]
  {:query [{:person-place-or-thing (comp/get-query PersonPlaceOrThingUnion)}]})
```

### Database Structure for Unions

```clojure
{ :person-place-or-thing [:place/id 3]
  :place/id { 3 { :place/id 3 :location "New York" }}}
```

### Union Resolution Process

1. Query starts at root and encounters the join
2. Union is resolved by looking at the first element of the ident (`[:place/id 3]`)
3. The keyword `:place` selects the appropriate query from the union
4. Processing continues normally with the selected component query

### To-Many Union Example

```clojure
{ :person-place-or-thing [[:person/id 1] [:place/id 3]]
  :person/id { 1 { :person/id 1 :person/name "Julie" }}
  :place/id { 3 { :place/id 3 :place/location "New York" }}}
```

### Union Ident Function

```clojure
(defsc PersonPlaceOrThingUnion [this props]
  {:ident (fn []
    (cond
      (contains? props :person/id) [:person/id (:person/id props)]
      (contains? props :place/id) [:place/id (:place/id props)]
      :else [:thing/id (:thing/id props)]))}
  ...)
```

### Union Rendering

The union component must detect the data type and render the appropriate child:

```clojure
(let [page (first (comp/get-ident this))]
  (case page
    :person/id ((comp/factory PersonDetail) (comp/props this))
    :place/id ((comp/factory PlaceDetail) (comp/props this))
    :thing/id ((comp/factory ThingDetail) (comp/props this))
    (dom/div (str "Cannot route: Unknown Screen " page))))
```

## Mutations

Mutations are data representations of abstract actions on the data model. They look like single-argument function calls where the argument is a parameter map.

### Basic Mutation Syntax

```clojure
[(do-something)]
```

### Mutation with Parameters

```clojure
[(do-something {:param1 "value" :param2 42})]
```

### Defining Mutations

```clojure
(ns app.mutations)

(defmutation do-something [params] 
  (action [env] ...)
  (remote [env] ...))
```

### Using Mutations in Components

```clojure
(ns app.ui
  (:require [app.mutations :as am]))

...
(comp/transact! this [(am/do-something {})])
```

### Quoting Considerations

- In Fulcro 3, mutations return themselves as data, eliminating most quoting requirements
- Use syntax quoting when circular references prevent namespace requiring
- The parameter map is optional but recommended for IDE support

## Parameters

Query elements support parameter maps, primarily useful when sending queries to servers.

### Parameterized Property

```clojure
[(:prop {:x 1})]
```

### Parameterized Join

```clojure
[({:child (comp/get-query Child)} {:x 1})]
```

### Syntax Quoting for Parameters

Due to Clojure's list evaluation, use syntax quoting in code:

```clojure
:query (fn [this] `[({:child ~(comp/get-query Child)} {:x 1})])
```

## Queries on Idents

Idents can be used in queries as plain properties or joins.

### Ident as Property

```clojure
[ [:person/id 1] ]
```

This pulls a table entry without normalization or following subqueries:

```clojure
(defsc X [this props]
  {:query [ [:person/id 1] ] }
  (let [person (get props [:person/id 1]) ; NOT get-in
        ...
        ; person contains {:id 1 :person/phone [:phone/id 4]}
```

### Ident as Join

```clojure
(defsc X [this props]
  {:query [{[:person/id 1] (comp/get-query Person)}]}
  (let [person (get props [:person/id 1])
        ...
        ; person contains {:id 1 :person/phone {:phone/id 4 :phone/number "555-1212"}}
```

This re-roots the graph walk at the ident's table entry and continues the subtree traversal.

## Link Queries

Link queries allow starting "back at the root" node, useful for singleton data like UI locale or current user.

### Basic Link Query

```clojure
[ [:ui/locale '_] ]
```

Results in `:ui/locale` in props with a value from the root database node.

### Link Query with Join

```clojure
[ {[:current-user '_] (comp/get-query Person)} ]
```

Pulls `:current-user` with continued graph traversal.

### Important Warning

Components using only ident/link queries need database presence:

```clojure
(defsc LocaleSwitcher [this {:keys [ui/locale]}]
  {:query [[:ui/locale '_]]
   :initial-state {}} ; Required: empty map for database presence
  (dom/div ...))

(defsc Root [this {:keys [locale-switcher]}]
  {:query [{:locale-switcher (comp/get-query LocaleSwitcher)}]
   :initial-state (fn [params] {:locale-switcher (comp/get-initial-state LocaleSwitcher)})}
  (ui-locale-switcher locale-switcher))
```

## Shared State

Alternative to ident/link queries for specific scenarios:

### Use Cases
- Data visible to all components that never changes after mount
- Data derived from UI props/globals, updating only on root renders

### Configuration

```clojure
(app/fulcro-app
  {:shared {:pi 3.14} ; never changes
   :shared-fn #(select-keys % :current-user)}) ; updates on root render
```

### Accessing Shared State

```clojure
(defsc C [this props]
  (let [{:keys [pi current-user]} (comp/shared this)]
    ; current-user will be denormalized from root props
    ...))
```

### Shared State Limitations
- Root component must still query for data used in shared-fn
- Updates only visible after root renders (use `comp/force-root-render!`)
- May not work correctly with history support if derived from non-database data

## Recursive Queries

EQL supports recursive queries for self-referential data structures.

### Recursion Notations
- `...` - Recurse until no more links (with circular detection)
- Number - Recursion depth limit

### Basic Recursive Query

```clojure
(defsc Person [this props]
  {:query (fn [] [:person/id :person/name {:person/friends ...}])}
  ...)
```

### Circular Recursion Handling

For circular relationships, calculate depth to prevent rendering issues:

```clojure
(defsc Person [this {:keys [person/name spouse] :as props}]
  {:query (fn [] [:person/id :person/name {:spouse 1}])}
  (let [depth (or (comp/get-computed this :depth) 0)]
    (dom/div
      (dom/p name)
      (when (and spouse (< depth 1))
        (ui-person (comp/computed spouse {:depth (inc depth)}))))))
```

### Duplicates in Recursive Structures

- Normalization merges duplicate entries into the same table entry
- Modifications are shared among all references
- Works seamlessly with recursive bullet lists and similar structures

## The AST (Abstract Syntax Tree)

EQL expressions can be converted to/from AST for complex query manipulation.

### AST Conversion Functions

```clojure
(eql/query->ast query)
(eql/ast->query ast)
```

### AST Use Cases
- Converting queries to other formats (e.g., SQL)
- Available in mutation/query `env` on client and server
- Morphing mutations before sending to server

### Morphing Mutations

```clojure
(defmutation do-thing [params]
  (action [env] ...)
  (remote [{:keys [ast]}] ast)) ; same as `true`

(defmutation do-thing [params]
  (action [env] ...)
  (remote [{:keys [ast]}] (eql/query->ast `[(do-other-thing)]))) ; change mutation

(defmutation do-thing [params]
  (action [env] ...)
  (remote [{:keys [ast]}] (assoc ast :params {:y 3}))) ; change parameters
```

## Best Practices

1. **Always use `get-query`** for joins to ensure proper normalization metadata
2. **Include `:initial-state`** for components using only ident/link queries
3. **Handle depth in recursive queries** to prevent circular rendering issues
4. **Use unions for polymorphic data** where different types need different queries
5. **Namespace mutations** and use syntax quoting for clean expressions
6. **Leverage AST** for complex query transformations and server processing

## Query Composition Examples

### Simple Component Query
```clojure
(defsc Person [this props]
  {:query [:person/id :person/name :person/email]
   :ident [:person/id :person/id]}
  ...)
```

### Component with Joins
```clojure
(defsc Person [this props]
  {:query [:person/id :person/name 
           {:person/address (comp/get-query Address)}
           {:person/friends (comp/get-query Person)}]
   :ident [:person/id :person/id]}
  ...)
```

### Root Component
```clojure
(defsc Root [this props]
  {:query [{:current-user (comp/get-query Person)}
           {:all-people (comp/get-query Person)}
           [:ui/loading-state '_]]
   :initial-state (fn [params] 
                   {:current-user (comp/get-initial-state Person {})
                    :all-people []})}
  ...)
```

This comprehensive guide covers all the essential EQL concepts needed for effective Fulcro development, from basic property queries to advanced recursive patterns and AST manipulation.