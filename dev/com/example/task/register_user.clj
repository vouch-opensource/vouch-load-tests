(ns com.example.task.register-user
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.core.async :refer [<! chan close! go put!]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]))

(defn- register-user
  [api-url email password]
  (let [chan (chan)]
    (http/post (str api-url "/user/register")
      {:body    (json/encode {:email email :password password})
       :async?  true
       :headers {:content-type "application/json"
                 :accept       "application/json"}}
      #(if-let [result (some-> % :body (json/decode true) :token)]
         (put! chan result)
         (close! chan))
      #(put! chan %))
    chan))

(defmethod executor/execute-task :register-user
  [{:keys [api-url id unique-email state]} msg]
  (go
    (log/info id msg)
    (let [email    (unique-email)
          password (str (rand))
          token    (<! (register-user api-url email password))]
      (if (instance? Exception token)
        token
        (swap! state assoc :auth-token token :email email :password password)))))
