(ns dev
  (:require
    [clojure.tools.namespace.repl :as repl]
    [com.example.core :as core]
    [com.example.io.reporter :as reporter]
    [com.example.sample-backend :as sample-backend]
    [ring.adapter.jetty :as jetty])
  (:import
    (java.io Closeable)))

(repl/set-refresh-dirs "dev" "src")

(defonce server (atom nil))

(def scenario
  {:workflows   {:listeners [{:task :register-user}
                             {:task :listen-to-friend-requests}]
                 :inviter   [{:task :register-user}
                             {:task :wait :duration 1}
                             {:task :send-friend-request :to {:behavior {:accept-friend-request true}}}
                             {:task :send-friend-request :to {:accept-friend-request false}}
                             {:task :send-friend-request :to {:workflow :listeners}}
                             {:task :send-friend-request :to {:tags [:singleton]}}
                             {:task :wait :duration 3}
                             {:task :terminate-scenario}]}
   :actor-pools [{:workflow :listeners :actors 10 :behavior {:accept-friend-request true}}
                 {:workflow :listeners :actors 10 :behavior {:accept-friend-request false}}
                 {:workflow :listeners :actors 1 :tags [:singleton]}
                 {:workflow :inviter :actors 10}]})

(defn go []
  (reset! server
    (let [jetty  (jetty/run-jetty sample-backend/handler {:port 3000 :join? false})
          system (core/start-system {:reporter   #_(reporter/create-csv-reporter "output.csv")
                                               (reporter/create-log-reporter)
                                     :api-url  "http://localhost:3000/api"
                                     :scenario scenario})]
      (reify Closeable
        (close [_this]
          (.close system)
          (.stop jetty))))))

(defn reset []
  (when-let [server (deref server)]
    (.close server))
  (repl/refresh :after 'dev/go))
