(ns user
  (:require
    [clj-reload.core :as reload]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [taoensso.timbre :as log])
  (:import (clojure.lang PersistentQueue)
           (java.io Writer)))

(alter-var-root (var clojure.core/*compiler-options*) assoc :disable-locals-clearing true)
(reload/init {:dirs ["src/dev" "src/main" "src/test"]})

(defmethod print-method PersistentQueue [v ^Writer w]
  (.write w (pr-str (into [] (iterator-seq (.iterator v))))))

(defn output-fn
  "Default (fn [data]) -> string output fn.
    Use`(partial default-output-fn <opts-map>)` to modify default opts."
  ([data] (output-fn nil data))
  ([opts data]
   (binding [clojure.pprint/*print-right-margin* 120
             clojure.pprint/*print-miser-width*  80]
     (let [{:keys [no-stacktrace? stacktrace-fonts]} opts
           {:keys [level ?err vargs msg_ ?ns-str ?file hostname_
                   timestamp_ ?line]} data
           leader         (butlast vargs)
           final-bit      (last vargs)
           prefix         (str/join " " leader)
           formatted-data (with-out-str (pprint final-bit))
           formatted-data (subs formatted-data 0 (dec (count formatted-data)))
           formatted-data (cond->> formatted-data
                            (str/includes? formatted-data "\n") (str "\n"))]
       (str
         (str/upper-case (subs (name level) 0 1)) " "
         (format "%-20s" (str (str/replace (or ?ns-str ?file "?") #"com.fulcrologic.statecharts." "") ":" (or ?line 0)))
         " - "
         (force msg_)
         ;prefix " "
         ;formatted-data
         (when-not no-stacktrace?
           (when-let [err ?err]
             (ex-message err))))))))

(log/set-config! (merge
                   log/default-config
                   {:min-level :info
                    :output-fn output-fn #_log/default-output-fn}))


(comment
  (require '[kaocha.repl :as k])
  (k/run-all {:fail-fast? true})
  )
