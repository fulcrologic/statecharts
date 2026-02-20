(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-test
  (:require
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [fulcro-spec.core :refer [=> assertions component specification]]))

;; ---------------------------------------------------------------------------
;; element-segment
;; ---------------------------------------------------------------------------

(specification "element-segment"
  (assertions
    "returns :route/segment when present"
    (ruc/element-segment {:route/target :ns/Foo :route/segment "bar"}) => "bar"

    "falls back to (name target) when no :route/segment"
    (ruc/element-segment {:route/target :ns/Foo}) => "Foo"

    "returns nil when no target and no segment"
    (ruc/element-segment {:id :something}) => nil))

;; ---------------------------------------------------------------------------
;; path-from-configuration with :route/segment overrides
;; ---------------------------------------------------------------------------

(specification "path-from-configuration with :route/segment"
  (let [elements {:root   {:id :root :children [:parent :leaf]}
                  :parent {:id :parent :parent :root :route/target :ns/Parent :route/segment "admin" :children [:leaf]}
                  :leaf   {:id :leaf :parent :parent :route/target :ns/Detail}}]
    (assertions
      "uses custom segment for parent, default for leaf"
      (ruc/path-from-configuration elements #{:root :parent :leaf}) => "/admin/Detail"))

  (let [elements {:root {:id :root :children [:a]}
                  :a    {:id :a :parent :root :route/target :ns/Dashboard}}]
    (assertions
      "default segment is target name when no :route/segment"
      (ruc/path-from-configuration elements #{:root :a}) => "/Dashboard"))

  (let [elements {:root {:id :root :children [:a]}
                  :a    {:id :a :parent :root :route/target :ns/Dashboard :route/segment "home"}}]
    (assertions
      "custom segment replaces target name"
      (ruc/path-from-configuration elements #{:root :a}) => "/home")))

;; ---------------------------------------------------------------------------
;; find-target-by-leaf-name with :route/segment
;; ---------------------------------------------------------------------------

(specification "find-target-by-leaf-name with :route/segment"
  (let [elements {:a {:id :a :route/target :ns/Foo :route/segment "bar"}
                  :b {:id :b :route/target :ns/Baz}}]
    (assertions
      "matches by custom segment"
      (ruh/find-target-by-leaf-name elements "bar") => :a

      "does not match by target name when custom segment present"
      (ruh/find-target-by-leaf-name elements "Foo") => nil

      "matches default target name when no custom segment"
      (ruh/find-target-by-leaf-name elements "Baz") => :b

      "returns nil for no match"
      (ruh/find-target-by-leaf-name elements "nope") => nil)))

(specification "current-url-path (cross-platform)"
  (assertions
    "extracts path segments from an absolute URL"
    (ruh/current-url-path "http://localhost/Foo/Bar") => ["Foo" "Bar"]

    "extracts single segment"
    (ruh/current-url-path "http://localhost/Dashboard") => ["Dashboard"]

    "returns empty for root path"
    (ruh/current-url-path "http://localhost/") => []

    "works with path-only input"
    (ruh/current-url-path "/Foo/Bar") => ["Foo" "Bar"]))
