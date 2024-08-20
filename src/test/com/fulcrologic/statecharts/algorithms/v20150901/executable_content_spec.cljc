(ns com.fulcrologic.statecharts.algorithms.v20150901.executable-content-spec
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901.setup :refer [test-env]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [Send final log on-entry on-exit parallel
                                                  script state transition]]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.event-queue.event-processing :as sc.event-processing :refer [standard-statechart-event-handler]]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.testing :as testing]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [com.fulcrologic.statecharts.working-memory-store.local-memory-store :as lms]
    [fulcro-spec.core :refer [=> assertions specification]]
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

(let [tries        (atom 0)
      events       (atom [])
      thing-to-try (fn [env {:keys [_event]}]
                     (swap! events conj _event)
                     (when (<= @tries 5)
                       (swap! tries inc)
                       (log/info "Retrying")
                       (env/raise env (evts/new-event
                                        {:name :retry
                                         :data (:data _event)}))))
      event-data   (fn [_env {:keys [_event]}] (:data _event))
      send-chart   (chart/statechart {:id :me}
                     (state {:id :top}
                       (on-entry {}
                         (script {:expr
                                  (fn [_ _]
                                    (reset! events [])
                                    (reset! tries 0))}))

                       (transition {:event :retry}
                         (Send {:event   :try
                                :content event-data}))
                       (transition {:event :try}
                         (script {:expr thing-to-try}))))]
  (specification "Send data passing through an expression on :content" :focus
    (let [{::sc/keys [event-queue working-memory-store processor] :as env} (assoc
                                                                             (test-env send-chart)
                                                                             ::sc/working-memory-store (lms/new-store))
          session-id (new-uuid)
          _          (sp/save-working-memory! working-memory-store env session-id
                       (sp/start! processor env ::m {::sc/session-id session-id}))
          next!      (fn [evt]
                       (let [wmem (sp/get-working-memory working-memory-store env session-id)
                             nmem (sp/process-event! processor env wmem (evts/new-event evt))]
                         (sp/save-working-memory! working-memory-store env session-id nmem)))]
      (next! (evts/new-event {:name :try
                              :data {:x 1}}))

      (sp/receive-events! event-queue env standard-statechart-event-handler)
      (sp/receive-events! event-queue env standard-statechart-event-handler)

      (assertions
        "Includes that content in the events"
        (every? #(= (:data %) {:x 1}) @events) => true))))

(specification "cond function that throws, disables the transition"
  (let [env                      (simple/simple-env)
        session-id               (new-uuid)
        throwing-cond-statechart (chart/statechart {}
                                   (state {:id :start}
                                     (transition {:event  :try-to-transition
                                                  :target :next
                                                  :cond   (fn [_env data]
                                                            (when (get-in data [:_event :data ::please-throw])
                                                              (throw (ex-info "Oops" {})))
                                                            true)}))
                                   (state {:id :next}))]

    (simple/register! env ::throwing-cond-statechart throwing-cond-statechart)
    (simple/start! env ::throwing-cond-statechart session-id)

    (simple/send! env {:event :try-to-transition :data {::please-throw true} :target session-id})
    (sc.event-processing/process-events env)

    (assertions "cond threw, no transition"
      (testing/in? {:session-id session-id :env env} :start) => true)

    (simple/send! env {:event :try-to-transition :target session-id})
    (sc.event-processing/process-events env)

    (assertions "cond did not throw, transition"
      (testing/in? {:session-id session-id :env env} :next) => true)))