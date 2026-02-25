(ns demo-registry
  "Default registry setup for visualization server with example charts."
  (:require
    [com.fulcrologic.statecharts.protocols :as scp]
    [com.fulcrologic.statecharts.registry.local-memory-registry :as lmr]))

(defn create-example-registry
  "Creates a registry pre-loaded with example charts from src/examples.

  This is useful for development and testing. In production, you would typically
  create an empty registry and populate it with your own charts."
  []
  (let [registry (lmr/new-registry)]
    ;; Load example charts
    (try
      ;; Traffic light example
      (require 'traffic-light)
      (when-let [chart (resolve 'traffic-light/traffic-lights)]
        (scp/register-statechart! registry :traffic-light @chart))
      (catch Exception e
        (.println System/err (str "Could not load traffic-light example: " (.getMessage e)))))

    (try
      ;; History sample example
      (require 'history-sample)
      (when-let [chart (resolve 'history-sample/sample)]
        (scp/register-statechart! registry :history-sample @chart))
      (catch Exception e
        (.println System/err (str "Could not load history-sample example: " (.getMessage e)))))

    ;; Load comprehensive history visualization examples
    (try
      (require 'viz-history)
      (when-let [chart (resolve 'viz-history/shallow-history)]
        (scp/register-statechart! registry :shallow-history @chart))
      (when-let [chart (resolve 'viz-history/deep-history)]
        (scp/register-statechart! registry :deep-history @chart))
      (when-let [chart (resolve 'viz-history/parallel-history)]
        (scp/register-statechart! registry :parallel-history @chart))
      (when-let [chart (resolve 'viz-history/multilevel-history)]
        (scp/register-statechart! registry :multilevel-history @chart))
      (when-let [chart (resolve 'viz-history/history-with-final)]
        (scp/register-statechart! registry :history-with-final @chart))
      (catch Exception e
        (.println System/err (str "Could not load viz-history examples: " (.getMessage e)))))

    ;; Load simple state examples
    (try
      (require 'viz-simple)
      (when-let [chart (resolve 'viz-simple/basic-states)]
        (scp/register-statechart! registry :basic-states @chart))
      (when-let [chart (resolve 'viz-simple/compound-states)]
        (scp/register-statechart! registry :compound-states @chart))
      (when-let [chart (resolve 'viz-simple/conditional-transitions)]
        (scp/register-statechart! registry :conditional-transitions @chart))
      (when-let [chart (resolve 'viz-simple/multi-transition)]
        (scp/register-statechart! registry :multi-transition @chart))
      (when-let [chart (resolve 'viz-simple/eventless-transitions)]
        (scp/register-statechart! registry :eventless-transitions @chart))
      (when-let [chart (resolve 'viz-simple/deeply-nested)]
        (scp/register-statechart! registry :deeply-nested @chart))
      (catch Exception e
        (.println System/err (str "Could not load viz-simple examples: " (.getMessage e)))))

    ;; Load label demo example
    (try
      (require 'viz-labels)
      (when-let [chart (resolve 'viz-labels/label-demo)]
        (scp/register-statechart! registry :label-demo @chart))
      (catch Exception e
        (.println System/err (str "Could not load viz-labels example: " (.getMessage e)))))

    ;; Load parallel state examples
    (try
      (require 'viz-parallel)
      (when-let [chart (resolve 'viz-parallel/simple-parallel)]
        (scp/register-statechart! registry :simple-parallel @chart))
      (when-let [chart (resolve 'viz-parallel/multi-region-parallel)]
        (scp/register-statechart! registry :multi-region-parallel @chart))
      (when-let [chart (resolve 'viz-parallel/nested-parallel)]
        (scp/register-statechart! registry :nested-parallel @chart))
      (when-let [chart (resolve 'viz-parallel/parallel-with-completion)]
        (scp/register-statechart! registry :parallel-with-completion @chart))
      (when-let [chart (resolve 'viz-parallel/complex-parallel)]
        (scp/register-statechart! registry :complex-parallel @chart))
      (catch Exception e
        (.println System/err (str "Could not load viz-parallel examples: " (.getMessage e)))))

    registry))

(defn create-empty-registry
  "Creates an empty registry for use with custom charts."
  []
  (lmr/new-registry))
