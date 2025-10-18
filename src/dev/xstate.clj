(ns xstate
  "ALPHA. Conversion of this library's state charts into a js notation that can
  be used for playing with and diagramming using XState tools."
  (:require
    [camel-snake-kebab.core :as csk]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]))

(defn jssym [n]
  (when n
    (let [base  (name (symbol n))
          alt   (if (str/ends-with? base "?")
                  (str "is-" base)
                  base)
          final (some-> alt
                  (csk/->camelCase)
                  (str/replace #"[^-a-zA-Z0-9_]" ""))]
      final)))

(defn collapse-initial-state
  "If `s` (element or id) is an initial state on `c` that contains only a simple transition, then
  return that target state's id. Otherwise returns s."
  [c s]
  (if (chart/initial? c s)
    (let [{:keys [children]} (chart/element c s)
          {:keys [target] :as t} (first children)]
      (if (and (= 1 (count children)) (= 1 (count target)))
        (first target)
        s))
    s))

(defn chart-to-tree [chart {:keys [children] :as node}]
  (let [child-elements (mapv (fn [child-id]
                               (chart-to-tree chart (chart/element chart child-id)))
                         children)]
    (-> node
      (dissoc ::sc/elements-by-id)
      (dissoc ::sc/ids-in-document-order)
      (assoc :children child-elements))))

(defn condition-symbol [{condition :cond
                         :keys     [event target] :as t}]
  (let [c (or (:diagram/condition t) (when condition 'unlabled))]
    (when c
      (jssym c))))

(defn actions [chart candidates]
  (vec
    (keep
      (fn [c]
        (let [{:keys [diagram/label diagram/expression event expr node-type]} (chart/element chart c)]
          (case node-type
            :script (or label expression (str "script"))
            :raise (str "raise('" (name event) "')")
            :send (str "send('" (name event) "')")
            nil)))
      candidates)))

(defn transitions [c state]
  (let [transitions-by-event (group-by :event
                               (map #(chart/element c %) (chart/transitions c state)))
        event-map            (reduce-kv
                               (fn [acc event ts]
                                 (assoc acc (if event (name event) "")
                                            (vec
                                              (keep
                                                (fn [t]
                                                  (let [{:keys [target children] :as t} (chart/element c t)
                                                        s       (condition-symbol t)
                                                        actions (actions c children)]
                                                    (cond
                                                      (and target (= 1 (count target)))
                                                      (cond-> {:target (str "#" (name (first target)))}
                                                        s (assoc :cond s)
                                                        (seq actions) (assoc :actions actions))
                                                      (and (empty? target) event (seq actions))
                                                      {:actions actions}
                                                      :else nil)))
                                                ts))))
                               {}
                               transitions-by-event)]
    (when (seq event-map)
      {:on event-map})))

(declare statechart->xstate)

(defn state-map
  [c states]
  (reduce
    (fn [acc s]
      (let [{:keys [id] :as state} (chart/element c s)
            atomic?      (chart/atomic-state? c s)
            ;transitions  (transitions c state)
            substates    (chart/child-states c id)
            substate-map (reduce
                           (fn [acc s]
                             (merge acc (statechart->xstate c s)))
                           {}
                           substates)]
        (assoc acc (name id)
                   (if atomic?
                     (transitions c state)
                     (merge
                       (transitions c state)
                       substate-map)))))
    {}
    states))

(defn state->xstate
  "Convert a state node"
  [c node]
  (if (chart/atomic-state? c node)
    (merge {:id (name (chart/element-id c node))}
      (transitions c node))
    (let [initial (chart/initial-element c node)]
      (merge
        (transitions c node)
        {:initial (name (:id initial))
         :id      (name (chart/element-id c node))
         :states  (reduce
                    (fn [acc s]
                      (assoc acc (name (chart/element-id c s))
                                 (state->xstate c s)))
                    {}
                    (chart/child-states c node))}))))

(defn mock-guards
  "Convert conditionals on states into symbols"
  [{::sc/keys [elements-by-id] :as c}]
  (let [cts (into []
              (filter
                #(and
                   (contains? % :cond)
                   (= :transition (:node-type %))))
              (vals elements-by-id))]
    (reduce
      (fn [acc t]
        (if-let [s (condition-symbol t)]
          (assoc acc s 'CONSTANTLY_TRUE)
          acc))
      {}
      cts)))

(comment
  (js/console.log
    (clj->js {'isFullyPaid          'CONSTANTLY_TRUE,
              'isInGracePeriod      'CONSTANTLY_TRUE,
              'isPastDueBalance     'CONSTANTLY_TRUE,
              'isOverdue            'CONSTANTLY_TRUE,
              'isNoPastDueBalance   'CONSTANTLY_TRUE,
              'isBalanceDue         'CONSTANTLY_TRUE,
              'isNoDocumentsCreated 'CONSTANTLY_TRUE,
              'isAutoRenew          'CONSTANTLY_TRUE,
              'isPlanPaidFor        'CONSTANTLY_TRUE,
              'isViablePlan         'CONSTANTLY_TRUE}))
  (js/console.log
    (clj->js
      {:initial "initial97394",
       :id      "ROOT",
       :states  {"initial97394"   {:id "initial97394", :on {"" [{:target "#primary-region"}]}},
                 "primary-region" {:on      {"enable-auto-renew"   [{:actions ["s.expr/enable-auto-renew!"]}],
                                             "disable-auto-renew"  [{:actions ["s.expr/disable-auto-renew!"]}],
                                             "select-plan"         [{:actions ["s.expr/select-plan!"]}],
                                             "error.dian-failure"  [{:actions ["send('retry-dian-send')"]}],
                                             "error.email-failure" [{:actions ["send('retry-email')"]}],
                                             "retry-dian-send"     [{:actions ["(s.expr/send-invoice-to-dian! env event)"]}],
                                             "retry-email"         [{:actions ["(s.expr/send-invoice-to-customer! env event)"]}]},
                                   :initial "initial97396",
                                   :id      "primary-region",
                                   :states  {"starting-condition" {:id "starting-condition",
                                                                   :on {"" [{:target "#paid", :cond "isPlanPaidFor"}
                                                                            {:target "#grace-period", :cond "isInGracePeriod"}
                                                                            {:target "#overdue", :cond "isOverdue"}
                                                                            {:target "#inactive"}]}},
                                             "inactive"           {:id "inactive",
                                                                   :on {"subscribe" [{:target  "#subscribed",
                                                                                      :cond    "isViablePlan",
                                                                                      :actions ["raise('enable-auto-renew')" "script"]}]}},
                                             "subscribed"         {:on      {"payment-made" [{:target "#unpaid-condition"}]},
                                                                   :initial "initial97398",
                                                                   :id      "subscribed",
                                                                   :states  {"unpaid"       {:on      {"subscription-expired" [{:target  "#paid",
                                                                                                                                :cond    "isNoPastDueBalance",
                                                                                                                                :actions ["raise('subscription-expired')"]}
                                                                                                                               {:target  "#abandoned",
                                                                                                                                :actions ["script"]}],
                                                                                                       "grace-period-expired" [{:target "#paid",
                                                                                                                                :cond   "isFullyPaid"}
                                                                                                                               {:target  "#overdue",
                                                                                                                                :cond    "isBalanceDue",
                                                                                                                                :actions ["script"]}],
                                                                                                       "reminder-timeout"     [{:target "#paid",
                                                                                                                                :cond   "isFullyPaid"}],
                                                                                                       "adjust-plan"          [{:actions ["script"]}],
                                                                                                       "payment-made"         [{:target "#unpaid-condition"}]},
                                                                                             :initial "initial97400",
                                                                                             :id      "unpaid",
                                                                                             :states  {"unpaid-condition"  {:id "unpaid-condition",
                                                                                                                            :on {"" [{:target "#paid",
                                                                                                                                      :cond   "isPlanPaidFor"}
                                                                                                                                     {:target "#grace-period"}]}},
                                                                                                       "grace-period"      {:id "grace-period",
                                                                                                                            :on {"reminder-timeout" [{:target  "#grace-period",
                                                                                                                                                      :cond    "isBalanceDue",
                                                                                                                                                      :actions ["script"]}
                                                                                                                                                     {:target "#paid",
                                                                                                                                                      :cond   "isFullyPaid"}]}},
                                                                                                       "overdue"           {:id "overdue",
                                                                                                                            :on {"support.renew-grace-period" [{:target "#grace-period"}],
                                                                                                                                 "payment-made"               [{:target "#overdue-condition"}]}},
                                                                                                       "overdue-condition" {:id "overdue-condition",
                                                                                                                            :on {"" [{:target "#paid",
                                                                                                                                      :cond   "isPlanPaidFor"}
                                                                                                                                     {:target "#overdue"}]}},
                                                                                                       "initial97400"      {:id "initial97400",
                                                                                                                            :on {"" [{:target "#unpaid-condition"}]}}}},
                                                                             "paid"         {:id "paid",
                                                                                             :on {"payment-rescinded"    [{:target "#overdue-condition"}],
                                                                                                  "subscribe"            [{:target  "#unpaid",
                                                                                                                           :actions ["script"]}],
                                                                                                  "subscription-expired" [{:target  "#unpaid",
                                                                                                                           :cond    "isAutoRenew",
                                                                                                                           :actions ["script"]}
                                                                                                                          {:target  "#inactive",
                                                                                                                           :actions ["raise('disable-auto-renew')"]}]}},
                                                                             "initial97398" {:id "initial97398",
                                                                                             :on {"" [{:target "#unpaid"}]}}}},
                                             "abandoned"          {:initial "initial97402",
                                                                   :id      "abandoned",
                                                                   :states  {"abandoned-condition"         {:id "abandoned-condition",
                                                                                                            :on {"" [{:target "#forgiveable",
                                                                                                                      :cond   "isNoDocumentsCreated"}
                                                                                                                     {:target "#payment-required"}]}},
                                                                             "payment-required"            {:id "payment-required",
                                                                                                            :on {"payment-made" [{:target "#abandoned-payment-condition"}],
                                                                                                                 "subscribe"    [{:target  "#inactive",
                                                                                                                                  :cond    "isNoPastDueBalance",
                                                                                                                                  :actions ["script"
                                                                                                                                            "raise('subscribe')"]}]}},
                                                                             "abandoned-payment-condition" {:id "abandoned-payment-condition",
                                                                                                            :on {"" [{:target "#payment-required",
                                                                                                                      :cond   "isPastDueBalance"}
                                                                                                                     {:target "#inactive"}]}},
                                                                             "forgiveable"                 {:id "forgiveable",
                                                                                                            :on {"subscribe" [{:target  "#overdue",
                                                                                                                               :actions ["script"]}]}},
                                                                             "initial97402"                {:id "initial97402",
                                                                                                            :on {"" [{:target "#abandoned-condition"}]}}}},
                                             "initial97396"       {:id "initial97396", :on {"" [{:target "#starting-condition"}]}}}}}}

      ))
  (mock-guards subscription-management)

  (chart-to-tree subscription-management subscription-management))
