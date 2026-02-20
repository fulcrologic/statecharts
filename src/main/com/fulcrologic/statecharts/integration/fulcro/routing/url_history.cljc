(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-history
  "ALPHA. This namespace's API is subject to change.

   URLHistoryProvider protocol and shared URL utility functions for
   statechart-driven routing. Implementations live in separate namespaces
   for CLJS dead-code elimination:

   - `browser-history` — BrowserURLHistory (CLJS-only, wraps js/window.history)
   - `simulated-history` — SimulatedURLHistory (cross-platform, for testing)"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as url-codec])
  #?(:clj (:import (java.net URI))))

;; ---------------------------------------------------------------------------
;; Browser URL utilities
;; ---------------------------------------------------------------------------

(defn current-url [] #?(:cljs (.-href (.-location js/window))))

(defn current-url-path
  "Returns the path segments of the given `href` (or current URL) as a vector of strings."
  ([] (current-url-path (current-url)))
  ([href]
   (let [path-string #?(:cljs (.-pathname (if (str/starts-with? href "/")
                                            (js/URL. href "http://localhost")
                                            (js/URL. href)))
                         :clj (.getPath (URI. href)))]
     (if (seq path-string)
       (filterv #(not= "" %) (str/split path-string #"/"))
       []))))

(defn push-url! [href] #?(:cljs (.pushState (.-history js/window) nil "" href)))

(defn replace-url! [href] #?(:cljs (.replaceState (.-history js/window) nil "" href)))

;; ---------------------------------------------------------------------------
;; URLHistoryProvider protocol
;; ---------------------------------------------------------------------------

(defprotocol URLHistoryProvider
  (current-href [this] "Returns the current URL string, or nil.")
  (current-index [this] "Returns the monotonic index of the current history entry.")
  (-push-url! [this href] "Push `href` onto the history stack with a new monotonic index.")
  (-replace-url! [this href] "Replace the current entry's URL without changing its index.")
  (go-back! [this] "Navigate back one entry. No-op if already at the beginning.")
  (go-forward! [this] "Navigate forward one entry. No-op if already at the end.")
  (set-popstate-listener! [this callback]
    "Register a `(fn [index])` callback for popstate-like events, or nil to remove."))

;; ---------------------------------------------------------------------------
;; URL restoration (URL -> state)
;; ---------------------------------------------------------------------------

(defn find-target-by-leaf-name
  "Given a normalized statechart's `elements-by-id` and a `leaf-name` string, finds the
   route target element whose URL segment matches. Checks `:route/segment` first, then
   falls back to `(name (:route/target element))`. Returns the element's `:id` or nil."
  [elements-by-id leaf-name]
  (some (fn [[_id element]]
          (when (:route/target element)
            (when (= (url-codec/element-segment element) leaf-name)
              (:id element))))
    elements-by-id))

(defn find-target-by-leaf-name-deep
  "Searches the parent chart's elements AND `:route/reachable` sets on istate elements.
   If a direct match is found in `elements-by-id` (via `element-segment`), returns
   `{:target-id id :child? false}`.
   If the leaf-name matches a keyword in a `:route/reachable` set (by keyword name),
   returns `{:target-key matched-keyword :child? true :owner-id istate-id}`.
   Returns nil if not found.

   NOTE: Reachable matching uses `(name kw)`, not `:route/segment`. If a child chart
   uses `:route/segment` on a reachable target, ensure the keyword name matches."
  [elements-by-id leaf-name]
  (or
    ;; Direct match in this chart (uses element-segment)
    (when-let [id (find-target-by-leaf-name elements-by-id leaf-name)]
      {:target-id id :child? false})
    ;; Search reachable sets on istates (matches by keyword name)
    (some (fn [[id element]]
            (when-let [reachable (:route/reachable element)]
              (when-let [matched (some (fn [kw] (when (= (name kw) leaf-name) kw)) reachable)]
                {:target-key matched :child? true :owner-id id})))
      elements-by-id)))

;; ---------------------------------------------------------------------------
;; Legacy compatibility -- keep old functions working during transition
;; ---------------------------------------------------------------------------

(defn new-url-path
  "Given a complete browser URL: Replace the path with the given new path
   that retains all the prior elements (host/port/query string/hash).
   Returns a string that is the complete new URL."
  [old-href new-path]
  #?(:cljs
     (let [url     (js/URL. old-href)
           origin  (.-origin url)
           search  (.-search url)
           hash    (.-hash url)
           new-url (str origin new-path search hash)]
       new-url)))
