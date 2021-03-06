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
package minoaner.relationsWeighting;

import com.google.common.collect.Ordering;
import it.unimi.dsi.fastutil.floats.FloatOpenHashSet;
import it.unimi.dsi.fastutil.floats.FloatSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import minoaner.utils.ComparableIntFloatPair;
import minoaner.utils.ComparableIntFloatPairDescendingComparator;
import minoaner.utils.Utils;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

/**
 *
 * @author vefthym
 */
public class RelationsRank implements Serializable {
    
    /**
     * return a map of topN neighbors per entity (reversed to point to in-neighbors (values) having the key entity as their top out-neighbor)
     * @param rawTriples
     * @param SEPARATOR
     * @param entityIdsRDD
     * @param MIN_SUPPORT_THRESHOLD
     * @param N topN neighbors per entity
     * @param positiveIds
     * @param jsc
     * @return 
     */
    public Map<Integer,IntArrayList> run(JavaRDD<String> rawTriples, String SEPARATOR, JavaRDD<String> entityIdsRDD, float MIN_SUPPORT_THRESHOLD, int N, boolean positiveIds, JavaSparkContext jsc) {
        //rawTriples.persist(StorageLevel.MEMORY_AND_DISK_SER());        
        
        //List<String> subjects = Utils.getEntityUrlsFromEntityRDDInOrder(rawTriples, SEPARATOR); //a list of (distinct) subject URLs, keeping insertion order (from original triples file)        
        //Object2IntOpenHashMap<String> subjects = Utils.getEntityIdsMapping(rawTriples, SEPARATOR);
        Object2IntOpenHashMap<String> entityIds = Utils.readEntityIdsMapping(entityIdsRDD, positiveIds);
        System.out.println("Found "+entityIds.size()+" entities in collection "+ (positiveIds?"1":"2"));
        
        long numEntitiesSquared = (long)entityIds.keySet().size();
        numEntitiesSquared *= numEntitiesSquared;
        
        Broadcast<Object2IntOpenHashMap<String>> entityIds_BV = jsc.broadcast(entityIds);
         
        JavaPairRDD<String,List<Tuple2<Integer, Integer>>> relationIndex = getRelationIndex(rawTriples, SEPARATOR, entityIds_BV); //a list of (s,o) for each predicate      
        
        //rawTriples.unpersist();        
        relationIndex.persist(StorageLevel.MEMORY_AND_DISK_SER());                
                        
        List<String> relationsRank = getRelationsRank(relationIndex, MIN_SUPPORT_THRESHOLD, numEntitiesSquared);      
        System.out.println("Top-5 relations in collection "+(positiveIds?"1: ":"2: ")+Arrays.toString(relationsRank.subList(0, Math.min(5,relationsRank.size())).toArray()));
        
        JavaPairRDD<Integer, IntArrayList> topOutNeighbors = getTopOutNeighborsPerEntity(relationIndex, relationsRank, N, positiveIds); //action
        
        relationIndex.unpersist(); 
        
        //reverse the outNeighbors, to get in neighbors
        Map<Integer, IntArrayList> inNeighbors =
        topOutNeighbors.flatMapToPair(x -> { //reverse the neighbor pairs from (in,[out1,out2,out3]) to (out1,in), (out2,in), (out3,in)
                    List<Tuple2<Integer,Integer>> inNeighbs = new ArrayList<>();
                    for (int outNeighbor : x._2()) {
                        inNeighbs.add(new Tuple2<>(outNeighbor, x._1()));
                    }
                    return inNeighbs.iterator();
                })
                .aggregateByKey(new IntOpenHashSet(), 
                        (x,y) -> {x.add(y); return x;}, 
                        (x,y) -> {x.addAll(y); return x;})
                .mapValues(x-> new IntArrayList(x))
                .collectAsMap();
        
        return inNeighbors;
    }
    
    
    
    /**
     * Returns a list of relations sorted in descending score. The rank of each relation is its index in this list (highest rank = index 0)    
     * @param relationIndex
     * @param minSupportThreshold the minimum support threshold allowed, used for filtering relations with lower support
     * @param numEntitiesSquared
     * @return a list of relations sorted in descending score.
     */
    public List<String> getRelationsRank(JavaPairRDD<String,List<Tuple2<Integer, Integer>>> relationIndex, float minSupportThreshold, long numEntitiesSquared) {                        
        JavaPairRDD<String,Float> supports = getSupportOfRelations(relationIndex, numEntitiesSquared, minSupportThreshold);
        JavaPairRDD<String,Float> discrims = getDiscriminabilityOfRelations(relationIndex);
        
        return getSortedRelations(supports, discrims);
    }   
    
    /**
     * Returns a relation index of the form: key: relationString, value: list of (subjectId, objectId) linked by this relation.
     * @param rawTriples
     * @param SEPARATOR
     * @param subjects_BV
     * @return a relation index of the form: key: relationString, value: list of (subjectId, objectId) linked by this relation
     */
    public JavaPairRDD<String,List<Tuple2<Integer, Integer>>> getRelationIndex(JavaRDD<String> rawTriples, String SEPARATOR, Broadcast<Object2IntOpenHashMap<String>> subjects_BV) {        
        return rawTriples        
        .mapToPair(line -> {
          String[] spo = line.toLowerCase().replaceAll(" \\.$", "").split(SEPARATOR); //lose the ending " ." from valid .nt files
          if (spo.length != 3) {
              return null;
          }
          int subjectId = subjects_BV.value().getInt(Utils.encodeURIinUTF8(spo[0])); //replace subject url with entity id (subjects belongs to subjects by default)
          int objectId = subjects_BV.value().getOrDefault(Utils.encodeURIinUTF8(spo[2]), -1); //-1 if the object is not an entity, otherwise the entityId      
          return new Tuple2<>(spo[1], new Tuple2<>(subjectId, objectId)); //relation, (subjectId, objectId)
        })        
        .filter(x -> x!= null)
        .groupByKey()       
        .filter(x -> {                  //keep only relations (properties that have more object values than datatype values)
            int relationCount = 0;
            int numInstances = 0;
            for (Tuple2<Integer,Integer> so : x._2()) {
                numInstances++;
                if (so._2() != -1) {
                    relationCount++;
                }
            }
            return relationCount > (numInstances-relationCount); //majority voting (is this property used more as a relation or as a datatype property?
        })
        .mapValues(x -> {
            List<Tuple2<Integer,Integer>> relationsOnly = new ArrayList<>();
            for (Tuple2<Integer,Integer> so : x) {                
                if (so._2() != -1) {
                    relationsOnly.add(new Tuple2<>(so._1(), so._2())); //(subject, object) pairs connected with this relation
                } 
            }
            return relationsOnly;
        });        
    }
    
    public JavaPairRDD<String,Float> getSupportOfRelations(JavaPairRDD<String,List<Tuple2<Integer, Integer>>> relationIndex, long numEntititiesSquared, float minSupportThreshold) {
        JavaPairRDD<String, Float> unnormalizedSupports = relationIndex
                .mapValues(so -> (float)so.size() / numEntititiesSquared);
        unnormalizedSupports.setName("unnormalizedSupports").cache();        
        
        System.out.println(unnormalizedSupports.count()+" relations have been assigned a support value"); // dummy action to trigger execution
        float max_support = unnormalizedSupports.values().max(Ordering.natural());        
        return unnormalizedSupports
                .mapValues(x-> x/max_support)           //normalize the support values
                .filter(x-> x._2()> minSupportThreshold); //filter out relations below the min support threshold (infrequent relations)
    }
    
    public JavaPairRDD<String,Float> getDiscriminabilityOfRelations(JavaPairRDD<String,List<Tuple2<Integer, Integer>>> relationIndex) {
        return relationIndex.mapValues(soIterable -> {
                int frequencyOfRelation = 0;
                IntOpenHashSet localObjects = new IntOpenHashSet();
                for (Tuple2<Integer, Integer> so : soIterable) {
                    frequencyOfRelation++;
                    localObjects.add(so._2());
                }
                return (float) localObjects.size() / frequencyOfRelation;
            });               
    }
    
    public List<String> getSortedRelations(JavaPairRDD<String,Float> supports, JavaPairRDD<String,Float> discriminabilities) {
        return supports
                .join(discriminabilities)
                .mapValues(x-> (2* x._1() * x._2()) / (x._1() + x._2())) // keep the f-measure of support and discriminability as the score of a relation
                .mapToPair(x-> x.swap()) //key: score, value: relation name
                .sortByKey(false)       //sort relations in descedning score
                .values()               //get the sorted (by score) relation names
                .collect();        
    }
    
    /**
     * Get the top neighbors (the neighbors found for the top-N relations, based on the local ranking of the relations).
     * @param relationIndex key: relation, value: (subjectId, objectId)
     * @param relationsRank a global ranking of relations per dataset (the rank of each relation is its index in this list, starting from 0 for top-ranked)
     * @param N the N from top-N
     * @param postiveIds true if entity ids should be positive, false, if they should be reversed (-eId), i.e., if it is dataset1, or dataset 2
     * @return 
     */
    public JavaPairRDD<Integer, IntArrayList> getTopOutNeighborsPerEntity(JavaPairRDD<String,List<Tuple2<Integer, Integer>>> relationIndex, List<String> relationsRank, int N, boolean postiveIds) {
        return relationIndex.flatMapToPair(x-> {
                List<Tuple2<Integer, Tuple2<String, Integer>>> entities = new ArrayList<>(); //key: subjectId, value: (relation, objectId)
                for (Tuple2<Integer,Integer> relatedEntities : x._2()) {
                    if (postiveIds) {
                        entities.add(new Tuple2<>(relatedEntities._1(), new Tuple2<>(x._1(), relatedEntities._2())));
                    } else {
                        entities.add(new Tuple2<>(-relatedEntities._1(), new Tuple2<>(x._1(), -relatedEntities._2())));
                    }
                }
                return entities.iterator();
            })    
            .combineByKey( //for each entity, keeps local top-Ns before shuffling, like a combiner in MapReduce
            //createCombiner
            relation -> {
                PriorityQueue<ComparableIntFloatPair> initial = new PriorityQueue<>(new ComparableIntFloatPairDescendingComparator());
                int relationRank = relationsRank.indexOf(relation._1()); //relation's name
                initial.add(new ComparableIntFloatPair(relation._2(), relationRank)); //neighbor's id, relation's rank
                return initial; 
            }
            //mergeValue
            , (PriorityQueue<ComparableIntFloatPair> pq, Tuple2<String,Integer> relation) -> {
                int relationRank = relationsRank.indexOf(relation._1());
                ComparableIntFloatPair c = new ComparableIntFloatPair(relation._2(), relationRank);
                pq.add(c);         
                pq = removeSameNeighborWithLowerRank(pq, c); //from duplicate neighbor Ids, keep the one from the relation with the better ranking                                                       
                if (getNumberOfDistinctRelationsInPQ(pq) > N) {
                    pq.poll();
                }
                return pq;
            }
            //mergeCombiners
            , (PriorityQueue<ComparableIntFloatPair> pq1, PriorityQueue<ComparableIntFloatPair> pq2) -> {
                while (!pq2.isEmpty()) {
                    ComparableIntFloatPair c = pq2.poll();                    
                    pq1.add(c);
                    pq1 = removeSameNeighborWithLowerRank(pq1, c);
                    if (getNumberOfDistinctRelationsInPQ(pq1) > N) {
                        pq1.poll();
                    }
                }
                return pq1;
            }
        ).mapValues(pq -> Utils.toIntArrayListReversed(pq));
       
    }
        
    /**
     * At this point, pq contains x and maybe 1 more element y with the same key as x. If y exists, keep from those two the one with the better value. 
     * If y does not exist, keep x. 
     * @param pq
     * @param x
     * @return 
     */
    private PriorityQueue<ComparableIntFloatPair> removeSameNeighborWithLowerRank(PriorityQueue<ComparableIntFloatPair> pq, ComparableIntFloatPair x) {
        int neighborIdToAdd = x.getEntityId();
        double newValue = x.getValue();
        ComparableIntFloatPair elementToDelete = null;
        boolean sameRankTwice = false;
        for (ComparableIntFloatPair qElement : pq) { //traverses the queue in random order
            if (qElement.getEntityId() == neighborIdToAdd) {
                if (qElement.getValue() > newValue) { //y is worse than x => delete y
                    elementToDelete = qElement;
                    break;
                } else if (qElement.getValue() < newValue) { //y is better than x => delete x
                    elementToDelete = x;
                    break;
                } else {  //qElement has the same value as x
                    if (!sameRankTwice) { //first time meeting this element (it can be x or a y with the same rank)
                        sameRankTwice = true; 
                    } else{               //second time meeting this element (x and y are equivalent => delete one of them)
                        elementToDelete = x;
                        break;
                    }
                }
            }
        }
        if (elementToDelete != null) {
            pq.remove(elementToDelete);
        }
        return pq;
    }

    private int getNumberOfDistinctRelationsInPQ(PriorityQueue<ComparableIntFloatPair> pq) {
        FloatSet distinctRelations = new FloatOpenHashSet();        
        pq.stream().forEach(relation -> distinctRelations.add(relation.getValue())); //adds the order of each relation to the set
        return distinctRelations.size();
    }
}
