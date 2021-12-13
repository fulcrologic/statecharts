(ns com.fulcrologic.statecharts.util
  (:require
    [com.fulcrologic.statecharts :as sc])
  #?(:clj
     (:import (clojure.lang PersistentQueue))))

(defn genid
  "Generate a unique ID with a base prefix. Like `gensym` but returns a keyword."
  [s] (keyword (str (gensym (name s)))))

(defn queue [& args]
  (reduce conj
    #?(:clj  PersistentQueue/EMPTY
       :cljs #queue [])
    args))

