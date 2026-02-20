(ns com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history-spec
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.integration.fulcro.routing.simulated-history :as rsh]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as ruc]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit :as ruct]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-history :as ruh]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "SimulatedURLHistory"
  (component "push and stack tracking"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (assertions
        "initial entry plus 3 pushes yields 4-entry stack"
        (rsh/history-stack h) => ["/" "/a" "/b" "/c"]
        "cursor is at the end"
        (rsh/history-cursor h) => 3
        "current-href returns last pushed URL"
        (ruh/current-href h) => "/c"
        "current-index matches cursor value"
        (ruh/current-index h) => 3)))

  (component "go-back navigation"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      (ruh/go-back! h)
      (assertions
        "cursor decrements by 2 after two go-backs"
        (rsh/history-cursor h) => 1
        "current-href reflects the cursor position"
        (ruh/current-href h) => "/a"
        "stack is unchanged by navigation"
        (rsh/history-stack h) => ["/" "/a" "/b" "/c"])))

  (component "back then forward round-trip"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      (ruh/go-back! h)
      (ruh/go-forward! h)
      (ruh/go-forward! h)
      (assertions
        "round-trip returns to original position"
        (rsh/history-cursor h) => 3
        "current-href is back at /c"
        (ruh/current-href h) => "/c"
        "stack is not corrupted"
        (rsh/history-stack h) => ["/" "/a" "/b" "/c"])))

  (component "push after back truncates forward history"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      (ruh/go-back! h)
      (ruh/-push-url! h "/new")
      (assertions
        "forward entries are removed, new entry appended"
        (rsh/history-stack h) => ["/" "/a" "/new"]
        "cursor points to the new entry"
        (rsh/history-cursor h) => 2)))

  (component "replace-url changes URL but not index"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (let [idx-before (ruh/current-index h)]
        (ruh/-replace-url! h "/b-replaced")
        (assertions
          "URL at cursor is updated"
          (ruh/current-href h) => "/b-replaced"
          "index remains the same"
          (ruh/current-index h) => idx-before
          "stack reflects replacement"
          (rsh/history-stack h) => ["/" "/a" "/b-replaced"]))))

  (component "listener invocation on go-back"
    (let [h   (rsh/simulated-url-history)
          log (atom [])]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/set-popstate-listener! h (fn [idx] (swap! log conj idx)))
      (ruh/go-back! h)
      (assertions
        "listener receives the target entry's index"
        @log => [2]
        "cursor is at correct position when listener fires"
        (rsh/history-cursor h) => 2)))

  (component "listener invocation on go-forward"
    (let [h   (rsh/simulated-url-history)
          log (atom [])]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/go-back! h)
      (ruh/set-popstate-listener! h (fn [idx] (swap! log conj idx)))
      (ruh/go-forward! h)
      (assertions
        "listener receives the target entry's index on forward"
        @log => [2])))

  (component "go-back at cursor=0 is a no-op"
    (let [h   (rsh/simulated-url-history)
          log (atom [])]
      (ruh/set-popstate-listener! h (fn [idx] (swap! log conj idx)))
      (ruh/go-back! h)
      (assertions
        "cursor stays at 0"
        (rsh/history-cursor h) => 0
        "listener is NOT invoked"
        @log => []
        "current-href unchanged"
        (ruh/current-href h) => "/")))

  (component "go-forward at end is a no-op"
    (let [h   (rsh/simulated-url-history)
          log (atom [])]
      (ruh/-push-url! h "/a")
      (ruh/set-popstate-listener! h (fn [idx] (swap! log conj idx)))
      (ruh/go-forward! h)
      (assertions
        "cursor stays at end"
        (rsh/history-cursor h) => 1
        "listener is NOT invoked"
        @log => [])))

  (component "monotonic index tracking"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (assertions
        "entries have monotonically increasing indices"
        (mapv :index (rsh/history-entries h)) => [0 1 2 3])
      (ruh/go-back! h)
      (ruh/go-back! h)
      (ruh/-push-url! h "/new")
      (assertions
        "new entry after truncation continues the counter"
        (mapv :index (rsh/history-entries h)) => [0 1 4])))

  (component "custom initial URL"
    (let [h (rsh/simulated-url-history "/app/dashboard")]
      (assertions
        "starts with the provided URL"
        (ruh/current-href h) => "/app/dashboard"
        "initial index is 0"
        (ruh/current-index h) => 0)))

  (component "replaceState after back preserves forward history"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      (ruh/go-back! h)
      ;; Now at /a (cursor=1). Replace /a with /a-settled (simulates child chart init)
      (ruh/-replace-url! h "/a-settled")
      (assertions
        "current URL is the replaced value"
        (ruh/current-href h) => "/a-settled"
        "cursor has not moved"
        (rsh/history-cursor h) => 1
        "forward entries /b and /c are still present"
        (rsh/history-stack h) => ["/" "/a-settled" "/b" "/c"])
      ;; Verify forward still works
      (ruh/go-forward! h)
      (assertions
        "forward navigates to the preserved /b entry"
        (ruh/current-href h) => "/b"
        (rsh/history-cursor h) => 2)))

  (component "multiple replaceState after back preserves forward history"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      (ruh/go-back! h)
      ;; Multiple replacements at cursor=1 (simulates settling + child chart saves)
      (ruh/-replace-url! h "/a-v1")
      (ruh/-replace-url! h "/a-v2")
      (ruh/-replace-url! h "/a-v3")
      (assertions
        "current URL reflects last replace"
        (ruh/current-href h) => "/a-v3"
        "cursor unchanged after 3 replaces"
        (rsh/history-cursor h) => 1
        "forward entries survive all replacements"
        (rsh/history-stack h) => ["/" "/a-v3" "/b" "/c"])
      (ruh/go-forward! h)
      (ruh/go-forward! h)
      (assertions
        "can still reach the end after multiple replaces"
        (ruh/current-href h) => "/c"
        (rsh/history-cursor h) => 3)))

  (component "replaceState then pushState after back"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      (ruh/go-back! h)
      ;; Replace (settling) then push (new user navigation)
      (ruh/-replace-url! h "/a-settled")
      (ruh/-push-url! h "/new-dest")
      (assertions
        "pushState after replace truncates forward entries"
        (rsh/history-stack h) => ["/" "/a-settled" "/new-dest"]
        "cursor is at the new entry"
        (rsh/history-cursor h) => 2
        "current URL is the pushed destination"
        (ruh/current-href h) => "/new-dest")))

  (component "interleaved back/forward with replace"
    (let [h (rsh/simulated-url-history)]
      ;; Build A->B->C->D
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/-push-url! h "/d")
      ;; Back twice to B
      (ruh/go-back! h)
      (ruh/go-back! h)
      (assertions
        "at /b after two backs"
        (ruh/current-href h) => "/b"
        (rsh/history-cursor h) => 2)
      ;; Replace B with B'
      (ruh/-replace-url! h "/b-prime")
      (assertions
        "replace updates current entry"
        (ruh/current-href h) => "/b-prime")
      ;; Forward should go to C (forward history preserved)
      (ruh/go-forward! h)
      (assertions
        "forward reaches /c despite replace at /b"
        (ruh/current-href h) => "/c"
        (rsh/history-cursor h) => 3)
      ;; Now push /e -- should truncate /d
      (ruh/-push-url! h "/e")
      (assertions
        "push after forward truncates remaining forward entries"
        (rsh/history-stack h) => ["/" "/a" "/b-prime" "/c" "/e"]
        (rsh/history-cursor h) => 4)))

  (component "rapid back-back-forward with listener"
    (let [h   (rsh/simulated-url-history)
          log (atom [])]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/-push-url! h "/d")
      (ruh/set-popstate-listener! h (fn [idx] (swap! log conj idx)))
      ;; Rapid: back, back, forward
      (ruh/go-back! h)
      (ruh/go-back! h)
      (ruh/go-forward! h)
      (assertions
        "listener fires three times with correct indices"
        (count @log) => 3
        "first back: index of /c entry"
        (first @log) => 3
        "second back: index of /b entry"
        (second @log) => 2
        "forward: index of /c entry again"
        (nth @log 2) => 3
        "cursor ends at /c"
        (ruh/current-href h) => "/c"
        (rsh/history-cursor h) => 3)))

  (component "replaceState preserves entry index"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/go-back! h)
      ;; At /b, cursor=2. Capture the entry's index (monotonic counter)
      (let [idx-before (ruh/current-index h)]
        (ruh/-replace-url! h "/b-replaced")
        (assertions
          "entry index is unchanged after replace"
          (ruh/current-index h) => idx-before
          "URL is updated"
          (ruh/current-href h) => "/b-replaced"
          "the entry's index in the entries vector is the same object"
          (:index (nth (rsh/history-entries h) (rsh/history-cursor h))) => idx-before))))

  (component "push after replace-after-back truncation"
    (let [h (rsh/simulated-url-history)]
      (ruh/-push-url! h "/a")
      (ruh/-push-url! h "/b")
      (ruh/-push-url! h "/c")
      (ruh/-push-url! h "/d")
      ;; Go back to /b
      (ruh/go-back! h)
      (ruh/go-back! h)
      ;; Replace /b with /b-settled (settling)
      (ruh/-replace-url! h "/b-settled")
      ;; Push /x (new user navigation)
      (ruh/-push-url! h "/x")
      (assertions
        "push truncates everything after the replace position"
        (rsh/history-stack h) => ["/" "/a" "/b-settled" "/x"]
        "cursor is at /x"
        (rsh/history-cursor h) => 3
        "replaced entry is preserved in the stack"
        (nth (rsh/history-stack h) 2) => "/b-settled"
        "forward history is empty (go-forward is a no-op)"
        (rsh/history-cursor h) => (dec (count (rsh/history-stack h))))))

;; ---------------------------------------------------------------------------
;; TransitBase64Codec tests (R8)
;; ---------------------------------------------------------------------------

(specification "TransitBase64Codec"
  (component "round-trip with params"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:page-a {:route/target 'com.example/PageA}
                          :page-b {:route/target 'com.example/PageB}}
          context        {:segments       [:page-a :page-b]
                          :params         {:page-b {:id 42 :name "test"}}
                          :route-elements route-elements}
          url            (ruc/encode-url codec context)
          decoded        (ruc/decode-url codec url route-elements)]
      (assertions
        "encode produces a URL with path segments"
        (str/starts-with? url "/PageA/PageB") => true
        "encode includes a _p query parameter for params"
        (str/includes? url "?_p=") => true
        "decode recovers the correct leaf-id"
        (:leaf-id decoded) => :page-b
        "decode recovers the params map keyed by state-id"
        (:params decoded) => {:page-b {:id 42 :name "test"}})))

  (component "round-trip without params"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:page-a {:route/target 'com.example/PageA}
                          :page-b {:route/target 'com.example/PageB}}
          context        {:segments       [:page-a :page-b]
                          :params         nil
                          :route-elements route-elements}
          url            (ruc/encode-url codec context)
          decoded        (ruc/decode-url codec url route-elements)]
      (assertions
        "encode produces a clean path without query string"
        url => "/PageA/PageB"
        "decode recovers the correct leaf-id"
        (:leaf-id decoded) => :page-b
        "decode returns nil params when none were encoded"
        (:params decoded) => nil)))

  (component "route-elements are accessible in encoding context"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:page-a {:route/target 'com.example/PageA :custom/key "metadata"}
                          :page-b {:route/target 'com.example/PageB}}
          context        {:segments       [:page-a :page-b]
                          :params         nil
                          :route-elements route-elements}
          url            (ruc/encode-url codec context)]
      (assertions
        "custom keys on elements do not affect URL generation"
        url => "/PageA/PageB"
        "route-elements map contains the custom key"
        (get-in route-elements [:page-a :custom/key]) => "metadata")))

  (component "custom :route/segment in encode"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:page-a {:route/target 'com.example/PageA :route/segment "home"}
                          :page-b {:route/target 'com.example/PageB :route/segment "about"}}
          context        {:segments       [:page-a :page-b]
                          :params         nil
                          :route-elements route-elements}
          url            (ruc/encode-url codec context)]
      (assertions
        "uses :route/segment strings instead of target names"
        url => "/home/about")))

  (component "decode with custom :route/segment"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:page-a {:route/target 'com.example/PageA :route/segment "home"}
                          :page-b {:route/target 'com.example/PageB :route/segment "about"}}
          decoded        (ruc/decode-url codec "/home/about" route-elements)]
      (assertions
        "decodes leaf matching by :route/segment"
        (:leaf-id decoded) => :page-b)))

  (component "decode returns nil for unrecognized URL"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:page-a {:route/target 'com.example/PageA}}
          decoded        (ruc/decode-url codec "/unknown/path" route-elements)]
      (assertions
        "returns nil when no route element matches the leaf segment"
        decoded => nil)))

  (component "single segment route"
    (let [codec          (ruct/transit-base64-codec)
          route-elements {:dashboard {:route/target 'com.example/Dashboard}}
          context        {:segments       [:dashboard]
                          :params         nil
                          :route-elements route-elements}
          url            (ruc/encode-url codec context)
          decoded        (ruc/decode-url codec url route-elements)]
      (assertions
        "encodes single segment as /Dashboard"
        url => "/Dashboard"
        "decodes single segment correctly"
        (:leaf-id decoded) => :dashboard))))

;; ---------------------------------------------------------------------------
;; Base64 param encoding tests
;; ---------------------------------------------------------------------------

(specification "Base64 param encoding"
  (component "round-trip"
    (let [params  {:state-a {:user-id 123 :mode "edit"}}
          encoded (ruct/encode-params-base64 params)
          decoded (ruct/decode-params-base64 encoded)]
      (assertions
        "encoded value is a non-empty string"
        (string? encoded) => true
        (seq encoded) => (seq encoded)
        "round-trip preserves the original params"
        decoded => params)))

  (component "nil/empty returns nil"
    (assertions
      "nil params returns nil"
      (ruct/encode-params-base64 nil) => nil
      "empty map returns nil"
      (ruct/encode-params-base64 {}) => nil
      "decode of nil returns nil"
      (ruct/decode-params-base64 nil) => nil
      "decode of empty string returns nil"
      (ruct/decode-params-base64 "") => nil))

  (component "complex nested data survives round-trip"
    (let [params  {:state-x {:items [1 2 3] :nested {:deep true} :kw :hello}}
          encoded (ruct/encode-params-base64 params)
          decoded (ruct/decode-params-base64 encoded)]
      (assertions
        "nested structures preserved"
        decoded => params)))))
