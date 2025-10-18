(ns com.fulcrologic.statecharts.events-spec
  (:require
    [com.fulcrologic.statecharts.events :as evts]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "name-match?"
  (assertions
    "ALL events match a nil candidate"
    (evts/name-match? nil :a.b) => true
    (evts/name-match? nil :a) => true
    (evts/name-match? nil :c/a.b) => true
    "nil events are undefined (a bug), and never match."
    (evts/name-match? :a.b nil) => false
    (evts/name-match? nil nil) => false)
  (component "Keyword candidates"
    (assertions
      "match if it contains strange characters"
      (evts/name-match? :done.invoke :done.invoke.:child/future) => true
      (evts/name-match? :done.invoke.* :done.invoke.:child/future) => true
      "match if its prefix matches"
      (evts/name-match? :a :a.b) => true
      (evts/name-match? :a.b :a.b) => true
      "match even if they end with an explicit wildcard"
      (evts/name-match? :* :a.b) => true
      (evts/name-match? :a.* :a.b) => true
      (evts/name-match? :a.b.* :a.b) => true))
  (component "Sequence of candidates"
    (assertions
      "matches if ANY of the elements match"
      (evts/name-match? [:a] :a.b) => true
      (evts/name-match? [:b :a.b] :a.b) => true
      (evts/name-match? [:b :c] :a.b) => false))
  (component "Namespace support"
    (assertions
      "Namespaces must match exactly"
      (evts/name-match? :x.y.z/a :x.y.z/a.b) => true
      (evts/name-match? :x.y/a :x.y.z/a.b) => false
      (evts/name-match? :x.y/a.b :x.y.z/a.b) => false))
  (component "Events as event-name"
    (assertions
      "Use the event name for matching"
      (evts/name-match? :a (evts/new-event :a.b)) => true
      (evts/name-match? :a.b (evts/new-event :a.b)) => true
      (evts/name-match? :x/a.b (evts/new-event :a.b)) => false
      (evts/name-match? :x/a.b (evts/new-event :x/a.b)) => true)))

(specification "invoke-done-event"
  (assertions "Prepends done.invoke to invoke id"
    (evts/invoke-done-event :foo) => :done.invoke.foo
    (evts/invoke-done-event :foo/bar) => :done.invoke.foo/bar
    (evts/invoke-done-event "foo") => :done.invoke.foo
    (evts/invoke-done-event 1) => :done.invoke.1
    (evts/invoke-done-event (parse-uuid "b6883c0a-0342-4007-9966-bc2dfa6b109e")) => :done.invoke.b6883c0a-0342-4007-9966-bc2dfa6b109e))
