(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-restore-params-spec
  "What a URL restore (bookmark, back/forward, `route-sync-from-url!`) puts in the route event: only the route params
   each state declares, never a routing control key, and each state's own params."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.headless-app :as h]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit :as ruct]
    [com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as sim]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]
    [com.fulcrologic.statecharts.promise :as p]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def route-events
  "Route target key -> the data of the route events its route state was entered with, latest last."
  (atom {}))

(defn- recording-props
  "An `sfro/initial-props` that records the entering route event's data under `k`."
  [k initial]
  (fn [_env data & _]
    (swap! route-events update k (fnil conj []) (get-in data [:_event :data]))
    initial))

(defn- last-event [k] (peek (get @route-events k)))

(defsc Root [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::Root])
   :initial-state {:ui/current-route {}}})

(defsc Landing [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :landing}})

(defsc Busy [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :busy}
   sfro/busy?     (fn [& _] true)})

(defsc Target [_ _]
  {:query             [:page/id]
   :ident             :page/id
   sfro/initial-props (recording-props ::Target {:page/id :target})})

(defsc Untracked [_ _]
  {:query             [:page/id]
   :ident             :page/id
   sfro/initial-props (recording-props ::Untracked {:page/id :untracked})})

(defsc Mixed [_ _]
  {:query             [:page/id]
   :ident             :page/id
   sfro/initial-props (recording-props ::Mixed {:page/id :mixed})})

(defsc PA [_ _]
  {:query             [:page/id]
   :ident             :page/id
   sfro/initial-props (recording-props ::PA {:page/id :pa})})

(defsc PB [_ _]
  {:query         [:page/id :pb/initial-params]
   :ident         :page/id
   :initial-state (fn [params] {:page/id :pb :pb/initial-params params})})

(defsc Dash [_ _]
  {:query                   [:ui/placeholder]
   :preserve-dynamic-query? true
   :ident                   (fn [] [:component/id ::Dash])
   :initial-state           {:ui/placeholder true}})

(defsc Inner [_ _]
  {:query             [:page/id]
   :ident             :page/id
   sfro/initial-props (recording-props ::Inner {:page/id :inner})})

(def module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/module-routes :routing/root `Module}
        (scr/rstate {:route/target `Inner :route/params #{:i} :route/url-params #{:iu}})))))

(defsc Module [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::Module])
   :initial-state  {}
   sfro/statechart module-chart})

(def routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/rstate {:route/target `Busy})
        (scr/rstate {:route/target `Target :route/params #{:t ::scr/force?}})
        (scr/rstate {:route/target `Untracked :route/url-params #{:u ::scr/force?}})
        (scr/rstate {:route/target `Mixed :route/params #{:t} :route/url-params #{:sort}})
        (scr/rstate {:route/target `Dash :parallel? true :route/params #{:d :sort}}
          (scr/rstate {:route/target `PA :route/params #{:sort :page}})
          (scr/rstate {:route/target `PB :route/params #{:sort :page}}))
        (scr/istate {:route/target    `Module    :route/params #{:m} :route/url-params #{:mu}
                     :route/reachable #{::Inner}})))))

(def codec (ruct/transit-base64-codec))

(defn- url
  "A URL whose path is `segments` and whose `_p` holds `params` (keyed by route state id), as a crafted link would."
  [segments params]
  (let [path (str "/" (str/join "/" segments))
        b64  (ruct/encode-params-base64 params)]
    (str path "?_p=" #?(:clj (java.net.URLEncoder/encode ^String b64 "UTF-8") :cljs (js/encodeURIComponent b64)))))

(defn- url-params "The params encoded in `href`, keyed by route state id." [href] (:params (ruc/decode-url codec href {})))

(defn- routing-params [a k] (get-in (h/local-data a) [:routing/parameters k]))

(defn- restore!
  "Makes `url` the current URL of the routed app `env` (as a browser would, without notifying url-sync) and routes
   to it with `route-sync-from-url!`."
  [{a :app :keys [provider]} url]
  (ruh/-replace-url! provider url)
  (scr/route-sync-from-url! a))

(specification "A URL restore's route params"
  (reset! route-events {})
  (component "a crafted routing control key"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Busy {})
      (restore! env (url ["Target"] {::Target {::scr/force? true :t 1}}))
      (assertions
        "doesn't bypass the busy guard: the route is denied"
        (scr/route-denied? a) => true
        (contains? (scf/current-configuration a scr/session-id) ::Busy) => true))
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Busy {})
      (restore! env (url ["Target"] {::Junk {::scr/force? true}}))
      (assertions
        "doesn't bypass it under a state id that isn't a route either"
        (scr/route-denied? a) => true)))
  (component "of a route state"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (restore! env (url ["Target"] {::Target {:t 1 :evil 2 ::scr/force? true}
                                     ::Junk   {:junk 3}}))
      (assertions
        "(the route is entered)"
        (contains? (scf/current-configuration a scr/session-id) ::Target) => true
        "keep only the keys the state declares, without routing control keys"
        (routing-params a ::Target) => {:t 1}
        "and the route event has only those"
        (dissoc (last-event ::Target) ::scr/params-by-state ::scr/restored-params) => {:t 1}
        "with each known state's own params at ::params-by-state (an unknown state's are dropped)"
        (::scr/params-by-state (last-event ::Target)) => {::Target {:t 1}})))
  (component "of states in a parallel route state"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `PA {})
      (scr/replace-route-params! a `Dash {:d 1})
      (scr/replace-route-params! a `PA {:sort :a :page 2})
      (scr/replace-route-params! a `PB {:sort :b})
      (let [bookmark (h/current-url env)
            {b :app} (h/routed-app Root routing-chart bookmark)]
        (assertions
          "(the URL has each state's params)"
          (url-params bookmark) => {::Dash {:d 1} ::PA {:sort :a :page 2} ::PB {:sort :b}}
          "restore independently, even with the same keys"
          (routing-params b ::PA) => {:sort :a :page 2}
          (routing-params b ::PB) => {:sort :b}
          (routing-params b ::Dash) => {:d 1}
          "and initialize each route with its own params"
          (get-in (app/current-state b) [:page/id :pb :pb/initial-params]) => {:sort :b}
          "while the route event itself has the params of the URL's route path (its leaf and the states above it)"
          (dissoc (last-event ::PA) ::scr/params-by-state ::scr/restored-params) => (if (str/includes? bookmark "/PA?")
                                                                                      {:d 1 :sort :a :page 2}
                                                                                      {:d 1 :sort :b}))))
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `PA {})
      (scr/replace-route-params! a `PA {:sort :a})
      (let [{b :app} (h/routed-app Root routing-chart (h/current-url env))]
        (assertions
          "a state with no params in the URL gets none (not a sibling's)"
          (routing-params b ::PB) => {}
          (routing-params b ::PA) => {:sort :a}))))
  (component "of a state in an invoked chart"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (restore! env (url ["Module" "Inner"] {::Module {:m 1 :evil 2}
                                             ::Inner  {:i 3 :evil 4 ::scr/force? true}}))
      (assertions
        "(the route is entered)"
        (contains? (scf/current-configuration a (h/child-session-id a ::Module)) ::Inner) => true
        "keep only the keys each state declares"
        (routing-params a ::Module) => {:m 1}
        (get-in (h/local-data a (h/child-session-id a ::Module)) [:routing/parameters ::Inner]) => {:i 3}
        "and the route event carries only the path's declared params"
        (dissoc (last-event ::Inner) ::scr/params-by-state ::scr/restored-params) => {:m 1 :i 3}))))

;; ===== :route/url-params =====

(specification "A route state's :route/url-params"
  (reset! route-events {})
  (component "on a state with no :route/params"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (restore! env (url ["Untracked"] {::Untracked {:u 1 :evil 2 ::scr/force? true}}))
      (assertions
        "(the route is entered)"
        (contains? (scf/current-configuration a scr/session-id) ::Untracked) => true
        "are accepted from a URL restore into the route event (undeclared keys and routing control keys are not)"
        (dissoc (last-event ::Untracked) ::scr/params-by-state ::scr/restored-params) => {:u 1}
        "and at ::params-by-state"
        (::scr/params-by-state (last-event ::Untracked)) => {::Untracked {:u 1}}
        "but aren't recorded in :routing/parameters"
        (routing-params a ::Untracked) => {}
        "so the URL url-sync writes for the restore (a replace) doesn't have them"
        (h/stack env) => ["/Untracked"])
      (scr/route-to! a `Landing {})
      (scr/route-to! a `Untracked {:u 5})
      (assertions
        "nor does a URL it pushes for a route-to! that has them"
        (:u (last-event ::Untracked)) => 5
        (h/stack env) => ["/Untracked" "/Landing" "/Untracked"]))
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Busy {})
      (restore! env (url ["Untracked"] {::Untracked {::scr/force? true}}))
      (assertions
        "a routing control key in them is still stripped: it doesn't bypass the busy guard"
        (scr/route-denied? a) => true
        (contains? (scf/current-configuration a scr/session-id) ::Busy) => true)))
  (component "on a state that also has :route/params"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (restore! env (url ["Mixed"] {::Mixed {:t 1 :sort :a :evil 2}}))
      (assertions
        "are accepted in addition to the :route/params"
        (dissoc (last-event ::Mixed) ::scr/params-by-state ::scr/restored-params) => {:t 1 :sort :a}
        "while only the :route/params are recorded"
        (routing-params a ::Mixed) => {:t 1}
        "and written to the URL"
        (url-params (h/current-url env)) => {::Mixed {:t 1}})))
  (component "on the states of the restored route's path, across an istate"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (restore! env (url ["Module" "Inner"] {::Module {:m 1 :mu 2 :evil 3}
                                             ::Inner  {:i 4 :iu 5 :evil 6}}))
      (assertions
        "(the route is entered)"
        (contains? (scf/current-configuration a (h/child-session-id a ::Module)) ::Inner) => true
        "reach the leaf's route event at its top level, with the :route/params"
        (dissoc (last-event ::Inner) ::scr/params-by-state ::scr/restored-params) => {:m 1 :mu 2 :i 4 :iu 5}
        "and each state's own at ::params-by-state"
        (::scr/params-by-state (last-event ::Inner)) => {::Module {:m 1 :mu 2} ::Inner {:i 4 :iu 5}}
        "while each state records only its :route/params"
        (routing-params a ::Module) => {:m 1}
        (get-in (h/local-data a (h/child-session-id a ::Module)) [:routing/parameters ::Inner]) => {:i 4}))))

;; ===== The path's params, and istates whose chart is a keyword =====

(specification "A URL restore's route event, with the same param at two levels of the restored path"
  (let [bookmark (url ["Dash" "PA"] {::Dash {:sort :outer :d 1} ::PA {:sort :inner}})
        {b :app} (h/routed-app Root routing-chart bookmark)]
    (assertions
      "has the deeper state's value at its top level"
      (:sort (last-event ::PA)) => :inner
      (:d (last-event ::PA)) => 1
      "while each state keeps its own"
      (routing-params b ::Dash) => {:sort :outer :d 1}
      (routing-params b ::PA) => {:sort :inner})))

(defn- keyword-module-chart
  "A routing module chart whose routing root is `Module` and whose second route, `Inner`, takes `:k`."
  [routes-id Module Home Inner]
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id routes-id :routing/root Module}
        (scr/rstate {:route/target Home})
        (scr/rstate {:route/target Inner :route/params #{:k}})))))

(defsc OptHome [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :opt-home}})
(defsc OptInner [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :opt-inner}})
(defsc IdHome [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :id-home}})
(defsc IdInner [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :id-inner}})

(defsc ByOption [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::ByOption])
   :initial-state {}})

(defsc ById [_ _]
  {:query             [:ui/current-route]
   :ident             (fn [] [:component/id ::ById])
   :initial-state     {}
   sfro/statechart-id ::by-id-chart})

(def keyword-routing-chart
  "Root routes with an istate whose chart is the istate's `:statechart-id` and one whose chart is its component's
   `sfro/statechart-id`."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/istate {:route/target `ByOption :statechart-id ::by-option-chart :route/reachable #{::OptHome ::OptInner}})
        (scr/istate {:route/target `ById :route/reachable #{::IdHome ::IdInner}})))))

(defn- keyword-app
  "A routed app of `keyword-routing-chart` (its module charts registered first) whose URL sync restores `bookmark`."
  [bookmark]
  (let [a        (h/new-app Root)
        provider (sim/simulated-url-history bookmark)]
    (scf/register-statechart! a ::by-option-chart (keyword-module-chart :region/by-option-routes `ByOption `OptHome `OptInner))
    (scf/register-statechart! a ::by-id-chart (keyword-module-chart :region/by-id-routes `ById `IdHome `IdInner))
    (scr/start! a keyword-routing-chart)
    (scr/install-url-sync! a {:provider provider})
    {:app a :provider provider}))

(specification "A URL restore into an istate whose chart is a keyword"
  (let [{a :app} (keyword-app (url ["ByOption" "OptInner"] {::OptInner {:k 1}}))
        sid (h/child-session-id a ::ByOption)]
    (assertions
      "(the istate's :statechart-id) routes into its chart"
      (contains? (scf/current-configuration a sid) ::OptInner) => true
      "and gives the route its params: the restore finds the chart's route states"
      (get-in (h/local-data a sid) [:routing/parameters ::OptInner]) => {:k 1}))
  (let [{a :app} (keyword-app (url ["ById" "IdInner"] {::IdInner {:k 2}}))
        sid (h/child-session-id a ::ById)]
    (assertions
      "(the component's sfro/statechart-id) routes into its chart"
      (contains? (scf/current-configuration a sid) ::IdInner) => true
      "and gives the route its params"
      (get-in (h/local-data a sid) [:routing/parameters ::IdInner]) => {:k 2})))

;; ===== Replaying a URL restore's route event =====

(specification "A URL restore's route event, replayed"
  (component "with a changed param"
    (let [{a :app :as env} (h/routed-app Root routing-chart (url ["Target"] {::Target {:t 1}}))
          restored (last-event ::Target)]
      (scr/route-to! a `Landing {})
      (scr/route-to! a `Target (assoc restored :t 2))
      (assertions
        "gives the route the changed value (an explicit top-level param wins over the restore's own)"
        (routing-params a ::Target) => {:t 2}
        "and writes it to the URL"
        (url-params (h/current-url env)) => {::Target {:t 2}})
      (scf/send! a scr/session-id (scr/route-to-event-name ::Target) (assoc restored :t 3))
      (assertions
        "also when the event is sent directly"
        (routing-params a ::Target) => {:t 3})
      (scr/route-to! a `Target {::scr/params-by-state {::Target {:t 4}} :t 5})
      (assertions
        "(an event with per-state params but no record of the restore's own: its top-level params all win)"
        (routing-params a ::Target) => {:t 5})))
  (component "unchanged"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `PA {})
      (scr/replace-route-params! a `PA {:sort :a :page 2})
      (scr/replace-route-params! a `PB {:sort :b})
      (let [{b :app} (h/routed-app Root routing-chart (h/current-url env))
            restored (last-event ::PA)]
        (scr/route-to! b `Landing {})
        (scr/route-to! b `PA restored)
        (assertions
          "still gives each state its own params"
          (routing-params b ::PA) => {:sort :a :page 2}
          (routing-params b ::PB) => {:sort :b})))))

;; ===== Deferred initial restore =====

(specification "install-url-sync! with :defer-restore? true"
  (let [bookmark (url ["Target"] {::Target {:t 7}})
        {a :app :as env} (h/routed-app Root routing-chart bookmark {:defer-restore? true})]
    (assertions
      "doesn't restore the URL: the chart stays on its initial route"
      (contains? (scf/current-configuration a scr/session-id) ::Landing) => true
      "and keeps the bookmarked entry"
      (h/stack env) => [bookmark])
    (scr/route-to! a `PA {})
    (scr/replace-route-params! a `PA {:sort :a})
    (assertions
      "writes no URL until the restore, so route changes keep the bookmark"
      (h/stack env) => [bookmark])
    (scr/route-to! a `Landing {})
    (scr/route-sync-from-url! a)
    (assertions
      "route-sync-from-url! restores it"
      (contains? (scf/current-configuration a scr/session-id) ::Target) => true
      (routing-params a ::Target) => {:t 7}
      "on the bookmarked entry (no new one)"
      (h/stack env) => [bookmark])
    (scr/route-to! a `Landing {})
    (assertions
      "and URL sync goes on as usual after it"
      (h/stack env) => [bookmark "/Landing"]))
  (let [bookmark (url ["Target"] {::Target {:t 7}})
        {a :app :as env} (h/routed-app Root routing-chart bookmark)]
    (assertions
      "(without it, install-url-sync! restores at once)"
      (contains? (scf/current-configuration a scr/session-id) ::Target) => true
      (h/stack env) => [bookmark])))

;; ===== Ending a deferred restore from a step =====

(defn- route-sync-script
  "A script that calls `route-sync-from-url!` (e.g. an auth flow that learned the user is logged in)."
  []
  (script {:expr (fn [env & _] (scr/route-sync-from-url! (:fulcro/app env)) nil)}))

(def auth-module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/auth-module-routes :routing/root `AuthModule}
        (transition {:event :event/logged-in} (route-sync-script))
        (scr/rstate {:route/target `AuthHome})))))

(defsc AuthHome [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :auth-home}})

(defsc AuthModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::AuthModule])
   :initial-state  {}
   sfro/statechart auth-module-chart})

(def auth-routing-chart
  "Starts on the `AuthModule` istate; `:event/logged-in` calls `route-sync-from-url!` from the routing chart's step."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/auth-routes :routing/root `Root}
        (transition {:event :event/logged-in} (route-sync-script))
        (scr/istate {:route/target `AuthModule})
        (scr/istate {:route/target `Module :route/reachable #{::Inner}})
        (scr/rstate {:route/target `Landing})
        (scr/rstate {:route/target `Busy})
        (scr/rstate {:route/target `Target :route/params #{:t}})))))

(specification "route-sync-from-url! ending a deferred initial restore"
  (let [bookmark (url ["Target"] {::Target {:t 7}})]
    (component "called from outside a step"
      (let [{a :app :as env} (h/routed-app Root auth-routing-chart bookmark {:defer-restore? true})]
        (scr/route-sync-from-url! a)
        (assertions
          "restores the URL on the bookmarked entry"
          (contains? (scf/current-configuration a scr/session-id) ::Target) => true
          (h/stack env) => [bookmark])
        (scr/route-to! a `Landing {})
        (assertions
          "and URL sync goes on as usual after it"
          (h/stack env) => [bookmark "/Landing"])))
    (component "called from a routing-chart transition"
      (let [{a :app :as env} (h/routed-app Root auth-routing-chart bookmark {:defer-restore? true})]
        (scf/send! a scr/session-id :event/logged-in {})
        (assertions
          "restores the URL on the bookmarked entry: the step's own save writes no URL first"
          (contains? (scf/current-configuration a scr/session-id) ::Target) => true
          (h/stack env) => [bookmark])
        (scr/route-to! a `Landing {})
        (assertions
          "and URL sync goes on as usual after it"
          (h/stack env) => [bookmark "/Landing"])))
    (component "called from an invoked child session's step"
      (let [{a :app :as env} (h/routed-app Root auth-routing-chart bookmark {:defer-restore? true})]
        (scf/send! a (h/child-session-id a ::AuthModule) :event/logged-in {})
        (assertions
          "restores the URL on the bookmarked entry: the child's save writes no URL first"
          (contains? (scf/current-configuration a scr/session-id) ::Target) => true
          (h/stack env) => [bookmark]))))
  (component "of a route in a module that isn't active (the module is entered for it)"
    (let [bookmark (url ["Module" "Inner"] {::Inner {:i 4}})
          {a :app :as env} (h/routed-app Root auth-routing-chart bookmark {:defer-restore? true})]
      (scr/route-sync-from-url! a)
      (assertions
        "restores it"
        (contains? (scf/current-configuration a (h/child-session-id a ::Module)) ::Inner) => true
        "on the bookmarked entry: the module's save before the routing session's writes no URL"
        (count (h/stack env)) => 1)
      (scr/route-to! a `Landing {})
      (assertions
        "and URL sync goes on as usual after it"
        (count (h/stack env)) => 2
        (h/current-url env) => "/Landing")))
  (component "of a route in a module that is active (the event is forwarded to it)"
    (let [{a :app :as env} (h/routed-app Root auth-routing-chart "/AuthModule/AuthHome" {:defer-restore? true})]
      (scr/route-sync-from-url! a)
      (scr/route-to! a `Landing {})
      (assertions
        "ends the deferral: URL sync goes on as usual after it"
        (h/stack env) => ["/AuthModule/AuthHome" "/Landing"])))
  (component "for a URL that matches no route"
    (let [{a :app :as env} (h/routed-app Root auth-routing-chart "/Nowhere" {:defer-restore? true})]
      (scr/route-sync-from-url! a)
      (scr/route-to! a `Landing {})
      (assertions
        "ends the deferral at once: the next route writes its URL"
        (h/stack env) => ["/Landing"])))
  (component "whose restore the busy guard denies"
    (let [bookmark (url ["Target"] {::Target {:t 7}})
          {a :app :as env} (h/routed-app Root auth-routing-chart bookmark {:defer-restore? true})]
      (scr/route-to! a `Busy {})
      (scr/route-sync-from-url! a)
      (assertions
        "is denied"
        (scr/route-denied? a) => true
        "and ends the deferral: URL sync writes the current route's URL"
        (h/stack env) => ["/Busy"]))))

(defsc M1 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :m1}})
(defsc M2 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :m2}})

(def two-route-module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/two-route-module-routes :routing/root `TwoRouteModule}
        (scr/rstate {:route/target `M1})
        (scr/rstate {:route/target `M2 :route/params #{:p}})))))

(defsc TwoRouteModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::TwoRouteModule])
   :initial-state  {}
   sfro/statechart two-route-module-chart})

(def active-module-chart
  "Starts on the `TwoRouteModule` istate (whose chart starts on `M1`)."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/active-module-routes :routing/root `Root}
        (scr/istate {:route/target `TwoRouteModule :route/reachable #{::M1 ::M2}})
        (scr/rstate {:route/target `Landing})))))

(def gate-chart
  "Waits in `:state/gate`, outside its routes, until `:event/logged-in`."
  (chart/statechart {:initial :state/gate}
    (state {:id :state/gate}
      (transition {:event :event/logged-in :target :state/route-root}))
    (scr/routing-regions
      (scr/routes {:id :region/gate-routes :routing/root `Root}
        (scr/rstate {:route/target `Landing})
        (scr/rstate {:route/target `Target :route/params #{:t}})))))

(def gated-routes-chart
  "Its routes node's only child takes every route event (`route-to.*`) while `:event/logged-in` hasn't been seen,
   instead of a route transition (a login gate inside the routes)."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/gated-routes :routing/root `Root}
        (state {:id :state/gated}
          (transition {:event :route-to.* :cond (fn [_ data & _] (not (:logged-in? data)))})
          (scr/rstate {:route/target `Landing})
          (scr/rstate {:route/target `Target :route/params #{:t}}))))))

(specification "route-sync-from-url! ending a deferred initial restore that no route transition takes"
  (let [bookmark (url ["Target"] {::Target {:t 7}})]
    (component "while the routing chart waits outside its routes"
      (let [{a :app :as env} (h/routed-app Root gate-chart bookmark {:defer-restore? true})]
        (scr/route-sync-from-url! a)
        (assertions
          "drops the route event (nothing routes)"
          (scf/current-configuration a scr/session-id) => #{:state/gate})
        (scf/send! a scr/session-id :event/logged-in {})
        (assertions
          "ends the deferral at the routing session's save: URL sync writes the chart's first route on the entry"
          (h/stack env) => ["/Landing"])
        (scr/route-to! a `Target {:t 1})
        (assertions
          "and goes on as usual"
          (count (h/stack env)) => 2)))
    (component "while a transition of a state in its routes takes every route event"
      (let [{a :app :as env} (h/routed-app Root gated-routes-chart bookmark {:defer-restore? true})]
        (scr/route-sync-from-url! a)
        (assertions
          "the gate takes the route event"
          (contains? (scf/current-configuration a scr/session-id) ::Landing) => true
          "and the deferral ends at that save: URL sync writes the current route on the entry"
          (h/stack env) => ["/Landing"])))))

(specification "route-sync-from-url! ending a deferred restore of another route of an active module"
  (let [bookmark (url ["TwoRouteModule" "M2"] {::M2 {:p 3}})
        {a :app :as env} (h/routed-app Root active-module-chart bookmark {:defer-restore? true})
        module-session (fn [] (h/child-session-id a ::TwoRouteModule))]
    (assertions
      "(the module is active, on its first route)"
      (contains? (scf/current-configuration a (module-session)) ::M1) => true)
    (scr/route-sync-from-url! a)
    (assertions
      "restores the route in the module (the routing session forwards the event to it)"
      (contains? (scf/current-configuration a (module-session)) ::M2) => true
      "on the bookmarked entry only: the deferral ends at the module's save, after it routed"
      (h/stack env) => [bookmark])
    (scr/route-to! a `Landing {})
    (assertions
      "and URL sync goes on as usual after it"
      (h/stack env) => [bookmark "/Landing"])))

(def restore-then-leave-chart
  "`:kick` restores the URL (`route-sync-from-url!`) and routes to `Landing` in the same step (e.g. an auth check)."
  (chart/statechart {:initial :idle}
    (state {:id :idle}
      (transition {:event :kick}
        (script {:expr (fn [env & _]
                         (scr/route-sync-from-url! (:fulcro/app env))
                         (scr/route-to! (:fulcro/app env) `Landing {})
                         nil)})))))

(specification "route-sync-from-url! ending a deferred restore forwarded to a module that is stopped before it takes it"
  (let [bookmark (url ["TwoRouteModule" "M2"] {::M2 {:p 3}})
        {a :app :as env} (h/routed-app Root active-module-chart bookmark {:defer-restore? true})]
    (scf/register-statechart! a ::restore-then-leave restore-then-leave-chart)
    (scf/start! a {:machine ::restore-then-leave :session-id :kicker})
    (let [module-session (h/child-session-id a ::TwoRouteModule)]
      (scf/send! a :kicker :kick {})
      (assertions
        "(the module is stopped: the routing session forwarded the route to it, then routed to Landing)"
        (contains? (scf/current-configuration a scr/session-id) ::Landing) => true
        (scf/current-configuration a module-session) => #{}
        "ends the deferral when the stopped module's route event is dequeued: URL sync writes the current route on the bookmarked entry, without waiting for another route"
        (h/stack env) => ["/Landing"]))
    (scr/route-to! a `TwoRouteModule {})
    (scr/route-to! a `Landing {})
    (assertions
      "and URL sync goes on as usual after it"
      (last (h/stack env)) => "/Landing")))

(def ^:private slow-gate
  "The resolve fn of the promise the last `:slow` step awaits."
  (atom nil))

(defn- slow-transition
  "A transition on `:slow` whose script awaits `slow-gate` (e.g. a load)."
  []
  (transition {:event :slow}
    (script {:expr (fn [& _] (p/then (p/create (fn [resolve _] (reset! slow-gate resolve))) (fn [_] nil)))})))

(defsc S1 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :s1}})
(defsc S2 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :s2}})

(def slow-module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/slow-module-routes :routing/root `SlowModule}
        (scr/rstate {:route/target `S1} (slow-transition))
        (scr/rstate {:route/target `S2 :route/params #{:p}})))))

(defsc SlowModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::SlowModule])
   :initial-state  {}
   sfro/statechart slow-module-chart})

(def outer-module-chart
  "Starts on the `SlowModule` istate, a module inside this module."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/outer-module-routes :routing/root `OuterModule}
        (scr/istate {:route/target `SlowModule :route/reachable #{::S1 ::S2}})))))

(defsc OuterModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::OuterModule])
   :initial-state  {}
   sfro/statechart outer-module-chart})

(def slow-module-routing-chart
  "Starts on the `SlowModule` istate."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/slow-module-root-routes :routing/root `Root}
        (scr/istate {:route/target `SlowModule :route/reachable #{::S1 ::S2}})
        (scr/rstate {:route/target `Landing})))))

(def outer-module-routing-chart
  "Starts on the `OuterModule` istate (whose chart starts on `SlowModule`)."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/outer-module-root-routes :routing/root `Root}
        (scr/istate {:route/target `OuterModule :route/reachable #{::S1 ::S2}})
        (scr/rstate {:route/target `Landing})))))

(defsc OuterHome [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :outer-home}})

(def outer-with-home-chart
  "Starts on the `SlowModule` istate; `OuterHome` is its other route."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/outer-with-home-routes :routing/root `OuterWithHome}
        (scr/istate {:route/target `SlowModule :route/reachable #{::S1 ::S2}})
        (scr/rstate {:route/target `OuterHome})))))

(defsc OuterWithHome [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::OuterWithHome])
   :initial-state  {}
   sfro/statechart outer-with-home-chart})

(def outer-with-home-routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/outer-with-home-root-routes :routing/root `Root}
        (scr/istate {:route/target `OuterWithHome :route/reachable #{::S1 ::S2 ::OuterHome}})
        (scr/rstate {:route/target `Landing})))))

(defsc E1 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :e1}})
(defsc E2 [_ _] {:query [:page/id] :ident :page/id :initial-state {:page/id :e2}})

(def slow-entry-module-chart
  "Starts on `E1`. `E2`'s entry awaits `slow-gate` (e.g. a load), so the step that routes to it is pending until then."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/slow-entry-module-routes :routing/root `SlowEntryModule}
        (scr/rstate {:route/target `E1})
        (scr/rstate {:route/target `E2 :route/params #{:p}}
          (on-entry {}
            (script {:expr (fn [& _] (p/then (p/create (fn [resolve _] (reset! slow-gate resolve))) (fn [_] nil)))})))))))

(defsc SlowEntryModule [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::SlowEntryModule])
   :initial-state  {}
   sfro/statechart slow-entry-module-chart})

(def slow-entry-routing-chart
  "Starts on the `SlowEntryModule` istate."
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/slow-entry-root-routes :routing/root `Root}
        (scr/istate {:route/target `SlowEntryModule :route/reachable #{::E1 ::E2}})
        (scr/rstate {:route/target `Landing})))))

(defn- async-routed-app
  "Returns `{:app :provider}`: an `:async? true` `h/new-app` running `chart`, with URL sync installed (its restore
   deferred) on a simulated history whose current URL is `bookmark`."
  [chart bookmark]
  (let [a        (h/new-app Root {:async? true})
        provider (sim/simulated-url-history bookmark)]
    (scr/start! a chart)
    (scr/install-url-sync! a {:provider provider :defer-restore? true})
    {:app a :provider provider}))

(defn- restore-deferred? [a] (some? (get-in @(::app/runtime-atom a) [::scr/url-sync :restore-deferred scr/session-id])))

#?(:clj
   (specification "route-sync-from-url! ending a deferred restore forwarded to a module that is stopped while its step is pending"
     (doseq [[label chart segments] [["one module deep" slow-module-routing-chart ["SlowModule" "S2"]]
                                     ["two modules deep (both stopped)" outer-module-routing-chart ["OuterModule" "SlowModule" "S2"]]]]
       (component label
         (let [bookmark (url segments {::S2 {:p 3}})
               {a :app :as env} (async-routed-app chart bookmark)
               outer       (h/child-session-id a ::OuterModule)
               slow-module (if outer (h/child-session-id a outer ::SlowModule) (h/child-session-id a ::SlowModule))]
           ;; the module's step is pending (e.g. a load), so the forwarded route waits behind it
           (scf/send! a slow-module :slow {})
           (scr/route-sync-from-url! a)
           (let [before (h/stack env)]
             ;; the user routes elsewhere before the module's load returns, which stops it: the stop drops its hold,
             ;; so its queue delivers the forwarded route, to a session that isn't running
             (scr/route-to! a `Landing {})
             (assertions
               "(the module was stopped)"
               (scf/current-configuration a slow-module) => #{}
               "(nothing was written while the restore was deferred)"
               before => [bookmark]
               "ends the deferral when the stopped module's route event is dequeued: URL sync writes the current route on the bookmarked entry, without waiting for another route"
               (restore-deferred? a) => false
               (h/stack env) => ["/Landing"]))
           (@slow-gate true)
           (scr/route-to! a `SlowModule {})
           (scr/route-to! a `Landing {})
           (assertions
             "and URL sync goes on as usual after it"
             (last (h/stack env)) => "/Landing"))))
     (component "in a module that stays active (the forwarder)"
       (let [bookmark (url ["OuterWithHome" "SlowModule" "S2"] {::S2 {:p 3}})
             {a :app :as env} (async-routed-app outer-with-home-routing-chart bookmark)
             outer       (h/child-session-id a ::OuterWithHome)
             slow-module (h/child-session-id a outer ::SlowModule)]
         (scf/send! a slow-module :slow {})
         (scr/route-sync-from-url! a)
         (let [before (h/stack env)]
           ;; the outer module routes away from the slow one, which stops it
           (scr/route-to! a `OuterHome {})
           (assertions
             "(the inner module was stopped, the outer one routed)"
             (scf/current-configuration a slow-module) => #{}
             (contains? (scf/current-configuration a outer) ::OuterHome) => true
             "(nothing was written while the restore was deferred)"
             before => [bookmark]
             "ends the deferral when the stopped module's route event is dequeued, without waiting for the forwarder to save: URL sync writes the current route on the bookmarked entry"
             (restore-deferred? a) => false
             (h/stack env) => ["/OuterWithHome/OuterHome"]))
         (@slow-gate true)))
     (component "in a module that forwarded it and is stopped before it saves again"
       (let [bookmark (url ["OuterWithHome" "SlowModule" "S2"] {::S2 {:p 3}})
             {a :app :as env} (async-routed-app outer-with-home-routing-chart bookmark)
             outer       (h/child-session-id a ::OuterWithHome)
             slow-module (h/child-session-id a outer ::SlowModule)]
         (scf/send! a slow-module :slow {})
         (scr/route-sync-from-url! a)
         (scr/route-to! a `OuterHome {})
         (@slow-gate true)
         ;; the user leaves the outer module (the forwarder), which stops it
         (scr/route-to! a `Landing {})
         (assertions
           "(the forwarder was stopped)"
           (scf/current-configuration a outer) => #{}
           "ends the deferral: it doesn't depend on the forwarder, so URL sync writes routes again"
           (restore-deferred? a) => false
           (h/stack env) => ["/OuterWithHome/OuterHome" "/Landing"])))
     (component "in the module that took it, stopped while its route's entry is pending"
       (let [bookmark (url ["SlowEntryModule" "E2"] {::E2 {:p 3}})
             {a :app :as env} (async-routed-app slow-entry-routing-chart bookmark)
             module (h/child-session-id a ::SlowEntryModule)]
         ;; forwarded to the module, which takes it: E2's entry awaits (e.g. a load)
         (scr/route-sync-from-url! a)
         ;; the user routes elsewhere before it settles, which stops the module (its step's save is skipped)
         (scr/route-to! a `Landing {})
         (let [before (h/stack env)]
           (@slow-gate true)
           (assertions
             "(the module was stopped)"
             (scf/current-configuration a module) => #{}
             "(nothing was written while the restore was deferred)"
             before => [bookmark]
             "ends the deferral when the step that took the route event ends unsaved: URL sync writes the current route on the bookmarked entry"
             (restore-deferred? a) => false
             (h/stack env) => ["/Landing"]))))))

#?(:clj
   (specification "route-sync-from-url! ending a deferred initial restore from a step, with the event loop"
     (let [bookmark (url ["Target"] {::Target {:t 7}})
           a        (h/new-app Root {:event-loop? true})
           provider (sim/simulated-url-history bookmark)
           env      {:app a :provider provider}]
       (try
         (scr/start! a auth-routing-chart)
         (loop [n 0] (when (and (< n 200) (empty? (scf/current-configuration a scr/session-id))) (Thread/sleep 10) (recur (inc n))))
         (scr/install-url-sync! a {:provider provider :defer-restore? true})
         (scf/send! a scr/session-id :event/logged-in {})
         (loop [n 0]
           (when (and (< n 200) (not (contains? (scf/current-configuration a scr/session-id) ::Target)))
             (Thread/sleep 10) (recur (inc n))))
         (Thread/sleep 100)
         (assertions
           "restores the URL on the bookmarked entry"
           (contains? (scf/current-configuration a scr/session-id) ::Target) => true
           (h/stack env) => [bookmark])
         (finally
           (some-> (:events-running-atom (scf/statechart-env a)) (reset! false)))))))

;; ===== :prefix =====

(specification "install-url-sync! with :prefix"
  (let [bookmark (str "/app" (url ["Target"] {::Target {:t 7}}))
        {a :app :as env} (h/routed-app Root routing-chart bookmark {:prefix "/app"})]
    (assertions
      "restores a URL under the prefix"
      (contains? (scf/current-configuration a scr/session-id) ::Target) => true
      (routing-params a ::Target) => {:t 7}
      "and keeps it as the restored entry"
      (h/stack env) => [bookmark])
    (scr/route-to! a `Landing {})
    (assertions
      "writes the URLs of later routes under it"
      (h/current-url env) => "/app/Landing"))
  (let [{a :app :as env} (h/routed-app Root routing-chart "/" {:prefix    "/app"
                                                               :url-codec (ruct/transit-base64-codec "/other")})]
    (scr/route-to! a `Landing {})
    (assertions
      "is ignored when a :url-codec is given too"
      (h/current-url env) => "/other/Landing")))

#?(:clj
   (specification "Back/forward while the initial restore is deferred"
     (let [bookmark (url ["Target"] {::Target {:t 7}})
           {a :app :keys [provider] :as env} (h/routed-app Root routing-chart bookmark {:defer-restore? true})]
       (ruh/-push-url! provider "/Dash/PA")
       (ruh/go-back! provider)
       (assertions
         "is ignored: nothing routes until the restore"
         (contains? (scf/current-configuration a scr/session-id) ::Landing) => true
         (h/current-url env) => bookmark))))

