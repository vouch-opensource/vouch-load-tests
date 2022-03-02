(ns com.example.io.reporter
  (:require
    [clojure.core.async :refer [<! chan go]]
    [clojure.data.csv :as csv]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]))

(defn create-csv-reporter
  ([log-file-name] (create-csv-reporter log-file-name false))
  ([log-file-name errors-to-console?]
   (let [reporting (chan)]
     (go
       (with-open [writer (io/writer log-file-name)]
         (csv/write-csv writer
           [["method" "duration" "executor" "status"]])
         (loop []
           (when-let [{:keys          [duration executor error]
                       {:keys [task]} :task} (<! reporting)]
             (csv/write-csv writer
               [[task duration executor (ex-message error)]])
             (when (and errors-to-console? error)
               (log/error (:id executor) "Failed to handle task" task error))
             (recur)))))
     reporting)))

(defn create-log-reporter
  []
  (let [reporting (chan)]
    (go
      (loop []
        (when-let [{:keys          [duration executor error]
                    {:keys [task]} :task} (<! reporting)]
          (if error
            (log/error executor "Failed to handle task" task error)
            (log/info (format "%s took %dms %s" task duration executor)))
          (recur))))
    reporting))
