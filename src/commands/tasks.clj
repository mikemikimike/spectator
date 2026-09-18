(ns commands.tasks
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn handle [env message]
  (if-let [text (:text message)
           user-id (get-in message [:from :id])
           command (= "/tasks" text)]
    (.then
     (db/all
      "SELECT text, selection_rule FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"
      [user-id])
     (fn [{:results results}]
       (let [tasks (-> results
                       (.map (fn [{:text task-text :selection_rule selection-rule} index]
                               (str (+ index 1)
                                    ". "
                                    task-text
                                    (if selection-rule (str " (" selection-rule ")") ""))))
                       (.join "\n"))]
         (.then
          (telegram/send-message env (get-in message [:chat :id]) (if (= "" tasks) "Задач пока нет." tasks))
          (fn [] (Response. "OK"))))))))
