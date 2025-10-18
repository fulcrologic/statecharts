(ns com.fulcrologic.statecharts.visualization.server
  "Public API for the statechart visualization HTTP server.

  Usage:

  (require '[com.fulcrologic.statecharts.visualization.server :as viz-server])

  ;; Start server
  (def server (viz-server/start-server! {:port 8080}))

  ;; Stop server
  (viz-server/stop-server! server)"
  (:require
    [clojure.java.io :as io]
    [cognitect.transit :as transit]
    [com.fulcrologic.statecharts.visualization.pathom :as pathom]
    [ring.adapter.jetty :as jetty]
    [ring.util.response :as response]
    [taoensso.timbre :as log])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (org.eclipse.jetty.server Server)))

(defn- serve-file
  "Serves a file from the filesystem, falling back to classpath resources"
  [path content-type]
  (let [;; Try filesystem first (for development)
        fs-file  (io/file "resources" path)
        ;; Fall back to classpath (for production jar)
        resource (io/resource path)]
    (cond
      ;; File exists on filesystem
      (and fs-file (.exists fs-file))
      (-> (response/response (slurp fs-file))
        (response/content-type content-type))

      ;; File exists as classpath resource
      resource
      (-> (response/response (slurp resource))
        (response/content-type content-type))

      ;; Not found
      :else
      nil)))

(defn static-file-handler
  "Serves static files from resources/public/viz/"
  [request]
  (let [uri  (:uri request)
        path (if (= uri "/")
               "public/viz/index.html"
               (str "public/viz" uri))]
    (cond
      ;; Serve HTML
      (or (= uri "/") (.endsWith uri ".html"))
      (or (serve-file path "text/html")
        (serve-file "public/viz/index.html" "text/html"))

      ;; Serve JavaScript
      (.endsWith uri ".js")
      (serve-file path "application/javascript")

      ;; Serve CSS
      (.endsWith uri ".css")
      (serve-file path "text/css")

      ;; SPA fallback - serve index.html for any other route
      :else
      (serve-file "public/viz/index.html" "text/html"))))

(defn- transit-read [body]
  "Read Transit data from request body."
  (let [in (ByteArrayInputStream. (.getBytes body))]
    (transit/read (transit/reader in :json))))

(defn- transit-write [data]
  "Write data as Transit JSON."
  (let [out    (ByteArrayOutputStream.)
        writer (transit/writer out :json)]
    (transit/write writer data)
    (.toString out)))

(defn pathom-handler
  "Handles Pathom API requests.

  Expects POST requests with Transit JSON body containing an EQL query.
  Returns Transit JSON response with query results."
  [parser env request]
  (try
    (let [body   (slurp (:body request))
          eql    (transit-read body)
          result (pathom/process-query parser env eql)]
      {:status  200
       :headers {"Content-Type" "application/transit+json"}
       :body    (transit-write result)})
    (catch Exception e
      (log/error e "Error handling Pathom request")
      {:status  400
       :headers {"Content-Type" "application/transit+json"}
       :body    (transit-write {:error (.getMessage e)})})))

(defn make-handler
  "Creates the Ring handler with the given env and Pathom parser."
  [parser env]
  (fn [request]
    (case (:uri request)
      "/api" (pathom-handler parser env request)
      (static-file-handler request))))

(defn add-cors-headers
  "Adds CORS headers to response"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (assoc-in response [:headers "Access-Control-Allow-Origin"] "*"))))

(defn wrap-handler
  "Wraps the handler with middleware.

  Creates a Pathom parser and wires it into the handler with the provided env."
  [env]
  (let [parser (pathom/make-parser)]
    (-> (make-handler parser env)
      (add-cors-headers))))

(defn start-server!
  "Start the visualization HTTP server.

  Options:
    :port (default 8080) - The port to listen on
    :env - Environment map with statechart protocols (registry, wmem-store, etc.)

  Returns a server instance that can be passed to stop-server!"
  ([] (start-server! {}))
  ([{:keys [port env] :or {port 8080 env {}}}]
   (log/info "Starting visualization server on port" port)
   (let [handler (wrap-handler env)
         server  (jetty/run-jetty handler
                   {:port  port
                    :join? false})]
     (log/info "Visualization server started at http://localhost:" port)
     (log/info "  - UI available at http://localhost:" port)
     (log/info "  - API endpoint at http://localhost:" port "/api")
     server)))

(defn stop-server!
  "Stop a running visualization server.

  Takes the server instance returned from start-server!"
  [^Server server]
  (when server
    (log/info "Stopping visualization server...")
    (.stop server)
    (log/info "Visualization server stopped")))

(comment
  ;; Example usage in REPL:
  (def server (start-server! {:port 8080}))

  ;; Later:
  (stop-server! server)

  ;; With env containing protocols:
  (require 'demo-registry)
  (require 'com.fulcrologic.statecharts.working-memory-store.local-memory-store)
  (let [my-registry (demo-registry/create-example-registry)]
    (def server
      (start-server!
        {:port 8081
         :env  {:com.fulcrologic.statecharts/statechart-registry my-registry}}))))
