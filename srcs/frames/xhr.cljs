(ns frames.xhr
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [re-frame.core :as rf]))

(rf/reg-sub
 :xhr/status
 (fn [db _]
   (get-in db [:xhr :status])))

(defn param-value [k v]
  (if (vector? v)
    (->> v (map name) (map str/trim) (str/join (str "&" (name k) "=" )))
    v))

(defn to-query [params]
  (->> (if (and (vector? params) (some (comp not vector?) (take-nth 2 params)))
         (partition-all 2 params)
         params)
       (mapv (fn [[k v]] (str (name k) "=" (param-value k v))))
       (str/join "&")))


(defn fetch [db {:keys [uri headers params] :as opts} & [acc]]
  (let [headers (merge {"Accept" "application/json"
                        "Content-Type" "application/json"
                        "Authorization" (str "Bearer " (get-in db [:auth :id_token]))}
                       (or headers {}))
        fetch-opts (-> (merge {:method "get" :mode "cors"} opts)
                       (dissoc :uri :headers :success :error :params)
                       (assoc :headers headers))
        fetch-opts (cond
                     (:body opts)
                     (assoc fetch-opts
                            :body
                            (if (= (get headers "Content-Type") "application/json")
                              (.stringify js/JSON (clj->js (:body opts)))
                              (:body opts)))
                     :else fetch-opts)
        fetch-opts (update fetch-opts :headers
                           (fn [h] (if (= (get h "Content-Type") "multipart/form-data")
                                     (dissoc h "Content-Type")
                                     h)))
        url (str (get-in db [:config :base-url]) uri (when params (str "?" (to-query params))))]
    (.catch
     (.then
      (js/fetch url (clj->js fetch-opts))
      (fn [resp]
        (if (not (.-ok resp))
          (do
            (.error js/console "Fetch error:" resp)
            (rf/dispatch [::xhr-fail opts])
            (.then
             (.json resp)
             (fn [data]
               {:request opts
                :response resp
                :data (js->clj data :keywordize-keys true)})))
          (if-not (= 204 (.-status resp))
            (.then
             (.json resp)
             (fn [data]
               (let [res {:request opts
                          :response resp
                          :data (js->clj data :keywordize-keys true)}]
                 (if acc
                   (conj acc res)
                   res))))
            {:request opts
             :response resp
             :data ""}))))
     (fn [err]
       (.error js/console "Fetch error:" err)
       (rf/dispatch [::xhr-fail opts])))))


(rf/reg-fx
 :json/fetch-with-db
 (fn [[db {:keys [success error cb ] :as opts}]]
   (.then
    (fetch db opts)
    (fn [{:keys [response data] :as res}]
      (if (< (.-status response) 299)
        (do
          (rf/dispatch [::xhr-success opts])
          (rf/dispatch (conj success res))
          (when cb (cb res)))
        (when error
          (rf/dispatch (conj error res))))))))

(defn mk-entry-opts [entry]
  {:uri (str "/" (:resourceType entry) "/" (:id entry))
   :method "put"
   :body entry})

(rf/reg-fx
 :json/fetch-bundle-with-db
 (fn [[db {:keys [success error bundle] :as opts}]]
   (.then
    (->> (:entry bundle) (map :resource)
         (reduce (fn [acc e]
                   (.then acc (fn [r] (fetch db (mk-entry-opts e) r))))
                 (.resolve js/Promise [])))
    (fn [res]
      (if (every? #(.-ok (:response %)) res)
        (do
          (rf/dispatch [::xhr-success opts])
          (rf/dispatch (conj success res)))
        (do
          (rf/dispatch [::xhr-fail opts])
          (rf/dispatch (conj error res))))))))

(rf/reg-fx
 :json/fetch-all-with-db
 (fn [[db {:keys [success error bundle] :as opts}]]
   (.then
    (.all js/Promise
          (mapv #(fetch db %) bundle))
    (fn [res]
      (if (every? #(.-ok (:response %)) res)
        (do
          (rf/dispatch [::xhr-success opts])
          (rf/dispatch (conj success res)))
        (do
          (rf/dispatch [::xhr-fail opts])
          (rf/dispatch (conj error res))))))))

(rf/reg-event-db
 ::xhr-fail
 (fn [db [_ arg]]
   (assoc-in db [:xhr :status] "fail")))

(rf/reg-event-fx
 ::xhr-success
 (fn [{db :db} [_ arg]]
   (let [db (update-in db [:xhr :requests ] dissoc (keyword (:uri arg)))
         db (assoc-in db [:xhr :status] "success")
         db (if (empty? (get-in db [:xhr :requests ]))
              (assoc-in db [:xhr :status] "success")
              db)]
     {:db db})))

(rf/reg-event-fx
 :fetch-with-db
 (fn [{db :db} [_ arg]]
   {:db (-> db
            (assoc-in [:xhr :status] "pending")
            (assoc-in [:xhr :requests (keyword (:uri arg))] "pending"))
    :json/fetch-with-db [db arg]}))

(rf/reg-event-fx
 :xhr-fetch-with-db
 (fn [{db :db} [_ arg]]
   {:db (-> db
            (assoc-in [:xhr :status] "pending")
            (assoc-in [:xhr :requests (keyword (:uri arg))] "pending"))
    :xhr/fetch-with-db [db arg]}))

(rf/reg-event-fx
 :fetch-bundle-with-db
 (fn [{db :db} [_ arg]]
   {:json/fetch-bundle-with-db [db arg]}))

(rf/reg-event-fx
 :fetch-all-with-db
 (fn [{db :db} [_ arg]]
   {:db (-> db
            (assoc-in [:xhr :status] "pending")
            (assoc-in [:xhr :requests (keyword (or (:uri arg) "/"))] "pending"))
    :json/fetch-all-with-db [db arg]}))


(rf/reg-fx
 :xhr/fetch
 #(rf/dispatch [:xhr-fetch-with-db %]))
(rf/reg-fx
 :json/fetch
 #(rf/dispatch [:fetch-with-db %]))
(rf/reg-fx
 :json/fetch-all
 #(rf/dispatch [:fetch-all-with-db %]))
(rf/reg-fx
 :json/bundle
 #(rf/dispatch [:fetch-bundle-with-db %]))
