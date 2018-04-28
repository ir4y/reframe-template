(ns ui.patients.core
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [reagent.core :as reagent]
            [re-frame.core :as rf]
            [ui.routes :refer [href]]
            [ui.widgets :as wgt]
            [ui.styles :as styles]
            [clojure.string :as str]
            [cljs.pprint :as pp]
            [ui.pages :as pages]
            [clojure.string :as str]))

(rf/reg-event-db
 ::save-patients
 (fn [db [_ {data :data}]]
   (assoc db ::patients data)))

(rf/reg-event-fx
 ::load-patients
 (fn [_ _]
   {:json/fetch {:uri "Patient"
                 :success [::save-patients]}}))

(defn rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(rf/reg-event-fx
 ::add-patient
 (fn [_ _]
   {:json/fetch {:uri "Patient"
                 :method :post
                 :body {:name [{:text (rand-str 8)}]}
                 :success [::load-patients]}}))

(rf/reg-event-fx
 ::delete-patient
 (fn [_ [_ id]]
   {:json/fetch {:uri (str "Patient/" id)
                 :method :delete
                 :success [::load-patients]}}))

(rf/reg-sub
 ::patients
 (fn [db _]
   (::patients db)))

(defn index [params]
  (rf/dispatch [::load-patients])
  (let [patients (rf/subscribe [::patients])]
    (fn [params]
      [:div (for [{{id :id :as patient} :resource} (:entry @patients)] ^{:key id}
              [:p
               (pr-str patient)
               [:button {:on-click #(rf/dispatch [::delete-patient id])} "delete"]])
       [:br]
       [:button {:on-click #(rf/dispatch [::add-patient])}"Add"]])))

(pages/reg-page :patients/index index)
(pages/reg-page :patients/edit (fn [route] [:h1 "patients edit"]))
(pages/reg-page :patients/show (fn [route] [:h1 "patients show"]))
(pages/reg-page :patients/new (fn [route] [:h1 "patients new"]))
