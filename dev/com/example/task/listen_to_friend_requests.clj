(ns com.example.task.listen-to-friend-requests
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.core.async :refer [<! chan close! go put! timeout]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]))

(defn- friend-requests
  [api-url auth-token]
  (let [chan (chan)]
    (http/get (str api-url "/user/friend-requests")
      {:async?  true
       :headers {:authorization (str "Bearer " auth-token)
                 :content-type  "application/json"
                 :accept        "application/json"}}
      #(if-let [result (some-> % :body (json/decode true))]
         (put! chan result)
         (close! chan))
      #(put! chan %))
    chan))

(defn- accept-or-reject-friend-requests
  [api-url auth-token id accept?]
  (let [chan (chan)]
    (http/post (str api-url "/user/friend-requests/" id (if accept? "/accept" "/reject"))
      {:async?  true
       :headers {:authorization (str "Bearer " auth-token)
                 :content-type  "application/json"
                 :accept        "application/json"}}
      #(if-let [result (some-> % :body (json/decode true))]
         (put! chan result)
         (close! chan))
      #(put! chan %))
    chan))

(defn- accept-friend-requests
  [api-url auth-token id]
  (accept-or-reject-friend-requests api-url auth-token id true))

(defn- reject-friend-requests
  [api-url auth-token id]
  (accept-or-reject-friend-requests api-url auth-token id true))

(defmethod executor/execute-task :listen-to-friend-requests
  [{:keys [api-url id state] :as executor} msg]
  (go
    (log/info id msg)
    (let [close  (chan)
          closed (atom false)]
      (executor/add-listener executor executor/stop-event #(do (reset! closed true) (close! close)))
      (loop []
        (let [auth-token (-> state deref :auth-token)
              requests   (<! (friend-requests api-url auth-token))]
          (doseq [id requests]
            (if (:accept-friend-request (executor/get-behavior executor))
              (<! (accept-friend-requests api-url auth-token id))
              (<! (reject-friend-requests api-url auth-token id))))
          (<! (timeout 1000))
          (when-not @closed
            (recur))))
      (<! close))))
