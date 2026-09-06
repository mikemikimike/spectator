(ns main
  (:require [commands.add :as add])
  (:require [commands.delete :as delete_cmd])
  (:require [commands.start :as start])
  (:require [commands.tasks :as tasks])
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn- log-task-error [task-id stage error]
  (globalThis.console.error
   (JSON.stringify {:event "task_error"
                    :task_id task-id
                    :stage stage
                    :error (str error)})))

(defn- update-cursor [task-id post-id]
  (db/run
   "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"
   [post-id task-id]))

(defn- notify-post [env {:id task-id :telegram_user_id user-id :text text} post-id]
  (.then
   (.catch
    (telegram/send-message
     env
     user-id
     (str text (if (.endsWith text "/") "" "/") post-id))
    (fn [error]
      (log-task-error task-id "send" error)))
   (fn [] (update-cursor task-id post-id))))

(defn- process-task [env task]
  (let [{:id task-id :text text :cursor cursor} task
        task-channel (telegram/channel text)]
    (.then
     (telegram/fetch-post-ids (str (telegram/preview-url task-channel) "?after=" cursor) false)
     (fn [ids]
       (let [new-ids (.sort
                      (.filter ids (fn [id] (> id cursor)))
                      (fn [left right] (- left right)))]
         (reduce
          (fn [promise post-id]
            (.then promise (fn [] (notify-post env task post-id))))
          (.resolve Promise nil)
          new-ids))))))

;; ponytail: tasks run sequentially; batch them only when Worker limits are measured.
(defn handle-scheduled [env]
  (.then
   (db/all
    "SELECT id, telegram_user_id, text, cursor FROM tasks ORDER BY id"
    [])
   (fn [{:results results}]
     (reduce
      (fn [promise task]
        (.then
         promise
         (fn []
           (.catch
            (process-task env task)
            (fn [error] (log-task-error (get task "id") "process" error))))))
      (.resolve Promise nil)
      results))))

(defn- handle-message [env update]
  (let [message (get update "message")]
    (or (start/handle env message)
        (delete_cmd/handle env message)
        (add/handle env message)
        (tasks/handle env message)
        (Response. "OK"))))

(defn- handle-update [env update]
  (if (get update "update_id")
    (.then
     (db/all
      "INSERT OR IGNORE INTO processed_updates (update_id) VALUES (?1) RETURNING update_id"
      [(get update "update_id")])
     (fn [{:results results}]
       (if (> (count results) 0)
         (handle-message env update)
         (Response. "OK"))))
    (handle-message env update)))

(defn handle-fetch [request env]
  (if (= "POST" (get request "method"))
    (if-let [secret (:TELEGRAM_WEBHOOK_SECRET env)
             authorized (= secret (.get (get request "headers") "X-Telegram-Bot-Api-Secret-Token"))]
      (.then
       (.json request)
       (fn [update] (handle-update env update)))
      (Response. "Unauthorized" {:status 401}))
    (Response. "OK")))

(export-default
 {:fetch (fn [request env ctx]
           (telegram/with-fetch
             (fn [url options] (globalThis.fetch url options))
             (fn []
               (db/with-db
                 (get env "TASKS")
                 (fn [] (handle-fetch request env))))))
  :scheduled (fn [controller env ctx]
               (telegram/with-fetch
                 (fn [url options] (globalThis.fetch url options))
                 (fn []
                   (db/with-db
                     (get env "TASKS")
                     (fn [] (handle-scheduled env))))))})
