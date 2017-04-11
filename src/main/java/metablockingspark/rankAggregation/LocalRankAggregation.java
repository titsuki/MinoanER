/*
 * Copyright 2017 vefthym.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package metablockingspark.rankAggregation;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.io.Serializable;
import java.util.PriorityQueue;
import metablockingspark.utils.ComparableIntFloatPair;
import metablockingspark.utils.Utils;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.Optional;
import org.apache.spark.util.LongAccumulator;
import scala.Tuple2;

/**
 *
 * @author vefthym
 */
public class LocalRankAggregation implements Serializable {
    
    /**
     * Aggregates the two lists of candidate matches per entity using Borda, and returns the top-1 aggregate candidate match per entity. 
     * @param topKValueCandidates the top candidate matches per entity based on values, in the form: key: entityId, value: map of [candidateMatch, valueSim(entityId,candidateMatch)]
     * @param topKNeighborCandidates the top candidate matches per entity based on neighbors, in the form: key: entityId, value: ranked list of [candidateMatch]
     * @param LISTS_WITH_COMMON_CANDIDATES
     * @return the top-1 aggregate candidate match per entity
     */
    public JavaPairRDD<Integer,Integer> getTopCandidatePerEntity(JavaPairRDD<Integer, Int2FloatOpenHashMap> topKValueCandidates, JavaPairRDD<Integer, IntArrayList> topKNeighborCandidates, LongAccumulator LISTS_WITH_COMMON_CANDIDATES) {
        return topKValueCandidates                
                .mapValues(x -> new IntArrayList(Utils.sortByValue(x, true).keySet())) //sort the int2floatopenhashmap and get the keys (entityIds) sorted by values (value similarity) (descending)                
                .fullOuterJoin(topKNeighborCandidates)
                .mapValues(x -> top1Borda(x, LISTS_WITH_COMMON_CANDIDATES))
                .filter((x -> x._2() != null));
    }
    
    /**
     * Aggregates the two lists of candidate matches per entity using Borda, and returns the top-1 aggregate candidate match per entity. 
     * @param topKValueCandidates the top candidate matches per entity based on values, in the form: key: entityId, value: map of [candidateMatch, valueSim(entityId,candidateMatch)]
     * @param topKNeighborCandidates the top candidate matches per entity based on neighbors, in the form: key: entityId, value: ranked list of [candidateMatch]
     * @param LISTS_WITH_COMMON_CANDIDATES
     * @param K how many candidates to keep per entity
     * @return the top-K aggregate candidate match per entity
     */
    public JavaPairRDD<Integer,IntArrayList> getTopKCandidatesPerEntity(JavaPairRDD<Integer, Int2FloatOpenHashMap> topKValueCandidates, JavaPairRDD<Integer, IntArrayList> topKNeighborCandidates, LongAccumulator LISTS_WITH_COMMON_CANDIDATES, int K) {
        return topKValueCandidates                
                .mapValues(x -> new IntArrayList(Utils.sortByValue(x, true).keySet())) //sort the int2floatopenhashmap and get the keys (entityIds) sorted by values (value similarity) (descending)                
                .fullOuterJoin(topKNeighborCandidates)
                .mapValues(x -> topKBorda(x, LISTS_WITH_COMMON_CANDIDATES, K))
                .filter((x -> x._2() != null));
    }
    
    public Integer top1Borda(Tuple2<Optional<IntArrayList>, Optional<IntArrayList>> lists, LongAccumulator LISTS_WITH_COMMON_CANDIDATES) {
        IntArrayList list1 = lists._1().orNull();
        IntArrayList list2 = lists._2().orNull();        
        
        if (list1 == null && list2 == null) {
            return null;
        } else if (list2 == null) {
            //System.out.println("The only candidate (from values) is :"+list1.get(0));
            return list1.getInt(0);
        } else if (list1 == null) {
            //System.out.println("The only candidate (from neighbors) is :"+list2.get(0));
            return list2.getInt(0);
        }
        
        int size1 = list1.size();
        int size2 = list2.size();
        
        int maxSize = Math.max(size1, size2);
        
        Tuple2<Integer, Integer> top1 = new Tuple2<>(list1.get(0), maxSize); //(entityId, score) default winner is the first element of the first list (from values)
        
        //assign the biggest list to list1        
        if (size2 > size1) {
            list1 = list2;
            list2 = lists._1().get();            
            size1 = maxSize;            
        }
        
        //find common elements and elements only in list1
        boolean commonElementFound = false;
        int currScore = maxSize;
        for (int element1 : list1) {
            int score1 = currScore--;
            int indexIn2 = list2.indexOf(element1);
            if (indexIn2 == -1) {
                indexIn2 = size1; //check this value for non-existing elements in second list. set to size1 to always ignore such elements, set to size2 to add as last of queue2
            } else {
                commonElementFound = true;
            }
            int score2 = size1-indexIn2; //(size2-list2.indexOf(element1))+(size1-size2);           
            if (score1+score2 > top1._2()) {
                top1 = new Tuple2<>(element1, score1+score2);
            }
        }
        
        if (commonElementFound) {
            LISTS_WITH_COMMON_CANDIDATES.add(1);
        }
        
        //the following is not needed in case we always prefer the first list
        //find elements only in list2
        currScore = maxSize;
        for (int element2 : list2) {
            int score2 = currScore--;
            int indexIn1 = list1.indexOf(element2);
            if (indexIn1 == -1) {
                if (score2 > top1._2()) {
                    top1 = new Tuple2<>(element2, score2);
                }
            } //else, this has been already checked            
        }
        /*
        System.out.println("The top candidates from values are: "+list1+"\n"
                + "The top candidates from neighb are: "+list2+"\n"
                + "The top candidate is "+top1._1());*/
        return top1._1();        
    }
    
    
    public IntArrayList topKBorda(Tuple2<Optional<IntArrayList>, Optional<IntArrayList>> lists, LongAccumulator LISTS_WITH_COMMON_CANDIDATES, int K) {
        IntArrayList list1 = lists._1().orNull();
        IntArrayList list2 = lists._2().orNull();        
        
        if (list1 == null && list2 == null) {
            return null;
        } else if (list2 == null) {
            //System.out.println("The only candidate (from values) is :"+list1.get(0));
            return new IntArrayList(list1.subList(0, Math.min(K, list1.size())));
        } else if (list1 == null) {
            //System.out.println("The only candidate (from neighbors) is :"+list2.get(0));
            return new IntArrayList(list2.subList(0, Math.min(K, list2.size())));
        }
        
        int size1 = list1.size();
        int size2 = list2.size();
        
        int maxSize = Math.max(size1, size2);
        
        //assign the biggest list to list1        
        if (size2 > size1) {
            list1 = list2;
            list2 = lists._1().get();            
            size1 = maxSize;            
        }
        
        //find common elements and elements only in list1
        boolean commonElementFound = false;
        int currScore = maxSize;
        PriorityQueue<ComparableIntFloatPair> pq = new PriorityQueue<>();
        for (int element1 : list1) {
            int score1 = currScore--;
            int indexIn2 = list2.indexOf(element1);
            if (indexIn2 == -1) {
                indexIn2 = size1; //check this value for non-existing elements in second list. set to size1 to always ignore such elements, set to size2 to add as last of queue2
            } else {
                commonElementFound = true;
            }
            int score2 = size1-indexIn2; //(size2-list2.indexOf(element1))+(size1-size2);           
            int score = score1+score2;
            
            pq.add(new ComparableIntFloatPair(element1, score));
            if (pq.size() > K) {
                pq.poll();
            }
        }
        
        if (commonElementFound) {
            LISTS_WITH_COMMON_CANDIDATES.add(1);
        }
        
        //the following is not needed in case we always prefer the first list
        //find elements only in list2
        currScore = maxSize;
        for (int element2 : list2) {
            int score2 = currScore--;
            int indexIn1 = list1.indexOf(element2);
            if (indexIn1 == -1) {
                pq.add(new ComparableIntFloatPair(element2, score2));
                if (pq.size() > K) {
                    pq.poll();
                }
            } //else, this has been already checked            
        }
        
        return Utils.toIntArrayListReversed(pq);        
    }
    
}
