package com.highperformancespark.examples.goldilocks

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.storage.StorageLevel

import scala.Predef
import scala.collection.{mutable, Map}
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.MutableList

//tag::hashMap[]
object GoldiLocksWithHashMap {

  /**
    * Find nth target rank for every column.                 
    *
    * For example:                                           
    *
    * dataframe:                                             
    *   (0.0, 4.5, 7.7, 5.0)                                 
    *   (1.0, 5.5, 6.7, 6.0)                                 
    *   (2.0, 5.5, 1.5, 7.0)                                 
    *   (3.0, 5.5, 0.5, 7.0)                                 
    *   (4.0, 5.5, 0.5, 8.0)                                 
    *
    * targetRanks:                                           
    *   1, 3                                                 
    *
    * The output will be:                                    
    *   0 -> (0.0, 2.0)                                      
    *   1 -> (4.5, 5.5)                                      
    *   2 -> (7.7, 1.5)                                      
    *   3 -> (5.0, 7.0)                                      
    *
    * @param dataFrame dataframe of doubles                  
    * @param targetRanks the required ranks for every column 
    *
    * @return map of (column index, list of target ranks)    
    */
  def findRankStatistics(dataFrame: DataFrame, targetRanks: List[Long]):
    Map[Int, Iterable[Double]] = {
    
    val aggregatedValueColumnPairs: RDD[((Double, Int), Long)] = getAggregatedValueColumnPairs(dataFrame)
    val sortedAggregatedValueColumnPairs = aggregatedValueColumnPairs.sortByKey()
    sortedAggregatedValueColumnPairs.persist(StorageLevel.MEMORY_AND_DISK)

    val numOfColumns =  dataFrame.schema.length
    val partitionColumnsFreq = getColumnsFreqPerPartition(sortedAggregatedValueColumnPairs, numOfColumns)
    val ranksLocations  = getRanksLocationsWithinEachPart(targetRanks, partitionColumnsFreq, numOfColumns)

    val targetRanksValues = findTargetRanksIteratively(sortedAggregatedValueColumnPairs, ranksLocations)
    targetRanksValues.groupByKey().collectAsMap()
  }

  /**
   * Step 1. Map the rows to pairs of ((value, colIndex), count) where count is the number of times
   * that value and that pair appear on this partition
   *
   * For example:
   *
   * dataFrame:
   *     1.5, 1.25, 2.0
   *     1.5,  2.5, 2.0
   *
   * The output RDD will be:
   *    ((1.5, 0), 2) ((1.25, 1), 1) ((2.5, 1), 1) ((2.0, 2), 2)
   *
   * @param dataFrame of double columns to compute the rank statistics for
   *
   * @return returns RDD of ((value, column index), count)
   */
  def getAggregatedValueColumnPairs(dataFrame : DataFrame) : RDD[((Double, Int), Long)] = {
    val aggregatedValueColumnRDD =  dataFrame.rdd.mapPartitions(rows => {
      val valueColumnMap = new mutable.HashMap[(Double, Int), Long]()
      rows.foreach(row => {
        row.toSeq.zipWithIndex.foreach{ case (value, columnIndex) => {
          val key = (value.toString.toDouble, columnIndex)
          val count = valueColumnMap.getOrElseUpdate(key, 0)
          valueColumnMap.update(key, count + 1)
        }}
      })

      valueColumnMap.toIterator
    })

    aggregatedValueColumnRDD
  }

  /**
    * Step 2. Find the number of elements for each column in each partition.
    *
    * For Example:
    *
    * sortedValueColumnPairs:
    *    Partition 1: ((1.5, 0), 2) ((2.0, 0), 1)
    *    Partition 2: ((4.0, 0), 3) ((3.0, 1), 1)
    *
    * numOfColumns: 3
    *
    * The output will be:
    *    [(0, [3, 0]), (1, [3, 1])]
    *
    * @param sortedAggregatedValueColumnPairs - sortedAggregatedValueColumnPairs RDD of ((value, column index), count)
    * @param numOfColumns the number of columns
    *
    * @return Array that contains (partition index, number of elements from every column on this partition)
    */
  private def getColumnsFreqPerPartition(sortedAggregatedValueColumnPairs: RDD[((Double, Int), Long)],
                                        numOfColumns : Int): Array[(Int, Array[Long])] = {

    val zero = Array.fill[Long](numOfColumns)(0)
    def aggregateColumnFrequencies(partitionIndex : Int, pairs : Iterator[((Double, Int), Long)]) = {
      val columnsFreq : Array[Long] = pairs.aggregate(zero)(
        (a : Array[Long], v : ((Double,Int), Long)) => {
          val ((value, colIndex), count) = v
          a(colIndex) = a(colIndex) + count
          a},
        (a : Array[Long], b : Array[Long]) => {
          a.zip(b).map{ case(aVal, bVal) => aVal + bVal}
        })

      Iterator((partitionIndex, columnsFreq))
    }

    sortedAggregatedValueColumnPairs.mapPartitionsWithIndex(aggregateColumnFrequencies).collect()
  }

  /**
    * Step 3: For each Partition determine the index of the elements that are desired rank statistics
    *
    * For Example:
    *    targetRanks: 5
    *    partitionColumnsFreq: [(0, [2, 3]), (1, [4, 1]), (2, [5, 2])]
    *    numOfColumns: 2
    *
    * The output will be:
    *    [(0, []), (1, [(0, 3)]), (2, [(1, 1)])]
    *
    * @param partitionColumnsFreq Array of (partition index, columns frequencies per this partition)
    *
    * @return  Array that contains (partition index, relevantIndexList where relevantIndexList(i) = the index
    *          of an element on this partition that matches one of the target ranks)
    */
  private def getRanksLocationsWithinEachPart(targetRanks : List[Long],
                                              partitionColumnsFreq : Array[(Int, Array[Long])],
                                              numOfColumns : Int) : Array[(Int, List[(Int, Long)])]  = {

    val runningTotal = Array.fill[Long](numOfColumns)(0)

    partitionColumnsFreq.sortBy(_._1).map { case (partitionIndex, columnsFreq)=> {
      val relevantIndexList = new MutableList[(Int, Long)]()

      columnsFreq.zipWithIndex.foreach{ case (colCount, colIndex)  => {
        val runningTotalCol = runningTotal(colIndex)

        val ranksHere: List[Long] = targetRanks.filter(rank =>
          (runningTotalCol < rank && runningTotalCol + colCount >= rank))
        relevantIndexList ++= ranksHere.map(rank => (colIndex, rank - runningTotalCol))

        runningTotal(colIndex) += colCount
      }}

      (partitionIndex, relevantIndexList.toList)
    }}
  }

  /**
    * Finds rank statistics elements using ranksLocations.
    *
    * @param sortedAggregatedValueColumnPairs - sorted RDD of (value, colIndex) pairs
    * @param ranksLocations Array of (partition Index, list of (column index, rank index of this column at this partition))
    *
    * @return returns RDD of the target ranks (column index, value)
    */
  //tag::mapPartitionsExample[]
  private def findTargetRanksIteratively(sortedAggregatedValueColumnPairs : RDD[((Double, Int), Long)],
                                         ranksLocations : Array[(Int, List[(Int, Long)])]
  ): RDD[(Int, Double)] = {

    sortedAggregatedValueColumnPairs.mapPartitionsWithIndex((partitionIndex : Int,
      aggregatedValueColumnPairs : Iterator[((Double, Int), Long)]) => {

      val targetsInThisPart: List[(Int, Long)] = ranksLocations(partitionIndex)._2
     if (!targetsInThisPart.isEmpty) {
        FindTargetsSubRoutine.asIteratorToIteratorTransformation(aggregatedValueColumnPairs,
          targetsInThisPart)
      }
      else Iterator.empty
    })
  }
  //end::mapPartitionsExample[]
  /**
   * We will want to use this in some chapter where we talk about check pointing
   * @param valPairs
   * @param colIndexList
   * @param targetRanks
   * @param storageLevel
   * @param checkPoint
   * @param directory
   * @return
   */
  def findQuantilesWithCustomStorage(valPairs: RDD[((Double, Int), Long)],
    colIndexList: List[Int],
    targetRanks: List[Long],
    storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK,
    checkPoint : Boolean, directory : String = "") = {

    val n = colIndexList.last+1
    val sorted  = valPairs.sortByKey()
    if (storageLevel != StorageLevel.NONE)
      sorted.persist(storageLevel)

    if (checkPoint) {
      sorted.sparkContext.setCheckpointDir(directory)
      sorted.checkpoint()
    }

    val partitionColumnsFreq = getColumnsFreqPerPartition(sorted, n)
    val ranksLocations  = getRanksLocationsWithinEachPart(targetRanks, partitionColumnsFreq, n)
    val targetRanksValues = findTargetRanksIteratively(sorted, ranksLocations)
    targetRanksValues.groupByKey().collectAsMap()
  }
}
//end::hashMap[]


object FindTargetsSubRoutine extends Serializable {

  //tag::notIter[]
  /**
    * This sub routine returns an Iterator of (columnIndex, value) that correspond to one of the
    desired rank statistics on this partition.

    Because in the original iterator, the pairs are distinct
    and include the count, one row of the original iterator could map to multiple elements in the output.
    I.e. if we were looking for the 2nd and 3rd element in column index 4 on this partition. And the head
    of this partition is ((3249.0, 4), 23) (i.e. the element 3249.0 in the 4 th column appears 23 times),
    then we would output (4, 3249.0) twice in the final iterator. Once because 3249.0 is the 2nd element and
    once because it is the third element on that partition for that column index
    and we are looking for both the second and third element.

    * @param valueColumnPairsIter - passed in from the mapPartitions function. An iterator of the sorted
    *                             ((value, columnIndex), count) tupples.
    * @param targetsInThisPart - (columnIndex, index-on-partition pairs). In the above example this would
    *                          include (4, 2) and (4,3) since we desire the 2nd element for column
    *                          index 4 on this partition and the 3rd element.
    * @return All of the rank statistics that live in this partition as an iterator of (columnIndex, value pairs)
    */
  def withArrayBuffer(valueColumnPairsIter : Iterator[((Double, Int), Long)],
    targetsInThisPart: List[(Int, Long)] ): Iterator[(Int, Double)] = {

      val columnsRelativeIndex: Predef.Map[Int, List[Long]] = targetsInThisPart.groupBy(_._1).mapValues(_.map(_._2))

    //the column indices of the pairs that are desired rank statistics that live in this partition.
      val columnsInThisPart: List[Int] = targetsInThisPart.map(_._1).distinct

    //a HashMap with the running totals of each column index. As we loop through the iterator
    //we will update the hashmap as we see elements of each column index.
      val runningTotals : mutable.HashMap[Int, Long]=  new mutable.HashMap()
      runningTotals ++= columnsInThisPart.map(columnIndex => (columnIndex, 0L)).toMap

    //we use an array buffer to build the resulting iterator
      val result: ArrayBuffer[(Int, Double)] = new scala.collection.mutable.ArrayBuffer()

      valueColumnPairsIter.foreach {
        case ((value, colIndex), count) =>

          if (columnsInThisPart contains colIndex) {

            val total = runningTotals(colIndex)
            //the ranks that are contains by this element of the input iterator.
            //get by filtering the
            val ranksPresent =  columnsRelativeIndex(colIndex)
                              .filter(index => (index <= count + total) && (index > total))

            ranksPresent.foreach(r => result += ((colIndex, value)))

            //update the running totals.
            runningTotals.update(colIndex, total + count)
        }
      }
    //convert
    result.toIterator
  }
   //end::notIter[]

   //tag::iterToIter[]
  /**
    * Same function as above but rather than building the result from an array buffer we use
    * a flatMap on the iterator to get the resulting iterator.
    */
  def asIteratorToIteratorTransformation(valueColumnPairsIter : Iterator[((Double, Int), Long)],
    targetsInThisPart: List[(Int, Long)] ): Iterator[(Int, Double)] = {

    val columnsRelativeIndex = targetsInThisPart.groupBy(_._1).mapValues(_.map(_._2))
    val columnsInThisPart = targetsInThisPart.map(_._1).distinct

    val runningTotals : mutable.HashMap[Int, Long]=  new mutable.HashMap()
     runningTotals ++= columnsInThisPart.map(columnIndex => (columnIndex, 0L)).toMap

    //filter out the pairs that don't have a column index that is in this part
    val pairsWithRanksInThisPart =  valueColumnPairsIter.filter{
      case (((value, colIndex), count)) =>
        columnsInThisPart contains colIndex
     }

    //map the valueColumn pairs to a list of (colIndex, value) pairs that correspond to one of the
    //desired rank statistics on this partition.
    pairsWithRanksInThisPart.flatMap{

      case (((value, colIndex), count)) =>

          val total = runningTotals(colIndex)
          val ranksPresent: List[Long] = columnsRelativeIndex(colIndex)
                                         .filter(index => (index <= count + total) && (index > total))

          val nextElems: Iterator[(Int, Double)] = ranksPresent.map(r => (colIndex, value)).toIterator

          //update the running totals
          runningTotals.update(colIndex, total + count)
          nextElems
    }
  }
  //end::iterToIter[]
}