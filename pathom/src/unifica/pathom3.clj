(ns unifica.pathom3
  #_(:require [com.wsscode.pathom3.connect.indexes :as pci]))

(defn use-pathom3 [{:keys [resolvers]}]
  (fn [{biff-modules :biff/modules
        app-modules :app/modules :as ctx}]
    (let [resolvers (or resolvers (some->> (or biff-modules app-modules) deref (mapcat :resolvers)))]
      (def resolvers* resolvers)
      #_(pci/register resolvers)
      ctx)))
