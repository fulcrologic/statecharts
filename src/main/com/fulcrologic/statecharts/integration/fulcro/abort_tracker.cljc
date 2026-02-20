(ns com.fulcrologic.statecharts.integration.fulcro.abort-tracker
  "Tracks in-flight abort IDs per statechart session for Fulcro load cancellation.

   The `tracker` is an atom whose shape is:

   ```
   {session-id {:abort-ids #{abort-id-1 abort-id-2 ...}
                :aborting? false}}
   ```

   All functions take the tracker atom as the first argument.")

(defn register-abort-id!
  "Register `abort-id` as in-flight for `session-id`."
  [tracker session-id abort-id]
  (swap! tracker update-in [session-id :abort-ids] (fnil conj #{}) abort-id))

(defn unregister-abort-id!
  "Remove `abort-id` from the in-flight set for `session-id`."
  [tracker session-id abort-id]
  (swap! tracker update-in [session-id :abort-ids] disj abort-id))

(defn aborting?
  "Returns true if `session-id` is in the aborting state."
  [tracker session-id]
  (boolean (get-in @tracker [session-id :aborting?])))

(defn set-aborting!
  "Mark `session-id` as aborting. New loads will short-circuit."
  [tracker session-id]
  (swap! tracker assoc-in [session-id :aborting?] true))

(defn clear-aborting!
  "Clear the aborting flag for `session-id`."
  [tracker session-id]
  (swap! tracker assoc-in [session-id :aborting?] false))

(defn get-abort-ids
  "Returns the set of currently tracked abort IDs for `session-id`."
  [tracker session-id]
  (get-in @tracker [session-id :abort-ids] #{}))
