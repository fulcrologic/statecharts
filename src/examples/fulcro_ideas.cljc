(ns fulcro-ideas
  (:require
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.state-machine :as sm :refer [machine]]
    [com.fulcrologic.statecharts.elements :refer [state transition data-model invoke final
                                                  parallel]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]))

(deftype FulcroDataModel [Component]
  sp/DataModel
  ...
  )

(deftype InvocationSystem []
  ;; Talk between state machines and other similar processes
  )

(deftype SessionManager []
  ;; A thing that can find instances of state machines
  )

(defn report-table
  [app Component]
  (let [fulcro-props (hooks/use-component app Component {})]
    ((comp/factory Component) fulcro-props)))

(def machine
  (machine {:name :form}
    (transition {:event  :leave-report
                 :target :finished})
    (state {:id :dirty}
      (entry-fn [env data]
        (env/assign! env [:Router :block?] true))
      (exit-fn [env data]
        (env/assign! env [:Router :block?] false)))
    (state {:id :clean})))

(def machine
  (machine {:name :report}
    (transition {:event  :leave-report
                 :target :finished})
    (state {:id :gathering})
    (state {:id :loading}
      (invoke {:params   (fn [env data]                     ;; issue load
                           )
               :type     :fulcro/load
               :finalize (fn [env data]
                           ;; when load finishes (sends back ANY event) post-process
                           )}))
    (state {:id :munging})
    (final {:id :finished})

    ))

(def rad-system
  (machine {:id :RoutingSystem}
    (data-model {:expr {:routing-blocked?  true
                        :actor/login-route LoginForm}})
    (state {:id :Router}
      (state {:CheckingSession})
      (state {:ResumingRoute})
      (state {:WaitingForTarget})
      (state {:RunningMutation}
        (transition {:event :fulcro.mutation/done :target :Y})
        (transition {:event :fulcro.mutation/failed :target :X})
        (invoke {:type          :fulcro/chart
                 :auto-forward? true
                 :expr          '[(login! {})]
                 })
        ))))

(deftype DatomicDataModel [])

(defsc S [_ _]
  {
   :fulcro/statechart (machine ...)
   }
  )
