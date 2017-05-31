(ns metabase.api.dataset
  "/api/dataset endpoints."
  (:require [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.string :as str]
            [compojure.core :refer [POST]]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [metabase
             [middleware :as middleware]
             [query-processor :as qp]
             [util :as u]]
            [metabase.api.common :as api]
            [metabase.api.common.internal :refer [route-fn-name]]
            [metabase.models
             [card :refer [Card]]
             [database :as database, :refer [Database]]
             [query :as query]]
            [metabase.query-processor.util :as qputil]
            [metabase.util.schema :as su]
            [schema.core :as s])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def ^:private ^:const max-results-bare-rows
  "Maximum number of rows to return specifically on :rows type queries via the API."
  2000)

(def ^:private ^:const max-results
  "General maximum number of rows to return from an API query."
  10000)

(def ^:const default-query-constraints
  "Default map of constraints that we apply on dataset queries executed by the api."
  {:max-results           max-results
   :max-results-bare-rows max-results-bare-rows})


(defn- has-source-query?
  "Is this QUERY one that uses nested queries (i.e., does it use another query as its source)?"
  [query]
  ;; Check whether the query specifies the 'Virtual' database (using the "secret" ID we assigned to it)
  (= (:database query) database/virtual-id))

(defn- execute-nested-query
  "For a QUERY that uses a saved Card as its source, check permissions as appropriate and add the approprate `:source-query` clause."
  [{inner-query :query, :as outer-query}]
  (let [[_ card-id-str] (re-matches #"^card__(\d+$)" (or (qputil/get-normalized inner-query :source-table)
                                                         (throw (Exception. "Missing :source_table in query."))))]
    (when-not card-id-str
      (throw (Exception. (str "Invalid source query ID. Expected a string like 'card__100'. Got: " card-id-str))))
    (let [card-id (Integer/parseInt card-id-str)]
      (api/read-check Card card-id)
      (qp/dataset-query outer-query
        {:executed-by api/*current-user-id*, :context :ad-hoc, :card-id card-id, :nested-query? true}))))

(defn- execute-query [{:keys [database], :as query}]
  (api/read-check Database database)
  ;; add sensible constraints for results limits on our query
  (qp/dataset-query (assoc query :constraints default-query-constraints)
    {:executed-by api/*current-user-id*, :context :ad-hoc}))

(api/defendpoint POST "/"
  "Execute a query and retrieve the results in the usual format."
  [:as {body :body}]
  (if (has-source-query? body)
    (execute-nested-query body)
    (execute-query body)))

;; TODO - this is no longer used. Should we remove it?
(api/defendpoint POST "/duration"
  "Get historical query execution duration."
  [:as {{:keys [database], :as query} :body}]
  (api/read-check Database database)
  ;; try calculating the average for the query as it was given to us, otherwise with the default constraints if there's no data there.
  ;; if we still can't find relevant info, just default to 0
  {:average (or (query/average-execution-time-ms (qputil/query-hash query))
                (query/average-execution-time-ms (qputil/query-hash (assoc query :constraints default-query-constraints)))
                0)})

(defn- export-to-csv [columns rows]
  (with-out-str
    ;; turn keywords into strings, otherwise we get colons in our output
    (csv/write-csv *out* (into [(mapv name columns)] rows))))

(defn- export-to-xlsx [columns rows]
  (let [wb  (spreadsheet/create-workbook "Query result" (conj rows (mapv name columns)))
        ;; note: byte array streams don't need to be closed
        out (ByteArrayOutputStream.)]
    (spreadsheet/save-workbook! out wb)
    (ByteArrayInputStream. (.toByteArray out))))

(defn- export-to-json [columns rows]
  (for [row rows]
    (zipmap columns row)))

(def ^:private export-formats
  {"csv"  {:export-fn    export-to-csv
           :content-type "text/csv"
           :ext          "csv"
           :context      :csv-download},
   "xlsx" {:export-fn    export-to-xlsx
           :content-type "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
           :ext          "xlsx"
           :context      :xlsx-download},
   "json" {:export-fn    export-to-json
           :content-type "applicaton/json"
           :ext          "json"
           :context      :json-download}})

(def ExportFormat
  "Schema for valid export formats for downloading query results."
  (apply s/enum (keys export-formats)))

(defn export-format->context
  "Return the `:context` that should be used when saving a QueryExecution triggered by a request to download results in EXPORT-FORAMT.

     (export-format->context :json) ;-> :json-download"
  [export-format]
  (or (get-in export-formats [export-format :context])
      (throw (Exception. (str "Invalid export format: " export-format)))))

(defn as-format
  "Return a response containing the RESULTS of a query in the specified format."
  {:style/indent 1, :arglists '([export-format results])}
  [export-format {{:keys [columns rows]} :data, :keys [status], :as response}]
  (api/let-404 [export-conf (export-formats export-format)]
    (if (= status :completed)
      ;; successful query, send file
      {:status  200
       :body    ((:export-fn export-conf) columns rows)
       :headers {"Content-Type"        (str (:content-type export-conf) "; charset=utf-8")
                 "Content-Disposition" (str "attachment; filename=\"query_result_" (u/date->iso-8601) "." (:ext export-conf) "\"")}}
      ;; failed query, send error message
      {:status 500
       :body   (:error response)})))

(def export-format-regex
  "Regex for matching valid export formats (e.g., `json`) for queries.
   Inteneded for use in an endpoint definition:

     (api/defendpoint POST [\"/:export-format\", :export-format export-format-regex]"
  (re-pattern (str "(" (str/join "|" (keys export-formats)) ")")))

(api/defendpoint POST ["/:export-format", :export-format export-format-regex]
  "Execute a query and download the result data as a file in the specified format."
  [export-format query]
  {query         su/JSONString
   export-format ExportFormat}
  (let [query (json/parse-string query keyword)]
    (api/read-check Database (:database query))
    (as-format export-format
      (qp/dataset-query (dissoc query :constraints)
        {:executed-by api/*current-user-id*, :context (export-format->context export-format)}))))

(api/define-routes
  (middleware/streaming-json-response (route-fn-name 'POST "/")))
