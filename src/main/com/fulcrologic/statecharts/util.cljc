(ns com.fulcrologic.statecharts.util
  #?(:clj
     (:import (clojure.lang PersistentQueue))))

(defn queue [& args]
  (reduce conj
    #?(:clj  PersistentQueue/EMPTY
       :cljs #queue [])
    args))

