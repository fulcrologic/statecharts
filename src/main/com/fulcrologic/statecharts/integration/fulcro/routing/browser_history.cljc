(ns com.fulcrologic.statecharts.integration.fulcro.routing.browser-history
  "ALPHA. This namespace's API is subject to change.

   BrowserURLHistory — a URLHistoryProvider that wraps `js/window.history` with
   monotonic index tracking. CLJS-only implementation; the namespace is CLJC so
   it can be required unconditionally, but provides no CLJ implementation."
  (:require
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]))

;; ---------------------------------------------------------------------------
;; BrowserURLHistory (CLJS only)
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftype BrowserURLHistory [counter-atom ^:mutable listener-fn]
     ruh/URLHistoryProvider
     (current-href [_this]
       (let [loc (.-location js/window)]
         (str (.-pathname loc) (.-search loc))))
     (current-index [_this]
       @counter-atom)
     (-push-url! [_this href]
       (let [idx (swap! counter-atom inc)]
         (.pushState (.-history js/window) #js {:index idx} "" href)))
     (-replace-url! [_this href]
       (.replaceState (.-history js/window) #js {:index @counter-atom} "" href))
     (go-back! [_this]
       (.back (.-history js/window)))
     (go-forward! [_this]
       (.forward (.-history js/window)))
     (set-popstate-listener! [_this callback]
       (when listener-fn
         (.removeEventListener js/window "popstate" listener-fn))
       (if callback
         (let [wrapped (fn [event]
                         (let [state (.-state event)
                               idx   (when state (.-index state))]
                           (callback idx)))]
           (set! listener-fn wrapped)
           (.addEventListener js/window "popstate" wrapped))
         (set! listener-fn nil)))))

#?(:cljs
   (defn browser-url-history
     "Creates a BrowserURLHistory that wraps js/window.history with monotonic index tracking.
      Seeds the initial entry with `replaceState({index: 0})`."
     []
     (let [provider (->BrowserURLHistory (atom 0) nil)]
       (.replaceState (.-history js/window) #js {:index 0} "" (.-href (.-location js/window)))
       provider)))
