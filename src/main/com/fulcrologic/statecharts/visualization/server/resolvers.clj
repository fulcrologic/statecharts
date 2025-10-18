(ns com.fulcrologic.statecharts.visualization.server.resolvers
  "Pathom resolvers for statechart visualization API."
  (:require
    [clojure.walk :as walk]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as scp]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]))

(pc/defresolver registered-charts-resolver
  "Returns a list of all registered statecharts.

  Uses the StatechartRegistry from the Pathom environment to fetch available charts.
  Returns an empty list if no registry is configured."
  [env _]
  {::pc/output [{::sc/all-charts [::sc/id :chart/name]}]}
  (let [registry (::sc/statechart-registry env)]
    (if registry
      (try
        ;; Use the protocol method to get all charts
        (let [all-charts (scp/all-charts registry)
              chart-ids  (keys all-charts)
              charts     (for [chart-id chart-ids]
                           {::sc/id     chart-id
                            :chart/name (name chart-id)})]
          {::sc/all-charts charts})
        (catch Exception e
          (log/error e "Error fetching registered charts")
          {::sc/all-charts []}))
      (do
        (log/warn "No registry found in environment, returning empty chart list")
        {::sc/all-charts []}))))

(defn remove-functions
  "Recursively removes functions from a data structure, replacing them with :fn placeholder.
  This is necessary because functions cannot be serialized to Transit for client transmission."
  [data]
  (walk/postwalk
    (fn [x]
      (if (fn? x)
        :fn                                                 ; Replace functions with :fn keyword placeholder
        x))
    data))

(pc/defresolver chart-definition-resolver
  "Returns the full definition of a specific chart.

  Takes a ::sc/id as input and returns the complete chart definition from the registry.
  Functions in the chart definition are replaced with :fn placeholders for serialization."
  [env {chart-id ::sc/id}]
  {::pc/input  #{::sc/id}
   ::pc/output [::sc/id :chart/name ::sc/elements-by-id ::sc/id-ordinals ::sc/ids-in-document-order
                :id :node-type :children :initial :initial?]}
  (let [registry (::sc/statechart-registry env)]
    (if registry
      (try
        (let [chart-def (scp/get-statechart registry chart-id)]
          (if chart-def
            (-> chart-def
              ;; Remove functions before serializing to Transit
              remove-functions
              ;; Add convenience name field
              (assoc :chart/name (str chart-id)))
            (do
              (log/warn "Chart not found:" chart-id)
              nil)))
        (catch Exception e
          (log/error e "Error fetching chart definition for" chart-id)
          nil))
      (do
        (log/warn "No registry found in environment")
        nil))))

(pc/defresolver session-state-resolver
  "Returns the working memory and current configuration for a session.

  Takes a ::sc/session-id as input and returns the session's working memory and active configuration.
  The configuration is the set of currently active states in the statechart."
  [env {session-id ::sc/session-id}]
  {::pc/input  #{::sc/session-id}
   ::pc/output [::sc/session-id ::sc/working-memory ::sc/configuration]}
  (let [wmem-store (::sc/working-memory-store env)]
    (if wmem-store
      (try
        (if-let [wmem (scp/get-working-memory wmem-store env session-id)]
          (let [config (::sc/configuration wmem)]
            {::sc/session-id     session-id
             ::sc/working-memory wmem
             ::sc/configuration  config})
          (do
            (log/warn "Session not found:" session-id)
            nil))
        (catch Exception e
          (log/error e "Error fetching session state for" session-id)
          nil))
      (do
        (log/warn "No working memory store found in environment")
        nil))))

(def resolvers
  "Vector of all resolvers for the visualization API."
  [registered-charts-resolver
   chart-definition-resolver
   session-state-resolver])
