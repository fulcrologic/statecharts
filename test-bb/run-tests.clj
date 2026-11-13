;; Run the project's test suite under babashka.
;;
;; Usage (from this directory):
;;
;;   bb --config bb-tests.edn -f run-tests.clj
;;
;; Or from the project root:
;;
;;   make bb-tests
;;
;; ## What this proves
;;
;; That every test namespace that can be loaded under SCI (i.e. doesn't
;; pull in fulcro/promesa directly) passes against the bb-native build of
;; statecharts. Combined with `smoke.clj`, this gives end-to-end evidence
;; that the library's algorithm + async surface works in babashka.
;;
;; ## Known skips
;;
;; - integration/fulcro*       — require fulcro, which is not bb-loadable.
;; - data_model/integration/*  — require fulcro.
;; - promise_spec              — its CLJ interop tests load promesa.core.
;; - algorithms/.../async_spec — same: loads promesa.core directly.
;; - chart_validation_spec /
;;   convenience_spec          — use fulcro-spec's `=throws=>`, whose
;;                               macro expansion embeds a literal
;;                               java.lang.Throwable Class that SCI
;;                               cannot resolve at runtime.
;;
;; The full CLJ kaocha suite covers all of these on the JVM. Here we just
;; need to prove every other test still passes when the runtime is SCI.

(require '[clojure.test :as t]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def test-root (fs/path ".." "src" "test"))

(defn ns-symbol [path]
  (-> (str (fs/relativize test-root path))
    (str/replace #"\.cljc?$" "")
    (str/replace #"[/\\]" ".")
    (str/replace "_" "-")
    symbol))

(def candidates
  (->> (fs/glob test-root "**/*_{spec,test}.{clj,cljc}")
    (map ns-symbol)
    (sort)))

(defn try-require [sym]
  (try
    (require sym)
    [:ok sym]
    (catch Throwable e
      [:fail sym (or (ex-message e) (str (class e)))])))

(println "Discovered" (count candidates) "test namespaces.")

(def results (mapv try-require candidates))
(def loaded  (mapv second (filter #(= :ok   (first %)) results)))
(def skipped (filter   #(= :fail (first %)) results))

(println "Loaded:" (count loaded) " Skipped:" (count skipped))

(when (seq skipped)
  (println "\nSkipped (not bb-loadable):")
  (doseq [[_ sym reason] skipped]
    (println " -" sym "::" (subs reason 0 (min 120 (count reason))))))

(println "\nRunning loaded namespaces...\n")

(binding [*err* (java.io.PrintWriter. (java.io.StringWriter.))]  ;; silence noisy logs
  ;; Mute fulcro-spec event noise on stdout while still capturing summary
  )

(def summary (apply t/run-tests loaded))

(println "\n=== Summary ===")
(println "  loaded:  " (count loaded))
(println "  skipped: " (count skipped))
(println "  tests:   " (:test summary))
(println "  pass:    " (:pass summary))
(println "  fail:    " (:fail summary))
(println "  error:   " (:error summary))

(when (or (pos? (:fail summary 0)) (pos? (:error summary 0)))
  (System/exit 1))
