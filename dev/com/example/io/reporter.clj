(ns com.example.io.reporter
  (:require
    [clojure.core.async :refer [<! chan go]]
    [clojure.data.csv :as csv]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]))

(defn create-csv-reporter
  [log-file-name]
  (let [reporting (chan)]
    (go
      (with-open [writer (io/writer log-file-name)]
        (csv/write-csv writer
          [["method" "duration" "executor"]])
        (loop []
          (when-let [{:keys          [duration executor]
                      {:keys [task]} :task} (<! reporting)]
            (csv/write-csv writer
              [[task duration executor]])
            (recur)))))
    reporting))

(defn create-log-reporter
  []
  (let [reporting (chan)]
    (go
      (loop []
        (when-let [{:keys          [duration executor]
                    {:keys [task]} :task} (<! reporting)]
          (log/info (format "%s took %dms %s" task duration executor))
          (recur))))
    reporting))
