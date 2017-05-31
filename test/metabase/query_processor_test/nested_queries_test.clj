(ns metabase.query-processor-test.nested-queries-test
  "Tests for handling queries with nested expressions."
  (:require [clojure.string :as str]
            [expectations :refer [expect]]
            [honeysql.core :as hsql]
            [metabase
             [query-processor :as qp]
             [query-processor-test :refer :all]
             [util :as u]]
            [metabase.models
             [card :refer [Card]]
             [database :as database]
             [table :refer [Table]]]
            [metabase.test.data :as data]
            [metabase.test.data.datasets :as datasets]
            [toucan.db :as db]
            [toucan.util.test :as tt]))

(defn- rows+cols
  "Return the `:rows` and relevant parts of `:cols` from the RESULTS.
   (This is used to keep the output of various tests below focused and manageable.)"
  {:style/indent 0}
  [results]
  {:rows (rows results)
   :cols (for [col (get-in results [:data :cols])]
           {:name      (str/lower-case (:name col))
            :base_type (:base_type col)})})


;; make sure we can do a basic query with MBQL source-query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  {:rows [[1  4 10.0646 -165.374 "Red Medicine"                 3]
          [2 11 34.0996 -118.329 "Stout Burgers & Beers"        2]
          [3 11 34.0406 -118.428 "The Apple Pan"                2]
          [4 29 33.9997 -118.465 "Wurstküche"                   2]
          [5 20 34.0778 -118.261 "Brite Spot Family Restaurant" 2]]
   :cols [{:name "id",          :base_type (data/id-field-type)}
          {:name "category_id", :base_type :type/Integer}
          {:name "latitude",    :base_type :type/Float}
          {:name "longitude",   :base_type :type/Float}
          {:name "name",        :base_type :type/Text}
          {:name "price",       :base_type :type/Integer}]}
  (rows+cols
    (qp/process-query
      {:database (data/id)
       :type     :query
       :query    {:source-query {:source-table (data/id :venues)
                                 :order-by     [:asc (data/id :venues :id)]
                                 :limit        10}
                  :limit        5}})))

(defn- venues-identifier
  "Return the identifier for the venues Table for the current DB.
   (Normally this is just `venues`, but some databases like Redshift do clever hacks
   like prefixing table names with a unique schema for each test run because we're not
   allowed to create new databases.)"
  []
  (let [{schema :schema, table-name :name} (db/select-one [Table :name :schema] :id (data/id :venues))]
    (name (hsql/qualify schema table-name))))

;; make sure we can do a basic query with a SQL source-query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  {:rows [[ 4 1 10.0646 -165.374 "Red Medicine"                 3]
          [11 2 34.0996 -118.329 "Stout Burgers & Beers"        2]
          [11 3 34.0406 -118.428 "The Apple Pan"                2]
          [29 4 33.9997 -118.465 "Wurstküche"                   2]
          [20 5 34.0778 -118.261 "Brite Spot Family Restaurant" 2]]
   :cols [{:name "category_id", :base_type :type/Integer}
          {:name "id",          :base_type :type/Integer}
          {:name "latitude",    :base_type :type/Float}
          {:name "longitude",   :base_type :type/Float}
          {:name "name",        :base_type :type/Text}
          {:name "price",       :base_type :type/Integer}]}
  (rows+cols
    (qp/process-query
      {:database (data/id)
       :type     :query
       :query    {:source-query {:native (format "SELECT * FROM %s" (venues-identifier))}
                  :order-by     [:asc [:field-literal (keyword (data/format-name :id)) :type/Integer]]
                  :limit        5}})))

(def ^:private ^:const breakout-results
  {:rows [[22 1]
          [59 2]
          [13 3]
          [ 6 4]]
   :cols [{:name "count", :base_type :type/Integer}
          {:name "price", :base_type :type/Integer}]})

;; make sure we can do a query with breakout and aggregation using an MBQL source query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  breakout-results
  (rows+cols
    (format-rows-by [int int]
      (qp/process-query
        {:database (data/id)
         :type     :query
         :query    {:source-query {:source-table (data/id :venues)}
                    :aggregation  [:count]
                    :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}}))))

;; make sure we can do a query with breakout and aggregation using a SQL source query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  breakout-results
  (rows+cols
    (format-rows-by [int int]
      (qp/process-query
        {:database (data/id)
         :type     :query
         :query    {:source-query {:native (format "SELECT * FROM %s" (venues-identifier))}
                    :aggregation  [:count]
                    :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}}))))

;; Make sure we can run queries using source table `card__id` format. This is the format that is actually used by the frontend;
;; it gets translated to the normal `source-query` format by middleware. It's provided as a convenience so only minimal changes
;; need to be made to the frontend.
(expect
  breakout-results
  (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                            :type     :query
                                            :query    {:source-table (data/id :venues)}}}]
    (rows+cols
      (format-rows-by [int int]
        (qp/process-query
          {:database database/virtual-id
           :type     :query
           :query    {:source-table (str "card__" (u/get-id card))
                      :aggregation  [:count]
                      :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}})))))

;; make sure `card__id`-style queries work with native source queries as well
(expect
  breakout-results
  (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                            :type     :native
                                            :native   {:query (format "SELECT * FROM %s" (venues-identifier))}}}]
    (rows+cols
      (format-rows-by [int int]
        (qp/process-query
          {:database database/virtual-id
           :type     :query
           :query    {:source-table (str "card__" (u/get-id card))
                      :aggregation  [:count]
                      :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}})))))


;; make sure we can filter by a field literal
(expect
  {:rows [[4 1 10.0646 -165.374 "Red Medicine" 3]]
   :cols [{:name "category_id", :base_type :type/Integer}
          {:name "id",          :base_type :type/Integer}
          {:name "latitude",    :base_type :type/Float}
          {:name "longitude",   :base_type :type/Float}
          {:name "name",        :base_type :type/Text}
          {:name "price",       :base_type :type/Integer}]}
  (rows+cols
    (qp/process-query
      {:database (data/id)
       :type     :query
       :query    {:source-query {:source-table (data/id :venues)}
                  :filter       [:= [:field-literal (data/format-name :id) :type/Integer] 1]}})))
