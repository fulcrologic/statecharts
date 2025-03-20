(ns com.fulcrologic.statecharts.integration.fulcro.route-history-browser
  "ALPHA. This namespace's API is subject to change."
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [com.fulcrologic.statecharts.integration.fulcro.route-history :as srhist]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.route-url :as ru]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.net URLDecoder URLEncoder)
                   (java.nio.charset StandardCharsets))))

(def uir-session-id :com.fulcrologic.statecharts.integration.fulcro.ui-routes/session)

(defn decode-uri-component
  "Decode the given string as a transit and URI encoded CLJ(s) value."
  [v]
  (when (string? v)
    #?(:clj  (URLDecoder/decode ^String v (.toString StandardCharsets/UTF_8))
       :cljs (js/decodeURIComponent v))))

;; TASK: Is this function used?
;(defn encode-uri-component
;  "Encode a key/value pair of CLJ(s) data such that it can be safely placed in browser query params. If `v` is
;   a plain string, then it will not be transit-encoded."
;  [v]
;  #?(:clj  (URLEncoder/encode ^String v (.toString StandardCharsets/UTF_8))
;     :cljs (js/encodeURIComponent v)))

;; TASK: Is this function used?
;(defn query-params
;  [raw-search-string]
;  (try
;    (let [param-string (str/replace raw-search-string #"^[?]" "")]
;      (reduce
;        (fn [result assignment]
;          (let [[k v] (str/split assignment #"=")]
;            (cond
;              (and k v (= k "_rp_")) (merge result (transit-str->clj (base64-decode (decode-uri-component v))))
;              (and k v) (assoc result (keyword (decode-uri-component k)) (decode-uri-component v))
;              :else result)))
;        {}
;        (str/split param-string #"&")))
;    (catch #?(:clj Exception :cljs :default) e
;      (log/error e "Cannot decode query param string")
;      {})))

(defn route->url
  "Construct URL from route and params"
  [{:keys       [id]
    :route/keys [path params]}]
  (-> (ru/current-url)
    (ru/update-url-state-param id (constantly params))
    (ru/new-url-path (str "/" (str/join "/" path)))))

(defn url->route
  "Convert the current browser URL into a route map. Returns:
   ```
   {:id id
    :route/path [\"path\" \"segment\"]
    :route/params {:param value}}
   ```"
  [app]
  (let [url        (ru/current-url)
        id->params (ru/current-url-state-params url)
        path       (ru/current-url-path url)
        statechart (scf/lookup-statechart app ::uir/chart)
        {:keys [id] :as state} (uir/state-for-path statechart path)]
    {:id           id
     :route/path   path
     :route/params (get id->params id)}))

(defrecord HTML5History [hash-based? current-uid prefix uid->history default-route
                         fulcro-app route->url url->route]
  srhist/RouteHistory
  (-push-route! [this {:keys [uid] :as r}]
    #?(:cljs
       (let [url (str prefix (route->url r hash-based?))]
         (when-not uid
           (swap! current-uid inc)
           (swap! uid->history assoc @current-uid (assoc r :uid @current-uid)))
         (.pushState js/history #js {"uid" @current-uid} "" url))))
  (-replace-route! [this {:keys [uid] :as r}]
    #?(:cljs
       (let [url (str prefix (route->url r hash-based?))
             uid (or uid @current-uid)]
         (swap! uid->history assoc uid (assoc r :uid uid))
         (.replaceState js/history #js {"uid" @current-uid} "" url))))
  (-back! [this]
    #?(:cljs
       (do
         (tap> "Going back")
         (cond
           (> (count @uid->history) 1) (do
                                         (tap> "Going back YAW!")
                                         (log/debug "Back to prior route" (some-> @uid->history last second))
                                         (.back js/history))
           :else (log/error "No prior route. Ignoring BACK request.")))))
  (-current-route [this] (url->route fulcro-app))
  (-recent-history [this] @uid->history))

(defn new-html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API.

   `app` - The Fulco application that is being served.
   `hash-based?` - Use hash-based URIs instead of paths
   `prefix`      - Prepend prefix to all routes, in cases we are not running on root url (context-root)
   `route->url` - Specify a function that can convert a given RAD route into a URL. Defaults to the function of this name in this ns.
   `url->route` - Specify a function that can convert a URL into a RAD route. Defaults to the function of this name in this ns."
  [app {:keys [hash-based? prefix route->url url->route] :or {hash-based? false
                                                              prefix      nil
                                                              route->url  route->url
                                                              url->route  url->route} :as params}]
  (assert (or (not prefix)
            (and (str/starts-with? prefix "/")
              (not (str/ends-with? prefix "/"))))
    "Prefix must start with a slash, and not end with one.")
  #?(:cljs
     (try
       (let [history (map->HTML5History (merge
                                          params
                                          {:fulcro-app   app
                                           :route->url   route->url
                                           :url->route   url->route
                                           :hash-based?  hash-based?
                                           :prefix       prefix
                                           :uid->history (atom (sorted-map))
                                           :current-uid  (atom 1)}))
             pop-state-listener
                     (fn [evt]
                       (when (gobj/getValueByKeys evt "state")
                         (let [event-uid (gobj/getValueByKeys evt "state" "uid")]
                           (log/debug "Got pop state event." evt)
                           (swap! (:current-uid history) (constantly event-uid))
                           (scf/send! app uir-session-id
                             :event/external-route-change {:route/uid event-uid}))))]
         (.addEventListener js/window "popstate" pop-state-listener)
         history)
       (catch :default e
         (log/error e "Unable to create HTML5 history.")))))