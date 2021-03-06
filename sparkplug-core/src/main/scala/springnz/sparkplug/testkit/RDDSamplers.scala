package springnz.sparkplug.testkit

import java.lang.Math._

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.partial.{ BoundedDouble, PartialResult }
import org.apache.spark.rdd.RDD
import springnz.sparkplug.util.SerializeUtils

import scala.reflect.ClassTag

object RDDSamplers extends LazyLogging {
  def identitySampler[A: ClassTag](rdd: RDD[A]): RDD[A] = rdd

  def shrinkingSampler[A: ClassTag](sampleParams: RDDShrinkingSamplerParams = sourceRDDParams)(rdd: RDD[A]): RDD[A] =
    shrinkingSample(rdd, sampleParams)

  def takeSampler[A: ClassTag](count: Int, partitions: Int = -1)(rdd: RDD[A]): RDD[A] = {
    val sc = rdd.sparkContext
    val parts = if (partitions > 0) partitions else sc.defaultParallelism
    sc.parallelize(rdd.take(count), parts)
  }

  val sourceRDDParams = RDDShrinkingSamplerParams(
    testerFraction = 0.0001,
    scaleParam = 3000.0,
    scalePower = 0.30102999566398,
    minimum = 1000000.0,
    sequential = false)

  val derivedRDDParams = RDDShrinkingSamplerParams(
    testerFraction = 0.1,
    scaleParam = 1.0,
    scalePower = 1.0,
    minimum = 1000000.0,
    sequential = false)

  private[sparkplug] def shrinkFactor(
    testerFraction: Double,
    scaleParam: Double,
    scalePower: Double,
    minimum: Double,
    testerLength: Double): Double = {

    val fullLength = testerLength / testerFraction
    val calcFrac = Math.pow(fullLength, scalePower) / fullLength * scaleParam
    // don't bother shrinking to less than the minimum, but cap at 1.0
    min(if (minimum > 0) max(calcFrac, minimum / fullLength) else calcFrac, 1.0)
  }

  private[sparkplug] def shrinkingSample[A: ClassTag](rdd: RDD[A], params: RDDShrinkingSamplerParams): RDD[A] = {

    def getSample(params: RDDShrinkingSamplerParams): RDD[A] = {
      val approxSize: PartialResult[BoundedDouble] = rdd.countApprox(60000, 0.95)
      val sampleLength = approxSize.initialValue.mean * params.testerFraction
      if (sampleLength < 50) {
        // take a bigger shrinkingSample
        val updatedTesterFraction = params.testerFraction * 50 / sampleLength
        getSample(params.copy(testerFraction = updatedTesterFraction))
      } else {
        val sample = rdd.take(sampleLength.toInt)
        val tester = SerializeUtils.serialize(sample)
        val sampleFraction = shrinkFactor(params.testerFraction, params.scaleParam, params.scalePower,
          params.minimum, tester.length)
        val fullCount = sample.length / params.testerFraction

        val reSampled = if (params.sequential)
          rdd.sparkContext.parallelize(rdd.take((fullCount * sampleFraction).toInt), 10)
        else
          rdd.sample(withReplacement = true, sampleFraction, 0)
        reSampled
      }
    }

    if (params.scaleParam == 1.0 && params.scalePower == 1.0) {
      logger.info("Not sampling RDD since scaleParam==scalePower==1.0")
      rdd
    } else {
      logger.info(s"Sampling RDD with $params ...")
      getSample(params)
    }
  }

  case class RDDShrinkingSamplerParams(
      testerFraction: Double,
      scaleParam: Double,
      scalePower: Double,
      minimum: Double,
      sequential: Boolean) {

    def withSequential(newSequential: Boolean): Unit = {
      RDDShrinkingSamplerParams(testerFraction, scaleParam, scalePower, minimum, newSequential)
    }
  }

}

