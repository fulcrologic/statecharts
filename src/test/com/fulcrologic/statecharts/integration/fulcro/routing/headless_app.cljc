(ns com.fulcrologic.statecharts.integration.fulcro.routing.headless-app
  "Test support: a headless Fulcro app wired the way applications wire routing. Transactions are synchronous,
   statechart events are processed during `send!` (`:event-loop? :immediate`), and every session save runs
   `url-sync-on-save`, so URL sync sees each save as it would in a browser."
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as sim]))

(defn new-app
  "Returns a headless app with `Root` mounted and statecharts installed (`:event-loop? :immediate`, synchronous
   transactions, `url-sync-on-save` on every save). `options` are merged into the `install-fulcro-statecharts!`
   options."
  ([Root] (new-app Root {}))
  ([Root options]
   (let [a (stx/with-synchronous-transactions (app/fulcro-app {}))]
     (app/set-root! a Root {:initialize-state? true})
     (scf/install-fulcro-statecharts! a (merge {:event-loop? :immediate
                                                :on-save     (fn [session-id wmem] (scr/url-sync-on-save session-id wmem a))}
                                          options))
     a)))

(defn routed-app
  "Returns `{:app :provider}`: a `new-app` running the routing `chart`, with URL sync installed on a simulated history
   whose current URL is `initial-url` (default \"/\"). `sync-options` are merged into the `install-url-sync!` options.
   Pass `{:url-sync? false}` as `sync-options` to skip installing URL sync."
  ([Root chart] (routed-app Root chart "/" {}))
  ([Root chart initial-url] (routed-app Root chart initial-url {}))
  ([Root chart initial-url {:keys [url-sync?] :or {url-sync? true} :as sync-options}]
   (let [a        (new-app Root)
         provider (sim/simulated-url-history initial-url)]
     (scr/start! a chart)
     (when url-sync?
       (scr/install-url-sync! a (merge {:provider provider} (dissoc sync-options :url-sync?))))
     {:app a :provider provider})))

(defn stack "The simulated history's URLs, oldest first." [{:keys [provider]}] (sim/history-stack provider))
(defn cursor "The simulated history's cursor." [{:keys [provider]}] (sim/history-cursor provider))
(defn current-url "The simulated history's current URL." [{:keys [provider]}] (nth (sim/history-stack provider) (sim/history-cursor provider)))

(defn local-data
  "The local data of the session `session-id` (default: the routing session) of `a`."
  ([a] (local-data a scr/session-id))
  ([a session-id] (get-in (rapp/current-state a) [::sc/local-data session-id])))

(defn child-session-id
  "The session id the session `parent-session-id` (default: the routing session) invoked for the istate `target-key`."
  ([a target-key] (child-session-id a scr/session-id target-key))
  ([a parent-session-id target-key] (get-in (local-data a parent-session-id) [:invocation/id target-key])))
