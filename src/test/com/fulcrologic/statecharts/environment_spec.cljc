(ns com.fulcrologic.statecharts.environment-spec
  "Tests for `com.fulcrologic.statecharts.environment`. Charts run through the IRP runner so every
   behavior is checked under the sync processor, the async processor, and promise-returning expressions."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry raise script state transition] :as ele]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=>]]))

(defn- internal-event-with-data?
  "Returns a transition condition that is true when the current event is `:internal` and carries `expected-data`."
  [expected-data]
  (fn [_ {:keys [_event]}]
    (and (= :internal (:type _event))
      (= expected-data (:data _event)))))

(defn- check-step
  "A state that consumes `event-name`, going to `next-state` when the event is internal with `expected-data`."
  [id event-name expected-data next-state]
  (state {:id id}
    (transition {:event event-name :cond (internal-event-with-data? expected-data) :target next-state})
    (transition {:event event-name :target :fail})))

(def raised-event-chart
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (raise {:event :e1})
          (raise {:event :e2 :data (fn [_ _] nil)})
          (script {:expr (fn [env _]
                           (env/raise env :e3 nil)
                           (env/raise env :e4 {:x 1})
                           nil)})
          (ele/send {:event :e5 :target :_internal}))
        (transition {:event :e1 :cond (internal-event-with-data? {}) :target :s1})
        (transition {:event :e1 :target :fail}))
      (check-step :s1 :e2 {} :s2)
      (check-step :s2 :e3 {} :s3)
      (check-step :s3 :e4 {:x 1} :s4)
      (check-step :s4 :e5 {} :pass)
      (final {:id :pass})
      (final {:id :fail}))))

(runner/async-specification "raise"
  "queues events with :type :internal and treats nil data as an empty map
   (raise element with/without data, env/raise 3-arity with nil and map data, send to :_internal)"
  (runner/passes? raised-event-chart) => true)

(def non-map-data-chart
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (ele/send {:event :e1 :target :_internal :content (fn [_ _] "foo")})
          (script {:expr (fn [env _] (env/raise env :e2 42) nil)}))
        (transition {:event :e1 :cond (internal-event-with-data? "foo") :target :s1})
        (transition {:event :e1 :target :fail}))
      (check-step :s1 :e2 42 :pass)
      (final {:id :pass})
      (final {:id :fail}))))

(runner/async-specification "non-map event data"
  "is delivered as-is from <send> content and from env/raise"
  (runner/passes? non-map-data-chart) => true)
