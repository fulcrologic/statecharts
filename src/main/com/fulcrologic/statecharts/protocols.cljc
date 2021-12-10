(ns com.fulcrologic.statecharts.protocols)

(defprotocol DataModel
  (get-data [data-model machine context]
    "Returns the data model as a map that makes sense for the given context.")
  (save-data! [data-model machine context data]
    "Replaces the data model for the given context with `data` (a map)."))

(defprotocol )
