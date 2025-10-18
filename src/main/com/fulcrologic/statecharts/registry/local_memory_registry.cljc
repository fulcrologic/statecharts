(ns com.fulcrologic.statecharts.registry.local-memory-registry
  "A statechart registry that keeps track of the statecharts in an atom."
  (:require
    [com.fulcrologic.statecharts.protocols :as sp]))

(defrecord LocalMemoryRegistry [charts]
  sp/StatechartRegistry
  (register-statechart! [_ src statechart-definition]
    (swap! charts assoc src statechart-definition))
  (get-statechart [_ src]
    (get @charts src))
  (all-charts [_]
    @charts))

(defn new-registry []
  (->LocalMemoryRegistry (atom {})))
