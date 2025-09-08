(ns com.fulcrologic.statecharts.integration.fulcro.ui-routes-test
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state]]
    [com.fulcrologic.statecharts.event-queue.event-processing :refer [process-events]]
    [com.fulcrologic.statecharts.event-queue.manually-polled-queue :as mpq]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
    [com.fulcrologic.statecharts.testing :as testing]
    [fulcro-spec.core :refer [=> assertions behavior specification]]))

(let [b (volatile! false)]
  (defn set-busy! [v] (vreset! b v) nil)
  (defn busy? [env {:keys [_event]} & args]
    (if (-> _event :data ::uir/force?)
      false
      (and @b (not (-> _event :data ::force?))))))

(defonce entry-count (volatile! 0))
(defn entered! [& _]
  (vswap! entry-count inc))

;; Route components for testing path-for-target
(comp/defsc RouteProducts [this props] {})
(comp/defsc RouteProductDetail [this props] {})
(comp/defsc RouteProductEdit [this props] {})
(comp/defsc RouteProductReviews [this props] {})
(comp/defsc RouteReviewDetail [this props] {})
(comp/defsc RouteUsers [this props] {})
(comp/defsc RouteUserDetail [this props] {})
(comp/defsc RouteUserProfile [this props] {})

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

(def path-test-chart
  "A statechart with nested routing states that have path vectors"
  (chart/statechart {}
    (uir/routing-regions
      (uir/routes {:id           :region/routes
                   :routing/root `Foo}
        (uir/rstate {:route/target `RouteProducts
                     :route/path   ["products"]}
          (uir/rstate {:route/target `RouteProductDetail
                       :route/path   [:product-id]})
          (uir/rstate {:route/target `RouteProductEdit
                       :route/path   [:product-id "edit"]})
          (uir/rstate {:route/target `RouteProductReviews
                       :route/path   [:product-id "reviews"]}
            (uir/rstate {:route/target `RouteReviewDetail
                         :route/path   [:review-id]})))
        (uir/rstate {:route/target `RouteUsers
                     :route/path   ["users"]}
          (uir/rstate {:route/target `RouteUserDetail
                       :route/path   [:user-id]})
          (uir/rstate {:route/target `RouteUserProfile
                       :route/path   [:user-id "profile"]}))))))

(defn test-app-with-chart [chart]
  (let [app (rapp/headless-synchronous-app Foo)]
    (scf/install-fulcro-statecharts! app)
    (uir/update-chart! app chart)
    app))

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

(specification "path-for-target" :group3 :focus
  (let [app (test-app-with-chart path-test-chart)]

    (behavior "returns paths without parameters"
      (assertions
        ;"Simple nested path"
        ;(uir/path-for-target app RouteProducts) => ["products"]

        "Deep nested path with mixed string/keyword segments"
        (uir/path-for-target app RouteProductEdit) => ["products" :product-id "edit"]

        ;"Deepest nested path"
        ;(uir/path-for-target app RouteReviewDetail) => ["products" :product-id "reviews" :review-id]

        ;"Different branch - users"
        ;(uir/path-for-target app RouteUserDetail) => ["users" :user-id]

        ;"User profile path"
        #_#_#_(uir/path-for-target app RouteUserProfile) => ["users" :user-id "profile"]))

    #_#_(behavior "replaces keywords with parameter values when provided"
          (assertions
            "Single parameter replacement"
            (uir/path-for-target app RouteProductDetail {:product-id 123}) => ["products" "123"]

            "Multiple parameters replacement"
            (uir/path-for-target app RouteReviewDetail {:product-id "abc" :review-id "xyz"})
            => ["products" "abc" "reviews" "xyz"]

            "Mixed parameters and static strings"
            (uir/path-for-target app RouteProductEdit {:product-id "def"}) => ["products" "def" "edit"]

            "User profile with parameter"
            (uir/path-for-target app RouteUserProfile {:user-id "john"}) => ["users" "john" "profile"]))

            (behavior "handles missing parameters gracefully"
              (assertions
                "Missing parameter keeps keyword"
                (uir/path-for-target app RouteProductDetail {:other-param "ignored"}) => ["products" :product-id]

                "Partial parameter replacement"
                (uir/path-for-target app RouteReviewDetail {:product-id "123"}) => ["products" "123" "reviews" :review-id]))))
