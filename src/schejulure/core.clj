(ns schejulure.core
  (:require [clj-time.core :refer [date-time year minute hour day month day-of-week
                                   minutes in-secs interval plus now]]
            [clj-time.local :refer [local-now]])
  (:import [java.util.concurrent Executors TimeUnit]))

(def pool (Executors/newScheduledThreadPool 1))

(defn current-minute [time]
  (apply date-time ((juxt year month day hour minute) time)))

(defn next-minute [time]
  (plus (current-minute time) (-> 1 minutes)))

(defn secs-to-next-minute [time]
  ;; Seconds to next minute will be a low estimation as it misses out
  ;; the milliseconds component. We are interested in a slight
  ;; overestimation so that it's just into the next minute if anything,
  ;; therefore add one to the interval in seconds
  (inc (in-secs (interval time (next-minute time)))))

(defn call-every-minute [f]
  (.scheduleAtFixedRate pool f (secs-to-next-minute (now)) 60 TimeUnit/SECONDS))

(defn cron-of [time]
  [(minute time)
   (hour time)
   (day time)
   (month time)
   (day-of-week time)])

(defn has? [coll item] (some #{item} coll))
(defn all? [coll] (every? identity coll))

(defn cron-match? [cron cron-range]
  (all? (map has? cron-range cron)))

(def cron-defaults {:minute (range 0 60)
                    :hour   (range 0 24)
                    :date   (range 1 32)
                    :month  (range 1 13)
                    :day    (range 1  8)})

(def day->number {:mon 1, 1 1
                  :tue 2, 2 2
                  :wed 3, 3 3
                  :thu 4, 4 4
                  :fri 5, 5 5
                  :sat 6, 6 6
                  :sun 7, 7 7, 0 7})

(def weekdays [:mon :tue :wed :thu :fri])
(def weekends [:sat :sun])

(defn keyword-day->number [x]
  (if (coll? x) (map day->number x)
      (list (day->number x))))

(defn cronmap->cronrange [cronmap]
  (map (fn [x] (if (coll? x) x (list x)))
       (-> (merge cron-defaults cronmap)
           (update-in [:day] keyword-day->number)
           ((juxt :minute :hour :date :month :day)))))

(defn fire-scheduled [scheduled-fns]
  (let [now (cron-of (local-now))]
    (doseq [[schedule f] scheduled-fns]
      (when (cron-match? now (cronmap->cronrange schedule))
        (try (f)
             (catch Exception e
               (println "Caught exception in scheduled action " f " at " now)
               (.printStackTrace e)))))))

(defn schedule
  "Takes pairs of cron-maps with the function to call when that cron-map
   matches the current time.

   The default cron-map executes every minute of every day, add elements
   into the map to restrict this, for example {:day [:mon :tue] :hour 5}
   will execute every minute between 5 and 6am on mondays and tuesdays
   where {:minute [15 45]} will execute and quarter past and quarter to
   the hour, every hour every day.

   If an exception is thrown and uncaught by your, execution will continue
   rather than interrupt future scheduled executions, if you need to handle
   the error your function must catch it.

   All the scheduled tasks run in a single thread, therefore long running
   tasks may impact the execution of subsequent tasks. If your task takes
   a non trivial amount of time, have the scheduler fire off a future
   rather than running it directly."
  [& args]
  (let [scheduled-fns (partition 2 args)]
    (call-every-minute #(fire-scheduled scheduled-fns))))
