(ns user
  (:require
    [clojure.test :refer :all]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [expound.alpha :as expound]
    [clojure.spec.alpha :as s]
    [edn-query-language.core :as eql]
    [com.fulcrologic.statecharts.tracing :refer [set-trace!]]
    [taoensso.timbre :as log])
  (:import (clojure.lang PersistentQueue)
           (java.io Writer)))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/todomvc"
  "../fulcro-websockets/src/main")

(alter-var-root #'s/*explain-out* (constantly expound/printer))
(defn queue? [v] (instance? PersistentQueue v))
(set-trace! (fn [msg v] (log/info msg " => " (if (queue? v)
                                               (iterator-seq (.iterator v))
                                               v))))
(defmethod print-method PersistentQueue [v ^Writer w]
  (.write w (pr-str (into [] (iterator-seq (.iterator v))))))

