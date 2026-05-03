(ns com.fulcrologic.statecharts.irp.test-399
  "IRP test 399 — event name matching works correctly: multiple descriptors
   (space-separated), prefix matching, and `*` wildcard are all verified.

   Source: https://www.w3.org/Voice/2013/scxml-irp/399/test399.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry raise Send]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-399
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0 :initial :s01}
        (on-entry {} (Send {:event :timeout :delay 2000}))
        (transition {:event :timeout :target :fail})
        ;; Multiple event descriptors: matches :foo OR :bar
        (state {:id :s01}
          (on-entry {} (raise {:event :foo}))
          (transition {:event [:foo :bar] :target :s02}))
        ;; :bar also matches the multi-descriptor
        (state {:id :s02}
          (on-entry {} (raise {:event :bar}))
          (transition {:event [:foo :bar] :target :s03}))
        ;; :foo.zoo matches :foo prefix
        (state {:id :s03}
          (on-entry {} (raise {:event :foo.zoo}))
          (transition {:event [:foo :bar] :target :s04}))
        ;; :foos does NOT match :foo (token boundary), but matches :foos
        (state {:id :s04}
          (on-entry {} (raise {:event :foos}))
          (transition {:event :foo :target :fail})
          (transition {:event :foos :target :s05}))
        ;; :foo.zoo matches :foo (prefix — foo.* is same as foo with implicit .*)
        (state {:id :s05}
          (on-entry {} (raise {:event :foo.zoo}))
          (transition {:event :foo :target :s06}))
        ;; :* matches any event
        (state {:id :s06}
          (on-entry {} (raise {:event :foo}))
          (transition {:event :* :target :pass})))
      (final {:id :pass})
      (final {:id :fail}))))

(specification "IRP test 399 — event name matching: multi-descriptor, prefix, wildcard"
  (assertions
    "reaches pass"
    (runner/passes? chart-399 []) => true))
