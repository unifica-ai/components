(ns unifica.connector
  (:require [unifica.biff.xtdb :as bxt :refer [q]]
            [clojure.tools.logging :as log]))

(def connectors-by-kw
  '{:find [(pull install [:xt/id :install/connector {:install/user-id [:xt/id]} ])]
    :in [conn]
    :where [[install :install/connector conn]]})

(defn users
  "Gets a list of users for a connector"
  [{:keys [biff/db]} kw]
    (let [users (q db connectors-by-kw kw)]
      (when (seq users) (apply mapcat :install/user-id users))))

(defn install
  "Installs a connector for in-session user"
  [{:keys [session] :as ctx} kw]
  (let [uid ((some-fn :as-uid :uid) session)]
    (log/info (str "Installing " kw " for " uid))
    (bxt/submit-tx ctx [{:db/doc-type :install
                          :db.op/upsert {:install/user-id uid}
                          :install/connector kw}])))

(defn installed? [{:keys [biff/db session]} kw]
  (let [uid ((some-fn :as-uid :uid) session)]
    (some-> (bxt/lookup db '[:xt/id] :install/connector kw :install/user-id uid)
            first val)))

(defn uninstall
  "Uninstalls a connector"
  [{:keys [session] :as ctx} kw]
  (let [uid ((some-fn :as-uid :uid) session)]
    (when-some [id (installed? ctx kw)]
      (log/info (str "Uninstalling " kw " for " uid))
      (bxt/submit-tx ctx [{:db/op :delete
                            :xt/id id}]))))
