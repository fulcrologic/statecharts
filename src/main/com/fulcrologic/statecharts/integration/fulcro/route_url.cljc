(ns ^:deprecated com.fulcrologic.statecharts.integration.fulcro.route-url
  "DEPRECATED."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [com.fulcrologic.statecharts :as-alias sc]
    [taoensso.timbre :as log]))

(defn current-url [] #?(:cljs (.-href (.-location js/window))))

(defn new-url-path
  "Given a complete browser URL: Replace the path with the given new path
   that retains all the prior elements (host/port/query string/hash)
   Returns a string that is the complete new URL"
  [old-href new-path]
  #?(:cljs
     (let [url     (js/URL. old-href)
           origin  (.-origin url)
           search  (.-search url)
           hash    (.-hash url)
           new-url (str origin new-path search hash)]
       new-url)))

(defn current-url-path
  ([] (current-url-path (current-url)))
  ([href]
   #?(:cljs
      (let [url         (js/URL. href)
            path-string (.-pathname url)]
        (if (seq path-string)
          (filterv #(not= "" %) (str/split path-string #"/"))
          [])))))

(defn current-url-state-params [href]
  #?(:cljs
     (let [url           (js/URL. href)
           search-params (.-searchParams url)
           scparam       (.get search-params "_sc_")
           params        (if scparam
                           (ft/transit-str->clj (js/atob scparam))
                           {})]
       params)))

(defn update-url-state-param [old-href state-id f & update-params]
  #?(:cljs
     (let [url           (js/URL. old-href)
           search-params (.-searchParams url)
           params        (current-url-state-params old-href)
           new-params    (apply update params state-id f update-params)
           encoded       (js/btoa (ft/transit-clj->str new-params))
           _             (.set search-params "_sc_" encoded)]
       (set! (.-search url) (.toString search-params))
       (.toString url))))

(defn push-url! [href] #?(:cljs (.pushState (.-history js/window) nil "" href)))

(defn replace-url! [href] #?(:cljs (.replaceState (.-history js/window) nil "" href)))

;; ---------------------------------------------------------------------------
;; Pure functions for hierarchical path composition and parameterized matching
;; ---------------------------------------------------------------------------

(defn resolve-full-path
  "Given a normalized `statechart` and a `state-id`, walk the parent chain collecting
   `:route/path` segments from each ancestor that has one, stopping at `:routing/root`
   or `:ROOT`. Returns a flat vector of strings and keyword param placeholders
   (e.g. `[\"users\" :id \"edit\"]`).

   If the state has no routable ancestors, returns its own `:route/path` unchanged
   (backward-compatible with flat paths)."
  [{::sc/keys [elements-by-id] :as _statechart} state-id]
  (loop [id       state-id
         segments []]
    (let [element (get elements-by-id id)]
      (cond
        (nil? element)
        segments

        (or (contains? element :routing/root) (= :ROOT (:parent element)))
        (into (vec (:route/path element)) segments)

        :else
        (let [path-prefix (:route/path element)]
          (recur (:parent element)
            (if (seq path-prefix)
              (into (vec path-prefix) segments)
              segments)))))))

(defn match-path
  "Match a URL `path` (vector of strings) against a `pattern` (vector of strings
   and keyword param placeholders). Returns nil if no match, or a map of extracted
   params on match.

   Examples:
     (match-path [\"users\" \"42\" \"edit\"] [\"users\" :id \"edit\"])
     ;; => {:id \"42\"}

     (match-path [\"users\" \"edit\"] [\"users\" \"edit\"])
     ;; => {}

     (match-path [\"users\"] [\"users\" :id])
     ;; => nil"
  [path pattern]
  (when (= (count path) (count pattern))
    (reduce
      (fn [params [path-seg pattern-seg]]
        (cond
          (keyword? pattern-seg)
          (assoc params pattern-seg path-seg)

          (= path-seg pattern-seg)
          params

          :else
          (reduced nil)))
      {}
      (map vector path pattern))))

(defn resolve-path-params
  "Substitute keyword placeholders in `pattern` with values from `params`.
   Returns a vector of strings. Missing params become empty strings.

   Example:
     (resolve-path-params [\"users\" :id \"edit\"] {:id \"42\"})
     ;; => [\"users\" \"42\" \"edit\"]"
  [pattern params]
  (mapv
    (fn [seg]
      (if (keyword? seg)
        (str (get params seg ""))
        seg))
    pattern))

(defn- literal-segment-count
  "Returns the number of literal (string) segments in a path pattern."
  [pattern]
  (count (filterv string? pattern)))

(defn build-route-table
  "Given a normalized `statechart`, find all states with `:route/target`, compute
   their full paths via `resolve-full-path`, sort by specificity (more literal segments
   first, then longer paths first), and warn about ambiguous patterns.

   Returns a sorted vector of maps:
     `{:state-id <id> :route/target <target> :pattern <full-path-vector>}`"
  [{::sc/keys [elements-by-id] :as statechart}]
  (let [route-entries (into []
                        (comp
                          (filter (fn [[_id el]] (:route/target el)))
                          (map (fn [[id el]]
                                 (let [full-path (resolve-full-path statechart id)]
                                   {:state-id     id
                                    :route/target (:route/target el)
                                    :pattern      full-path}))))
                        elements-by-id)
        sorted        (vec (sort-by
                             (fn [{:keys [pattern]}]
                               [(- (literal-segment-count pattern))
                                (- (count pattern))])
                             route-entries))
        ;; Check for ambiguous patterns (same length, same literal positions)
        pattern-sig   (fn [p] (mapv #(if (keyword? %) :param %) p))
        by-sig        (group-by (comp pattern-sig :pattern) sorted)]
    (doseq [[sig entries] by-sig]
      (when (> (count entries) 1)
        (let [all-literal? (every? string? sig)]
          (when-not all-literal?
            (log/warn "Ambiguous route patterns detected (same shape with param placeholders):"
              (mapv (fn [e] {:state-id (:state-id e) :pattern (:pattern e)}) entries))))))
    sorted))

(defn find-route-for-url
  "Given a `route-table` (from `build-route-table`) and a URL `path` (vector of
   strings), find the first matching entry and extract params. Returns nil if no
   match, or a map with `:state-id`, `:route/target`, `:pattern`, and `:route/path-params`."
  [route-table path]
  (reduce
    (fn [_ entry]
      (when-let [params (match-path path (:pattern entry))]
        (reduced (assoc entry :route/path-params params))))
    nil
    route-table))

(comment
  (-> (.-href (.-location js/window))
    (new-url-path "/")
    (update-url-state-param :state/foo assoc :x 1)
    (update-url-state-param :state/bar assoc :y 2)
    (update-url-state-param :state/foo update :x inc)
    (push-url!)
    )
  (current-url-state-params (.-href (.-location js/window)))
  (new-url-path (.-href (.-location js/window)) "/bax")
  )

