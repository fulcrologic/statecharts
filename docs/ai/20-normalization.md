# Normalization in Fulcro

## Overview

Normalization is a central mechanism in Fulcro that transforms data trees (received from component queries against servers) into a normalized graph database. This process enables efficient data management, prevents duplication, and maintains referential integrity across the application.

## Why Normalization Matters

- **Data Consistency**: Single source of truth for each entity
- **Memory Efficiency**: Eliminates data duplication
- **Update Propagation**: Changes to an entity are automatically reflected everywhere it's referenced
- **Query Optimization**: Enables efficient data fetching and caching

## The Normalization Process

### Core Function: `tree->db`

The function `fnorm/tree->db` is the workhorse that turns an incoming tree of data into normalized data, which can then be merged into the overall database.

### Step-by-Step Process

Given incoming tree data:

```clojure
{:people [{:db/id 1 :person/name "Joe" ...} 
          {:db/id 2 :person/name "Sally" ...}]}
```

And the query:

```clojure
[{:people (comp/get-query Person)}]
```

Which expands to:

```clojure
[{:people [:db/id :person/name]}]
          ; ^ metadata {:component Person}
```

The `tree->db` function recursively walks the data structure and query:

1. **Root Processing**: Sees `:people` as a root key and property, remembers it will be writing `:people` to the root
2. **Relationship Detection**: Examines the value of `:people` and finds it to be a vector of maps, indicating a to-many relationship
3. **Component Discovery**: Examines the metadata on the subquery of `:people` and discovers that entries are represented by the component `Person`
4. **Ident Generation**: For each map in the vector, calls the `ident` function of `Person` (found in metadata) to get a database location
5. **Data Placement**: Places the "person" values into the result via `assoc-in` on the ident
6. **Reference Replacement**: Replaces the entries in the vector with the idents

## Graph Database Structure

### Before Normalization (Tree Structure)

```clojure
{:current-user {:user/id 1
                :user/name "Alice"
                :user/friends [{:user/id 2 :user/name "Bob"}
                               {:user/id 3 :user/name "Charlie"}]}
 :all-users [{:user/id 1 :user/name "Alice"}
             {:user/id 2 :user/name "Bob"}
             {:user/id 3 :user/name "Charlie"}]}
```

### After Normalization (Graph Structure)

```clojure
{:user/id {1 {:user/id 1 
              :user/name "Alice"
              :user/friends [[:user/id 2] [:user/id 3]]}
           2 {:user/id 2 :user/name "Bob"}
           3 {:user/id 3 :user/name "Charlie"}}
 :current-user [:user/id 1]
 :all-users [[:user/id 1] [:user/id 2] [:user/id 3]]}
```

## Idents and Entity References

### What are Idents?

Idents are two-element vectors that uniquely identify entities in the normalized database:

```clojure
[:user/id 1]     ; Points to user with ID 1
[:product/sku "ABC123"]  ; Points to product with SKU "ABC123"
[:component :singleton]  ; Points to a singleton component
```

### Component Ident Functions

```clojure
(defsc Person [this props]
  {:ident :person/id  ; Simple keyword ident
   :query [:person/id :person/name]}
  ...)

(defsc Product [this props]
  {:ident (fn [] [:product/sku (:product/sku props)])  ; Computed ident
   :query [:product/sku :product/name]}
  ...)
```

## Critical Importance of Query Composition

### Why Metadata Matters

If metadata is missing from queries, normalization won't occur:

```clojure
;; WRONG - Missing component metadata
[:people [:db/id :person/name]]

;; CORRECT - Has component metadata from get-query
[{:people (comp/get-query Person)}]
```

### Parallel Structure Requirement

The query and tree of data must have parallel structure, as should the UI:

```clojure
;; Component structure
(defsc PersonList [this {:keys [people]}]
  {:query [{:people (comp/get-query Person)}]}
  ...)

;; Matching data structure
{:people [{:person/id 1 :person/name "Alice"}
          {:person/id 2 :person/name "Bob"}]}

;; Resulting normalized structure
{:person/id {1 {:person/id 1 :person/name "Alice"}
             2 {:person/id 2 :person/name "Bob"}}
 :people [[:person/id 1] [:person/id 2]]}
```

## Normalization in Different Contexts

### 1. Initial State Normalization

At startup, `:initial-state` supplies data that matches the UI tree structure:

```clojure
(defsc Root [this props]
  {:initial-state (fn [params]
                    {:current-user (comp/get-initial-state User {:id 1 :name "Alice"})
                     :user-list [(comp/get-initial-state User {:id 2 :name "Bob"})]})
   :query [{:current-user (comp/get-query User)}
           {:user-list (comp/get-query User)}]}
  ...)
```

Fulcro automatically detects and normalizes this initial tree structure.

### 2. Server Interaction Normalization

Network interactions send UI-based queries with component annotations:

```clojure
;; Query sent to server
[{:people (comp/get-query Person)}]

;; Response data (tree structure matching query)
{:people [{:person/id 1 :person/name "Alice"}
          {:person/id 2 :person/name "Bob"}]}

;; Automatic normalization and merge into database
```

### 3. WebSocket Data Normalization

Server push data can be normalized using client-side queries:

```clojure
;; Incoming WebSocket data
{:new-message {:message/id 123 :message/text "Hello"}}

;; Generate client-side query
[{:new-message (comp/get-query Message)}]

;; Use fnorm/tree->db to normalize
(fnorm/tree->db query incoming-data true)
```

### 4. Mutation Data Normalization

Mutations can normalize new entity data within the action:

```clojure
(defmutation create-user [user-data]
  (action [{:keys [state]}]
    (let [normalized-user (fnorm/tree->db 
                            [{:new-user (comp/get-query User)}]
                            {:new-user user-data}
                            true)]
      (swap! state merge normalized-user))))
```

## Useful Normalization Functions

### Merge Functions

```clojure
;; Merge new component instances
(merge/merge-component! app User new-user-data)
(merge/merge-component state User new-user-data)

;; Merge root-level data
(merge/merge! app {:global-settings {...}})
(merge/merge* state {:global-settings {...}})
```

### Core Normalization

```clojure
;; General normalization utility
(fnorm/tree->db query data-tree include-root?)

;; Example usage
(fnorm/tree->db 
  [{:users (comp/get-query User)}]
  {:users [{:user/id 1 :user/name "Alice"}]}
  true)
```

### Integration Utilities

```clojure
;; Add ident to existing relationships
(targeting/integrate-ident* state [:user/id 1] :append [:root/users])
(targeting/integrate-ident* state [:user/id 1] :prepend [:user/id 2 :user/friends])
(targeting/integrate-ident* state [:user/id 1] :replace [:root/current-user])
```

## Advanced Options

### Remove Missing Data

The `:remove-missing?` option controls cleanup behavior:

```clojure
(merge/merge-component! app User user-data {:remove-missing? true})
```

When `true`:
- Items in query but not in data are removed from state database
- Useful for server load cleanups
- Defaults to `false` to preserve UI-only attributes

### Deep Merge Behavior

The deep merge used by merge routines:
- Does not overwrite existing entity versions by default
- Preserves UI-only attributes that incoming trees don't know about
- Maintains data integrity across partial updates

## Best Practices

1. **Always use `comp/get-query`** in parent component queries to ensure proper metadata
2. **Maintain parallel structure** between queries, data, and UI components
3. **Use consistent ident patterns** across your application
4. **Leverage normalization in mutations** for efficient state updates
5. **Consider `:remove-missing?`** carefully based on your data update patterns
6. **Test normalization** by examining the resulting database structure

## Common Patterns

### To-One Relationship

```clojure
;; Component definition
(defsc User [this {:keys [user/profile]}]
  {:query [:user/id {:user/profile (comp/get-query Profile)}]
   :ident :user/id}
  ...)

;; Data structure
{:user/id 1 :user/profile {:profile/id 100 :profile/bio "..."}}

;; Normalized result
{:user/id {1 {:user/id 1 :user/profile [:profile/id 100]}}
 :profile/id {100 {:profile/id 100 :profile/bio "..."}}}
```

### To-Many Relationship

```clojure
;; Component definition
(defsc User [this {:keys [user/posts]}]
  {:query [:user/id {:user/posts (comp/get-query Post)}]
   :ident :user/id}
  ...)

;; Data structure  
{:user/id 1 :user/posts [{:post/id 1 :post/title "First"}
                         {:post/id 2 :post/title "Second"}]}

;; Normalized result
{:user/id {1 {:user/id 1 :user/posts [[:post/id 1] [:post/id 2]]}}
 :post/id {1 {:post/id 1 :post/title "First"}
           2 {:post/id 2 :post/title "Second"}}}
```

Understanding normalization is crucial for effective Fulcro development, as it underlies all data management operations in the framework.