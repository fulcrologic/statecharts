(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes-test
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :as ele :refer [on-entry parallel script state transition]]
    [com.fulcrologic.statecharts.event-queue.event-processing :refer [process-events]]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.protocols :as scp]
    [com.fulcrologic.statecharts.testing :as testing]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
    [fulcro-spec.core :refer [=> assertions behavior specification]]
    [taoensso.timbre :as log]))

(let [b (volatile! false)]
  (defn set-busy! [v] (vreset! b v) nil)
  (defn busy? [env {:keys [_event]} & args]
    (and @b (not (-> _event :data ::force?)))))

(defn record-failed-route! [env {:keys [_event]} & args]
  [(ops/assign ::failed-route-event _event)])

(defonce entry-count (volatile! 0))
(defn entered! [& _]
  (vswap! entry-count inc))


(def application-chart
  (chart/statechart {}
    (uir/routing-regions
      (uir/routes {:id :region/routes}
        (uir/rstate {:route/target :route/foo}
          (uir/rstate {:route/target :route/routeA.1})
          (uir/rstate {:route/target :route/routeA.2}
            (uir/rstate {:route/target :route/routeA.2.1})
            (uir/rstate {:route/target :route/routeA.2.2}
              (on-entry {}
                (script {:expr entered!})))))))))

(specification "Route entry"
  (let [event-queue (mpq/new-queue)
        env         (testing/new-testing-env {:statechart  application-chart
                                              :event-queue event-queue} {entered!             entered!
                                                                         busy?                busy?
                                                                         override-route!      override-route!
                                                                         clear-override!      clear-override!
                                                                         record-failed-route! record-failed-route!})]
    (set-busy! false)
    (vreset! entry-count 0)
    (testing/start! env)
    (assertions
      "Starts on route A.1"
      (testing/in? env :routeA.1) => true)

    (behavior "Asking a non-busy route to route"
      (testing/run-events! env :route-to.routeA.2.2)

      (assertions
        "Can route"
        (testing/in? env :routeA.2.2) => true
        "The entry handler is called"
        @entry-count => 1))

    (behavior "Asking a busy route to route"
      (set-busy! true)
      (testing/run-events! env :route-to.routeA.1)

      (assertions
        "Records the failed route"
        (testing/ran? env record-failed-route!) => true
        "Stays where it is"
        (testing/in? env :routeA.2.2) => true
        "Skips the entry handler"
        @entry-count => 1
        "Activates the routing modal"
        (testing/in? env :routing-info/open) => true
        "Remembers the route that was denied"
        (::failed-route-event (testing/data env)) => {:type                                   :external,
                                                      :name                                   :route-to.routeA.1,
                                                      :data                                   {},
                                                      :com.fulcrologic.statecharts/event-name :route-to.routeA.1}))
    (behavior "Forcing a failed route"
      (testing/run-events! env :event.routing-info/force-route)
      (process-events (:env env))

      (assertions
        "Closes the denial modal"
        (testing/in? env :routing-info/idle) => true
        "Continues on to the failed route"
        (testing/in? env :routeA.1) => true
        "Clears the failed route"
        (::failed-route-event (testing/data env)) => nil))))
