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
  (:require [clj-http.client :as client])
  (:require [clojure.data.json :as json])
)

(def trading-days-in-year 252)
(def start-date "20000101")
(def end-date "20091231")
(def data-table-uri "http://192.168.1.174:6502/v1/ivmero/api")
(def transaction-fee 10.0)

;;
;; Portal to Data Server
;;

(defn get-stock-price [row]
  "Submit a request to the DataTable service to get the stock price for a given row"
  (def data-table (str data-table-uri "/table/stock-price"))
  (-> 
   (client/get data-table {:query-params {"row" row}})
   (:body)
   (json/read-str :key-fn keyword)))

(defn get-data-range [from to]
  "Submit a request to the DataTable service to get the range of rows for a given date range"
  (def data-table (str data-table-uri "/table/range"))
  (-> 
    (client/get data-table {:query-params {"from" from "to" to}})
    (:body)
    (json/read-str :key-fn keyword)))

(defn get-value-from-datatable [row column]
  "Submit a request to the DataTable service to get a cell's value"
  (def data-table (str data-table-uri "/table/cell"))
  (-> 
    (client/get data-table {:query-params {"row" row "column" column}})
    (:body)
    (json/read-str :key-fn keyword)
    (:value))) 

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


(defn update-brokerage-account [brokerage-account buy-sell row]
  (let [stock-price (:adj-open get-stock-price (inc row))
        cash (:cash brokerage-account)
        fee (:transaction-fee brokerage-account)]
    (case (true? buy-sell) 
      true (let [quantity-to-purchase (int (/ (- cash fee) stock-price))
                 total-cost (+ (* stock-price quantity-to-purchase) fee)]
             (if (> quantity-to-purchase 0)
               (if (>= cash total-cost)
                 (->
                  (update brokerage-account :stock + quantity-to-purchase)
                  (update :cash - total-cost)))))
      false (let [stock (:stock brokerage-account)
                  total-gain (- (* stock-price stock) fee)]
              (if (and (> stock 0) (> total-gain 0.0))
                (->
                 (assoc brokerage-account :stock 0)
                 (update :cash + total-gain)))))
    brokerage-account))

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
        {:instruction (int (- (rand-int 100) (/ 100 2))) :close 1}
        ))

(defn random-data-load-plush-genome
  "Returns a random Plush genome to load data from the data server with size limited by max-genome-size."
  [genome-size]
  ;; (vec (repeatedly genome-size
  ;;                     #(random-data-load-plush-instruction)))
  (loop [n genome-size
         result []]
    (if (zero? n)
;;      (seq result)
      result
      (recur (dec n) (random-data-load-plush-instruction result))))
)

;; ;;
;; ;; Error function utilities
;; ;;

(defn eval-test-case [input-start individual]
  "Evaluates a test case by processing a year's worth of input and returns the aggregate error"
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

        (> row (+ input-start trading-days-in-year)) 
        (- (get-brokerage-account-value brokerage-account row))

        :else 
        (recur (update-brokerage-account brokerage-account top-boolean row) 
               (inc row))))))

(defn add-data-load-instructions
  [individual]
  (let [ind1 (assoc individual 
                    :genome (into [] (cond-> []
                                       (not (get individual :load-data-instructions-added false)) 
                                       (concat (random-data-load-plush-genome 10))
                                       
                                       :always (concat (:genome individual))))
                    :program (if (get individual :load-data-instructions-added false)
                               (:program individual)
                               nil)
                    )
        ind2 (assoc ind1
                    :program (translate-plush-genome-to-push-program ind1 @push-argmap))
        ]
    
    ind2)
  )

;; (def argmap
;;   {
;;    ;; :error-function (fn [individual]
;;    ;;                   (let [data-range (get-data-range start-date end-date)
;;    ;;                         contains-load-data-instructions (get individual :load-data-instructions-added false)
;;    ;;                         n (:max-genome-size-in-initial-program @push-argmap)]
;;    ;;                     (do
;;    ;;                       (if (not contains-load-data-instructions)
;;    ;;                         (assoc individual
;;    ;;                                :genome (conj (random-data-load-plush-genome n) (:genome individual))
;;    ;;                                :load-data-instructions-added true))
;;    ;;                       (assoc individual
;;    ;;                                 :errors
;;    ;;                                 (doall
;;    ;;                                  (for [input-start (range (:start data-range) (- (:end data-range) trading-days-in-year))]
;;    ;;                                    (eval-test-case input-start individual)))))))

;;    :error-threshold 0.01
;;    ;; :Atom-generators (concat registered-nonrandom                           ;; all registered instrs except random instructions
;;    ;;                          (repeatedly 100 #(rand-int Integer/MAX_VALUE))  ;; random integers 
;;    ;;                          (repeatedly 100 #(rand Float/MAX_VALUE)))       ;; random floats
;;    :population-size 1000
;;    :epigenetic-markers []
;;    :parent-selection :epsilon-lexicase
;;    :genetic-operator-probabilities {:alternation 0.5
;;                                     :uniform-mutation 0.5}
;;    :uniform-mutation-rate 0.1
;;    :alternation-rate 0.1
;;    :alignment-deviation 100                                
;;    :uniform-mutation-constant-tweak-rate 0.8
;;    :uniform-mutation-float-gaussian-standard-deviation 0.1
;;    })


;;;;;;;;;;;;
;; Integer symbolic regression of x^3 - 2x^2 - x (problem 5 from the 
;; trivial geography chapter) with minimal integer instructions and an 
;; input instruction that uses the default input stack

(def argmap
  {
   ;; :error-function (fn [individual]
   ;;                   (assoc individual
   ;;                          :errors
   ;;                          (doall
   ;;                           (for [input (range 10)]
   ;;                             (let [state (run-push (:program individual)
   ;;                                                   (push-item input :input 
   ;;                                                              (push-item input :integer 
   ;;                                                                         (make-push-state))))
   ;;                                   top-int (top-item :integer state)]
   ;;                               (if (number? top-int)
   ;;                                 (abs (- top-int 
   ;;                                         (- (* input input input) 
   ;;                                            (* 2 input input) input)))
   ;;                                 1000))))))


   :error-function (fn [individual]
                     (let [data-range (get-data-range start-date end-date)
                           contains-load-data-instructions (get individual :load-data-instructions-added false)
                           n (:max-genome-size-in-initial-program @push-argmap)]
                       (do
                         (if (not contains-load-data-instructions)
                           (assoc individual
                                  :genome (conj (random-data-load-plush-genome n) (:genome individual))
                                  :load-data-instructions-added true))
                         (assoc individual
                                   :errors
                                   (doall
                                    (for [input-start (range (:start data-range) (- (:end data-range) trading-days-in-year))]
                                      (eval-test-case input-start individual)))))))

   :error-threshold 0.01
   
   :atom-generators (concat (registered-nonrandom)                          ;; all registered instrs except random instructions
                            ;; (repeatedly 100 #(rand-int Integer/MAX_VALUE))  ;; random integers 
                            ;; (repeatedly 100 #(rand Float/MAX_VALUE))        ;; random floats
                            )       


   :population-size 100
   :max-generations 10
   :epigenetic-markers []
   :parent-selection :epsilon-lexicase
   :genetic-operator-probabilities {:alternation 0.5
                                    :uniform-mutation 0.5}
   :uniform-mutation-rate 0.1
   :alternation-rate 0.1
   :alignment-deviation 100                                
   :uniform-mutation-constant-tweak-rate 0.8
   :uniform-mutation-float-gaussian-standard-deviation 0.1
   })

;; (assoc individual 
;;                  :genome (into []  (cond-> []
;;                                      (not contains-load-data-instructions) (concat (random-data-load-plush-genome 10))
;;                                      :always (concat (strip-random-insertion-flags
                              ;; (random-plush-genome
                              ;;  max-genome-size-in-initial-program
                              ;;  atom-generators
                              ;;  argmap)))))



(defn -test
  []
  ;; (println (registered-for-stacks [:integer :boolean :code :exec]))
  (println
   (let [max-genome-size-in-initial-program 10
         atom-generators (registered-for-stacks [:integer :boolean :code :exec])
         individual (make-individual
                     :genome (strip-random-insertion-flags
                              (random-plush-genome
                               max-genome-size-in-initial-program
                               atom-generators
                               argmap))
                     :genetic-operators :random)
         data-range (get-data-range start-date end-date)
;;         contains-load-data-instructions (get individual :load-data-instructions-added false)
;;         n (:max-genome-size-in-initial-program @push-argmap)
         
         gen-indy (add-data-load-instructions individual)
         ]
     (do
       (println "gen-indy = " individual)
       ;; (println (run-push (:program gen-indy)
       ;;                    (push-item (:from data-range) :input
       ;;                               (make-push-state))))

       (println "eval-test-case = " (eval-test-case (:from data-range) individual)
                )
       )
     )
   )


  ;; (println (run-push (:program (add-data-load-instructions individual))
  ;;                    (push-item (:from data-range) :input
  ;;                               (make-push-state))))




  ;; (println (run-push (:program individual)
  ;;                    (make-push-state))))))










  ;; (println "***** New Genome *****")
  ;; (println  (into []  (cond-> []
  ;;                       (not contains-load-data-instructions) (concat (random-data-load-plush-genome 10))
  ;;                       :always (concat (:genome individual)))))
  ;; (println "")

  ;; (println "***** New Program *****")
  ;; (println 
  ;;  (translate-plush-genome-to-push-program 
  ;;    (assoc individual 
  ;;           :genome (into []  (cond-> []
  ;;                               (not contains-load-data-instructions) (concat (random-data-load-plush-genome 10))
  ;;                               :always (concat (:genome individual)))))
  ;;   @push-argmap))
  ;; (println "done")

  ;; (println "***** New Individual *****")
  ;; (println 
  ;;  (assoc individual 
  ;;         :genome (into [] (cond-> []
  ;;                            (not contains-load-data-instructions) (concat (random-data-load-plush-genome 10))
  ;;                            :always (concat (:genome individual))))))

  ;; (println "")


  ;; (println "")
  ;; (println "***** Original Genome *****")
  ;; (println (:genome individual))
  ;; (println "")



  ;; (println (run-push (:program individual)
  ;;                    (push-item (:start data-range) :input
  ;;                    (make-push-state))))

  ;; (assoc i :program (translate-plush-genome-to-push-program i argmap)


  ;;       (println (eval-test-case (:start data-range) individual))
  
  ;;        (assoc (if (not contains-load-data-instructions)
  ;;                 (do
  ;;                  (println "***** not contains-load-data-instructions *****")
  ;;                  (assoc individual
  ;; ;;                :genome (into [] cat [(random-data-load-plush-genome max-genome-size-in-initial-program) (:genome individual)])
  ;;                         :program (translate-plush-genome-to-push-program individual @push-argmap)
  ;;                         :load-data-instructions-added true))
  
  ;;                 ((println "***** contains-load-data-instructions *****")

  ;;                  individual))
  
  ;;               :errors
  ;;               [100]
  ;;               ;; (doall
  ;;               ;;  (for [input-start (range (:start data-range) (- (:end data-range) trading-days-in-year))]
  ;;               ;;    (eval-test-case input-start individual)
  ;;               ;;    )
  ;;               ;;  )
  ;;        )



  ;; (println "***** Original Program *****")
  ;; (println (:program individual))
  ;; (println "")

  ;; (println "***** New Program *****")
  ;; (println (translate-plush-genome-to-push-program individual @push-argmap))
  ;; (println "")
  ;; (println "***** FInal Individual *****")
  ;; individual
  )
