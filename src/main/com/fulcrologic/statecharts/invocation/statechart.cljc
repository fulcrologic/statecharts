(ns com.fulcrologic.statecharts.invocation.statechart
  "Support for invoking other statecharts. This support can be added by adding it to the
   env's `::sc/invocation-processors`. An invoke element can specify it wants to
   run another statechart by specifying the type as :statechart, ::sc/chart, or the official w3 URL for scxml.

   The `src` attribute of the `invoke` element must be the name of a machine that is in the
   statechart registry (in env as ::sc/statechart-registry).
   "
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defrecord StatechartInvocationProcessor [active-invocations]
  sp/InvocationProcessor
  (supports-invocation-type? [this typ]
    (or
      (and (string? typ) (str/starts-with? (str/lower-case typ) "http://www.w3.org/tr/scxml"))
      (= typ :statechart)
      (= typ ::sc/chart)))
  (start-invocation! [this {::sc/keys [event-queue processor statechart-registry working-memory-store]
                            :as       env} {:keys [invokeid src params]}]
    (log/debug "Start invocation" invokeid src params)
    (let [source-session-id (env/session-id env)
          child-session-id  invokeid
          statechart        (sp/get-statechart statechart-registry src)]
      (if-not statechart
        (do
          (log/error "Invocation failed. No statechart found for src:" src)
          (sp/send! event-queue env {:target            source-session-id
                                     :sendid            child-session-id
                                     :source-session-id child-session-id
                                     :event             :error.platform
                                     :data              {:message "Could not invoke child chart. Not registered."
                                                         :target  src}}))
        (let [wmem (sp/start! processor env src {::sc/invocation-data         params
                                                 ::sc/session-id              child-session-id
                                                 ::sc/parent-session-id       source-session-id
                                                 :org.w3.scxml.event/invokeid invokeid})]
          (sp/save-working-memory! working-memory-store env child-session-id wmem)))
      true))
  (stop-invocation! [this {::sc/keys [event-queue working-memory-store] :as env} {:keys [invokeid] :as data}]
    (log/debug "Stop invocation" invokeid)
    (let [source-session-id (env/session-id env)
          child-session-id  invokeid]
      (sp/delete-working-memory! working-memory-store env child-session-id)
      true))
  (forward-event! [this {::sc/keys [event-queue] :as env} {:keys [type invokeid event]}]
    (log/debug "Forward event " invokeid event)
    (let [source-session-id (env/session-id env)
          child-session-id  invokeid]
      (when event-queue
        (log/debug "sending event on event queue to" child-session-id)
        (sp/send! event-queue env {:target            child-session-id
                                   :type              (or type ::sc/chart)
                                   :sendid            child-session-id
                                   :source-session-id source-session-id
                                   :data              (or (:data event) {})
                                   :event             (:name event)}))
      true)))

(defn new-invocation-processor
  "Create an invocation processor that can be used with a statechart to start other statecharts."
  []
  (->StatechartInvocationProcessor (atom {})))
