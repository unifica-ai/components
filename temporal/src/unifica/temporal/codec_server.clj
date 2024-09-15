(ns unifica.temporal.codec-server
  (:require
   [com.biffweb :as biff]
   [taoensso.nippy :as nippy]
   [ring.middleware.cors :as cors]))

(defn decode [{:keys [headers params] :as ctx}]
  (let [{:keys [payloads]} params
        body {:payloads
              (for [payload payloads
                           :let [{:keys [metadata data]} payload
                                 encoded (.getBytes data)
                                 decoded (-> encoded
                                             biff/base64-decode
                                             nippy/thaw)]]
            
                    {:metadata {:encoding "application/json"}
                     :data decoded})}]
    {:headers {"content-type" "application/json"}
     :status 200
     :body body}))

(defn wrap-cors
  "Wrap CORS headers for Temporal codec server.

  See:
  
  https://docs.temporal.io/production-deployment/data-encryption
  "
  [handler]
  (fn [{:keys [temporal/ui-url
               temporal/codec-server-allow-methods
               temporal/codec-server-allow-headers] :as ctx}]
    (-> handler
        (cors/wrap-cors
         :access-control-allow-origin (re-pattern ui-url)
         :access-control-allow-headers codec-server-allow-headers
         :access-control-allow-methods codec-server-allow-methods)
        (as-> h (h ctx)))))

(def module
  {:api-routes [["/codec"
                 ["/decode" {:post decode}]]]})
