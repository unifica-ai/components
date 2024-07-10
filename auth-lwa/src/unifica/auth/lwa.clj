(ns unifica.auth.lwa
  (:require
   [clj-http.client :as http]
   [clojure.tools.logging :as log]
   [unifica.biff.xtdb :refer [q] :as bxt]
   [unifica.connector :as conn]
   [temporal.activity :refer [defactivity] :as ta]
   [temporal.workflow :refer [defworkflow] :as tw]))

(def ^:private schema
  {::id :uuid
   :unifica.auth.lwa
   [:map {:closed true}
    [:xt/id ::id]
    [::user :user/id]
    [::access-token :string]
    [::refresh-token :string]
    [::expires-in {:optional true} :int]
    [::created-at inst?]]})

(defn redirect [{:biff/keys [base-url]
                 ::keys  [auth-grant-url client-id permission-scope]

                 :as ctx}]
  (let [state "State"
        url (str auth-grant-url "?scope=" permission-scope
                 "&response_type=code"
                 "&client_id=" client-id
                 "&state=" state
                 "&redirect_uri=" (str base-url "/app/lwa/signin"))]
    {:status 303
     :headers {"location" url}}))

(defn get-tokens
  "Gets a token from LWA from an authorization code or refresh token"
  [{:biff/keys [base-url secret]
    ::keys [token-url client-id code refresh-token
            debug debug-body]}]
  (let [url token-url
        params {:grant_type (if code "authorization_code" "refresh_token")
                :refresh_token refresh-token
                :redirect_uri (str base-url "/app/lwa/signin")
                :client_id client-id
                :client_secret (secret ::client-secret)}
        tokens (try (-> (http/post url {:form-params (merge params (if code
                                                                     {:code code}
                                                                     {:refresh_token refresh-token}))
                                        :as :json
                                        :debug debug
                                        :debug-body debug-body})
                        :body)
                    (catch Exception e
                      (log/error e "Error getting Amazon authorization token")))]
    (if-some [{:keys [access_token refresh_token expires_in]} tokens]
      {:success true
       :access-token access_token
       :refresh-token refresh_token
       :expires-in expires_in}
      {:success false})))

(defn authenticate
  [{:keys [unifica.lwa/auth-fn] :or {auth-fn get-tokens} :as ctx}]
   (auth-fn ctx))

(defn- persist-tokens
  "Persist tokens to the database"
  ([{:keys [session] :as ctx} token]
   (persist-tokens ctx token ((some-fn :as-uid :uid) session)))
  ([ctx {:keys [access-token refresh-token expires-in]} uid]
   (bxt/submit-tx ctx [{:db/doc-type :unifica.lwa-auth
                        :db.op/upsert {:unifica.lwa/user uid}
                        :unifica.lwa/access-token access-token
                        :unifica.lwa/refresh-token refresh-token
                        :unifica.lwa/expires-in expires-in
                        :unifica.lwa/created-at :db/now}])))

(defn- clear-tokens
  "Clear tokens in database for user in session"
  [{:keys [biff/db session] :as ctx}]
  (bxt/submit-tx
   ctx
   (for [id (q db '{:find auth
                    :in [user]
                    :where [[auth :unifica.lwa/user user]]}
               ((some-fn :as-uid :uid) session))]
     {:db/op :delete
      :xt/id id})))

(defn- connect [ctx tokens]
  (persist-tokens ctx tokens)
  (conn/install ctx :amzn-ads))

(defn- disconnect [ctx]
  (clear-tokens ctx)
  (conn/uninstall ctx :amzn-ads))

(defn signin [{:keys [session params] :as ctx}]
  (log/info (str "Received code for user " ((some-fn :as-uid :uid) session)))
  (log/info params)
  (let [{:keys [code state scope]} params
        {:keys [success] :as tokens} (authenticate (assoc ctx :unifica.lwa/code code))]
    (when success (connect ctx tokens))
    {:status 303
     :headers {"location" "/"}}))

(defn signout [ctx]
  (disconnect ctx)
  {:status 303
   :headers {"location" "/"}})

(def user-auth-tokens-by-id
  '{:find (pull auth [::access-token
                      ::refresh-token
                      ::created-at])
    :where [[auth ::user id]]
    :in [id]})

(defn auth-tokens
  "Returns LWA tokens for the given user ID or the currently logged-in user"
  ([{:keys [session] :as ctx}]
   (auth-tokens ctx ((some-fn :as-uid :uid) session)))
  ([{:keys [biff/db]} uid]
  (first (q db user-auth-tokens-by-id uid))))

(defn merge-context
  "Merge user authentication tokens for user (or signed-in user) into context"
  ([ctx]
   (merge ctx (auth-tokens ctx)))
  ([ctx uid]
   (merge ctx (auth-tokens ctx uid))))

(defactivity refresh-access-tokens [ctx]
  (let [ctx (bxt/merge-context ctx)
        _ (log/info "Refreshing access tokens")
        users (conn/users ctx :amzn-ads)]
    (doseq [[_ uid] users
            :let [ctx (merge-context ctx uid)
                  {:keys [success] :as tokens} (authenticate ctx)]
            :when success]
      (persist-tokens ctx tokens uid))))

(defworkflow authentication [_]
  @(ta/invoke refresh-access-tokens))

(defn module [{:keys [wrap-signed-in]}]
  {:schema schema

   :routes ["/app" {:middleware [wrap-signed-in]}
            ["/lwa"
             ["/redirect" {:post redirect}]
             ["/signin" {:get signin}]
             ["/signout" {:post signout}]]]

   :tasks {:lwa-authentication {:schedule {:trigger-immediately? false}
                                :state {:paused? false}
                                :policy {:pause-on-failure? true}
                                :spec {:cron-expressions ["0 * * * *"] ;; every hour
                                       :timezone "US/Central"} ;; every minute
                                :action {:arguments {} ;; TODO use Meander to load config / env values here
                                         :workflow-type authentication
                                         :options {:workflow-id "authentication"
                                                   :task-queue ::queue}}}}})
