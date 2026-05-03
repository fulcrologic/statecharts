(ns com.fulcrologic.statecharts.send-target-spec
  "Regression tests for special `<send>` target prefixes per W3C SCXML §C.1.1,
   adapted to this library's keyword conventions:

     :_internal  ↔ \"#_internal\"
     :_parent    ↔ \"#_parent\"
     :foo        — direct session-id keyword (covers #_<invokeid> / #_scxml_<sid>)"
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry Send]]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [fulcro-spec.core :refer [=> assertions specification behavior]]))

(defn- run! [chart]
  (let [{::sc/keys [processor working-memory-store] :as e} (simple/simple-env)
        sid (new-uuid)]
    (simple/register! e ::chart chart)
    (sp/save-working-memory! working-memory-store e sid
      (sp/start! processor e ::chart {::sc/session-id sid}))
    (sp/get-working-memory working-memory-store e sid)))

(specification "Special `<send>` target prefixes resolve correctly"
  (behavior ":_internal keyword routes to the internal queue (no event-queue round trip)"
    (let [c (chart/statechart {:initial :_root}
              (state {:id :_root :initial :s0}
                (state {:id :s0}
                  (on-entry {} (Send {:event :foo :target :_internal}))
                  (transition {:event :foo :target :pass}))
                (final {:id :pass})))
          w (run! c)]
      (assertions
        (contains? (::sc/configuration w) :pass) => true)))

  (behavior "\"#_internal\" string form is also accepted (legacy)"
    (let [c (chart/statechart {:initial :_root}
              (state {:id :_root :initial :s0}
                (state {:id :s0}
                  (on-entry {} (Send {:event :foo :target "#_internal"}))
                  (transition {:event :foo :target :pass}))
                (final {:id :pass})))
          w (run! c)]
      (assertions
        (contains? (::sc/configuration w) :pass) => true))))

(specification "resolve-send-target — pure resolver"
  (let [env-with-parent {::sc/vwmem (volatile! {::sc/parent-session-id :the-parent})}
        env-without     {::sc/vwmem (volatile! {})}]
    (assertions
      "nil → literal nil"
      (env/resolve-send-target env-without nil) => [:literal nil]

      ":_internal keyword resolves to :internal sentinel"
      (env/resolve-send-target env-without :_internal) => :internal
      "\"#_internal\" string also resolves to :internal sentinel"
      (env/resolve-send-target env-without "#_internal") => :internal

      ":_parent resolves to the parent session id"
      (env/resolve-send-target env-with-parent :_parent) => [:session :the-parent]
      "\"#_parent\" string also resolves to the parent session id"
      (env/resolve-send-target env-with-parent "#_parent") => [:session :the-parent]

      "\"#_scxml_my-sid\" strips the prefix and returns a session keyword"
      (env/resolve-send-target env-without "#_scxml_my-sid") => [:session :my-sid]

      "\"#_my-invokeid\" strips the prefix and returns a keyword"
      (env/resolve-send-target env-without "#_my-invokeid") => [:session :my-invokeid]

      "any other keyword passes through as a literal session-id"
      (env/resolve-send-target env-without :other) => [:literal :other])))
