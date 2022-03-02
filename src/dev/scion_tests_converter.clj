(ns scion-tests-converter
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [scxml-converter :refer [translate-scxml]]
            [clojure.data.json :as json]
            [cljfmt.main :as cljfmt]
            [cljfmt.core :as cljfmt-core]
            [camel-snake-kebab.core :as csk]
            [zprint.core :as zp]))

(defn make-assertions [states]
  (let [assertions (mapcat
                     (fn [state]
                       [`(~'testing/in? ~'env ~(keyword state)) '=> 'true])
                     states)]
    `(~'assertions
       ~@assertions)))

(defn make-event-and-assertions [{:keys [event nextConfiguration] :as payload}]
  [`(~'testing/run-events! ~'env ~(-> event :name keyword))
   (make-assertions nextConfiguration)])

(defn statechart-initial->maybe-keyword [props]
  (if (:initial props)
    (update props :initial keyword)
    props))

(defn make-test-code [test-name chart test]
  (let [[_ chart-props & chart-body] chart
        statechart-props (-> chart-props
                           (select-keys [:initial])
                           statechart-initial->maybe-keyword)
        test-body (into [(make-assertions (:initialConfiguration test))]
                     (mapcat make-event-and-assertions (:events test)))
        template  `(~'specification ~test-name
                     (~'let [~'chart (~'chart/statechart ~statechart-props
                                   ~@chart-body)
                           ~'env   (~'testing/new-testing-env {:statechart ~'chart} {})]

                       (~'testing/start! ~'env)

                       ~@test-body))]
    template))

(defn port-test [[test-name {:keys [scxml json] :as v}]]
  (let [translated-scxml (translate-scxml scxml {:ns-alias nil})
        test (json/read-str json :key-fn keyword)]
    (println json)
    (make-test-code test-name translated-scxml test)))

(defn file-header [filename]
  (let [file-ns (symbol (str "com.fulcrologic.statecharts.algorithms.v20150901."
                          (csk/->kebab-case filename) "-spec"))]
    `(~'ns ~file-ns
       ~'(:require
           [com.fulcrologic.statecharts.elements
            :refer [state
                    initial
                    parallel
                    final
                    transition
                    raise
                    on-entry
                    on-exit
                    data-model
                    assign
                    script
                    history
                    log]]
           [com.fulcrologic.statecharts :as sc]
           [com.fulcrologic.statecharts.chart :as chart]
           [com.fulcrologic.statecharts.testing :as testing]
           [com.fulcrologic.statecharts.data-model.operations :as ops]
           [fulcro-spec.core :refer [specification assertions =>]]))))

(defn port-tests [root-dir dir]
  (let [directory (io/file (str root-dir dir))
        files (file-seq directory)
        contents  (->> (reduce
                         (fn [acc file]
                           (if (.isDirectory file)
                             acc
                             (let [filename  (last (str/split (.getName file) #"/"))
                                   test-name (first (str/split filename #"\."))
                                   ext       (last (str/split filename #"\."))]
                               (assoc-in acc [test-name (keyword ext)] (slurp file)))))
                         {}
                         files)
                    (sort-by (fn [[k _]] k))
                    (map port-test))
        filename  (str (System/getProperty "user.dir") "/out/converted.clj")
        contents  (str/join "\n\n" (map pr-str (into [(file-header dir)] contents)))]

    (io/make-parents filename)

    (spit filename (zp/zprint-file-str contents "" {:width 80}))))

(comment
  (port-tests  "/Users/retro/Projects/scion-test-framework/test/" "parallel+interrupt")
  ;;
  )