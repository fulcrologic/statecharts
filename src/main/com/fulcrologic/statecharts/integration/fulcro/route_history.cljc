(ns com.fulcrologic.statecharts.integration.fulcro.route-history
  "ALPHA. This namespace's API is subject to change."
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
  (recent-history [history] "Returns a vector of the recent routes (current route is first, older routes in age order)")
  (current-route [history] "Returns a map containing {:route [vector of strings] :params map-of-data}."))
