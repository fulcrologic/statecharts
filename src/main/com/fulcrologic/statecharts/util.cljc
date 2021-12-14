(ns com.fulcrologic.statecharts.util
  (:require
    [com.fulcrologic.statecharts :as sc])
  #?(:clj
     (:import (clojure.lang PersistentQueue)
              (java.util Date))))

(defn genid
  "Generate a unique ID with a base prefix. Like `gensym` but returns a keyword."
  [s] (keyword (str (gensym (name s)))))

(defn queue [& args]
  (reduce conj
    #?(:clj  PersistentQueue/EMPTY
       :cljs #queue [])
    args))

(defn now-ms []
  #?(:clj  (inst-ms (Date.))
     :cljs (inst-ms (js/Date.))))
