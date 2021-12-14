(ns com.fulcrologic.statecharts.data-model.operations
  "Convenience helpers for interacting with `DataModel`s")

(defn assign
  "A transaction element that indicates the desire to overwrite the given paths in the data model. E.g.

  ```
  (assign
   [:x] 2
   :local 42
   [:y :v] \"Hello\")
  ```

  See your data model implementation for the interpretation (and support) of the path vectors. ALL data models
  MUST support single keywords as paths to mean \"the current context or scope\".

  A common interpretation will be `[stateid data-key]` means
  the data-key in the data model for at a given state. Data models *may* choose to search for data in surrounding
  scopes (states).
  "
  [& path-value-pairs]
  {:op   :assign
   :data (into {} (partition 2 path-value-pairs))})

(defn delete
  "A transaction element that indicates the desire to remove certain values from the data model.

   ```
   (delete :x [:a :b])
   ```

   See your data model implementation for the interpretation (and support) of the path vectors. ALL data models
   will support single keywords as \"the current context or scope\".
  "
  [paths]
  {:op    :delete
   :paths (vec paths)})

(defn set-map-txn
  "Returns a transaction that, when transacted, will set all of the k-v pairs from `m` into the
   data model."
  [m]
  [{:op   :assign
    :data m}])
