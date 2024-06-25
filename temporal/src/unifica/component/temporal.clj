(ns unifica.component.temporal
  (:require
   [temporal.client.core :as tc]
   [temporal.tls :as tls]
   [clojure.tools.logging :as log]))

(defn use-temporal [{:temporal/keys [target namespace cert-path key-path enable-https] :as ctx}]
  (let [ssl-context (delay (tls/new-ssl-context {:cert-path cert-path
                                                 :key-path key-path}))
        client (tc/create-client (cond-> {:target target
                                          :namespace namespace
                                          :enable-https enable-https}
                                   enable-https (assoc :ssl-context @ssl-context)))]
    (log/info "Connected to Temporal endpoint" target "namespace" namespace)
    (assoc ctx :temporal/client client)))

(defn create-workflow
  "A convenience method for temporal.client.core/create-workflow."
  [{:temporal/keys [client]} workflow opts]
  (tc/create-workflow client workflow opts))

(defn start [workflow params]
  (tc/start workflow params))

(defn get-result [workflow]
  (tc/get-result workflow))
