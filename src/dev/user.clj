(ns user
  (:require
    [clojure.spec.alpha :as s]
    [expound.alpha :as expound]
    [taoensso.timbre :as log])
  (:import (clojure.lang PersistentQueue)
           (java.io Writer)))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(defmethod print-method PersistentQueue [v ^Writer w]
  (.write w (pr-str (into [] (iterator-seq (.iterator v))))))

(log/set-level! :trace)

