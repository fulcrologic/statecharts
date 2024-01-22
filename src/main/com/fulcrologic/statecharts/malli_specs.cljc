(ns com.fulcrologic.statecharts.malli-specs
  (:require
    [malli.core :as m]
    [com.fulcrologic.guardrails.malli.core :refer [>def]]
    [com.fulcrologic.guardrails.malli.registry :as gr.reg]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]))

(>def ::sc/document-order [:enum :breadth-first :depth-first])
(>def ::sc/node-type keyword?)
(>def ::sc/id [:or uuid? number? keyword? string?])
(>def ::sc/ids [:every ::sc/id])
(>def ::sc/session-id ::sc/id)
(>def ::sc/parent-session-id ::sc/id)
(>def ::sc/children [:sequential [:or ::sc/id [:ref ::sc/element]]])
(>def ::sc/element [:map
                    [:node-type ::sc/node-type]
                    [:children {:optional true} [:ref ::sc/children]]])
(>def ::sc/transition-element [:and ::sc/element [:fn #(= :transition (:node-type %))]])
(>def ::sc/history-element [:and ::sc/element [:fn #(= :history (:node-type %))]])
(>def ::sc/on-exit-element [:and ::sc/element [:fn #(= :on-exit (:node-type %))]])
(>def ::sc/on-entry-element [:and ::sc/element [:fn #(= :on-entry (:node-type %))]])
(>def ::sc/initial-element [:and ::sc/element [:fn #(:initial? %)]])
(>def ::sc/id-ordinals [:map-of ::sc/id nat-int?])
(>def ::sc/state-element [:and ::sc/element [:fn #(boolean (#{:state :parallel :final} (:node-type %)))]])
(>def ::sc/elements-by-id [:map-of keyword? ::sc/element])
(>def ::sc/element-or-id [:or ::sc/element ::sc/id])
(>def ::sc/running? boolean?)
(>def ::sc/configuration [:set ::sc/id])
(>def ::sc/initialized-states [:set ::sc/id])
(>def ::sc/enabled-transitions [:set ::sc/id])
(>def ::sc/history-value [:map-of ::sc/id ::sc/configuration])
(>def ::sc/statechart-src [:or qualified-keyword? qualified-symbol?])
(>def ::sc/working-memory [:map
                           ::sc/session-id
                           ::sc/statechart-src
                           ::sc/configuration
                           ::sc/initialized-states
                           ::sc/history-value
                           ::sc/running?
                           [:org.w3.scxml.event/invokeid {:optional true}]
                           [::sc/parent-session-id {:optional true}]])

(>def ::sc/ids-in-document-order [:vector ::sc/id])
(>def ::sc/statechart [:map
                       [::sc/id-ordinals {:optional true}]
                       [::sc/ids-in-document-order {:optional true}]
                       [::sc/elements-by-id {:optional true}]
                       [:id {:optional true} ::sc/id]
                       [:children {:optional true} ::sc/children]])

(>def ::sc/event-name keyword?)
(>def :org.w3.scxml.event/name ::sc/event-name)
(>def :org.w3.scxml.event/data map?)
(>def :org.w3.scxml.event/type [:or string? keyword?])
(>def :org.w3.scxml.event/sendid ::sc/id)
(>def :org.w3.scxml.event/origin vector?)
(>def :org.w3.scxml.event/origintype keyword?)
(>def :org.w3.scxml.event/invokeid ::sc/id)

(>def ::sc/event [:map {:closed false}
                  [:name :org.w3.scxml.event/name]
                  [:data :org.w3.scxml.event/data]
                  [:type :org.w3.scxml.event/type]
                  [:sendid {:optional true} :org.w3.scxml.event/sendid]
                  [:origin {:optional true} :org.w3.scxml.event/origin]
                  [:origintype {:optional true} :org.w3.scxml.event/origintype]
                  [:invokeid {:optional true} :org.w3.scxml.event/invokeid]])

(>def ::sc/event-or-name [:or ::sc/event-name ::sc/event])
(>def ::sc/context-element-id ::sc/id)
(>def ::sc/processor [:fn #(satisfies? sp/Processor %)])
(>def ::sc/data-model [:fn #(satisfies? sp/DataModel %)])
(>def ::sc/event-queue [:fn #(satisfies? sp/EventQueue %)])
(>def ::sc/execution-model [:fn #(satisfies? sp/ExecutionModel %)])
(>def ::sc/invocation-processor [:fn #(satisfies? sp/InvocationProcessor %)])
(>def ::sc/statechart-registry [:fn #(satisfies? sp/StatechartRegistry %)])
(>def ::sc/working-memory-store [:fn #(satisfies? sp/WorkingMemoryStore %)])
(>def ::sc/invocation-processors [:vector ::sc/invocation-processor])
(>def ::sc/vwmem [:fn volatile?])

(>def ::sc/env [:map {:closed false}
                ::sc/data-model
                ::sc/event-queue
                ::sc/statechart-registry                    ; how to look up machine definitions
                ::sc/execution-model
                ;; These are only needed if you are supporting self-running systems with a self-running event queue
                ;; or invocations.
                [::sc/working-memory-store {:optional true}] ; session-id -> persistence of wmem
                [::sc/processor {:optional true}]           ; The statechart algorithms
                [::sc/invocation-processors {:optional true}]]) ; Invocation support

(>def ::sc/processing-env [:and
                           ::sc/env
                           [:map
                            ::sc/vwmem
                            ::sc/statechart
                            ::sc/context-element-id]])
