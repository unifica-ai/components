(ns unifica.temporal
  (:require
   [clojure.tools.logging :as log]
   [temporal.client.core :as tc]
   [temporal.client.schedule :as ts]
   [temporal.client.worker :as twk]
   [temporal.tls :as tls])
  (:import
   (io.temporal.client.schedules ScheduleException)))

(defn- schedule-exists? [client id]
  (try
    (ts/describe client id)
    (catch ScheduleException _ nil)))

(defn use-temporal
  "Add Temporal client(s) to the context. If worker-options is given, start up workers with those options,
  passing in the context.

  Provide :use-schedules true or schedule-client-options to start up a schedule client and add it to the context.

  and schedule-client-options are given, also
  start a worker and schedule client with those options.

  https://cljdoc.org/d/io.github.manetu/temporal-sdk/1.0.2/api/temporal.client.options#schedule-client-options
  "
  [{:keys [tasks worker-options use-schedules schedule-client-options]}]
  (fn [{biff-modules :biff/modules
        app-modules :app/modules
        :temporal/keys [target namespace cert-path key-path enable-https] :as ctx}]
    (let [task-configs (or tasks (some->> (or biff-modules app-modules) deref (mapcat :tasks)))

          use-workers (seq worker-options)
          use-schedules (or use-schedules (seq schedule-client-options))

          ssl-context (delay (tls/new-ssl-context {:cert-path cert-path
                                                   :key-path key-path}))
          client (tc/create-client (cond-> {:target target
                                            :namespace namespace
                                            :enable-https enable-https}
                                     enable-https (assoc :ssl-context @ssl-context)))

          _ (log/info "Connected to Temporal endpoint" target "namespace" namespace)

          schedule-client (delay (ts/create-client
                                  (merge schedule-client-options
                                         (cond-> {:target target
                                                  :namespace namespace
                                                  :enable-https enable-https}
                                           enable-https (assoc :ssl-context @ssl-context)))))

          start-worker (fn [{:keys [task-queue] :as opts}]
                         (log/info "Starting worker for" task-queue)
                         (twk/start client (assoc opts :ctx ctx)))

          workers (delay (doall (mapv start-worker worker-options)))]
      (when use-schedules
        (doseq [[t cfg] task-configs
                :let [id (name t)]]
          (when-not (schedule-exists? @schedule-client id)
            (log/info "Scheduling" t)
            (ts/schedule @schedule-client id cfg))))
      (-> ctx
          (assoc :temporal/client client
                 :temporal/worker-options worker-options)
          (update :biff/stop conj #(run! twk/stop @workers))
          (cond->
              use-workers (assoc :temporal/workers @workers)
              use-schedules (assoc :temporal/schedule-client @schedule-client))))))
