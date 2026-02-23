(ns com.fulcrologic.statecharts.working-memory-store.local-memory-store
  "A working memory store that uses a simple internal atom to track working memory by session id."
  (:require
    [com.fulcrologic.statecharts.protocols :as sp]))

(defrecord LocalMemoryStore
  ;; NOTE: Individual operations are atomic via `swap!`, but read-then-process-then-save
  ;; cycles at the event loop level are NOT atomic. Event loops must serialize per-session
  ;; event processing to prevent stale overwrites.
  [storage]
  sp/WorkingMemoryStore
  (get-working-memory [_ _ session-id]
    (get @storage session-id))
  (save-working-memory! [_ _ session-id wmem]
    (swap! storage assoc session-id wmem))
  (delete-working-memory! [_ _ session-id]
    (swap! storage dissoc session-id)))

(defn new-store
  "Create a new local memory store for chart working memory."
  []
  (->LocalMemoryStore (atom {})))
