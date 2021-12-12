(ns com.fulcrologic.statecharts.model.simple-model
  "An implementation of the statecharts models (data, event, and execution). The data model is a simple
   global map, the event queue is a (local, unshared) in-memory queue, and the execution model assumes your code will
   be CLJC functions of the form `(fn [env current-data])` that can optionally return a map that is
   marked to replace the data in the session (via `replacement-data` from this ns)."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.state-machine :as sm]
    [com.fulcrologic.statecharts.model.environment :as env]
    [com.fulcrologic.statecharts.elements :as e]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))

(defn replacement-data
  "Use this to wrap the return value of an expression in order to replace the data model's value with
   `v`."
  [v]
  (vary-meta v assoc ::replace? true))

(defn replacement-data? [v] (boolean (and (map? v) (::replace? (meta v)))))

(deftype SimpleDataModel [wmem-atom data-atom system-atom Q delayed-events cancelled-events]
  sp/DataModel
  (load-data [_ _] (log/error "src not supported by simple model."))
  (current-data [_ _] (assoc @data-atom :_x (assoc @system-atom :_ioprocessors [])))
  (set-system-variable! [_ _ k v]
    (if (nil? v)
      (swap! system-atom dissoc k)
      (swap! system-atom assoc k v)))
  (get-at [_ _ path]
    (or
      (get-in @data-atom path)
      (get-in @system-atom path)))
  (put-at! [_ _ path v]
    (swap! data-atom assoc-in path v))
  (replace-data! [_ _ new-data]
    (reset! data-atom new-data))
  sp/EventQueue
  (send! [_ _ {:keys [event delay]}]
    (async/go
      (if delay
        (let [nm (evts/event-name event)]
          (swap! delayed-events update nm (fnil inc 0))
          (async/<! (async/timeout delay))
          (when-not (contains? @cancelled-events nm)
            (async/>! Q event))
          (when (zero? (swap! delayed-events update nm dec))
            (swap! cancelled-events disj nm)))
        (async/>! Q event))))
  (cancel! [_ _ {:keys [event]}]
    (let [nm (evts/event-name event)]
      (when (pos? (get @delayed-events nm))
        (swap! @cancelled-events conj nm))))
  (process-next-event! [_ env handler]
    (async/go
      (let [event (async/<! Q)]
        (log/debug "Processing event: " event)
        (handler env event)
        (log/debug "New configuration: " (::sc/configuration @wmem-atom))
        :ok)))
  sp/ExecutionModel
  (run-expression! [this env expr]
    (when (fn? expr)
      (let [data    (sp/current-data this env)
            result  (try
                      (expr env data)
                      (catch #?(:clj Throwable :cljs :default) e
                        (env/send-error-event! env :error.execution e (select-keys env #{:context-element-id
                                                                                         :session-id
                                                                                         :working-memory}))
                        nil))
            update? (replacement-data? result)]
        (when update?
          (sp/replace-data! this env result))
        result))))

(defn new-simple-model
  "Creates a new simple model that can act as a data model, event queue, AND execution model with all-local resources.
   This model MUST NOT be shared with more than one machine instance (may change in the future to enable
   local comms between machines).

   See `run-event-loop!` for a pre-implemented way to run the machine on this model.
   "
  [wmem-atom]
  (->SimpleDataModel wmem-atom (atom {}) (atom {}) (async/chan 1000) (atom {}) (atom #{})))

(defn- fill-system-variables! [data-model env session-id machine-name event]
  (sp/set-system-variable! data-model env :_event event)
  (sp/set-system-variable! data-model env :_sessionid session-id)
  (sp/set-system-variable! data-model env :_name machine-name))

(defn- clear-system-variables! [data-model env]
  (sp/set-system-variable! data-model env :_event nil)
  (sp/set-system-variable! data-model env :_sessionid nil)
  (sp/set-system-variable! data-model env :_name nil))

(defn run-event-loop!
  "Creates a simple model for `machine` and starts an async loop (returns immediately) that runs the `machine`
   using the `wmem-atom` you supply.

   You can look at the working memory via that atom to see if the machine is still running, etc."
  [machine wmem-atom]
  (let [model (new-simple-model wmem-atom)]
    (reset! wmem-atom (sm/initialize machine))
    (async/go-loop []
      (let [env (env/new-env machine @wmem-atom nil model model model)
            _   (async/<!
                  (sp/process-next-event! model env
                    (fn [_ event]
                      (try
                        (fill-system-variables! model env (sm/session-id @wmem-atom) (:name machine) event)
                        (swap! wmem-atom (fn [wmem] (sm/process-event machine wmem event)))
                        (catch #?(:clj Throwable :cljs :default) e
                          (env/send-error-event! env :error.execution e {:source-event   event
                                                                         :working-memory @wmem-atom}))
                        (finally
                          (clear-system-variables! model env))))))])
      (when (::sc/running? @wmem-atom)
        (recur)))
    model))

(comment
  (def test
    (sm/machine {}
      (e/state {:id :A}
        (e/transition {:event  :trigger
                       :target :B}))
      (e/state {:id :B}
        (e/transition {:event  :trigger
                       :target :A}))))

  (def wmem-atom (atom {}))
  (def _model (run-event-loop! test wmem-atom))
  (defn make-env [] (env/new-env test @wmem-atom nil _model _model _model))


  (sp/send! _model (make-env) {:event (evts/new-event :trigger)})

  )
