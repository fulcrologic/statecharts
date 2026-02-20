(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-codec
  "ALPHA. This namespace's API is subject to change.

   URLCodec protocol and codec-agnostic functions that convert statechart
   configuration into URLs. The protocol defines encode/decode; implementations
   live in separate namespaces (e.g. `url-codec-transit` for the default
   transit+base64 codec)."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.protocols :as scp]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn element-segment
  "Returns the URL path segment for a route `element`. Uses `:route/segment` when
   present, otherwise falls back to `(name (:route/target element))`."
  [element]
  (or (:route/segment element)
    (when-let [target (:route/target element)]
      (name target))))

;; ---------------------------------------------------------------------------
;; URLCodec protocol
;; ---------------------------------------------------------------------------

(defprotocol URLCodec
  (encode-url [this context]
    "Given an encoding context map, return a URL path string (with query if needed).
     Context keys: :segments (vector of state IDs), :params (map keyed by state-id),
     :route-elements (map of state-id -> element map).")
  (decode-url [this href route-elements]
    "Given a URL string and route-elements map, return {:leaf-id <state-id> :params <map>}."))

;; ---------------------------------------------------------------------------
;; Path generation from active configuration (state -> URL)
;; ---------------------------------------------------------------------------

(defn- leaf-route-path-segments
  "Returns a vector of path segment strings for the active leaf route in a single chart level.
   Given `elements-by-id` and a `configuration` set, finds the deepest active route state and
   walks up collecting `:route/target` simple names. Stops at `:routing/root` boundaries."
  [elements-by-id configuration]
  (let [route-states (into []
                       (comp
                         (map elements-by-id)
                         (filter :route/target))
                       configuration)
        leaf-routes  (filterv
                       (fn [{:keys [id]}]
                         (not-any? (fn [other]
                                     (and (not= (:id other) id)
                                       (let [pid (:parent other)]
                                         (loop [p pid]
                                           (cond
                                             (nil? p) false
                                             (= p id) true
                                             :else (recur (:parent (get elements-by-id p))))))))
                           route-states))
                       route-states)]
    (when-let [leaf (first leaf-routes)]
      (vec
        (loop [id       (:id leaf)
               segments ()]
          (let [element (get elements-by-id id)]
            (cond
              (nil? element)
              segments

              (contains? element :routing/root)
              segments

              :else
              (let [seg (element-segment element)]
                (recur (:parent element)
                  (if seg
                    (cons seg segments)
                    segments))))))))))

(defn path-from-configuration
  "Given a normalized statechart's `elements-by-id` and a set of active state IDs (`configuration`),
   finds the active leaf route states and walks up the element tree collecting `:route/target`
   simple names from each ancestor. Returns a path string like `/AdminPanel/AdminUserDetail`.

   A leaf route state is one that has `:route/target` and no children with `:route/target`.
   If multiple leaf routes are active (parallel regions), returns the path for the first one found."
  [elements-by-id configuration]
  (when-let [segments (leaf-route-path-segments elements-by-id configuration)]
    (str "/" (str/join "/" segments))))

(defn params-from-configuration
  "Given a normalized statechart's `elements-by-id`, the active `configuration`, and the
   statechart's data model map, collects `[:routing/parameters <state-id>]` for each active
   route state. Returns a map keyed by state ID, or nil if no state has params.

   The `data-model` is the session's local data map (from working memory)."
  [elements-by-id configuration data-model]
  (let [routing-params (get data-model :routing/parameters)
        result         (reduce
                         (fn [acc state-id]
                           (let [element (get elements-by-id state-id)]
                             (if-let [params (and (:route/target element)
                                               (get routing-params state-id))]
                               (if (seq params)
                                 (assoc acc state-id params)
                                 acc)
                               acc)))
                         {}
                         configuration)]
    (when (seq result)
      result)))

(defn- leaf-route-path-elements
  "Returns a vector of `[state-id element]` pairs for the active route path (parent->leaf order).
   Used to build the codec encoding context's `:route-elements` and `:segments`."
  [elements-by-id configuration]
  (let [route-states (into []
                       (comp
                         (map elements-by-id)
                         (filter :route/target))
                       configuration)
        leaf-routes  (filterv
                       (fn [{:keys [id]}]
                         (not-any? (fn [other]
                                     (and (not= (:id other) id)
                                       (let [pid (:parent other)]
                                         (loop [p pid]
                                           (cond
                                             (nil? p) false
                                             (= p id) true
                                             :else (recur (:parent (get elements-by-id p))))))))
                           route-states))
                       route-states)]
    (when-let [leaf (first leaf-routes)]
      (vec
        (loop [id    (:id leaf)
               pairs ()]
          (let [element (get elements-by-id id)]
            (cond
              (nil? element) pairs
              (contains? element :routing/root) pairs
              :else
              (let [has-target? (:route/target element)]
                (recur (:parent element)
                  (if has-target?
                    (cons [id element] pairs)
                    pairs))))))))))

(defn configuration->url
  "Combines path and params into a URL string. Builds an encoding context and delegates
   to the codec."
  [elements-by-id configuration data-model codec]
  (when-let [path-elements (leaf-route-path-elements elements-by-id configuration)]
    (let [segments       (mapv first path-elements)
          route-elements (into {} path-elements)
          params         (params-from-configuration elements-by-id configuration data-model)
          context        {:segments       segments
                          :params         params
                          :route-elements route-elements}]
      (encode-url codec context))))

(defn deep-configuration->url
  "Walks the full invocation tree starting from `session-id` to build a URL that includes
   child chart state. Uses the same tree-walk pattern as `deep-busy?` in routing.

   `state-map` is the current Fulcro app state. `registry` is the statechart registry.
   `local-data-path-fn` is a function `(fn [session-id & ks])` returning the Fulcro state
   path for a session's local data (to avoid circular dependency on fulcro_impl).
   `codec` is a URLCodec instance."
  [state-map registry session-id local-data-path-fn codec]
  (letfn [(collect [sid seen]
            (when-not (contains? seen sid)
              (let [wmem           (get-in state-map [::sc/session-id sid])
                    configuration  (::sc/configuration wmem)
                    statechart-src (::sc/statechart-src wmem)]
                (when (and configuration statechart-src)
                  (let [chart          (scp/get-statechart registry statechart-src)
                        elements-by-id (::sc/elements-by-id chart)
                        local-data     (get-in state-map (local-data-path-fn sid))
                        path-elements  (or (leaf-route-path-elements elements-by-id configuration) [])
                        state-ids      (mapv first path-elements)
                        params         (params-from-configuration elements-by-id configuration local-data)
                        seen           (conj seen sid)
                        child-result   (reduce
                                         (fn [acc state-id]
                                           (let [target-key (get-in elements-by-id [state-id :route/target])]
                                             (if-let [child-sid (and target-key
                                                                  (get-in local-data [:invocation/id target-key]))]
                                               (let [child (collect child-sid seen)]
                                                 (cond-> acc
                                                   (seq (:state-ids child)) (update :state-ids into (:state-ids child))
                                                   (seq (:params child)) (update :params merge (:params child))
                                                   (seq (:route-elements child)) (update :route-elements merge (:route-elements child))))
                                               acc)))
                                         {:state-ids [] :params nil :route-elements {}}
                                         configuration)
                        merged-elements (merge (into {} path-elements) (:route-elements child-result))
                        ;; R5 collision detection: check for duplicate state IDs across invocation tree
                        parent-ids (into #{} (map first) path-elements)
                        child-ids  (into #{} (keys (:route-elements child-result)))
                        collisions (not-empty (set/intersection parent-ids child-ids))]
                    (when collisions
                      (throw (ex-info (str "Duplicate route state IDs across invocation tree: " collisions
                                        ". Create a wrapper component with a distinct registry key.")
                               {:colliding-ids collisions :session-id sid})))
                    {:state-ids      (into state-ids (:state-ids child-result))
                     :route-elements merged-elements
                     :params         (merge params (:params child-result))})))))]
    (let [{:keys [state-ids params route-elements]} (collect session-id #{})]
      (when (seq state-ids)
        (let [context {:segments       state-ids
                       :params         params
                       :route-elements route-elements}]
          (encode-url codec context))))))
