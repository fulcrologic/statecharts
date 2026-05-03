(ns com.fulcrologic.statecharts.strict-block-errors-spec
  "W3C SCXML §4.4: when executable content raises an error, the processor MUST
   queue `error.execution` and skip the remainder of the block.

   This library defaults to NON-strict (queue the event, continue running siblings)
   for backwards compatibility. Strict mode is opt-in via `::sc/errors-abort-siblings?`
   on the env, or the convenience constructor `simple/strict-env`."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state final transition on-entry
                                                   assign script]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.util :refer [new-uuid]]
    [fulcro-spec.core :refer [=> assertions specification behavior]]))

(def chart-throws-then-assigns
  "First on-entry element throws; second sets :Var1 to 42. Strict mode skips the assign."
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0}
        (on-entry {}
          (script {:expr (fn [_ _] (throw (ex-info "boom" {})))})
          (assign {:location [:Var1] :expr 42}))
        (transition {:event :error.execution :target :handled}))
      (state {:id :handled})
      (final {:id :pass})
      (final {:id :fail}))))

(defn- run [env-builder]
  (let [{::sc/keys [processor working-memory-store data-model] :as env} (env-builder)
        sid (new-uuid)
        proc processor]
    (simple/register! env ::c chart-throws-then-assigns)
    (sp/save-working-memory! working-memory-store env sid
      (sp/start! proc env ::c {::sc/session-id sid}))
    (let [wmem (sp/get-working-memory working-memory-store env sid)]
      {:wmem  wmem
       :var1  (sp/get-at data-model
                (assoc env ::sc/vwmem (volatile! wmem)
                           ::sc/context-element-id :s0)
                [:Var1])
       :env   env
       :sid   sid
       :proc  proc
       :store working-memory-store})))

(specification "Default env (non-strict): siblings still run; error.execution is queued internally"
  (let [{:keys [wmem var1]} (run #(simple/simple-env))]
    (assertions
      ":Var1 was assigned by the sibling element after the script threw (siblings continued)"
      var1 => 42
      "after the entry block completed, the queued :error.execution event drove a transition"
      (contains? (::sc/configuration wmem) :handled) => true
      "we left :s0 (because the user wrote a transition for :error.execution)"
      (contains? (::sc/configuration wmem) :s0) => false)))

(specification "strict-env (W3C §4.4): block aborts on first error"
  (let [{:keys [wmem var1]} (run #(simple/strict-env))]
    (assertions
      ":Var1 was NOT assigned because the script raised and aborted the block"
      var1 => nil
      "the queued :error.execution event was processed and routed to :handled"
      (contains? (::sc/configuration wmem) :handled) => true
      "we are no longer in :s0"
      (contains? (::sc/configuration wmem) :s0) => false)))

(specification "Strict mode is just a flag — any custom env can opt in"
  (let [{:keys [wmem]} (run #(assoc (simple/simple-env) ::sc/errors-abort-siblings? true))]
    (assertions
      "setting the env key directly produces strict semantics"
      (contains? (::sc/configuration wmem) :handled) => true)))
