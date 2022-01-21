(ns com.fulcrologic.statecharts.algorithms.v20150901.executable-content-spec
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel script on-entry
                                                  Send on-exit final log
                                                  initial data-model transition]]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [specification assertions component =>]]
    [taoensso.timbre :as log]))

(def env (simple/simple-env))

(defn a [_ _])
(defn b [_ _])
(defn c [_ _])
(defn d [_ _])
(defn e [_ _])

(def chart
  (chart/statechart {}
    (state {:id :start}
      (on-entry {}
        (script {:expr a})
        (Send {:event :X :delay 10})
        (script {:expr b})
        (log {:label "_" :expr c}))
      (on-exit {}
        (script {:expr d})
        (script {:expr e})
        (Send {:event :Y :delay 10}))
      (transition {:event  :done
                   :target :done}))
    (final {:id :done})))

(specification "Executable content"
  (log/with-level :debug                                    ; needed for log to run
    (let [env (testing/new-testing-env {:statechart chart} {a true b true c true d true e true})]

      (testing/start! env)

      (assertions
        "On entry runs executable content in document order"
        (testing/ran-in-order? env [a b c]) => true
        "On exit not run until exited"
        (testing/ran-in-order? env [d e]) => false)

      (testing/run-events! env :done)

      (assertions
        "On exit runs in document order"
        (testing/ran-in-order? env [d e]) => true))))

(def pchart
  (chart/statechart {}
    (parallel {}
      (state {:id :B}
        (on-entry {}
          (script {:expr c}))
        (on-exit {}
          (script {:expr d})))
      (state {:id :A}
        (on-entry {}
          (script {:expr a}))
        (on-exit {}
          (script {:expr b}))))))

(specification "Parallel State Executable Content"
  (let [env (testing/new-testing-env {:statechart pchart} {a true b true c true d true e true})]

    (testing/start! env)

    ;; NOTE: Document order only applies within blocks. There is no guaranteed order among parallel states
    (assertions
      "Runs entry content of all parallel states"
      (testing/ran? env c) => true
      (testing/ran? env a) => true)

    (testing/run-events! env evts/cancel-event)

    (assertions
      "Runs exit content of the parallel states"
      (testing/ran? env d) => true
      (testing/ran? env b) => true)))


(def nchart
  (chart/statechart {}
    (state {}
      (on-entry {}
        (script {:expr a}))
      (state {}
        (on-entry {}
          (script {:expr b}))
        (state {}
          (on-entry {}
            (script {:expr c})))))))

(def nchart2
  (chart/statechart {}
    (state {}
      (on-exit {}
        (script {:expr a}))
      (state {}
        (on-exit {}
          (script {:expr b}))
        (state {}
          (on-exit {}
            (script {:expr c})))))))

(specification "Nested State Executable Content"
  (let [env (testing/new-testing-env {:statechart nchart} {a true b true c true d true e true})]

    (testing/start! env)

    (assertions
      "Runs entry in nested document order"
      (testing/ran-in-order? env [a b c]) => true))
  (let [env (testing/new-testing-env {:statechart nchart2} {a true b true c true d true e true})]

    (testing/start! env)
    (testing/run-events! env evts/cancel-event)

    (assertions
      "Runs exits in reverse nested document order"
      (testing/ran-in-order? env [c b a]) => true)))

