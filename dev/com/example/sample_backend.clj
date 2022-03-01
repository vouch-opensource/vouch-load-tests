(ns com.example.sample-backend
  (:require
    [cheshire.core :as json]
    [clojure.string :as string]
    [reitit.ring :as ring]
    [ring.middleware.params])
  (:import
    (java.util UUID)))

(def friend-requests (ref {}))
(def tokens (ref {}))
(def users (ref {}))

(defn- register-user
  [request]
  (let [{:keys [email] :as user} (-> request :body slurp (json/decode true))
        token   (str (UUID/randomUUID))
        user-id (str (UUID/randomUUID))]
    (if (-> users deref (get email))
      {:status 400 :body "Email already taken"}
      (dosync
        (alter users assoc user-id (assoc user :id user-id))
        (alter tokens assoc token user-id)
        {:status 200 :body (json/encode {:token token})}))))

(defn- get-friend-requests
  [request]
  (let [auth-token              (second (string/split (get-in request [:headers "authorization"] "") #"Bearer "))
        filter-pending-requests #(filter (fn [[_ status]] (nil? status)) %)]
    (if-let [{:keys [id]} (and auth-token
                            (get (deref users) (get (deref tokens) auth-token)))]
      (dosync
        {:status 200 :body (json/encode (map first (-> friend-requests deref (get id) filter-pending-requests)))})
      {:status 401})))

(defn- send-friend-request
  [request]
  (let [auth-token (second (string/split (get-in request [:headers "authorization"] "") #"Bearer "))
        {:keys [email]} (-> request :body slurp (json/decode true))]
    (if-let [inviter (and auth-token
                       (get (deref users) (get (deref tokens) auth-token)))]
      (if-let [invited-user (first (filter (comp (partial = email) :email) (vals (deref users))))]
        (dosync
          (do (alter friend-requests update (:id invited-user) assoc (:id inviter) nil)
              {:status 200}))
        {:status 404})
      {:status 401})))

(defn- complete-friend-request
  [accept? request]
  (let [auth-token    (second (string/split (get-in request [:headers "authorization"] "") #"Bearer "))
        invitation-id (-> request :path-params :id)]
    (if-let [invited-user (and auth-token
                            (get (deref users) (get (deref tokens) auth-token)))]
      (dosync
        (alter friend-requests update-in [(:id invited-user) invitation-id]
          assoc :status (if accept? :accepted :rejected))
        {:status 200})
      {:status 401})))

(def handler
  (ring/ring-handler
    (ring/router
      [["/api"
        [["/user"
          ["/friend" {:post send-friend-request}]
          ["/friend-requests"
           [["" {:get get-friend-requests}]
            ["/:id"
             [["/accept" {:post (partial complete-friend-request true)}]
              ["/reject" {:post (partial complete-friend-request false)}]]]]]
          ["/register" {:post register-user}]]]]])
    (constantly {:status 404 :body ""})))

