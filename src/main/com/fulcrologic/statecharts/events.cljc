(ns com.fulcrologic.statecharts.events
  (:require
    [clojure.spec.alpha :as s]
    com.fulcrologic.statecharts.specs
    [com.fulcrologic.guardrails.core :refer [>defn => ? >defn-]]
    [com.fulcrologic.statecharts :as sc]
    [clojure.string :as str]))

(>defn name-match?
  "Match event names.

  `event` is either the full name of an event, which can have dot-separated segments,
  or an event map containing the event name at `::sc/event-name`.
  `candidates` is either a single keyword, or a vector of them. These are all treated
   as event prefixes, and are treated as-if they have `.*` on the end.

   If the event-name is a keyword with a namespace, then a candidate ONLY matches
   if it has that EXACT namespace. This is an extension of the event naming
   defined by SCXML.

   Returns `true` if any of the `candidates` is a prefix of `event-name` (on
   a dot-separated segment-by-segment basis)"
  [candidates event]
  [(? (s/or :k ::sc/event-name
        :ks (s/every ::sc/event-name))) (? ::sc/event-or-name) => boolean?]
  (let [event-name (if (map? event) (::sc/event-name event) event)]
    (boolean
      (and
        (not (nil? event-name))
        (or
          (nil? candidates)
          (boolean
            (if (keyword? candidates)
              (and
                (= (namespace candidates) (namespace event-name))
                (let [candidate  (remove #(= "*" %) (str/split (name candidates) #"\."))
                      event-name (str/split (name event-name) #"\.")]
                  (and
                    (<= (count candidate) (count event-name))
                    (= candidate (subvec event-name 0 (count candidate))))))
              (some #(name-match? % event-name) (seq candidates)))))))))

(>defn new-event
  "Generate a new event containing `data`. It is recommended that `data` be
   easily serializable (plain EDN without code) if you wish to use it
   in a distributed or durable environment."
  [event-name data]
  [::sc/event-name (? map?) => ::sc/event]
  (merge data
    {::sc/event-name event-name}))
