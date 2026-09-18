(ns main
  (:require [commands.add :as add])
  (:require [commands.delete :as delete_cmd])
  (:require [commands.filter :as filter_cmd])
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

(defn- contains-keyword [text keyword]
  (.test
   (RegExp.
    (str "(^|[^\p{L}\p{N}])" (RegExp.escape keyword) "($|[^\p{L}\p{N}])")
    "iu")
   text))

(defn- matches-selection-rule [selection-rule text]
  (if (or (not selection-rule) (not text) (= "" text))
    true
    (let [keywords (.split selection-rule " ")
          required (-> keywords
                       (.filter (fn [keyword] (.startsWith keyword "+")))
                       (.map (fn [keyword] (.slice keyword 1))))
          prohibited (-> keywords
                         (.filter (fn [keyword] (.startsWith keyword "-")))
                         (.map (fn [keyword] (.slice keyword 1))))]
      (and (or (= 0 (count required))
               (.some required (fn [keyword] (contains-keyword text keyword))))
           (not (.some prohibited (fn [keyword] (contains-keyword text keyword))))))))

(defn- notify-post [env {:id task-id :telegram_user_id user-id :text text} post-id]
  (-> (telegram/send-message
       env
       user-id
       (str text (if (.endsWith text "/") "" "/") post-id))
      (.catch (fn [error] (log-task-error task-id "send" error)))
      (.then (fn [] (update-cursor task-id post-id)))))

(defn- process-task [env task]
  (let [{:id task-id :text text :cursor cursor :selection_rule selection-rule} task
        task-channel (telegram/channel text)]
    (.then
     (telegram/fetch-posts (str (telegram/preview-url task-channel) "?after=" cursor) false)
     (fn [posts]
       (->> (-> posts
                (.filter (fn [post] (> (get post "id") cursor)))
                (.sort (fn [left right] (- (get left "id") (get right "id")))))
            (reduce
             (fn [promise post]
               (.then
                promise
                (fn []
                  (let [post-id (get post "id")]
                    (if (matches-selection-rule selection-rule (get post "text"))
                      (notify-post env task post-id)
                      (update-cursor task-id post-id))))))
             (.resolve Promise nil)))))))

;; ponytail: tasks run sequentially; batch them only when Worker limits are measured.
(defn handle-scheduled [env]
  (.then
   (db/all
    "SELECT id, telegram_user_id, text, cursor, selection_rule FROM tasks ORDER BY id"
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
 :fetch (fn [request env ctx]
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
                    (fn [] (handle-scheduled env)))))))
