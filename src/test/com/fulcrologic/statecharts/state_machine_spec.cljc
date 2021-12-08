(ns com.fulcrologic.statecharts.state-machine-spec
  (:require
    [com.fulcrologic.statecharts.util :refer [queue]]
    [fulcro-spec.core :refer [specification assertions =>]])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))

(specification "A queue"
  (assertions
    "Pulls items from the front"
    (pop (queue 1 2 3)) => (queue 2 3)
    "Adds items to the rear"
    (conj (queue 1 2 3) 4) => (queue 1 2 3 4)))
