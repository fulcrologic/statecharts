(ns com.fulcrologic.statecharts.irp.test-436
  "IRP test 436 — In() predicate correctly reflects the current configuration
   in a parallel state.

   Source: https://www.w3.org/Voice/2013/scxml-irp/436/test436.txml"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition In]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-436
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :p}
      (parallel {:id :p}
        (state {:id :ps0}
          (transition {:cond (In :s1) :target :fail})
          (transition {:cond (In :ps1) :target :pass})
          (transition {:target :fail}))
        (state {:id :ps1}))
      (state {:id :s1})
      (final {:id :pass})
      (final {:id :fail}))))

(def events [])

(specification "IRP test 436 — In() predicate in parallel state"
  (assertions
    "reaches pass"
    (runner/passes? chart-436 events) => true))
