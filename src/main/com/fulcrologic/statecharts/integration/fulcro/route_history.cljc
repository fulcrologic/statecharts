(ns com.fulcrologic.statecharts.integration.fulcro.route-history
  "ALPHA. This namespace's API is subject to change."
  #?(:clj (:import (java.net URLDecoder URLEncoder)
                   (java.nio.charset StandardCharsets)))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log]))

(defonce history (volatile! nil))

(defprotocol RouteHistory
  "A Route History is mainly a storage device. It records a history stack along with optional additional parameters
   at each history entry. It can be asked what it thinks the current route is, and it can be asked to replace the
   current top of the stack.

   A history implementation *may* be hooked to some external source of events (i.e. browser back/forward buttons, phone
   native navigation). These events (e.g. like HTML5 popstate events) are only expected when there is an *external* change
   to the route that your application did not initiate with its own API (not that A tags in HTML with URIs will cause
   these events, since it is the browser, not your app, that is technically initiating the change). Such an implementation
   *must* honor the add/remove calls to hook up a listener to these external events.
   "
  (-push-route! [history route] "Pushes the given route with params onto the current history stack.")
  (-replace-route! [history route] "Replaces the top entry in the history stack.")
  (-back! [history] "Moves the history back one in the history stack.")
  (-current-route [history] "Returns a map containing {:route [vector of strings] :params map-of-data}.")
  (-recent-history [history] "Returns a list of recent routes."))

(s/def ::RouteHistory #(satisfies? RouteHistory %))
(s/def :route/path (s/coll-of string? :kind vector?))
(s/def :route/params map?)
(s/def ::route (s/map-of #{:id :route/path :route/params :uid} any?))

(>defn active-history
  "Returns the active (installed) RouteHistory implementation, or nil if none is installed."
  []
  [=> (? ::RouteHistory)]
  (try
    (some-> history deref)
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn history-support?
  "Returns true if history support is enabled on the given app (you can also pass a component)."
  []
  [=> boolean?]
  (boolean (active-history)))

(>defn install-route-history!
  [history-impl]
  [::RouteHistory => any?]
  (vreset! history history-impl))

(defn push-route!
  "Push the given route onto the route history (if history is installed). A route is a vector of the route segments
   that locate a given target."
  [route]
  (try
    (some-> (active-history) (-push-route! route))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(defn replace-route!
  "Replace the top of the current route stack "
  [route]
  (try
    (some-> (active-history) (-replace-route! route))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(defn back!
  "Go to the last position in history (if history is installed)."
  []
  (try
    (some-> (active-history) (-back!))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn current-route
  "Returns a map of {:route [\"a\" \"b\"] :params {}}. The params are the extra state/params, and the route is purely strings."
  []
  [=> (? ::route)]
  (try
    (some-> (active-history) (-current-route))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(defn recent-history
  "Returns a list of recent routes."
  []
  [=> (s/map-of :int ::route)]
  (try
    (some-> (active-history) (-recent-history))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))