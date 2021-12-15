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

(defn extend-key
  "Extend the length of a  keyword by turning the full original keyword into a namespace
   and adding the given `new-name`.

   E.g.

   ```
   (extend-key :a \"b\") => :a/b
   (extend-key :a/b \"c\") => :a.b/c
   ```
  "
  [k new-name]
  (let [old-ns (namespace k)
        nm     (name k)
        new-ns (if old-ns
                 (str old-ns "." nm)
                 nm)]
    (keyword new-ns new-name)))
