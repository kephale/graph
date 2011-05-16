;; regexp.clj
(ns examples.regexp
  (:use ;;[runner]
	[clojush :exclude [ensure-list rcons]]
	[graph :exclude 'add-edge]
	[graph-utils.fa :exclude '*verbose*]))

;;(def *verbose* (atom false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; modification clojush to print the best graph
(in-ns 'clojush)
(use 'graph)
(reset! global-error-reuse false)
(def best-graph (atom ()))
       
(defn graph-display [loc]
  (when (seq loc)
    (list loc
	  {:nodes (nodes loc)
	   :edges (map #(select-keys % '(:from :to :read)) (edges loc))
	   :accept-nodes (map node
			      (filter #((accept-fn loc) %)
				      (map #(move-graph loc %) (nodes loc))))
	   })))

(defn problem-specific-report
  [best population generation error-function report-simplifications]
  (println "\nBest graph:" (graph-display (:graph (aux/find-first
						   #(and (= (:code %)
							    (:program best))
							 (= (ensure-list (:fitness %))
							    (:errors best)))
						   @best-graph)))))
(defn stack-safe? [type state & [n]]
  (and (n-on-stack? (or n 1) type state)
       (not-any? #(or (= :no-stack-item %)
		      (nil? %)
		      (and (coll? %) (empty? %)))
		 (take (or n 1) (type state)))))

(defn safe-top-item [type state]
  (and (stack-safe? type state) (top-item type state)))
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(in-ns 'examples.regexp)
(use 'clojush)
(use 'graph-utils.fa)

(def aa*b
     (fa-graph {'a* {"a" ['a* 'a*a]}
		'a*a {"b" 'accept}}
	       {'start {"a" ['a* 'a*a] "b" 'dead}}
	       #{'accept}
	       nil))

(def alphabet '("a" "b"))

(def first-10-regexps (doall (enumerate-strings aa*b 10)))

(defn is-graph? [loc]
  (and (vector? loc)
       (symbol? (first loc))
       (map? (loc 1))
       (meta loc)
       loc))

(defn eval-machine [char-list loc]
  (if (is-graph? loc)
    (deref (evaluate (with-input (move-graph loc 'start) char-list)))
    (do (println "loc is not a graph: " loc)
	:reject)))

(defn new-empty-graph []
  (fa-graph {'start {:epsilon 'accept}}
	    {}
	    #{'accept}
	    nil))

(defn edge-exists? [loc from to read]
  (contains? (set (map #(select-keys % '(:from :to :read)) (edges loc)))
	     {:read read :from from :to to}))

#_(-> (new-empty-graph)
    (add-node 'A)
    (add-node 'B)
    (add-edge 'start 'A (edge-test "a") (edge-transition-rule 'start 'A "a") "a")
    (add-edge 'start 'B (edge-test "a") (edge-transition-rule 'start 'B "a") "a")
    (add-edge 'A 'A (edge-test "a") (edge-transition-rule 'A 'A "a") "a")
    (add-edge 'A 'B (edge-test "a") (edge-transition-rule 'A 'B "a") "a")
    (add-edge 'B 'accept (edge-test "b") (edge-transition-rule 'B 'accept "b") "b")
    (with-input "aab")
    (evaluate))

(defn regexp-fitness
  [program]
  (let [state (run-push program (push-item (new-empty-graph) :auxiliary (make-push-state)) @*verbose*)
	evolved-graph (top-item :auxiliary state)
	regexps-1 (take 5 (shuffle first-10-regexps))
	regexps-2 (doall (enumerate-strings evolved-graph 10))
	fit-0 (->> (edges evolved-graph)
		    (map :read)
		    (filter #(= % :epsilon))
		    (count)
		    (* 2))
	fit-1 (->> (pmap #(eval-machine % evolved-graph) regexps-1)
		   (filter #(= :accept %))
		   (count)
		   (- 5))
	fit-2 (->> (pmap #(eval-machine % aa*b) regexps-2)
 		   (filter #(= :accept %))
 		   (count)
 		   (- 10))
 	fitness (+ fit-0 fit-1 fit-2)]
    (cond (or (not (seq @best-graph))
 	      (> (:fitness (first @best-graph)) fitness))
     	  (reset! best-graph
 		  (list {:fitness fitness :code program
 			 :graph (top-item :auxiliary state)}))
 	  (= (:fitness (first @best-graph)) fitness)
 	  (swap! best-graph conj {:fitness fitness :code program
				  :graph (top-item :auxiliary state)}))
     (list fitness)))

#_(-> (random-code 10 regexp-instructions)
      (regexp-fitness))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Push graph instructions

;; moves the graph to the start node
(define-registered start
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  g (is-graph? (move-graph top-aux 'start))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))

;; moves the graph to the accept node
(define-registered accept
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  g (is-graph? (move-graph top-aux 'accept))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))

;; move to the nth child of the current node
(define-registered nth-next
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  top-int (safe-top-item :integer state)
		  next-locs (aux/return-if-all (ensure-list (next-generator top-aux)) #(> (count %) 0))
		  g (is-graph? (nth next-locs (mod top-int (count next-locs))))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))


;; move the graph back to the last valid location
(define-registered prev
  (fn [state]
    (aux/if-let* [top-aux (is-graph? (safe-top-item :auxiliary state))
		  g (is-graph? (prev top-aux))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))

;; jump to the nth added node (leverages gensym's lexicographic ordering)
(define-registered nth-node
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  top-int (safe-top-item :integer state)
		  g (is-graph? (move-graph top-aux
					   (nth (sort (nodes top-aux))
						(mod top-int (count (nodes top-aux))))))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))
      
				      
;; add completely new node, but do not move the graph
(define-registered add-unconnected-node
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  g (is-graph? (add-node top-aux (gensym "NODE::")))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))

;;adds a node moves the graph to the added location
(define-registered add-connected-node
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  top-int (safe-top-item :integer state)
		  read (nth (cons :epsilon alphabet)
			    (mod top-int (inc (count alphabet))))
		  to (gensym "NODE::")
		  g (is-graph? (move-graph (add-edge (add-node top-aux to)
						 (node top-aux)
						 to
						 (edge-test read)
						 (edge-transition-rule (node top-aux) to read)
						 read)
					   to))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))


;; remove the node that the graph is currently at
;; chose here instead of prev to counterbalance presence of start and accept
(define-registered remove-node
  (fn [state]
    (aux/if-let* [top-aux (aux/return-if-all (safe-top-item :auxiliary state)
					     #(not (contains? #{'start 'accept} (node %))))
		  g (is-graph? (remove-node top-aux (node top-aux)))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))
  
;; add an edge between the current location and two before where the read value
;; is the last input read and moves the graph to that location
(define-registered add-edge
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  top-int (safe-top-item :integer state)
		  alpha (cons :epsilon alphabet)
		  read (nth alpha (mod top-int (count alpha)))
		  path (and (> (count (path top-aux)) 2) (path top-aux))
		  g (is-graph?
		     (when-not (edge-exists? top-aux (node top-aux) (nth path 2) read)
		       (move-graph (add-edge top-aux
					     (node top-aux)
					     (nth path 2)
					     (edge-test read)
					     (edge-transition-rule (node top-aux) (second path) read)
					     read)
				   (nth path 2))))]
		 (push-item g :auxiliary  (pop-item :auxiliary state))
		 state)))

;; analogue to nth-node; adds an edge between the current node and the nth connected node.
;; Does not move.
(define-registered add-nth-edge
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  top-int (safe-top-item :integer state)
		  alpha (cons :epsilon alphabet)
		  read (nth alpha (mod top-int (count alpha)))
		  to (nth (sort (nodes top-aux)) (mod top-int (count (nodes top-aux))))
		  g (is-graph? (add-edge top-aux
					 (node top-aux)
					 to
					 (edge-test read)
					 (edge-transition-rule (node top-aux) to read)
					 read))]
		  (push-item g :auxiliary (pop-item :auxiliary state))
		  state)))

;; Removes the edge between the current node and the previously visited node. Stays at the current node.
(define-registered remove-edge
  (fn [state]
    (aux/if-let* [top-aux (aux/return-if-all (safe-top-item :auxiliary state)
					     is-graph? #(first (path %)))
		  g (is-graph? (remove-edge top-aux (node top-aux) (first (path top-aux))))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))

;; Connects the the node sitting atop the auxiliary stack to the accept node. Does not move the node;
;; just adds an edge whose transition function is a function of the alphabet plus :epsilon
(define-registered connect-to-accept
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
		  top-int (safe-top-item :integer state)
		  g (-> (let [loc top-aux
			      read (nth (cons :epsilon alphabet) (mod top-int (inc (count alphabet))))]
		      (when-not (some #(= % {:read read :to 'accept :from (node loc)})
				      (map #(select-keys % (list :from :to :read)) (edges loc)))
			(add-edge loc
				  (node loc)
				  'accept
				  (edge-test read)
				  (edge-transition-rule (node loc) 'accept read)
				  read)))
			(is-graph?))]
		 (push-item g :auxiliary (pop-item :auxiliary state))
		 state)))

;; THIS INSTRUCTION FOR TESTING PURPOSES ONLY
;; it enumerates the search space
(define-registered add-connected-nodes
  (fn [state]
    (aux/if-let* [top-aux (safe-top-item :auxiliary state)
	      g (loop [loc top-aux remaining-reads (cons :epsilon alphabet)]
		  (let [from (node loc)
			to (gensym "NODE::")
			read (first remaining-reads)]
		    (cond (nil? read) loc
			  (contains? (set (map :read (edges loc))) read) (recur loc (rest remaining-reads))
			  :else (recur (add-edge (add-edge (add-node loc to) from to
							   (edge-test read)
							   (edge-transition-rule from to read)
							   read)
						 to 'accept (edge-test :epsilon) (edge-transition-rule to 'accept :epsilon) :epsilon)
				       (rest remaining-reads)))))]
	     (push-item g :auxiliary (pop-item :auxiliary state))
      state)))

(def regexp-instructions (cons (fn [] (rand-int 50))
			       '(start accept nth-next prev nth-node add-unconnected-node add-connected-node
				       remove-node add-edge add-nth-edge remove-edge ;;add-connected-nodes
				       connect-to-accept)))

(defn -main [max-gens max-points pop-size]
  (do (reset! best-graph '())
      (pushgp :error-function regexp-fitness
	      :atom-generators regexp-instructions
	      :max-generations max-gens
	      :max-points max-points
	      :population-size pop-size)))

;; (in-ns 'runner)
;; (use 'clojush)
;; (use 'examples.regexp)
;; ;;(reset! *verbose* true)

;; (defn run [params]
;;   (let [max-gen (params :max-generations)
;; 	max-points (params :max-points)
;; 	pop-size (params :population-size)]
;;     (try 
;;       (do (reset! best-graph '())
;; 	  (pushgp :error-function regexp-fitness
;; 		  :atom-generators regexp-instructions
;; 		  :max-generations max-gen
;; 		  :max-points max-points
;; 		  :population-size pop-size))
;;       (catch Exception e :error))))
;;     ;; 	    :report-simplifications 0
;; ;; 	    :final-report-simplifications 0
;; ;; 	    :reproduction-simplifications 0))

