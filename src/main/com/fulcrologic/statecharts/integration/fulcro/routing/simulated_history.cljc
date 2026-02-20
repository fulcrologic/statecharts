(ns com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history
  "ALPHA. This namespace's API is subject to change.

   SimulatedURLHistory — a cross-platform URLHistoryProvider backed by an atom.
   Useful for headless testing of statechart-driven routing without a browser."
  (:require
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]))

;; ---------------------------------------------------------------------------
;; SimulatedURLHistory (cross-platform, for testing)
;; ---------------------------------------------------------------------------

(defrecord SimulatedURLHistory [state-atom]
  ruh/URLHistoryProvider
  (current-href [_this]
    (let [{:keys [entries cursor]} @state-atom]
      (:url (nth entries cursor))))
  (current-index [_this]
    (let [{:keys [entries cursor]} @state-atom]
      (:index (nth entries cursor))))
  (-push-url! [_this href]
    (swap! state-atom
      (fn [{:keys [entries cursor counter] :as state}]
        (let [new-counter (inc counter)
              truncated   (subvec entries 0 (inc cursor))]
          (assoc state
            :entries (conj truncated {:url href :index new-counter})
            :cursor (inc cursor)
            :counter new-counter))))
    nil)
  (-replace-url! [_this href]
    (swap! state-atom
      (fn [{:keys [cursor] :as state}]
        (assoc-in state [:entries cursor :url] href)))
    nil)
  (go-back! [_this]
    (let [old-state @state-atom]
      (when (pos? (:cursor old-state))
        (let [new-state (swap! state-atom update :cursor dec)
              {:keys [entries cursor]} new-state
              listener  (:listener new-state)
              idx       (:index (nth entries cursor))]
          (when listener
            (listener idx))))))
  (go-forward! [_this]
    (let [old-state @state-atom]
      (when (< (:cursor old-state) (dec (count (:entries old-state))))
        (let [new-state (swap! state-atom update :cursor inc)
              {:keys [entries cursor]} new-state
              listener  (:listener new-state)
              idx       (:index (nth entries cursor))]
          (when listener
            (listener idx))))))
  (set-popstate-listener! [_this callback]
    (swap! state-atom assoc :listener callback)
    nil))

(defn simulated-url-history
  "Creates a SimulatedURLHistory for headless testing.
   Optionally accepts an `initial-url` (defaults to \"/\")."
  ([] (simulated-url-history "/"))
  ([initial-url]
   (->SimulatedURLHistory
     (atom {:entries  [{:url initial-url :index 0}]
            :cursor   0
            :counter  0
            :listener nil}))))

;; ---------------------------------------------------------------------------
;; Inspection helpers
;; ---------------------------------------------------------------------------

(defn history-stack
  "Returns a vector of URL strings from `provider`'s history."
  [provider]
  (mapv :url (:entries @(:state-atom provider))))

(defn history-cursor
  "Returns the current cursor position in `provider`'s history."
  [provider]
  (:cursor @(:state-atom provider)))

(defn history-entries
  "Returns the full entries (with indices) from `provider`'s history."
  [provider]
  (:entries @(:state-atom provider)))
