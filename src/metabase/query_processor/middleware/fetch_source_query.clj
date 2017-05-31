(ns metabase.query-processor.middleware.fetch-source-query
  (:require [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase.query-processor.util :as qputil]
            [metabase.util :as u]
            [toucan.db :as db]))

(defn- card-id->database-id+source-query
  "Return a pair with the `:database` ID and value for `:source-query` needed to use Card with CARD-ID as a source query."
  [card-id]
  (let [card         (db/select-one ['Card :dataset_query :database_id] :id card-id)
        card-query   (:dataset_query card)]
    [(:database card-query)
     (or (:query card-query)
         (when-let [native-query (get-in card-query [:native :query])]
           {:native native-query})
         (throw (Exception. (str "Missing source query in Card " card-id))))]))

(defn- add-source-query-for-card-id [outer-query card-id]
  (let [[database-id source-query] (card-id->database-id+source-query card-id)]
    (-> outer-query
        (assoc :database database-id)
        (update :query (fn [query]
                         (-> (qputil/dissoc-normalized query :source-table)
                             (assoc :source-query source-query)))))))

(defn- fetch-source-query* [outer-query]
  (let [source-table (qputil/get-in-normalized outer-query [:query :source-table])]
    (if-not (string? source-table)
      outer-query
      (let [[_ card-id] (re-find #"^card__(\d+)$" source-table)]
        (u/prog1 (add-source-query-for-card-id outer-query (Integer/parseInt card-id))
          (log/info "\nFETCHED SOURCE QUERY:\n" (u/pprint-to-str 'yellow (select-keys <> [:database :query]))))))))

(defn fetch-source-query
  "Middleware that assocs the `:source-query` for this query if it was specified using the shorthand `:source-table` `card__n` format."
  [qp]
  (comp qp fetch-source-query*))
