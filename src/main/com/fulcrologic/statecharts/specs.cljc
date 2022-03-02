(ns com.fulcrologic.statecharts.specs
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]))

(s/def ::sc/document-order #{:breadth-first :depth-first})
(s/def ::sc/node-type keyword?)
(s/def ::sc/id (s/or :u uuid? :n number? :k keyword? :s string?))
(s/def ::sc/session-id ::sc/id)
(s/def ::sc/parent-session-id ::sc/id)
(s/def ::sc/children (s/every (s/or :i ::sc/id :e ::sc/element)))
(s/def ::sc/element (s/keys
                      :req-un [::sc/node-type ::sc/id]
                      :opt-un [::sc/children]))

(s/def ::sc/transition-element (s/and ::sc/element #(= :transition (:node-type %))))
(s/def ::sc/history-element (s/and ::sc/element #(= :history (:node-type %))))
(s/def ::sc/on-exit-element (s/and ::sc/element #(= :on-exit (:node-type %))))
(s/def ::sc/on-entry-element (s/and ::sc/element #(= :on-entry (:node-type %))))
(s/def ::sc/initial-element (s/and ::sc/element #(:initial? %)))
(s/def ::sc/id-ordinals (s/map-of ::sc/id nat-int?))
(s/def ::sc/state-element (s/and ::sc/element #(boolean (#{:state :parallel :final} (:node-type %)))))
(s/def ::sc/elements-by-id (s/map-of keyword? ::sc/element))
(s/def ::sc/element-or-id (s/or :element ::sc/element :id ::sc/id))
(s/def ::sc/running? boolean?)
(s/def ::sc/configuration (s/every ::sc/id :kind set?))
(s/def ::sc/initialized-states (s/every ::sc/id :kind set?))
(s/def ::sc/enabled-transitions (s/every ::sc/id :kind set?))
(s/def ::sc/history-value (s/map-of ::sc/id ::sc/configuration))
(s/def ::sc/statechart-src (s/or :k qualified-keyword? :sym qualified-symbol?))
(s/def ::sc/working-memory (s/keys :req [::sc/session-id
                                         ::sc/statechart-src
                                         ::sc/configuration
                                         ::sc/initialized-states
                                         ::sc/history-value
                                         ::sc/running?]
                             :opt [:org.w3.scxml.event/invokeid
                                   ::sc/parent-session-id]))

(s/def ::sc/statechart (s/and
                         (s/keys
                           :opt [::sc/id-ordinals
                                       ::sc/ids-in-document-order
                                       ::sc/elements-by-id]
                           :opt-un [::sc/id
                                    ::sc/children])))

(s/def ::sc/event-name keyword?)
(s/def :org.w3.scxml.event/name ::sc/event-name)
(s/def :org.w3.scxml.event/data map?)
(s/def :org.w3.scxml.event/type (s/or :s string? :k keyword?))
(s/def :org.w3.scxml.event/sendid ::sc/id)
(s/def :org.w3.scxml.event/origin vector?)
(s/def :org.w3.scxml.event/origintype keyword?)
(s/def :org.w3.scxml.event/invokeid ::sc/id)

(s/def ::sc/event (s/keys
                    :req-un [:org.w3.scxml.event/name
                             :org.w3.scxml.event/data
                             :org.w3.scxml.event/type]
                    :opt-un [:org.w3.scxml.event/sendid
                             :org.w3.scxml.event/origin
                             :org.w3.scxml.event/origintype
                             :org.w3.scxml.event/invokeid]))
(s/def ::sc/event-or-name (s/or
                            :n ::sc/event-name
                            :e ::sc/event))

(s/def ::sc/context-element-id ::sc/id)
(s/def ::sc/processor #(satisfies? sp/Processor %))
(s/def ::sc/data-model #(satisfies? sp/DataModel %))
(s/def ::sc/event-queue #(satisfies? sp/EventQueue %))
(s/def ::sc/execution-model #(satisfies? sp/ExecutionModel %))
(s/def ::sc/invocation-processor #(satisfies? sp/InvocationProcessor %))
(s/def ::sc/statechart-registry #(satisfies? sp/StatechartRegistry %))
(s/def ::sc/working-memory-store #(satisfies? sp/WorkingMemoryStore %))
(s/def ::sc/invocation-processors (s/coll-of ::sc/invocation-processor :kind vector?))
(s/def ::sc/vwmem volatile?)

(s/def ::sc/env (s/keys :req [::sc/data-model
                              ::sc/event-queue
                              ::sc/statechart-registry      ; how to look up machine definitions
                              ::sc/execution-model]
                  ;; These are only needed if you are supporting self-running systems with a self-running event queue
                  ;; or invocations.
                  :opt [::sc/working-memory-store           ; session-id -> persistence of wmem
                        ::sc/processor                      ; The statechart algorithms
                        ::sc/invocation-processors]))       ; Invocation support

(s/def ::sc/processing-env (s/merge
                             ::sc/env
                             (s/keys :req [::sc/vwmem
                                           ::sc/statechart
                                           ::sc/context-element-id])))
