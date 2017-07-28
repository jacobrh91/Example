package prototypes.rddprototype

import statistics._
import scala.annotation.tailrec
import scala.collection.mutable.HashSet
import prototypes.dfprototype.Parser
import scala.io.Source
import org.apache.spark._
import org.apache.spark.sql._
import org.apache.spark.broadcast._
import converters.Table

case class StepCollections(not_added: HashSet[String],
  added_prev: HashSet[String] = HashSet(),
  skipped: HashSet[String] = HashSet())

class FileData(val sampleNames: Vector[String], val dataPairs: Vector[(String, Vector[Double])]) {
  lazy val dataNames = dataPairs.map(_._1)
}

object RDDPrototype {

  var threshold = 0.05

  /** Reads in a file from HDFS converted previously with the ConvertFormat tool */
  def readHDFSFile(filePath: String, spark: SparkContext): FileData = {
    val splitLines = spark.textFile(filePath).map(_.split("\t").toVector)
    val headerLine = splitLines.filter(x => x(0) == "HeaderLine" || x(0) == "Headerline")
      .collect
      .flatten
      .toVector
    val dataLines = splitLines.filter(x => x(0) != "HeaderLine" && x(0) != "Headerline")
      .collect
      .toVector
    // Turns each data line into a tuple where (sampleName, [values])
    val dataTuples = dataLines.map(x => Tuple2(x(0),
      x.drop(1).map(_.toDouble)))
    new FileData(sampleNames = headerLine.drop(1), dataPairs = dataTuples)
  }

  /**
   * Create a non-redundant pairwise Vector of names from a vector of string inputs
   *
   *  Non-redundant means that X_Y is the same as Y_X, and we only create one
   *    of the two with this function
   */
  def createPairwiseVector(columnNames: Seq[String]): Seq[(String, String)] = {

    // Creates a Vector of all pairwise combinations of each column (names only, does not
    //   actually compute the new values: that will be done per partition)
    for (i <- 0 until columnNames.length; j <- i + 1 until columnNames.length) yield {
      (columnNames(i), columnNames(j))
    }
  }

  /*
   * 1. Broadcast the original table throughout the cluster
   * 2. Create and distribute a Vector of columnName pairs for the SNP table
   * 3. On each Executor, create the SNP pairs for the columnName pairs on that Executor
   * 4. Perform all of the regressions in a Map and Reduce style
   */

  def createPairwiseColumn(pair: (String, String),
    broadMap: Broadcast[Map[String, Vector[Double]]]): (String, Vector[Double]) = {
    val combinedName = pair._1 + "_" + pair._2
    val firstVals = broadMap.value(pair._1)
    val secondVals = broadMap.value(pair._2)
    val newVals = for (i <- 0 until firstVals.size) yield firstVals(i) * secondVals(i)
    (combinedName, newVals.toVector)
  }

  var iterations = 0
  
  @tailrec
  def performSteps(spark: SparkContext,
    snpDataRDD: rdd.RDD[(String, Vector[Double])],
    broadcastPhenotypes: Broadcast[Map[String, Vector[Double]]],
    phenotypeName: String,
    collections: StepCollections,
    prev_best_model: OLSRegression = null): OLSRegression = {
    
    iterations += 1
    println("Iteration number: " + iterations.toString)
    
    /*
     * LOCAL FUNCTIONS
     */

    /**
     * Returns the p-value associated with the newest term
     *
     *  This returns the second to last p-value of the input OLSRegression,
     *    as it assumes the last one is associated with the intercept
     */
    val getNewestTermsPValue = (reg: OLSRegression) => {
      // Drop the estimate of the intercept and return the p-value of the most recently added term 
      reg.pValues.toArray.dropRight(1).last
    }

    /** Returns the name of the most recently added term */
    val getNewestTermsName = (reg: OLSRegression) => {
      reg.xColumnNames.last
    }

    /**
     * Returns a OLSRegression object if the inputSnp is in the NotAdded category
     *
     *  Otherwise, an object of type None is returned (this SNP was not analyzed
     *    on this iteration)
     *
     */
    def mapFunction(inputSnp: (String, Vector[Double]),
      addedPrevBroadcast: Broadcast[Map[String, Vector[Double]]]): Option[OLSRegression] = {
      /* If the inputSnp is not already in the model or in the skipped category
       * 
       * Checking !Skipped and !AddedPrev will be faster than checking NotAdded,
       *   as it will almost certainly be much larger than both of the other
       *   categories
       */

      if (!collections.added_prev.contains(inputSnp._1) &&
        !collections.skipped.contains(inputSnp._1)) {
        val yVals = broadcastPhenotypes.value(phenotypeName)

        val xColNames = collections.added_prev.toArray
        val xVals = xColNames.map(addedPrevBroadcast.value(_)).toVector

        val newXColNames = xColNames :+ inputSnp._1
        val newXVals = xVals :+ inputSnp._2

        return Some(new OLSRegression(newXColNames, phenotypeName, newXVals, yVals))
      } else {
        // Do not analyze this SNP
        return None
      }
    }

    def reduceFunction(inputRDD: rdd.RDD[Option[OLSRegression]]): OLSRegression = {
      val filtered = inputRDD.filter(x => !x.isEmpty).map(_.get)
      if (!filtered.isEmpty()) {
        filtered.reduce((x, y) => {
          if (getNewestTermsPValue(x) <= getNewestTermsPValue(y)) x else y
        })
      } else {
        /*
         * warning: if this occurs the broadcast variable will not have been explicitly destroyed
         * Not certain spark automatically destroys it when it leaves scope, although that seems likely
         */
        // There are no more potential SNPs to be added
        throw new Exception("There are no more SNPs under consideration")
      }
    }

    /*
     * IMPLEMENTATION
     */

    /* First, create a Broadcast of the snp values that are already in the model so that their values
     *   will be available to all of the nodes (we know that they will all need copies of these)

     * Since a broadcast cannot be updated, these need to be recreated at the beginning of each iteration
     *   as the SNPs included in the models change
     */

    val addedPrevValMap = collections.added_prev.toArray
      .map(name => (name, snpDataRDD.lookup(name).flatten.toVector))
      .toMap

    val addedPrevBroadcast = spark.broadcast(addedPrevValMap)

    /*
     *  Step 1: find the best regression for those SNPs still under consideration
     */
    // Map generates all of the regression outputs, and reduce finds the best one

    val mappedValues = snpDataRDD.map(x => mapFunction(x, addedPrevBroadcast))
    val bestRegression = reduceFunction(mappedValues)

    // If the p-value of the newest term does not meet the threshold, return the prev_best_model
    if (getNewestTermsPValue(bestRegression) >= threshold) {
      if (prev_best_model != null) {
        return prev_best_model
      } else {
        addedPrevBroadcast.destroy()
        throw new Exception("No terms could be added to the model at a cutoff of " + threshold)
      }
    } else {
      val new_collections = collections.copy()

      // Now that the regressions for this round have completed, return any entries in the skipped
      //   category to the not_added category so they may be considered in subsequent iterations
      new_collections.skipped.foreach(x => {
        new_collections.skipped.remove(x)
        new_collections.not_added.add(x)
      })

      /*
       * Remove the newest term from the not_added category and put it in the added_prev category
       */
      new_collections.not_added.remove(getNewestTermsName(bestRegression))
      new_collections.added_prev.add(getNewestTermsName(bestRegression))

      /*
       * Step 2: Check to make sure none of the previously added terms are no longer significant
       * 				 If they are, remove them from consideration in the next round (put them in skipped) and
       *         take them out of the model
       */
      val namePValuePairs = bestRegression.xColumnNames.zip(bestRegression.pValues)
      namePValuePairs.foreach(pair => {
        if (pair._2 >= threshold) {
          // Remove this term from the prev_added collection, and move it to the skipped category
          new_collections.added_prev.remove(pair._1)
          new_collections.skipped.add(pair._1)
        }
      })

      if (new_collections.not_added.size == 0) {
        /*
         * No more terms that could be added. Return the current best model, unless there are entries
         * in the skipped category.
         * 
         * If this is the case, perform one last regression with the current add_prev collection in 
         * the model. 
         * 
         * If something is in the skipped category at this point it was added during this iteration.
         * and the "bestRegression" variable will still have that term included.
         */
        if (new_collections.skipped.size == 0) return bestRegression
        else {
          val xColNames = new_collections.added_prev.toArray
          // New x values: look up the previously added from the broadcast table, then include the values of
          //   the latest term to be added
          val xVals = xColNames.map(addedPrevBroadcast.value(_)).toVector :+ bestRegression.lastXColumnsValues

          val yVals = broadcastPhenotypes.value(phenotypeName)

          addedPrevBroadcast.destroy()
          return new OLSRegression(xColNames, phenotypeName, xVals, yVals)
        }
      } else {
        addedPrevBroadcast.destroy()
        performSteps(spark, snpDataRDD, broadcastPhenotypes, phenotypeName, new_collections, bestRegression)
      }
    }
  }

  def main(args: Array[String]) {

    val startTime = System.nanoTime()

    val spark = SparkSession.builder.master("local").appName("SEMS").getOrCreate()
    //val spark = SparkSession.builder.appName("DataFramePrototype").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    val snpData = readHDFSFile("/Users/jacobheldenbrand/Documents/Spark_playground/Angela_test_data/1106_Markers_NAM_Kernel_Color_Families_Only_for_R_10SNPs.epiq",
                               spark.sparkContext
                              )

    val phenoData = readHDFSFile("/Users/jacobheldenbrand/Documents/Spark_playground/Angela_test_data/Simulated.Data.100.Reps.Herit.0.5_1Pheno.epiq",
                                 spark.sparkContext
                                )

    if (phenoData.sampleNames != snpData.sampleNames) throw new Error("Sample order did not match between the SNP and Phenotype input files")

    val pairwiseCombinations = createPairwiseVector(snpData.dataNames)

    // Broadcast original SNP_Phenotype map
    val broadSnpTable = spark.sparkContext.broadcast(snpData.dataPairs.toMap)

    // Spread pairwise Vector across cluster
    val pairRDD = spark.sparkContext.parallelize(pairwiseCombinations)

    // Parallelize the original table into an RDD
    val singleSnpRDD = spark.sparkContext.parallelize(snpData.dataPairs)

    // Create the pairwise combinations across the cluster
    val pairedSnpRDD = pairRDD.map(createPairwiseColumn(_, broadSnpTable))

    val fullSnpRDD = (singleSnpRDD ++ (pairedSnpRDD)).persist()

    val phenoBroadcast = spark.sparkContext.broadcast(phenoData.dataPairs.toMap)

    val phenotypeNames = phenoData.dataNames

    // The :_* unpacks the contents of the array as input to the hash set
    val snpNames = HashSet(fullSnpRDD.keys.collect(): _*)

    val initialCollections = new StepCollections(not_added = snpNames)

    val bestRegression = performSteps(spark.sparkContext,
                                      fullSnpRDD,
                                      phenoBroadcast,
                                      phenotypeNames(0),
                                      initialCollections
                                     )

    bestRegression.printSummary

    val endTime = System.nanoTime()

    println("\nTotal time (seconds): " + ((endTime - startTime) / 1e9).toString)
  }
}