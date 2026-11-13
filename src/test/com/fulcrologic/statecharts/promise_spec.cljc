(ns com.fulcrologic.statecharts.promise-spec
  "Behavioral tests for the promise adapter.

   ## Modes

   The adapter chooses between two implementations at load time:

   * **`:promesa`** — promesa is on the classpath. Every primitive is a
     direct alias for `promesa.core`. Our `p/X` and the consumer's
     `promesa.core/X` are literally the same fn/macro and produce the
     same promise type, so 'interop' tests become tautological at runtime
     — but they still verify call sites compose without error.

   * **`:native`** — promesa not loadable (notably babashka). Our
     IPending-backed impl is used standalone. Native-mode coverage lives
     in the bb smoke test at `test-bb/smoke.clj`.

   These CLJ specs run under `:dev`, so they execute in `:promesa` mode.
   The first spec asserts that fact explicitly.

   ## Platform split

   * **CLJ** — Has `p/await!`, so specs read straight-line: build a
     chain, await the result, assert.
   * **CLJS** — `js/Promise` cannot be blocked on, so specs use
     `cljs.test/async` and chain assertions inside `.then`. Our CLJS impl
     uses js/Promise natively; promesa-cljs's `PromiseImpl` interops
     through the standard JS Thenable contract."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    [fulcro-spec.core :refer [=> assertions specification]]
    #?@(:clj  [[promesa.core :as pp]]
        :cljs [[cljs.test :refer-macros [async deftest]]
               [promesa.core :as pp]])))

;; ============================================================================
;; CLJ tests — promesa-delegation mode (the :dev test environment)
;; ============================================================================

#?(:clj
   (specification "adapter mode and delegation invariants"
     (assertions
       "Adapter selected promesa-delegation mode because promesa is on the :dev classpath"
       p/mode => :promesa
       "p/resolved produces a real promesa promise (CompletableFuture on JVM)"
       (instance? java.util.concurrent.CompletionStage (p/resolved 1)) => true
       "Our p/promise? predicate is the same fn as promesa.core/promise?"
       (identical? p/promise? pp/promise?) => true
       "Our p/then is the same fn as promesa.core/then"
       (identical? p/then pp/then) => true)))

#?(:clj
   (specification "resolved + then"
     (let [result (p/await! (p/then (p/resolved 1) inc))]
       (assertions
         "Chains synchronous transformations and produces the final value"
         result => 2))))

#?(:clj
   (specification "then flattens when callback returns a promise"
     (let [result (p/await! (p/then (p/resolved 5)
                              (fn [v] (p/resolved (* v 10)))))]
       (assertions
         "A nested promise returned from `f` is unwrapped, not delivered as a value"
         result => 50))))

#?(:clj
   (specification "rejected + catch"
     (let [recovered (p/await! (p/catch (p/rejected (ex-info "boom" {:k 1}))
                                 (fn [e] {:msg (ex-message e) :data (ex-data e)})))]
       (assertions
         "Rejection is delivered to the catch handler as the captured exception"
         recovered => {:msg "boom" :data {:k 1}}))))

#?(:clj
   (specification "rejection skips intermediate then handlers"
     (let [caught (try
                    (p/await! (-> (p/rejected (ex-info "fail" {:tag 9}))
                                (p/then (fn [_] :should-not-run))
                                (p/then (fn [_] :still-not))))
                    (catch Exception e (ex-data e)))]
       (assertions
         "An unhandled rejection propagates through the whole chain"
         caught => {:tag 9}))))

#?(:clj
   (specification "catch can recover and resume"
     (let [result (p/await! (-> (p/rejected (ex-info "oops" {}))
                              (p/catch (fn [_] :recovered))
                              (p/then name)))]
       (assertions
         "Once caught, the chain resumes with the catch handler's value"
         result => "recovered"))))

#?(:clj
   (specification "create resolves and rejects"
     (assertions
       "Resolution path delivers the supplied value"
       (p/await! (p/create (fn [resolve _] (resolve :ok)))) => :ok)
     (let [thrown (try
                    (p/await! (p/create (fn [_ reject] (reject (ex-info "no" {:rc 2})))))
                    (catch Exception e (ex-data e)))]
       (assertions
         "Rejection path surfaces the exception when the promise is awaited"
         thrown => {:rc 2}))))

#?(:clj
   (specification "let macro: sequential binding"
     (let [result (p/await! (p/let [a (p/resolved 1)
                                    b (p/resolved 2)
                                    c (p/resolved (+ a b))]
                              [a b c]))]
       (assertions
         "Each binding can use the resolved value of the previous one"
         result => [1 2 3]))))

#?(:clj
   (specification "let macro: rejection short-circuits"
     (let [side-effect (atom :not-touched)
           thrown      (try
                         (p/await!
                           (p/let [a (p/resolved 1)
                                   _ (p/rejected (ex-info "stop" {}))
                                   _ (reset! side-effect :should-not-be-set)]
                             a))
                         (catch Exception e (ex-message e)))]
       (assertions
         "An error in one binding halts further binding evaluation"
         thrown => "stop"
         "Subsequent bindings never execute"
         @side-effect => :not-touched))))

#?(:clj
   (specification "promise? predicate"
     (assertions
       "True for our native resolved promise"
       (p/promise? (p/resolved 1)) => true
       "True for our native rejected promise"
       (p/promise? (p/rejected (ex-info "" {}))) => true
       "False for a plain value"
       (p/promise? 42) => false
       "False for nil"
       (p/promise? nil) => false)))

#?(:clj
   (specification "wrap lifts plain values"
     (assertions
       "Plain values become resolved promises"
       (p/promise? (p/wrap 1)) => true
       (p/await! (p/wrap 1)) => 1
       "Existing promises pass through unchanged"
       (let [pp (p/resolved :x)]
         (identical? pp (p/wrap pp))) => true)))

#?(:clj
   (specification "do! captures sync throws as rejections"
     (let [ok (p/await! (p/do! (+ 1 2)))
           ng (try
                (p/await! (p/do! (throw (ex-info "thrown" {:tag :throw}))))
                (catch Exception e (ex-data e)))]
       (assertions
         "Successful body resolves with the value"
         ok => 3
         "A throw is captured and surfaces as a rejection"
         ng => {:tag :throw}))))

#?(:clj
   (specification "await! blocks until resolution and rethrows on rejection"
     (let [p (p/create (fn [resolve _]
                         (clojure.core/future
                           (Thread/sleep 30)
                           (resolve :late))))]
       (assertions
         "Awaiting a deferred resolution returns the eventual value"
         (p/await! p) => :late))
     (let [bad (p/create (fn [_ reject]
                           (clojure.core/future
                             (Thread/sleep 30)
                             (reject (ex-info "after-delay" {:k 5})))))
           thrown (try (p/await! bad)
                       (catch Exception e (ex-data e)))]
       (assertions
         "Awaiting a delayed rejection throws the original exception"
         thrown => {:k 5}))))

;; ----------------------------------------------------------------------------
;; CLJ — Forward-direction bridge: OUR adapter consuming promesa promises.
;; ----------------------------------------------------------------------------

#?(:clj
   (specification "promesa delegation: adapter is in :promesa mode"
     (assertions
       "Adapter chose promesa-delegation because promesa is on the :dev classpath"
       p/mode => :promesa)))

#?(:clj
   (specification "promesa bridge: promise? recognizes promesa promises"
     (assertions
       "A promesa.core/resolved value is detected as a promise"
       (p/promise? (pp/resolved 1)) => true
       "A promesa.core/rejected value is detected as a promise"
       (p/promise? (pp/rejected (ex-info "x" {}))) => true)))

#?(:clj
   (specification "promesa bridge: our `then` dispatches through promesa for promesa inputs"
     (let [result (p/await! (p/then (pp/resolved 7) inc))]
       (assertions
         "A consumer's promesa promise is chained correctly by our adapter"
         result => 8))))

#?(:clj
   (specification "promesa bridge: our `catch` dispatches through promesa for promesa rejections"
     (let [recovered (p/await! (p/catch (pp/rejected (ex-info "p-fail" {:p 1}))
                                 (fn [e] (ex-data e))))]
       (assertions
         "A consumer's promesa rejection is caught by our adapter"
         recovered => {:p 1}))))

#?(:clj
   (specification "promesa bridge: mixed-chain flattening (promesa → native)"
     (let [result (p/await! (-> (pp/resolved 2)
                              (p/then (fn [v] (p/resolved (* v 3))))
                              (p/then inc)))]
       (assertions
         "A native promise returned from a promesa-rooted chain is flattened, not nested"
         result => 7))))

#?(:clj
   (specification "promesa bridge: mixed-chain flattening (native → promesa)"
     (let [result (p/await! (-> (p/resolved 4)
                              (p/then (fn [v] (pp/resolved (* v 5))))
                              (p/then inc)))]
       (assertions
         "A promesa promise returned from a native-rooted chain is flattened, not nested"
         result => 21))))

#?(:clj
   (specification "promesa bridge: deep mixed chain stays sane"
     (let [result (p/await! (-> (pp/resolved 1)
                              (p/then (fn [v] (p/resolved (inc v))))
                              (p/then (fn [v] (pp/resolved (inc v))))
                              (p/then (fn [v] (p/resolved (inc v))))
                              (p/then (fn [v] (pp/resolved (inc v))))))]
       (assertions
         "Alternating native/promesa returns chain end-to-end"
         result => 5))))

#?(:clj
   (specification "promesa bridge: bridged rejection propagates across both promise kinds"
     (let [thrown (try
                    (p/await! (-> (pp/resolved 1)
                                (p/then (fn [_] (pp/rejected (ex-info "bridge-fail" {:b 1}))))
                                (p/then (fn [_] :should-not-run))))
                    (catch Exception e (ex-data e)))]
       (assertions
         "An error injected from a promesa callback reaches the awaiting caller"
         thrown => {:b 1}))))

#?(:clj
   (specification "promesa bridge: our `let` macro tolerates promesa-valued bindings"
     (let [result (p/await! (p/let [a (pp/resolved 10)
                                    b (p/resolved 20)
                                    c (pp/resolved (+ a b))]
                              c))]
       (assertions
         "A `p/let` chain with mixed promise types resolves correctly"
         result => 30))))

#?(:clj
   (specification "promesa bridge: a promesa promise can be passed to statecharts internal call sites"
     (let [user-result (pp/resolved {:user :alice})
           statechart-pipeline (-> user-result
                                 (p/then (fn [user] (assoc user :level 7)))
                                 (p/then (fn [user] (p/resolved (assoc user :verified? true)))))]
       (assertions
         "End-to-end: consumer-promesa input, statecharts native+then chain, awaited value"
         (p/await! statechart-pipeline) => {:user :alice :level 7 :verified? true}))))

;; ----------------------------------------------------------------------------
;; CLJ — Reverse-direction bridge: promesa.core APIs operating on OUR
;; native promises. This requires the bridge to extend
;; `promesa.protocols/IPromiseFactory` on `clojure.lang.IPending` at
;; namespace-init time.
;; ----------------------------------------------------------------------------

#?(:clj
   (specification "reverse interop: promesa.core/then on our native resolved promise"
     (assertions
       "Promesa's `then` coerces our native promise and chains correctly"
       (pp/await (pp/then (p/resolved 5) inc)) => 6)))

#?(:clj
   (specification "reverse interop: promesa.core/then on our native rejected promise"
     ;; pp/await RETURNS the captured exception on rejection (it does not
     ;; throw). p/await! throws. Use our await! so the assertion proves
     ;; the rejection actually propagated through promesa's chain.
     (let [thrown (try (p/await! (pp/then (p/rejected (ex-info "x" {:k 9})) inc))
                       (catch Exception e (ex-data e)))]
       (assertions
         "A rejected native promise propagates as a promesa rejection"
         thrown => {:k 9}))))

#?(:clj
   (specification "reverse interop: promesa.core/catch recovers from our native rejection"
     (assertions
       "Promesa's `catch` sees the original exception captured in our Rejection sentinel"
       (pp/await (pp/catch (p/rejected (ex-info "boom" {:tag 7}))
                           (fn [e] (ex-data e)))) => {:tag 7})))

#?(:clj
   (specification "reverse interop: promesa.core/let binds our native promise values"
     (assertions
       "A `pp/let` form with native-promise RHS resolves to the underlying value"
       (pp/await (pp/let [a (p/resolved 1)
                          b (p/resolved 2)]
                   (+ a b))) => 3
       "Mixed native + promesa bindings interleave correctly inside `pp/let`"
       (pp/await (pp/let [a (pp/resolved 1)
                          b (p/resolved 2)
                          c (pp/resolved 3)]
                   [a b c])) => [1 2 3])))

#?(:clj
   (specification "reverse interop: chaining promesa.core/then onto a native chain"
     (assertions
       "Promesa's then composes with the value coming from p/then on a native promise"
       (pp/await (pp/then (p/then (p/resolved 1) inc) inc)) => 3)))

#?(:clj
   (specification "reverse interop: chaining our then onto a promesa chain"
     (assertions
       "Our adapter composes with the value coming from promesa.core/then on a promesa promise"
       (p/await! (p/then (pp/then (pp/resolved 1) inc) inc)) => 3)))

#?(:clj
   (specification "reverse interop: end-to-end consumer scenario"
     (let [statecharts-native-result
           (p/then (p/resolved {:user :alice}) #(assoc % :level 7))

           consumer-pipeline
           (pp/let [user statecharts-native-result
                    enriched (pp/resolved (assoc user :verified? true))]
             enriched)]
       (assertions
         "Consumer-side promesa.let consumes a statecharts-side native promise transparently"
         (pp/await consumer-pipeline) => {:user :alice :level 7 :verified? true}))))

;; ----------------------------------------------------------------------------
;; Reverse interop, deeper protocol coverage. Promesa exposes a handful of
;; entry points that bypass the IPromiseFactory coercion the usual call
;; sites use; the bridge extends IAwaitable, IState, and IPromise on
;; clojure.lang.IPending to plug those gaps so our native promises behave
;; like first-class promesa promises everywhere a consumer might touch them.
;; ----------------------------------------------------------------------------

#?(:clj
   (specification "reverse interop: promesa.core/promise? recognizes our native promise"
     (assertions
       "Our resolved native promise satisfies promesa's IPromise check"
       (pp/promise? (p/resolved 1)) => true
       "Our rejected native promise also satisfies it"
       (pp/promise? (p/rejected (ex-info "x" {}))) => true
       "A plain value still isn't a promise on either side"
       (pp/promise? 42) => false)))

#?(:clj
   (specification "reverse interop: promesa.core/await / await! on our native promise"
     (let [p   (p/resolved :ok)
           rej (p/rejected (ex-info "explode" {:reason :test}))]
       (assertions
         "pp/await! returns the value of our resolved native promise"
         (pp/await! p) => :ok
         "pp/await also returns the value of our resolved native promise"
         (pp/await p) => :ok)
       (let [thrown (try (pp/await! rej)
                         (catch Exception e (ex-data e)))
             returned (pp/await rej)]
         (assertions
           "pp/await! throws the underlying exception on a rejected native promise"
           thrown => {:reason :test}
           "pp/await returns the exception on a rejected native promise (no throw)"
           (ex-data returned) => {:reason :test})))))

#?(:clj
   (specification "reverse interop: promesa.core/await with timeout on our native promise"
     ;; Our async-resolving native promise can be awaited with a Duration
     (let [later (p/create (fn [resolve! _]
                             (clojure.core/future
                               (Thread/sleep 30)
                               (resolve! :late))))
           result (pp/await! later (java.time.Duration/ofMillis 500))]
       (assertions
         "Promesa's timeout-aware await! still works through our IAwaitable extension"
         result => :late))
     (let [never     (p/create (fn [_ _] nil))
           ;; pp/await! swallows TimeoutException and returns nil on timeout
           ;; (see promesa.core source). So a timed-out native promise yields
           ;; nil from pp/await!.
           timed-out (pp/await! never (java.time.Duration/ofMillis 30))]
       (assertions
         "A native promise that never resolves trips promesa's timeout (yields nil)"
         timed-out => nil))))

#?(:clj
   (specification "reverse interop: promesa.core IState introspection on our native promise"
     (let [done    (p/resolved 99)
           failed  (p/rejected (ex-info "broken" {:b 1}))
           pending (p/create (fn [_ _] nil))]
       (assertions
         "Resolved native promise reports resolved? true"
         (pp/resolved? done) => true
         "Rejected native promise reports rejected? true"
         (pp/rejected? failed) => true
         "Unsettled native promise reports pending? true"
         (pp/pending? pending) => true
         "Extract returns the value of a resolved native promise"
         (pp/extract done) => 99
         "Extract returns the underlying exception data for a rejected one"
         (ex-data (pp/extract failed)) => {:b 1}))))

#?(:clj
   (specification "reverse interop: promesa.core/all over a vector of native promises"
     (let [result (pp/await (pp/all [(p/resolved 1) (p/resolved 2) (p/resolved 3)]))]
       (assertions
         "pp/all coerces each native promise and yields the vector of resolved values"
         result => [1 2 3]))))

#?(:clj
   (specification "reverse interop: promesa.core/race over native promises"
     (let [slow (p/create (fn [resolve! _]
                            (clojure.core/future (Thread/sleep 200) (resolve! :slow))))
           fast (p/create (fn [resolve! _]
                            (clojure.core/future (Thread/sleep 10)  (resolve! :fast))))
           winner (pp/await (pp/race [slow fast]))]
       (assertions
         "pp/race resolves with the first native promise to settle"
         winner => :fast))))

#?(:clj
   (specification "reverse interop: promesa.core/handle on our native promise"
     ;; pp/handle is invoked for both resolution and rejection; it gets
     ;; [value error] and returns either kind of result.
     (let [ok  (pp/await (pp/handle (p/resolved 5)
                                    (fn [v _e] (if v (* v 10) :nope))))
           bad (pp/await (pp/handle (p/rejected (ex-info "x" {:k 9}))
                                    (fn [_v e] (ex-data e))))]
       (assertions
         "pp/handle on resolved native promise sees the value"
         ok => 50
         "pp/handle on rejected native promise sees the exception"
         bad => {:k 9}))))

#?(:clj
   (specification "reverse interop: promesa.core/chain composes across native and promesa values"
     (let [result (pp/await (pp/chain (p/resolved 1)
                                      inc
                                      (fn [v] (p/resolved (inc v)))
                                      (fn [v] (pp/resolved (inc v)))))]
       (assertions
         "pp/chain folds then over a mixed sequence of native and promesa returns"
         result => 4))))

;; ============================================================================
;; CLJS tests — adapter delegates to js/Promise; promesa-cljs ALSO bottoms
;; out on js/Promise, so cross-library composition is automatic (same type).
;; Tests run async via cljs.test/async because js/Promise resolution is
;; deferred to the microtask queue, even for already-settled promises.
;; ============================================================================

#?(:cljs
   (specification "cljs: promise? recognizes js/Promise"
     (assertions
       "True for an adapter-resolved promise"
       (p/promise? (p/resolved 1)) => true
       "True for an adapter-rejected promise"
       (p/promise? (p/rejected (ex-info "x" {}))) => true
       "True for a promesa-cljs promise (same js/Promise underneath)"
       (p/promise? (pp/resolved 1)) => true
       "True for a raw js/Promise built directly"
       (p/promise? (js/Promise.resolve 1)) => true
       "False for plain values"
       (p/promise? 42) => false
       (p/promise? nil) => false
       (p/promise? "x") => false)))

;; CLJS async tests are plain `deftest`s: fulcro-spec's `specification` discards its body's value, so an `async`
;; body inside it never runs and the test passes without asserting anything.

#?(:cljs
   ;; cljs: then chain resolves to expected value
   (deftest cljs-then-chain-resolves-to-expected-value
     (async done
       (-> (p/then (p/resolved 1) inc)
         (p/then (fn [v]
                   (assertions
                     "A simple then chain produces inc(1) = 2"
                     v => 2)
                   (done)))))))

#?(:cljs
   ;; cljs: then flattens when callback returns a promise
   (deftest cljs-then-flattens-when-callback-returns-a-promise
     (async done
       (-> (p/then (p/resolved 5) (fn [v] (p/resolved (* v 10))))
         (p/then (fn [v]
                   (assertions
                     "A nested promise return is unwrapped automatically"
                     v => 50)
                   (done)))))))

#?(:cljs
   ;; cljs: rejected + catch recovers
   (deftest cljs-rejected-catch-recovers
     (async done
       (-> (p/catch (p/rejected (ex-info "boom" {:k 1}))
             (fn [e] (ex-data e)))
         (p/then (fn [v]
                   (assertions
                     "Catch handler receives the rejection's exception"
                     v => {:k 1})
                   (done)))))))

#?(:cljs
   ;; cljs: rejection skips intermediate then handlers
   (deftest cljs-rejection-skips-intermediate-then-handlers
     (async done
       (-> (p/then (p/rejected (ex-info "fail" {:tag 9})) (fn [_] :should-not-run))
         (p/catch (fn [e]
                    (assertions
                      "Unhandled rejection propagates to the first catch"
                      (ex-data e) => {:tag 9})
                    (done)))))))

#?(:cljs
   ;; cljs: create + resolve
   (deftest cljs-create-resolve
     (async done
       (-> (p/create (fn [resolve _] (resolve :ok)))
         (p/then (fn [v]
                   (assertions
                     "Resolution path delivers the supplied value"
                     v => :ok)
                   (done)))))))

#?(:cljs
   ;; cljs: create + reject
   (deftest cljs-create-reject
     (async done
       (-> (p/create (fn [_ reject] (reject (ex-info "no" {:rc 2}))))
         (p/catch (fn [e]
                    (assertions
                      "Rejection path delivers the exception to catch"
                      (ex-data e) => {:rc 2})
                    (done)))))))

#?(:cljs
   ;; cljs: let macro: sequential binding
   (deftest cljs-let-macro-sequential-binding
     (async done
       (-> (p/let [a (p/resolved 1)
                   b (p/resolved 2)
                   c (p/resolved (+ a b))]
             [a b c])
         (p/then (fn [v]
                   (assertions
                     "Each binding can use the previous resolved value"
                     v => [1 2 3])
                   (done)))))))

#?(:cljs
   ;; cljs: let macro: rejection short-circuits
   (deftest cljs-let-macro-rejection-short-circuits
     (async done
       (let [side-effect (atom :not-touched)]
         (-> (p/let [a (p/resolved 1)
                     _ (p/rejected (ex-info "stop" {}))
                     _ (reset! side-effect :should-not-be-set)]
               a)
           (p/catch (fn [e]
                      (assertions
                        "An error in one binding halts further binding evaluation"
                        (ex-message e) => "stop"
                        "Subsequent bindings never execute"
                        @side-effect => :not-touched)
                      (done))))))))

#?(:cljs
   ;; cljs: do! captures sync throws as rejections
   (deftest cljs-do-captures-sync-throws-as-rejections
     (async done
       (-> (p/do! (throw (ex-info "thrown" {:tag :throw})))
         (p/catch (fn [e]
                    (assertions
                      "Synchronous throw inside do! body becomes a rejection"
                      (ex-data e) => {:tag :throw})
                    (done)))))))

#?(:cljs
   ;; cljs: wrap lifts plain values
   (deftest cljs-wrap-lifts-plain-values
     (async done
       (-> (p/wrap 99)
         (p/then (fn [v]
                   (assertions
                     "Plain value 99 lifted into a promise resolves to 99"
                     (p/promise? (p/wrap 99)) => true
                     v => 99)
                   (done)))))))

;; ----------------------------------------------------------------------------
;; CLJS — promesa-cljs interop is implicit: both libraries produce
;; js/Promise. These tests exercise common cross-library patterns to make
;; sure nothing snags on the seam.
;; ----------------------------------------------------------------------------

#?(:cljs
   ;; cljs interop: our then on a promesa-cljs promise
   (deftest cljs-interop-our-then-on-a-promesa-cljs-promise
     (async done
       (-> (p/then (pp/resolved 7) inc)
         (p/then (fn [v]
                   (assertions
                     "p/then composes with a promesa-cljs promise"
                     v => 8)
                   (done)))))))

#?(:cljs
   ;; cljs interop: promesa-cljs then on our adapter promise
   (deftest cljs-interop-promesa-cljs-then-on-our-adapter-promise
     (async done
       (-> (pp/then (p/resolved 5) inc)
         (pp/then (fn [v]
                    (assertions
                      "pp/then composes with our adapter's resolved promise"
                      v => 6)
                    (done)))))))

#?(:cljs
   ;; cljs interop: mixed chain alternating libraries
   (deftest cljs-interop-mixed-chain-alternating-libraries
     (async done
       (-> (pp/resolved 1)
         (p/then  (fn [v] (p/resolved (inc v))))
         (pp/then (fn [v] (pp/resolved (inc v))))
         (p/then  (fn [v] (pp/resolved (inc v))))
         (pp/then (fn [v]
                    (assertions
                      "Alternating p/then and pp/then resolves end-to-end"
                      v => 4)
                    (done)))))))

#?(:cljs
   ;; cljs interop: rejection crosses the library seam
   (deftest cljs-interop-rejection-crosses-the-library-seam
     (async done
       (-> (pp/resolved 1)
         (p/then  (fn [_] (pp/rejected (ex-info "p-fail" {:p 1}))))
         (pp/then (fn [_] :should-not-run))
         (p/catch (fn [e]
                    (assertions
                      "A promesa-cljs rejection reaches a p/catch handler downstream"
                      (ex-data e) => {:p 1})
                    (done)))))))
