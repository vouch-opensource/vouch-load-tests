(ns com.example.task.send-friend-request
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.core.async :refer [<! go timeout]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]))

(defn- send-friend-request
  [api-url auth-token email]
  (http/post (str api-url "/user/friend")
    {:body    (json/encode {:email email})
     :headers {:authorization (str "Bearer " auth-token)
               :content-type  "application/json"
               :accept        "application/json"}}))

(defmethod executor/execute-task :send-friend-request
  [{:keys [api-url state] :as executor} {:keys [to]}]
  (go
    (if-let [email (some-> executor
                     (executor/filter-executors to #(some-> % :state deref :email))
                     shuffle first :state deref :email)]
      (let [auth-token (-> state deref :auth-token)]
        (send-friend-request api-url auth-token email))
      (log/warn "No executor matching following criteria" to))))
