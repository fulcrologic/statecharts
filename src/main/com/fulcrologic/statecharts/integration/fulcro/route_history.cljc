(ns com.fulcrologic.statecharts.integration.fulcro.route-history
  "ALPHA. This namespace's API is subject to change."
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.net URLDecoder URLEncoder)
                   (java.nio.charset StandardCharsets))))

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
  (push-route! [history route] "Pushes the given route with params onto the current history stack.")
  (replace-route! [history route] "Replaces the top entry in the history stack.")
  (recent-history [history] "Returns a vector of the recent routes (current route is first, older routes in age order)"))

(defn decode-uri-component
  "Decode the given string as a transit and URI encoded CLJ(s) value."
  [v]
  (when (string? v)
    #?(:clj  (URLDecoder/decode ^String v (.toString StandardCharsets/UTF_8))
       :cljs (js/decodeURIComponent v))))

(defn encode-uri-component
  "Encode a key/value pair of CLJ(s) data such that it can be safely placed in browser query params. If `v` is
   a plain string, then it will not be transit-encoded."
  [v]
  #?(:clj  (URLEncoder/encode ^String v (.toString StandardCharsets/UTF_8))
     :cljs (js/encodeURIComponent v)))

(defn query-params
  [raw-search-string]
  (try
    (let [param-string (str/replace raw-search-string #"^[?]" "")]
      (reduce
        (fn [result assignment]
          (let [[k v] (str/split assignment #"=")]
            (cond
              (and k v (= k "_rp_")) (merge result (transit-str->clj (base64-decode (decode-uri-component v))))
              (and k v) (assoc result (keyword (decode-uri-component k)) (decode-uri-component v))
              :else result)))
        {}
        (str/split param-string #"&")))
    (catch #?(:clj Exception :cljs :default) e
      (log/error e "Cannot decode query param string")
      {})))

(defn query-string
  "Convert a map to an encoded string that is acceptable on a URL.
  The param-map allows any data type acceptable to transit. The additional key-values must all be strings
  (and will be coerced to string if not). "
  [param-map & {:as string-key-values}]
  (str "?_rp_="
    (encode-uri-component (base64-encode (transit-clj->str param-map)))
    "&"
    (str/join "&"
      (map (fn [[k v]]
             (str (encode-uri-component (name k)) "=" (encode-uri-component (str v)))) string-key-values))))

(defn route->url
  "Construct URL from route and params"
  [route params hash-based?]
  (let [q (query-string (or params {}))]
    (if hash-based?
      (str q "#/" (str/join "/" (map str route)))
      (str "/" (str/join "/" (map str route)) q))))

(defn url->route
  "Convert the current browser URL into a route path and parameter map. Returns:

   ```
   {:route [\"path\" \"segment\"]
    :params {:param value}}
   ```

   You can save this value and later use it with `apply-route!`.

   Parameter hash-based? specifies whether to expect hash based routing. If no
   parameter is provided the mode is autodetected from presence of hash segment in URL.
  "
  ([] (url->route #?(:clj  false
                     :cljs (some? (seq (.. js/document -location -hash)))) nil))
  ([hash-based?] (url->route hash-based? nil))
  ([hash-based? prefix]
   #?(:cljs
      (let [path      (if hash-based?
                        (str/replace (.. js/document -location -hash) #"^[#]" "")
                        (.. js/document -location -pathname))
            pcnt      (count prefix)
            prefixed? (> pcnt 0)
            path      (if (and prefixed? (str/starts-with? path prefix))
                        (subs path pcnt)
                        path)
            route     (vec (drop 1 (str/split path #"/")))
            params    (or (some-> (.. js/document -location -search) (query-params)) {})]
        {:route  route
         :params params}))))

(defrecord HTML5History [hash-based? current-uid prefix uid->history default-route
                         fulcro-app route->url url->route]
  RouteHistory
  (push-route! [this {:keys [uid] :as r}]
    #?(:cljs
       (let [url (str prefix (route->url r hash-based?))]
         (when-not uid
           (swap! current-uid inc)
           (swap! uid->history assoc @current-uid (assoc r :uid @current-uid)))
         (.pushState js/history #js {"uid" @current-uid} "" url))))
  (replace-route! [this {:keys [uid] :as r}]
    #?(:cljs
       (let [url (str prefix (route->url r hash-based?))
             uid (or uid @current-uid)]
         (swap! uid->history assoc uid (assoc r :uid uid))
         (.replaceState js/history #js {"uid" @current-uid} "" url))))
  (recent-history [_] @uid->history))

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
                           (scf/send! app :com.fulcrologic.statecharts.integration.fulcro.ui-routes/session
                             :event/external-route-change {:route/uid event-uid}))))]
         (.addEventListener js/window "popstate" pop-state-listener)
         history)
       (catch :default e
         (log/error e "Unable to create HTML5 history.")))))
