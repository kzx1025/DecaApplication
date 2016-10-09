package decaApp

import java.io.{ByteArrayOutputStream, DataOutputStream}

import breeze.linalg.DenseVector
import org.apache.hadoop.io.WritableComparator
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkContext, SparkConf}
import sparkApp.SparkLR

import java.util.HashMap
import scala.util.Random

/**
  * Created by zx on 2016/10/9.
  */

class FieldPointChunk(dimensions: Int,size: Int = 4196)
  extends ByteArrayOutputStream(size)
    with Serializable { self =>

  val fieldsToPages = new HashMap[String, Integer]()

  def setFields(fieldName: String, pageAddress: Integer): Unit ={
    fieldsToPages.put(fieldName, pageAddress)
  }

  def show():Unit={
    var address = 0
    while(address<size){
      println(WritableComparator.readDouble(buf, address))
      address+=8
    }
  }

  def getNewMapTable(): HashMap[String, Integer] = {
    val newMapTbale = new HashMap[String, Integer]()
    newMapTbale.putAll(fieldsToPages)
    newMapTbale
  }

  def getVectorValueIterator(w: Array[Double]) = new Iterator[Array[Double]] {
    var offset = 0
    var currentMapTable = getNewMapTable()
    var currentPoint=new Array[Double](dimensions)
    var i = 0
    var y = 0.0
    var dotvalue = 0.0

    override def hasNext = offset < self.count

    override def next() = {
      if (!hasNext) Iterator.empty.next()
      else {
        //read data from the chunk
        i=0
        while (i < dimensions) {
          currentPoint(i)= WritableComparator.readDouble(buf, currentMapTable.get("x"))
          currentMapTable.put("x", currentMapTable.get("x") + 8)
          offset += 8
          i += 1
        }
        y = WritableComparator.readDouble(buf, currentMapTable.get("y"))
        currentMapTable.put("y", currentMapTable.get("y") + 8)
        offset += 8
        //calculate the dot value
        i=0
        dotvalue = 0.0
        while (i < dimensions) {
          dotvalue += w(i)*currentPoint(i)
          i += 1
        }
        //transform to values
        i=0
        while (i < dimensions) {
          currentPoint(i) *= (1 / (1 + Math.exp(-y * dotvalue)) - 1) * y
          i += 1
        }
        currentPoint.clone()
      }
    }
  }
}

object DecaFieldLR {

  val rand = new Random(42)

  def testOptimized(points: RDD[SparkLR.DataPoint],iterations:Int,numDests:Int,w:DenseVector[Double]): Unit = {
    val cachedPoints = points.mapPartitions ({ iter =>
      val (iterOne ,iterTwo) = iter.duplicate
      val (iterTree, iterFour) = iterTwo.duplicate
      val length = iterOne.length
      val chunk = new FieldPointChunk(numDests,8*length*(1+numDests))
      val dos = new DataOutputStream(chunk)
      chunk.setFields("x", 0)
      for (point <- iterTree) {
        point.x.foreach(dos.writeDouble)
      }
      chunk.setFields("y", chunk.size())
      for(point <- iterFour)
        dos.writeDouble(point.y)
      Iterator(chunk)
    },true).persist(StorageLevel.MEMORY_AND_DISK)

    cachedPoints.foreach(x => Unit)

    val w_op=new Array[Double](numDests)
    for(i <- 0 until numDests)
      w_op(i) = w(i)

    val startTime = System.currentTimeMillis
    for (i <- 1 to iterations) {
      println("On iteration " + i)
      val gradient= cachedPoints.mapPartitions{ iter =>
        val chunk = iter.next()
        chunk.getVectorValueIterator(w_op)
      }.reduce{(lArray, rArray) =>
        val result_array = new Array[Double](lArray.length)
        for(i <- 0 to numDests-1)
          result_array(i) = lArray(i) + rArray(i)
        result_array
      }

      for(i <- 0 to numDests-1)
        w_op(i) = w_op(i) - gradient(i)
      //println("w is :"+w_op.mkString(";"))
    }
    val duration = System.currentTimeMillis - startTime
    println("result:"+w_op.mkString(";"))
    println("Duration is " + duration / 1000.0 + " seconds")

  }

  def main(args: Array[String]) {

    val sparkConf = new SparkConf().setAppName(args(3))
    val sc = new SparkContext(sparkConf)
    val iterations = args(1).toInt
    val numDests = args(2).toInt
    val points = sc.objectFile(args(0)).asInstanceOf[RDD[SparkLR.DataPoint]]

    val w = DenseVector.fill(numDests){2*rand.nextDouble() - 1}
    println("Initial w:"+w)

    testOptimized(points,iterations,numDests,w)

    sc.stop()
  }

}
