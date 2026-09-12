(ns commands.filter
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn- usage [env chat-id]
  (.then
   (telegram/send-message env chat-id "Использование: /filter <номер> +нужное -запрещённое или /filter <номер> off")
   (fn [] (Response. "OK"))))

(defn handle [env message]
  (if-let [text (:text message)]
    (if (= "/filter" text)
      (usage env (get-in message [:chat :id]))
      (if-let [user-id (get-in message [:from :id])
               command (.startsWith text "/filter ")]
        (let [chat-id (get-in message [:chat :id])
              parts (-> text (.slice 8) .trim (.split (RegExp. " +")))
              task-number (Number (get parts 0))
              keywords (.slice parts 1)
              disabled (and (= 1 (count keywords)) (= "off" (get keywords 0)))
              valid-keywords (and (> (count keywords) 0)
                                  (.every keywords
                                          (fn [keyword]
                                            (and (not (= "" (.slice keyword 1)))
                                                 (or (.startsWith keyword "+")
                                                     (.startsWith keyword "-"))))))]
          (if (and (.isInteger Number task-number)
                   (> task-number 0)
                   (or disabled valid-keywords))
            (let [selection-rule (if disabled nil (.join keywords " "))]
              (.then
               (db/all
                "UPDATE tasks SET selection_rule = ?1 WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?2 ORDER BY id LIMIT 1 OFFSET ?3) AND telegram_user_id = ?2 RETURNING id"
                [selection-rule user-id (- task-number 1)])
               (fn [{:results results}]
                 (.then
                  (telegram/send-message
                   env
                   chat-id
                   (cond
                     (= 0 (count results)) "Задача не найдена."
                     disabled "Фильтр удалён."
                     :else "Фильтр сохранён."))
                  (fn [] (Response. "OK"))))))
            (usage env chat-id)))))))
