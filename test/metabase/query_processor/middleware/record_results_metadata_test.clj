(ns metabase.query-processor.middleware.record-results-metadata-test
  (:require [expectations :refer [expect]]
            [metabase
             [query-processor :as qp]
             [util :as u]]
            [metabase.models.card :refer [Card]]
            [metabase.test.data :as data]
            [toucan.db :as db]
            [toucan.util.test :as tt]))

;; test that Card result metadata is saved after running a Card
(expect
  [{:name "ID",          :base_type "type/Integer"}
   {:name "NAME",        :base_type "type/Text"}
   {:name "PRICE",       :base_type "type/Integer"}
   {:name "CATEGORY_ID", :base_type "type/Integer"}
   {:name "LATITUDE",    :base_type "type/Float"}
   {:name "LONGITUDE",   :base_type "type/Float"}]
  (tt/with-temp Card [card]
    (qp/process-query {:database (data/id)
                       :type     :native
                       :native   {:query (format "SELECT ID, NAME, PRICE, CATEGORY_ID, LATITUDE, LONGITUDE FROM VENUES")}
                       :info     {:card-id    (u/get-id card)
                                  :query-hash (byte-array 0)}})
    (db/select-one-field :result_metadata Card :id (u/get-id card))))
