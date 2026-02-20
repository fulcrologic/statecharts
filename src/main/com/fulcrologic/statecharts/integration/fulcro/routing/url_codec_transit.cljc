(ns com.fulcrologic.statecharts.integration.fulcro.routing.url-codec-transit
  "ALPHA. This namespace's API is subject to change.

   Default URLCodec implementation using transit+base64 encoding.
   URL shape: `/Seg1/Seg2?_p=<base64-encoded-transit>`."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [com.fulcrologic.statecharts.integration.fulcro.routing.url-codec :as url-codec]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.net URI)
                   (java.util Base64))))

;; ---------------------------------------------------------------------------
;; Base64 encoding (cross-platform)
;; ---------------------------------------------------------------------------

(defn- base64-encode
  "Encodes a UTF-8 string to Base64."
  [^String s]
  #?(:clj  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8"))
     :cljs (js/btoa (js/unescape (js/encodeURIComponent s)))))

(defn- base64-decode
  "Decodes a Base64 string to UTF-8."
  [^String s]
  #?(:clj  (String. (.decode (Base64/getDecoder) ^String s) "UTF-8")
     :cljs (js/decodeURIComponent (js/escape (js/atob s)))))

;; ---------------------------------------------------------------------------
;; Transit+Base64 param encoding
;; ---------------------------------------------------------------------------

(defn encode-params-base64
  "Encode a CLJ(S) map as transit->base64. Returns raw base64 (no URI encoding).
   The caller is responsible for URI-encoding if the result will be placed in a URL.
   Returns nil if `params` is nil or empty."
  [params]
  (when (seq params)
    (let [transit-str (ft/transit-clj->str params)]
      (base64-encode transit-str))))

(defn decode-params-base64
  "Decode a base64->transit param string back into a CLJ(S) map. Expects raw base64 input
   (not URI-encoded). Returns nil on nil/empty input or decode failure."
  [encoded]
  (when (seq encoded)
    (try
      (let [transit-str (base64-decode encoded)]
        (ft/transit-str->clj transit-str))
      (catch #?(:clj Exception :cljs :default) e
        (log/error e "Failed to decode base64 params")
        nil))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- extract-query-param
  "Extracts the value of `param-name` from a query string. Returns nil if not found."
  [query-string param-name]
  (when (seq query-string)
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= k param-name) v)))
      (str/split query-string #"&"))))

(defn- parse-href-parts
  "Parses an href into `{:segments [...] :p-param ...}`. Cross-platform helper for decode."
  [href]
  (let [#?(:cljs url :clj uri) #?(:cljs (if (str/starts-with? href "/")
                                           (js/URL. href "http://localhost")
                                           (js/URL. href))
                                   :clj  (URI. href))
        path-str #?(:cljs (.-pathname url) :clj (.getPath uri))
        segments (if (seq path-str)
                   (filterv #(not= "" %) (str/split path-str #"/"))
                   [])
        p-param  #?(:cljs (.get (.-searchParams url) "_p")
                    :clj  (extract-query-param (.getQuery uri) "_p"))]
    {:segments segments :p-param p-param}))

;; ---------------------------------------------------------------------------
;; TransitBase64Codec
;; ---------------------------------------------------------------------------

(defrecord TransitBase64Codec []
  url-codec/URLCodec
  (encode-url [_this {:keys [segments params route-elements]}]
    (let [seg-strs (mapv (fn [state-id]
                           (let [element (get route-elements state-id)]
                             (or (url-codec/element-segment element) (name state-id))))
                     segments)
          path     (str "/" (str/join "/" seg-strs))
          raw-b64  (encode-params-base64 params)
          encoded  (when raw-b64
                     #?(:cljs (js/encodeURIComponent raw-b64)
                        :clj  (java.net.URLEncoder/encode ^String raw-b64 "UTF-8")))]
      (if encoded
        (str path "?_p=" encoded)
        path)))
  (decode-url [_this href route-elements]
    (let [{:keys [segments p-param]} (parse-href-parts href)
          params    (when p-param (decode-params-base64 p-param))
          leaf-name (peek segments)
          leaf-id   (when leaf-name
                      (some (fn [[id element]]
                              (when (and (:route/target element)
                                      (= (url-codec/element-segment element) leaf-name))
                                id))
                        route-elements))]
      (when leaf-id
        {:leaf-id leaf-id
         :params  params}))))

(defn transit-base64-codec
  "Creates the default URLCodec that encodes params as transit->base64->uri-encode.
   URL shape: `/Seg1/Seg2?_p=<base64-encoded-transit>`."
  []
  (->TransitBase64Codec))
