(ns com.fulcrologic.statecharts.integration.fulcro.route-url
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ft]))

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

