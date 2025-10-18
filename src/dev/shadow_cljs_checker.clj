(ns shadow-cljs-checker
  "Check shadow-cljs build status programmatically via nREPL"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [nrepl.core :as nrepl]))

(defn read-shadow-nrepl-port
  "Read the shadow-cljs nREPL port from .shadow-cljs/nrepl.port"
  []
  (try
    (-> ".shadow-cljs/nrepl.port"
      io/file
      slurp
      str/trim
      parse-long)
    (catch Exception e
      (throw (ex-info "Could not read shadow-cljs nREPL port. Is shadow-cljs running?"
               {:file ".shadow-cljs/nrepl.port"}
               e)))))

(defn connect-to-shadow
  "Create a connection to the shadow-cljs nREPL server"
  []
  (let [port (read-shadow-nrepl-port)]
    (nrepl/connect :port port)))

(defn get-build-warnings
  "Get all warnings for a specific build ID.

   Returns a vector of warning maps with:
   - :warning - keyword like :undeclared-var
   - :line - line number
   - :column - column number
   - :msg - warning message
   - :resource-name - file path (e.g., 'dataico/client.cljs')
   - :file - absolute file path
   - :source-excerpt - map with :before, :line, :after context"
  [build-id]
  (let [conn   (connect-to-shadow)
        client (nrepl/client conn 10000)
        code   (format "(let [worker (shadow.cljs.devtools.api/get-worker %s)
                            state @(:state-ref worker)
                            build-state (:build-state state)
                            build-info (:shadow.build/build-info build-state)
                            sources (:sources build-info)
                            all-warnings (mapcat :warnings sources)]
                        (vec all-warnings))"
                 build-id)
        result (-> (nrepl/message client {:op "eval" :code code})
                 nrepl/response-values
                 first)]
    result))

(defn check-build
  "Check if a build has any warnings or errors.

   Returns:
   {:status :ok | :warnings | :errors
    :count  <number of issues>
    :issues <vector of warning/error maps>}"
  [build-id]
  (try
    (let [warnings (get-build-warnings build-id)]
      {:status (if (empty? warnings) :ok :warnings)
       :count  (count warnings)
       :issues warnings})
    (catch Exception e
      {:status    :error
       :error     (.getMessage e)
       :exception e})))

(defn print-warning
  "Pretty-print a single warning"
  [{:keys [warning line column msg resource-name file source-excerpt]}]
  (println)
  (println (format "Warning %s in %s at %d:%d" warning resource-name line column))
  (println msg)
  (when source-excerpt
    (let [{:keys [before line after]} source-excerpt]
      (println)
      (doseq [l (take-last 2 before)]
        (println "  " l))
      (println "=>" line)
      (doseq [l (take 2 after)]
        (println "  " l)))))

(defn check-and-report
  "Check build and print warnings if any exist.

   Returns exit code: 0 if OK, 1 if warnings, 2 if errors"
  [build-id]
  (let [{:keys [status count issues]} (check-build build-id)]
    (case status
      :ok
      (do
        (println (format "✓ Build %s compiled successfully with no warnings" build-id))
        0)

      :warnings
      (do
        (println (format "⚠ Build %s has %d warning%s:"
                   build-id count (if (= count 1) "" "s")))
        (doseq [w issues]
          (print-warning w))
        1)

      :error
      (do
        (println (format "✗ Error checking build %s:" build-id))
        (println (:error issues))
        2))))

(defn get-compile-status
  "Get the current compilation status for a build.

   Returns:
   {:stage          - keyword like :flush, :compile-prepare, etc.
    :compile-cycle  - integer tracking number of compilations
    :compile-start  - timestamp when compilation started
    :compile-finish - timestamp when compilation finished
    :autobuild      - boolean if watching is enabled
    :compiling?     - boolean if currently compiling}"
  [build-id]
  (let [conn   (connect-to-shadow)
        client (nrepl/client conn 10000)
        code   (format "(let [worker (shadow.cljs.devtools.api/get-worker %s)
                            state @(:state-ref worker)
                            build-state (:build-state state)
                            start (:compile-start build-state)
                            finish (:compile-finish build-state)]
                        {:stage (:shadow.build/stage build-state)
                         :compile-cycle (:shadow.build.api/compile-cycle build-state)
                         :compile-start start
                         :compile-finish finish
                         :autobuild (:autobuild state)
                         :compiling? (or (nil? finish) (< finish start))})"
                 build-id)
        result (-> (nrepl/message client {:op "eval" :code code})
                 nrepl/response-values
                 first)]
    result))

(defn wait-for-build
  "Wait for a build to complete compilation.

   Options:
   - :timeout-ms - Maximum time to wait in milliseconds (default: 30000)
   - :poll-interval-ms - How often to check status (default: 500)

   Returns the final compile status or throws if timeout exceeded."
  [build-id & {:keys [timeout-ms poll-interval-ms]
               :or   {timeout-ms       30000
                      poll-interval-ms 500}}]
  (let [start-time (System/currentTimeMillis)
        deadline   (+ start-time timeout-ms)]
    (loop []
      (let [status (get-compile-status build-id)]
        (if (:compiling? status)
          (do
            (when (> (System/currentTimeMillis) deadline)
              (throw (ex-info "Timeout waiting for build to complete"
                       {:build-id build-id
                        :timeout  timeout-ms
                        :status   status})))
            (Thread/sleep poll-interval-ms)
            (recur))
          status)))))

(defn wait-and-check
  "Wait for build to complete, then check for warnings/errors.

   Returns the same map as check-build:
   {:status :ok | :warnings | :errors
    :count  <number of issues>
    :issues <vector of warning/error maps>}"
  [build-id & {:keys [timeout-ms poll-interval-ms]
               :or   {timeout-ms       30000
                      poll-interval-ms 500}}]
  (try
    (wait-for-build build-id
      :timeout-ms timeout-ms
      :poll-interval-ms poll-interval-ms)
    (check-build build-id)
    (catch Exception e
      {:status    :error
       :error     (.getMessage e)
       :exception e})))

(defn wait-and-report
  "Wait for build to complete, then print warnings if any exist.

   Returns exit code: 0 if OK, 1 if warnings, 2 if errors"
  [build-id & {:keys [timeout-ms poll-interval-ms]
               :or   {timeout-ms       30000
                      poll-interval-ms 500}}]
  (let [{:keys [status count issues error]} (wait-and-check build-id
                                              :timeout-ms timeout-ms
                                              :poll-interval-ms poll-interval-ms)]
    (case status
      :ok
      (do
        (println (format "✓ Build %s compiled successfully with no warnings" build-id))
        0)

      :warnings
      (do
        (println (format "⚠ Build %s has %d warning%s:"
                   build-id count (if (= count 1) "" "s")))
        (doseq [w issues]
          (print-warning w))
        1)

      :error
      (do
        (println (format "✗ Error checking build %s:" build-id))
        (println error)
        2))))

(comment
  ;; Usage examples:

  ;; Check the main build (immediate check)
  (check-build :main)
  ;; => {:status :ok, :count 0, :issues []}

  ;; Get raw warnings
  (get-build-warnings :main)
  ;; => []

  ;; Check and print report (immediate check)
  (check-and-report :main)
  ;; ✓ Build :main compiled successfully with no warnings
  ;; => 0

  ;; Get current compilation status
  (get-compile-status :main)
  ;; => {:stage :flush, :compile-cycle 12, :compile-start 1760809300011,
  ;;     :compile-finish 1760809300070, :autobuild true, :compiling? false}

  ;; Wait for build to complete, then check (recommended)
  (wait-and-check :main)
  ;; => {:status :ok, :count 0, :issues []}

  ;; Wait for build to complete, then print report (recommended)
  (wait-and-report :main)
  ;; ✓ Build :main compiled successfully with no warnings
  ;; => 0

  ;; With custom timeout
  (wait-and-report :main :timeout-ms 60000 :poll-interval-ms 1000)

  ;; When there are warnings:
  (wait-and-report :main)
  ;; ⚠ Build :main has 1 warning:
  ;;
  ;; Warning :undeclared-var in dataico/client.cljs at 51:3
  ;; Use of undeclared Var dataico.client/XXX-UNDEFINED-SYMBOL
  ;;
  ;;    (defn ^:export init []
  ;;      (log/info "Application starting." (version/current-version))
  ;; =>   XXX-UNDEFINED-SYMBOL
  ;;      (register-statecharts!)
  ;;      (.addEventListener js/window "beforeunload"
  ;; => 1
  )
