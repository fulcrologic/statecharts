(ns com.fulcrologic.statecharts.util
  #?(:clj
     (:import (clojure.lang PersistentQueue)
              (java.util UUID Date))))

#?(:clj
   (def ^:dynamic *java-clock* nil))

(defn genid
  "Generate a unique ID with a base prefix. Like `gensym` but returns a keyword."
  [s] (keyword (str (gensym (name s)))))

(defn new-uuid []
  #?(:clj  (UUID/randomUUID)
     :cljs (random-uuid)))

(defn queue [& args]
  (reduce conj
    #?(:clj  PersistentQueue/EMPTY
       :cljs #queue [])
    args))

(defn now-ms []
  #?(:clj  (if *java-clock*
             (.millis *java-clock*)
             (inst-ms (Date.)))
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
