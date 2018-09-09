;; genesis.clj

(ns clojush.genesis.genesis
  (:use [clojush.pushgp.pushgp]
        [clojush.pushstate]
        [clojush.util]
        [clojush.random]
        [clojush.interpreter]
        [clojure.math.numeric-tower]
        [clojush args random util globals]
        [clojush.individual]
        [clojush.translate])
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
	    [semantic-csv.core :refer :all]
	    [clojure.data.csv :as cd-csv]
            [clojure-csv.core :as csv]
	    [clojure.java.io :as io])
)

(def trading-days-in-year 252)
;; (def start-date "20000101")
;; (def end-date "20091231")
;; (def data-table-uri "http://192.168.1.174:6502/v1/ivmero/api")
(def transaction-fee 10.0)
(def adj-close-column 2)

;;
;; Data Table
;;

(defn load-datatable
  "Loads the data file into a data table"
  []
  (drop 1 
        (with-open [in-file (io/reader "RandomData.csv")]
          (->>
           (csv/parse-csv in-file)
           mappify
           (cast-with #(Double/parseDouble %))
           vectorize
           doall)
          )))

(def datatable (load-datatable))

(defn datatable-rows
  "Returns the number of rows in global data table"
  []
  (count datatable))

(defn datatable-columns
  "Returns the number of columns in global data table"
  []
  (count (first datatable)))

(defn get-cell-from-datatable
  "Return cell at location row / column from the global datatable"
  [row column]
  (if (or (< row 0) (>= row (datatable-rows)) (< column 0))
    0.0
    (let [bounded-column (mod column (datatable-columns))] 
      (nth 
       (nth datatable row) 
       bounded-column))))

(defn get-value-from-datatable
  "Return a cell's value at location row / column from the global datatable"
  [row column]
  (if (< column 0)
    (let [col (- -1 column)]
      (if (> row 0)
        (- (get-cell-from-datatable row column) (get-cell-from-datatable (dec row) column))
        0.0))  
    (if (< row 0)
      0.0
      (get-cell-from-datatable row column)
      )))

(defn get-data-range
  []
  (let [data-range {:from 0}]
    (assoc data-range :to (dec (datatable-rows))))
  )

(defn get-stock-price
  "Return the value of the Adjusted Close Stock Price column from the global datatable"
  [row]
  (get-value-from-datatable row adj-close-column))

;; ;;
;; ;; PushP instructions
;; ;;

(define-registered
  float_fromdatatable
  ^{:stack-types [:input :integer :float]}
  (fn [state]
    (if (and (not (empty? (:integer state)))
             (not (empty? (:input state))))
      (let [row (top-item :input state)
            column (top-item :integer state)]
        (->> (pop-item :integer state)
             (push-item (keep-number-reasonable (get-value-from-datatable row column)) :float)
             )
        )
      state)))

;; ;;
;; ;; Brokerage utilities
;; ;;

(def brokerage-account-fields '(:cash :stock :transaction-fee))

(defmacro define-brokerage-account-state-record-type []
 `(defrecord ~'BrokerageAccountState [~@(map keyword->symbol brokerage-account-fields)]))

(define-brokerage-account-state-record-type)

(let [empty-brokerage-account-state (map->BrokerageAccountState {:cash 0.0
                                                                 :stock 0
                                                                 :transaction-fee transaction-fee})]
  (defn make-brokerage-account
    "Returns an empty brokerage account state."
    [] empty-brokerage-account-state))


(defn update-brokerage-account 
  [brokerage-account buy-sell row]
  (let [stock-price (get-stock-price (inc row))
        cash (:cash brokerage-account)
        fee (:transaction-fee brokerage-account)]
    (case (true? buy-sell) 
      true (let [quantity-to-purchase (int (/ (- cash fee) stock-price))
                 total-cost (+ (* stock-price quantity-to-purchase) fee)]
             (if (and (> quantity-to-purchase 0) (>= cash total-cost))
               (->
                (update brokerage-account :stock + quantity-to-purchase)
                (update :cash - total-cost))
               brokerage-account))
    
      false (let [stock (:stock brokerage-account)
                  total-gain (- (* stock-price stock) fee)]
              (if (and (> stock 0) (> total-gain 0.0))
                (->
                 (assoc brokerage-account :stock 0)
                 (update :cash + total-gain))
                brokerage-account)))))

(defn get-brokerage-account-value [brokerage-account row]
  (:cash (update-brokerage-account brokerage-account false row)))

;; ;;
;; ;; PushGP helper functions
;; ;;

(defn random-data-load-plush-instruction
  "Returns a genme to load a data cell from the data server"
  [genome]
  (conj genome
        {:instruction 'noop_open_paren} 
        {:instruction 'float_fromdatatable} 
;;        {:instruction (int (- (rand-int Integer/MAX_VALUE) (/ Integer/MAX_VALUE 2))) :close 1}
        ;; {:instruction (int (- (rand-int 100) (/ 100 2))) :close 1}
        {:instruction (int (- (* 2 (rand-int (datatable-columns))) (datatable-columns))) :close 1}
        ))

(defn random-data-load-plush-genome
  "Returns a random Plush genome to load data from the datatable with size limited by genome-size."
  [genome-size]
  (loop [n (inc (rand-int (dec genome-size)))
         result []]
    (if (zero? n)
      result
      (recur (dec n) (random-data-load-plush-instruction result))))
)

;; ;;
;; ;; Error function utilities
;; ;;

(defn eval-test-case 
  "Evaluates a test case by processing a year's worth of input and returns the aggregate error"
  [input-start individual]
  (loop [brokerage-account (make-brokerage-account)
         row input-start]
    (let [state (run-push (:program individual)
                          (push-item row :input
                                     (make-push-state)))
          top-boolean (top-item :boolean state)  
          invalid-output (= (:termination state) :abnormal)]
      (cond
        invalid-output 
        max-number-magnitude

        (or (> row (+ input-start trading-days-in-year)) (>= row (dec (datatable-rows)))) 
        (let [error (- (get-brokerage-account-value brokerage-account row))]
          (if (zero? error)
            max-number-magnitude
            error))

        :else 
        (recur (update-brokerage-account brokerage-account top-boolean row) 
               (inc row))))))

(defn add-data-load-instructions
  [individual]
  (let [ind1 (assoc individual 
                    :genome (into [] (cond-> []
                                       (and (not (get individual :load-data-instructions-added false))
                                            (get individual :genome [])) 
                                       (concat (random-data-load-plush-genome 10))
        
                                       :always (concat (:genome individual))))
                    :program (if (get individual :load-data-instructions-added false)
                               (:program individual)
                               nil)
                    )
        ind2 (assoc ind1
                    :program (translate-plush-genome-to-push-program ind1 @push-argmap))
        ]
    
    (assoc ind2 :load-data-instructions-added true)))

(def argmap
  {
   :error-function (fn [individual]
                     (let [data-range (get-data-range)
                           gen-indy (add-data-load-instructions individual)
                           ]
                       (assoc gen-indy
                              :errors
                              (doall
                               (for [input-start (range (:from data-range) (- (:to data-range) trading-days-in-year))]
                                 (eval-test-case input-start gen-indy))))))

   :error-threshold 0.01

   :atom-generators (concat (registered-nonrandom)                          ;; all registered instrs except random instructions
                            ;; (repeatedly 100 #(rand-int Integer/MAX_VALUE))  ;; random integers 
                            ;; (repeatedly 100 #(rand Float/MAX_VALUE))        ;; random floats
                            
                            (list
                             (fn [] (lrand-int 100))                        ;; random integers
                             (fn [] (lrand)))                               ;; random floats
                            )       

   :use-single-thread false
   :population-size 5
   :max-generations 2
   :epigenetic-markers []
   :parent-selection :epsilon-lexicase
   :genetic-operator-probabilities {:alternation 0.5
                                    :uniform-mutation 0.5}
   :uniform-mutation-rate 0.1
   :alternation-rate 0.1
   :alignment-deviation 100                                
   :uniform-mutation-constant-tweak-rate 0.8
   :uniform-mutation-float-gaussian-standard-deviation 0.1

   :print-csv-logs true
   :print-edn-logs true
   :print-json-logs true

   :csv-columns [:generation :location :parent-uuids :genetic-operators :push-program-size :plush-genome-size :push-program :plush-genome :total-error :test-case-errors]
   ;; The columns to include in a printed CSV beyond the generation and individual. Options
   ;; include: [:generation :location :parent-uuids :genetic-operators :push-program-size
   ;; :plush-genome-size :push-program :plush-genome :total-error :test-case-errors]

   :edn-keys [:uuid :parent-uuids :genetic-operators :program :genome :total-error :errors]
   ;; Keys from clojush.individual.individual that should be included.
   
   :edn-additional-keys [:generation :location :push-program-size :plush-genome-size]
   ;; Additional information to include in the edn-printout. Available options are
   ;; [:generation :location :push-program-size :plush-genome-size].

   :log-fitnesses-for-all-cases true
   ;; If true, the CSV and JSON logs will include the fitnesses of each individual on every
   ;; test case.

   :json-log-program-strings true
   ;; If true, JSON logs will include program strings for each individual.
   }
  )
