(ns com.fulcrologic.statecharts.routing-demo.app
  "Application entry point for the async routing demo.

   Sets up Fulcro app with async statecharts, registers the routing chart,
   and provides URL-based route restoration."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    ;; Required for :async? true in install-fulcro-statecharts!
    [com.fulcrologic.statecharts.algorithms.v20150901-async]
    [com.fulcrologic.statecharts.event-queue.async-event-loop]
    [com.fulcrologic.statecharts.execution-model.lambda-async]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.routing-demo.chart :as demo-chart]
    [com.fulcrologic.statecharts.routing-demo.ui :as ui]
    [taoensso.timbre :as log]))

(defonce app-instance (app/fulcro-app {}))

(def chart-key ::routing-chart)

(defn parse-url
  "Parses the current browser URL into a routing event.
   Returns {:event event-name :data params} or nil.

   URL patterns:
     /events                        -> :route/go-events
     /events/:id                    -> :route/go-event {:event-id id}
     /events/:id/day/:num           -> :route/go-day {:event-id id :day-num num}
     /events/:id/day/:num/menu      -> :route/go-menu {:event-id id :day-num num}"
  []
  (let [path     (.-pathname js/window.location)
        segments (vec (remove str/blank? (str/split path #"/")))]
    (log/info "Parsing URL:" path "segments:" segments)
    (cond
      ;; /events/:id/day/:num/menu
      (and (= 5 (count segments))
           (= "events" (nth segments 0))
           (= "day" (nth segments 2))
           (= "menu" (nth segments 4)))
      {:event :route/go-menu
       :data  {:event-id (nth segments 1)
               :day-num  (nth segments 3)}}

      ;; /events/:id/day/:num
      (and (= 4 (count segments))
           (= "events" (nth segments 0))
           (= "day" (nth segments 2)))
      {:event :route/go-day
       :data  {:event-id (nth segments 1)
               :day-num  (nth segments 3)}}

      ;; /events/:id
      (and (= 2 (count segments))
           (= "events" (nth segments 0)))
      {:event :route/go-event
       :data  {:event-id (nth segments 1)}}

      ;; /events
      (and (= 1 (count segments))
           (= "events" (nth segments 0)))
      {:event :route/go-events
       :data  {}}

      :else nil)))

(defn restore-route!
  "Reads the current URL and sends the appropriate routing event."
  []
  (when-let [{:keys [event data]} (parse-url)]
    (log/info "Restoring route:" event data)
    (scf/send! app-instance ui/session-id event data)))

(defn ^:export init
  "Application entry point."
  []
  (log/info "Initializing async routing demo")
  (app/set-root! app-instance ui/Root {:initialize-state? true})
  ;; Install with async support and on-save to trigger re-renders
  (scf/install-fulcro-statecharts! app-instance
    {:async?      true
     :event-loop? true
     :on-save     (fn [_session-id]
                    ;; Bump a trigger key to force Root re-render
                    (swap! (::app/state-atom app-instance)
                      update :root/trigger (fnil inc 0)))})
  (scf/register-statechart! app-instance chart-key demo-chart/routing-chart)
  (scf/start! app-instance {:machine    chart-key
                             :session-id ui/session-id
                             :data       {}})
  (app/mount! app-instance ui/Root "app")
  ;; Try to restore route from URL after a brief delay to let chart initialize
  (js/setTimeout restore-route! 200)
  (js/console.log "Routing demo initialized. Try buttons or navigate to /events/1/day/2/menu"))

(defn ^:export refresh
  "Called by shadow-cljs on hot reload."
  []
  (app/force-root-render! app-instance))
