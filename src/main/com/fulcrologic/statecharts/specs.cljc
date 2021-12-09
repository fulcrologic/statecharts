(ns com.fulcrologic.statecharts.specs
  (:require
    [com.fulcrologic.statecharts :as sc]
    [clojure.spec.alpha :as s]))

(s/def ::sc/document-order #{:breadth-first :depth-first})
(s/def ::sc/node-type #{:state :parallel :final :history :invoke
                        :on-entry :on-exit :transition})
(s/def ::sc/id keyword?)
(s/def ::sc/element (s/keys
                      :req-un [::sc/node-type ::sc/id]
                      :opt-un []))
(s/def ::sc/elements-by-id (s/map-of keyword? ::sc/element))

