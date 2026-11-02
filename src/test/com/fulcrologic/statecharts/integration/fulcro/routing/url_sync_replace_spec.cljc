(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-sync-replace-spec
  "`replace-route-params!` and url-sync's record of the URL it last wrote: a save that doesn't change the route
   leaves the history alone after a replace."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [com.fulcrologic.statecharts.integration.fulcro.routing-options :as sfro]
    [com.fulcrologic.statecharts.integration.fulcro.routing.headless-app :as h]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit :as ruct]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defsc Root [_ _]
  {:query         [:ui/current-route]
   :ident         (fn [] [:component/id ::Root])
   :initial-state {:ui/current-route {}}})

(defsc Landing [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :landing}})

(defsc Other [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :other}})

(defsc Inner [_ _]
  {:query         [:page/id]
   :ident         :page/id
   :initial-state {:page/id :inner}})

(def module-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/module-routes :routing/root `Module}
        (scr/rstate {:route/target `Inner :route/params #{:i}})))))

(defsc Module [_ _]
  {:query          [:ui/current-route]
   :ident          (fn [] [:component/id ::Module])
   :initial-state  {}
   sfro/statechart module-chart})

(def routing-chart
  (chart/statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :region/routes :routing/root `Root}
        (scr/rstate {:route/target `Landing :route/params #{:x}})
        (scr/rstate {:route/target `Other})
        (scr/istate {:route/target `Module :route/params #{:m}})))))

(specification "A save after replace-route-params!"
  (component "of the routing session"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Other {})
      (scr/route-to! a `Landing {})
      (scr/replace-route-params! a `Landing {:x 2})
      (let [replaced (h/stack env)]
        (scf/send! a scr/session-id :event/unhandled {})
        (assertions
          "(the replace rewrote the current entry)"
          (count replaced) => 2
          "pushes nothing: the history is the one the replace left"
          (h/stack env) => replaced
          "and the cursor stays on the replaced entry"
          (h/cursor env) => 1))))
  (component "of a session the routing session invoked"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Landing {})
      (scr/route-to! a `Module {})
      (scr/replace-route-params! a `Module {:m 1})
      (let [replaced (h/stack env)]
        (scf/send! a (h/child-session-id a ::Module) :event/unhandled {})
        (assertions
          "pushes nothing either"
          (h/stack env) => replaced))))
  (component "followed by a route change"
    (let [{a :app :as env} (h/routed-app Root routing-chart)]
      (scr/route-to! a `Landing {})
      (scr/replace-route-params! a `Landing {:x 2})
      (let [replaced (h/current-url env)]
        (scr/route-to! a `Other {})
        (assertions
          "still pushes the new route"
          (h/stack env) => [replaced "/Other"])))))

(specification "replace-route-params! of a route in a module (an istate's child chart)"
  (let [{a :app :as env} (h/routed-app Root routing-chart)]
    (scr/route-to! a `Landing {})
    (scr/route-to! a `Inner {})
    (let [entries (count (h/stack env))
          module  (h/child-session-id a ::Module)]
      (scr/replace-route-params! a `Inner {:i 9})
      (assertions
        "writes the params in the module's session, which has the route"
        (get-in (h/local-data a module) [:routing/parameters ::Inner]) => {:i 9}
        "not in the routing session"
        (get-in (h/local-data a) [:routing/parameters ::Inner]) => nil
        "and replaces the current URL with one that has them"
        (get (:params (ruc/decode-url (ruct/transit-base64-codec) (h/current-url env) {})) ::Inner) => {:i 9}
        (count (h/stack env)) => entries)
      (scf/send! a module :event/unhandled {})
      (assertions
        "so the module's next save pushes nothing"
        (count (h/stack env)) => entries)
      (scr/replace-route-params! a `Module {:m 3})
      (assertions
        "(the module's own route params are still the routing session's)"
        (get-in (h/local-data a) [:routing/parameters ::Module]) => {:m 3})
      (scr/replace-route-params! a `Other {:o 1})
      (assertions
        "(a route that isn't active: in the routing session, as before)"
        (get-in (h/local-data a) [:routing/parameters ::Other]) => {:o 1}))))
