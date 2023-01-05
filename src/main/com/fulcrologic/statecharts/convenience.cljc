(ns com.fulcrologic.statecharts.convenience
  "ALPHA. NOT API STABLE.

   This namespace includes functions and macros that emit statechart elements, but have a more
   concise notation for common cases that are normally a bit verbose.

   Another purpose that is built into some of the macros is to make it more convenient to get clear diagram output
   when rendering the chart (TODO). The `choice` macro, for example, includes the conditional expressions as
   strigified notes on the nodes."
  (:require
    [com.fulcrologic.statecharts.elements :refer [assign transition script state on-entry on-exit Send cancel]]))

(defn handle
  "Generate a target-less transition with an expression (script node). Basically an event handler.

   Emits:

   ```
   (transition {:event event}
     (script {:expr expr}))
   ```
  "
  [event expr]
  (transition {:event event}
    (script {:expr expr})))

(defn assign-on
  "Creates a target-less transition that simply assigns the given expression to the data model at
   the given location when `event` happens.

   Emits:

   ```
   (transition {:event event}
     (assign {:location location :expr expression}))
   ```
   "
  [event location expression]
  (transition {:event event}
    (assign {:location location :expr expression})))

(defn on
  "Shorthand for `(transition {:event event :target target} ...actions)`"
  [event target & actions] (apply transition {:event event :target target} actions))

(defn choice
  "Create a choice state. Notation is like `cond`, but `:else` is the only acceptable \"default\" predicate.
   The `pred` should `(fn [env data] boolean?)`. See also the `choice` macro in convenience-macros, which
   can include the expressions as annotations on the nodes.

  ```
  (choice {:id node-id ...}
    pred  target-state
    pred2 target-state2
    :else else-target-state)
  ```

  is exactly equivalent to:

  ```
  (state {:id node-id :diagram/prototype :choice}
    (transition {:cond pred :target target-state})
    (transition {:cond pred2 :target target-state2})
    (transition {:target else-target-state})
  ```
  "
  [props & args]
  (let [clauses                 (partition 2 args)
        else-clause?            #(= :else (first %))
        conditional-clauses     (remove else-clause? clauses)
        final-clause            (first (filter else-clause? clauses))
        conditional-transitions (mapv
                                  (fn [[condition target]]
                                    (transition {:cond   condition
                                                 :target target}))
                                  conditional-clauses)
        final-transition        (when final-clause (transition {:target (second final-clause)}))
        all-transitions         (cond-> conditional-transitions
                                  final-transition (conj final-transition))]
    (state (assoc props :diagram/prototype :choice)
      all-transitions)))

(defn send-after
  "Creates a pair of elements: an on-entry and on-exit, which send an event after some delay (see `send` for delay/delayexpr),
   but cancel that event if the enclosing state is exited. The argument is the props for a `send` element, which MUST
   include an `:id` (currently does not support idlocation).

   If you don't specify a delay it won't technically break the entry, but the extra exit will be cruft.

   Emits:

   ```
   [(on-entry {}
      (Send send-props))
    (on-exit {}
      (cancel {:sendid id}))]
   ```
   "
  [{:keys [id delay delayexpr] :as send-props}]
  (when-not id (throw (ex-info "send-after's props MUST include an :id." {})))
  [(on-entry {}
     (Send send-props))
   (on-exit {}
     (cancel {:sendid id}))])
