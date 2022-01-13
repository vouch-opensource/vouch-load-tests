(ns com.example.core
  (:require
    [clojure.tools.logging :as log]
    [com.example.random :refer [create-unique-generator random-email]]
    [com.example.task.default]
    [com.example.task.listen-to-friend-requests]
    [com.example.task.register-user]
    [com.example.task.send-friend-request]
    [io.vouch.load-tests.master :as master]
    [io.vouch.load-tests.task.loop]
    [io.vouch.load-tests.task.wait])
  (:import
    (java.io Closeable)))

(defrecord TestSystem
  [master]
  Closeable
  (close [_]
    (log/info "Closing test system")
    (master/stop master)))

(defn start-system
  [{:keys [] :as config}]
  (let [master (master/start
                 (assoc config
                   :unique-email (create-unique-generator random-email)))]
    (map->TestSystem {:master master})))
