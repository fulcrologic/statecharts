(ns com.fulcrologic.statecharts.routing-demo.chart
  "The async routing statechart for the event planner demo.

   Demonstrates:
   - Direct deep-targeting transitions (URL restore to nested state)
   - Cascading async on-entry at each route level
   - Error recovery via `raise` on the internal queue
   - Automatic redirect to error state when async loads fail

   State hierarchy:
   ```
   :state/root (initial → :state/landing)
     :state/landing
     :state/error
     :state/app
       :state/event-list (initial child)
       :state/event (async loads event data)
         :state/event-overview (initial child)
         :state/day (async loads day data)
           :state/day-overview (initial child)
           :state/menu (async loads menu data)
   ```"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [on-entry on-exit script state transition]]
    [com.fulcrologic.statecharts.routing-demo.model :as model]))

(defn- store-route-params
  "Transition action that stores the triggering event's data as route params
   in the data model, so on-entry handlers at each level can read them."
  [_env data _event-name event-data]
  [(ops/assign [:ROOT :route/params] event-data)])

(defn- clear-error
  "Clears any previous error message from the data model."
  [_env _data & _]
  [(ops/assign [:ROOT :error/message] nil)
   (ops/assign [:ROOT :error/type] nil)])

(defn- set-error-from-event
  "Reads the error details from the internal event and stores them."
  [_env data _event-name event-data]
  [(ops/assign [:ROOT :error/message] (:message event-data))
   (ops/assign [:ROOT :error/type] (:type event-data))])

(defn- clear-loaded-data
  "Clears loaded entities below the exiting level."
  [level]
  (fn [_env _data & _]
    (case level
      :event [(ops/assign [:ROOT :current-event] nil)
              (ops/assign [:ROOT :current-day] nil)
              (ops/assign [:ROOT :current-menu] nil)]
      :day [(ops/assign [:ROOT :current-day] nil)
            (ops/assign [:ROOT :current-menu] nil)]
      :menu [(ops/assign [:ROOT :current-menu] nil)])))

(def routing-chart
  "The main routing statechart. Uses the async execution engine to cascade
   async loads through nested route states.

   To restore a URL like /events/1/day/2/menu, send:
     {:name :route/go-menu :data {:event-id 1 :day-num 2}}

   The algorithm enters states in order: root → app → event → day → menu.
   Each on-entry performs an async load. If any load fails, it raises
   :error/not-found on the internal queue. After all entries complete,
   the error event triggers a transition to :state/error.

   :state/root is a wrapper state that holds all routing transitions.
   Since SCXML transition selection walks up the ancestor chain, these
   transitions are reachable from ANY descendant state — no duplication."
  (chart/statechart {:initial :state/root}
    (state {:id :state/root :initial :state/landing}
      ;; ── Routing transitions ────────────────────────────────────────
      ;; Available from ANY descendant state.
      (transition {:event :route/go-home :target :state/landing}
        (script {:expr store-route-params}))
      (transition {:event :route/go-events :target :state/event-list}
        (script {:expr store-route-params}))
      (transition {:event :route/go-event :target :state/event-overview}
        (script {:expr store-route-params}))
      (transition {:event :route/go-day :target :state/day-overview}
        (script {:expr store-route-params}))
      (transition {:event :route/go-menu :target :state/menu}
        (script {:expr store-route-params}))

      ;; Error handler — catches :error/not-found from any async load
      (transition {:event :error/not-found :target :state/error}
        (script {:expr set-error-from-event}))

      ;; ── States ───────────────────────────────────────────────────────
      (state {:id :state/landing}
        (on-entry {}
          (script {:expr clear-error})))

      (state {:id :state/error}
        (on-entry {}
          (script {:expr set-error-from-event})))

      (state {:id :state/app :initial :state/event-list}

        (state {:id :state/event-list}
          (on-entry {}
            (script {:expr clear-error})))

        (state {:id :state/event :initial :state/event-overview}
          (on-entry {}
            (script {:expr model/load-event!}))
          (on-exit {}
            (script {:expr (clear-loaded-data :event)}))

          (state {:id :state/event-overview})

          (state {:id :state/day :initial :state/day-overview}
            (on-entry {}
              (script {:expr model/load-day!}))
            (on-exit {}
              (script {:expr (clear-loaded-data :day)}))

            (state {:id :state/day-overview})

            (state {:id :state/menu}
              (on-entry {}
                (script {:expr model/load-menu!}))
              (on-exit {}
                (script {:expr (clear-loaded-data :menu)})))))))))
