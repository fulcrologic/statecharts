(ns com.fulcrologic.statecharts.tracing
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.tracing])))

(defonce trace-fn (atom (fn [expr v])))

(defn set-trace!
  "Set the function `(fn [msg value] )` that does tracing of the state machine. If unset no tracing will occur."
  [f] (reset! trace-fn f))

#?(:clj
   (defmacro trace
     "Trace `expr` with an optional `msg` instead of showing the expression.
      Requires you run `set-trace!` in order to set the logging function."
     ([msg expr]
      `(let [v# ~expr
             f# (deref trace-fn)]
         (f# ~msg v#)
         v#))
     ([expr]
      (let [msg (pr-str expr)]
        `(trace ~msg ~expr)))))
