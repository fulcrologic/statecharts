(ns com.fulcrologic.statecharts.convenience-macros
  "ALPHA. NOT API STABLE.

   This namespace includes functions and macros that emit statechart elements, but have a more
   concise notation for common cases that are normally a bit verbose.

   Another purpose that is built into some of the macros is to make it more convenient to get clear diagram output
   when rendering the chart (TODO). The `choice` macro, for example, includes the conditional expressions as
   strigified notes on the nodes."
  #?(:cljs (:require-macros com.fulcrologic.statecharts.convenience-macros))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.statecharts.elements :refer [assign transition script state on-entry on-exit Send cancel]]))

(defmacro handle
  "A MACRO to generate a target-less transition with an expression (script node). Basically an event handler.

   This is a macro. The event must be a symbol referencing a keyword or a keyword. The `expr`
   MUST be a symbol referring to a `(fn [env data])`. The result includes a diagram hint that
   can help with rendering the chart in a user-comprehensible form.

   Emits:

   ```
   (transition {:event event}
     (script {:expr expr :diagram/expression \"expr\" :diagram/label \"expr\"}))
   ```

"
  [event expr]
  `(transition {:event ~event}
     (script {:expr ~expr :diagram/expression ~(str expr) :diagram/label ~(str expr)})))

(s/fdef handle
  :args (s/cat
          :event (s/or :s symbol? :k keyword?)
          :expr symbol?))

(defmacro assign-on
  "Creates a target-less transition that simply assigns the given expression to the data model at
   the given location when `event` happens. The `expression` MUST be symbolic. Use the `convenience/assign-on` function
   if don't want the expression macro-expanded as a string notation on the node.

   Emits:

   ```
   (transition {:event event}
     (assign {:location location :expr expression :diagram/expression \"expression\" :diagram/label \"expression\"}))
   ```
   "
  [event location expression]
  `(transition {:event ~event}
     (assign {:location ~location :expr ~expression :diagram/expression ~(str expression) :diagram/label ~(str expression)})))

(defmacro choice
  "Create a choice state. Notation is like `cond`, but `:else` is the only acceptable \"default\" predicate
   The `pred` should be the symbols that will resolve to functions that take `[env data]`.
   The target states may be raw keywords, or symbols that refer to the raw keyword, but MUST
   NOT be more complex expressions.

  ```
  (choice {:id node-id ...}
    pred  target-state
    pred2 target-state2
    :else else-target-state)
  ```

  is exactly equivalent to:

  ```
  (state {:id node-id :diagram/prototype :choice}
    (transition {:cond pred :target target-state :diagram/condition \"pred\"})
    (transition {:cond pred2 :target target-state2 :diagram/condition \"pred2\"})
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
                                    `(transition {:cond              ~condition
                                                  :diagram/condition ~(str condition)
                                                  :target            ~target}))
                                  conditional-clauses)
        final-transition        (when final-clause `(transition {:target ~(second final-clause)}))
        all-transitions         (cond-> conditional-transitions
                                  final-transition (conj final-transition))]
    `(state (assoc ~props :diagram/prototype :choice) ~@all-transitions)))

(s/fdef choice
  :args (s/cat
          :props any?
          :clauses (s/* (s/cat
                          :cond (s/or :k #{:else} :s symbol?)
                          :target (s/or :k keyword? :s symbol?)))))
