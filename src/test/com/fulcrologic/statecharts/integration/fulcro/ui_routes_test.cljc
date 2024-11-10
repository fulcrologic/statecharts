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
    (if (-> _event :data ::uir/force?)
      false
      (and @b (not (-> _event :data ::force?))))))

(defonce entry-count (volatile! 0))
(defn entered! [& _]
  (vswap! entry-count inc))

(def application-chart
  (chart/statechart {}
    (uir/routing-regions
      (uir/routes {:id           :region/routes
                   :routing/root `Foo}
        (uir/rstate {:route/target :route/foo}
          (uir/rstate {:route/target :route/routeA1})
          (uir/rstate {:route/target :route/routeA2}
            (uir/rstate {:route/target :route/routeA21})
            (uir/rstate {:route/target :route/routeA22}
              (on-entry {}
                (script {:expr entered!})))))))
    (state {:id :state/other})))

(specification "has-routes?"
  (assertions
    "Non-leaf"
    (uir/has-routes? application-chart :region/routes) => true
    (uir/has-routes? application-chart :route/foo) => true
    (uir/has-routes? application-chart :route/routeA2) => true
    "Leaf"
    (uir/has-routes? application-chart :route/routeA22) => false))

(specification "leaf-route?" :focus
  (assertions
    "Non-routing nodes"
    (uir/leaf-route? application-chart :state/other) => false
    "Non-leaf"
    (uir/leaf-route? application-chart :region/routes) => false
    (uir/leaf-route? application-chart :route/foo) => false
    "Leaf"
    (uir/leaf-route? application-chart :route/routeA22) => true))

(specification "Route entry"
  (let [event-queue (mpq/new-queue)
        env         (testing/new-testing-env {:statechart  application-chart
                                              :event-queue event-queue} {entered!                 entered!
                                                                         uir/busy?                busy?
                                                                         uir/override-route!      uir/override-route!
                                                                         uir/clear-override!      uir/clear-override!
                                                                         uir/record-failed-route! uir/record-failed-route!})]
    (set-busy! false)
    (vreset! entry-count 0)
    (testing/start! env)
    (assertions
      "Starts routes"
      (testing/in? env :region/routes) => true
      "Starts on route A1"
      (testing/in? env :route/routeA1) => true)

    (behavior "Asking for a direct non-busy route"
      (testing/run-events! env (uir/route-to-event-name :route/routeA22))

      (assertions
        "Can route"
        (testing/in? env :route/routeA22) => true
        "The entry handler is called"
        @entry-count => 1))

    (behavior "Asking a busy route to route"
      (set-busy! true)
      (testing/run-events! env (uir/route-to-event-name :route/routeA1))

      (assertions
        "Records the failed route"
        (testing/ran? env uir/record-failed-route!) => true
        "Stays where it is"
        (testing/in? env :route/routeA22) => true
        "Skips the entry handler"
        @entry-count => 1
        "Activates the routing modal"
        (testing/in? env :routing-info/open) => true
        "Remembers the route that was denied"
        (::uir/failed-route-event (testing/data env)) => {:type                                   :external,
                                                          :name                                   (uir/route-to-event-name :route/routeA1)
                                                          :data                                   {},
                                                          :com.fulcrologic.statecharts/event-name (uir/route-to-event-name :route/routeA1)}))
    (behavior "Forcing a failed route"
      (testing/run-events! env :event.routing-info/force-route)
      (process-events (:env env))

      (assertions
        "Closes the denial modal"
        (testing/in? env :routing-info/idle) => true
        "Continues on to the failed route"
        (testing/in? env :route/routeA1) => true
        "Clears the failed route"
        (::uir/failed-route-event (testing/data env)) => nil))))
