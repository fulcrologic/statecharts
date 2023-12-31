(ns com.fulcrologic.statecharts.integration.fulcro.hooks
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [taoensso.timbre :as log]))

(defn use-statechart
  "Start a statechart that is co-located on a component under the :statechart key of its options.

   The component itself will be auto-assigned to the Actor :actor/component.

   Returns a map with:

   * :send! - A (fn ([event]) ([event data]) that can be used to send the chart events. The events that the statechart
     receives will include (in :data) the value of `this` under the `:this` key.
   * :config - The current statechart config (active states)
   * :local-data - The local data of the statechart instance
   * :aliases - Any fulcro state that has fulcro aliases on the actors

   When the containing component leaves the screen, it will send an `:event/unmount`
   to the chart. You can use this to move to a final state (which will GC the chart). If you
   want to keep the chart running, then you should send `session-id`, or a new chart will be
   created with a new ID (and the old one will just stay running with no associated UI)."
  [this {:keys [session-id data]
         :as   start-args}]
  (let [data        (assoc-in data [:fulcro/actors :actor/component] (scf/actor (comp/react-type this) (comp/get-ident this)))
        send-ref    (hooks/use-ref nil)
        id          (hooks/use-generated-id)
        session-id  (or session-id id)
        state-map   (rapp/current-state this)
        app         (comp/any->app this)
        machine-key (comp/class->registry-key (comp/react-type this))
        _           (hooks/use-component app (rc/nc [:session/id
                                                     (scf/local-data-path session-id)
                                                     (scf/statechart-session-ident session-id)]
                                               {:initial-state (fn [_] {})
                                                :ident         (fn [_] [:statechart/placeholder session-id])})
                      {:initialize? true})]
    (hooks/use-gc this [:statechart/placeholder session-id] #{})
    (when (nil? (.-current send-ref))
      (scf/register-statechart! app machine-key (comp/component-options this :statechart))
      (set! (.-current send-ref)
        (fn send*
          ([event data]
           (scf/send! this session-id event (assoc data :this this)))
          ([event]
           (scf/send! this session-id event {:this this})))))
    (hooks/use-lifecycle
      (fn []
        (let [running? (seq (scf/current-configuration this session-id))]
          (if running?
            (log/debug "Statechart with session id" session-id "was already running.")
            (scf/start! (comp/any->app this) (assoc start-args :session-id session-id :data data :machine machine-key)))))
      (fn [] (scf/send! this session-id :event/unmount)))
    {:send!      (.-current send-ref)
     :local-data (get-in state-map (scf/local-data-path session-id))
     :aliases    (scf/resolve-aliases {:_event           {:target session-id}
                                       :fulcro/state-map state-map})
     :config     (scf/current-configuration this session-id)}))
