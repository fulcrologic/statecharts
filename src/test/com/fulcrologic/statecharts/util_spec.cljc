(ns com.fulcrologic.statecharts.util-spec
  (:require
    [com.fulcrologic.statecharts.util :refer [now-ms queue]]
    [fulcro-spec.core :refer [=> =fn=> assertions specification]]))

(specification "A queue"
  (assertions
    "Pulls items from the front"
    (pop (queue 1 2 3)) => (queue 2 3)
    "Adds items to the rear"
    (conj (queue 1 2 3) 4) => (queue 1 2 3 4)))

#?(:clj
   (specification "Can change the clock in use for now-ms, on the JVM"
     (assertions
       "Do not use clock, if none is set"
       (now-ms) =fn=> (fn [act]
                        ; now-ms is withing 1s of now.
                        (< (- (inst-ms (java.util.Date.)) act) 1000)))

     (assertions
       "Can bind clock to something else, and then now-ms returns from that clock"
       (binding [com.fulcrologic.statecharts.util/*java-clock* (java.time.Clock/fixed
                                                                 (java.time.Instant/ofEpochMilli 1313131313131)
                                                                 (java.time.ZoneOffset/UTC))]
         (now-ms))
       => 1313131313131)
     ()))