# Dataico RAD Model

See also @ai/clojure-library-source-and-documentation.md in this project.

Our project defines a Datomic database (along with virtual attributes) via a Fulcro RAD attribute model. The
attribute options are defined in the namespace com.fulcrologic.rad.attributes-options. Our model is defined in the
source folder @src/main/dataico/model_rad, with the model.cljc file being the join point for everything.

Each entity in our database model has a model file (e.g. src/main/dataico/model_rad/invoice.cljc), though
entity attributes are actually grouped around an "identity" attribute (i.e. any defattr with `ao/identity?`
set to true.)

The grouping is done with `ao/identities`, which is a set of id attributes whose entities CAN include that
attribute. For example `ao/identities #{:product/id :invoice/id}` would be that the attribute could
appear on products or invoices.

A `defattr` MAY have an optional docstring just after the symbol (just like `def`). So the signature is
`(defattr sym docstring? database-keyword type options-map)`. The `type` is database specific, but most common
types like :int, :string, :keyword, and :ref are supported by Datomic.

The :ref type indicates that the attribute points to one (or many if the ao/cardinality is set to :many) entity. The
value of `ao/target` or `ao/targets` will specify id attributes of the target entities of the reference.

From this you can deduce a lot about the data model, and in fact namespaces like `dataico.lib.rad.introspection` do
just that.

The RAD model is used to generate many things: database schema, pathom resolvers, validations (malli schemas),
reports (see defsc-report) forms (defsc-form), import/export formats (csv, excel, etc.).

The legacy code has "builders" for making instances of objects for tests. In the newer code, we prefer a
pattern where, in the RAD model file, we add a function called `new-MODELNAME`, e.g. `new-product`. Such
functions should look like this:

```
(defn new-product
  "Create a product. The name will be the sku and temporary ID of the product. "
  [sku & {:as addl-attrs}]
  (merge
    {:db/id                      sku
     :product/id                 (new-uuid)
     :product/sku                sku
     :product/description        sku
     :product/inventory-tracked? false}
    addl-attrs))
```

Where the bare minimum arguments for a usable entity are included, and the user can specify named args to
override/fill in things that we give defaults for. Note that Datomic allows us to use strings as a :db/id, and
those will be unified among entities on a single transaction. The :tempids of the datomic result will then
include those strings as keys to the native :db/id of the created entities.
This allows us to quickly build things for tests:

```
(let [c    (new-company "Acme") ; sets :db/id to "Acme"
      ;; assigns a alt description and relates the new product to the company
      p    (new-product "sku-100" :product/company "Acme" :product/description "My Product") 
      conn (test-conn)
      {{:strs [Acme sku-100]} :tempids} (d/transact conn {:tx-data [p c]})]
  ...)
```

You should never change an existing "errant" builder function, since they are widely used in tests.
