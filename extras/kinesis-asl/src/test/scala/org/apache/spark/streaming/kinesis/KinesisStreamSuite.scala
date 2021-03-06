/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.kinesis

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream
import com.amazonaws.services.kinesis.model.Record
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import org.apache.spark.network.util.JavaUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.kinesis.KinesisTestUtils._
import org.apache.spark.streaming.receiver.BlockManagerBasedStoreResult
import org.apache.spark.streaming.scheduler.ReceivedBlockInfo
import org.apache.spark.util.Utils
import org.apache.spark.{SparkConf, SparkContext}

abstract class KinesisStreamTests(aggregateTestData: Boolean) extends KinesisFunSuite
  with Eventually with BeforeAndAfter with BeforeAndAfterAll {

  // This is the name that KCL will use to save metadata to DynamoDB
  private val appName = s"KinesisStreamSuite-${math.abs(Random.nextLong())}"
  private val batchDuration = Seconds(1)

  // Dummy parameters for API testing
  private val dummyEndpointUrl = defaultEndpointUrl
  private val dummyRegionName = RegionUtils.getRegionByEndpoint(dummyEndpointUrl).getName()
  private val dummyAWSAccessKey = "dummyAccessKey"
  private val dummyAWSSecretKey = "dummySecretKey"

  private var testUtils: KinesisTestUtils = null
  private var ssc: StreamingContext = null
  private var sc: SparkContext = null

  override def beforeAll(): Unit = {
    val conf = new SparkConf()
      .setMaster("local[4]")
      .setAppName("KinesisStreamSuite") // Setting Spark app name to Kinesis app name
    sc = new SparkContext(conf)

    runIfTestsEnabled("Prepare KinesisTestUtils") {
      testUtils = new KPLBasedKinesisTestUtils()
      testUtils.createStream()
    }
  }

  override def afterAll(): Unit = {
    if (ssc != null) {
      ssc.stop()
    }
    if (sc != null) {
      sc.stop()
    }
    if (testUtils != null) {
      // Delete the Kinesis stream as well as the DynamoDB table generated by
      // Kinesis Client Library when consuming the stream
      testUtils.deleteStream()
      testUtils.deleteDynamoDBTable(appName)
    }
  }

  before {
    ssc = new StreamingContext(sc, batchDuration)
  }

  after {
    if (ssc != null) {
      ssc.stop(stopSparkContext = false)
      ssc = null
    }
    if (testUtils != null) {
      testUtils.deleteDynamoDBTable(appName)
    }
  }

  test("KinesisUtils API") {
    // Tests the API, does not actually test data receiving
    val kinesisStream1 = KinesisUtils.createStream(ssc, "mySparkStream",
      dummyEndpointUrl, Seconds(2),
      InitialPositionInStream.LATEST, StorageLevel.MEMORY_AND_DISK_2)
    val kinesisStream2 = KinesisUtils.createStream(ssc, "myAppNam", "mySparkStream",
      dummyEndpointUrl, dummyRegionName,
      InitialPositionInStream.LATEST, Seconds(2), StorageLevel.MEMORY_AND_DISK_2)
    val kinesisStream3 = KinesisUtils.createStream(ssc, "myAppNam", "mySparkStream",
      dummyEndpointUrl, dummyRegionName,
      InitialPositionInStream.LATEST, Seconds(2), StorageLevel.MEMORY_AND_DISK_2,
      dummyAWSAccessKey, dummyAWSSecretKey)
  }

  test("RDD generation") {
    val inputStream = KinesisUtils.createStream(ssc, appName, "dummyStream",
      dummyEndpointUrl, dummyRegionName, InitialPositionInStream.LATEST, Seconds(2),
      StorageLevel.MEMORY_AND_DISK_2, dummyAWSAccessKey, dummyAWSSecretKey)
    assert(inputStream.isInstanceOf[KinesisInputDStream[Array[Byte]]])

    val kinesisStream = inputStream.asInstanceOf[KinesisInputDStream[Array[Byte]]]
    val time = Time(1000)

    // Generate block info data for testing
    val seqNumRanges1 = SequenceNumberRanges(
      SequenceNumberRange("fakeStream", "fakeShardId", "xxx", "yyy"))
    val blockId1 = StreamBlockId(kinesisStream.id, 123)
    val blockInfo1 = ReceivedBlockInfo(
      0, None, Some(seqNumRanges1), new BlockManagerBasedStoreResult(blockId1, None))

    val seqNumRanges2 = SequenceNumberRanges(
      SequenceNumberRange("fakeStream", "fakeShardId", "aaa", "bbb"))
    val blockId2 = StreamBlockId(kinesisStream.id, 345)
    val blockInfo2 = ReceivedBlockInfo(
      0, None, Some(seqNumRanges2), new BlockManagerBasedStoreResult(blockId2, None))

    // Verify that the generated KinesisBackedBlockRDD has the all the right information
    val blockInfos = Seq(blockInfo1, blockInfo2)
    val nonEmptyRDD = kinesisStream.createBlockRDD(time, blockInfos)
    nonEmptyRDD shouldBe a [KinesisBackedBlockRDD[Array[Byte]]]
    val kinesisRDD = nonEmptyRDD.asInstanceOf[KinesisBackedBlockRDD[Array[Byte]]]
    assert(kinesisRDD.regionName === dummyRegionName)
    assert(kinesisRDD.endpointUrl === dummyEndpointUrl)
    assert(kinesisRDD.retryTimeoutMs === batchDuration.milliseconds)
    assert(kinesisRDD.awsCredentialsOption ===
      Some(SerializableAWSCredentials(dummyAWSAccessKey, dummyAWSSecretKey)))
    assert(nonEmptyRDD.partitions.size === blockInfos.size)
    nonEmptyRDD.partitions.foreach { _ shouldBe a [KinesisBackedBlockRDDPartition] }
    val partitions = nonEmptyRDD.partitions.map {
      _.asInstanceOf[KinesisBackedBlockRDDPartition] }.toSeq
    assert(partitions.map { _.seqNumberRanges } === Seq(seqNumRanges1, seqNumRanges2))
    assert(partitions.map { _.blockId } === Seq(blockId1, blockId2))
    assert(partitions.forall { _.isBlockIdValid === true })

    // Verify that KinesisBackedBlockRDD is generated even when there are no blocks
    val emptyRDD = kinesisStream.createBlockRDD(time, Seq.empty)
    emptyRDD shouldBe a [KinesisBackedBlockRDD[Array[Byte]]]
    emptyRDD.partitions shouldBe empty

    // Verify that the KinesisBackedBlockRDD has isBlockValid = false when blocks are invalid
    blockInfos.foreach { _.setBlockIdInvalid() }
    kinesisStream.createBlockRDD(time, blockInfos).partitions.foreach { partition =>
      assert(partition.asInstanceOf[KinesisBackedBlockRDDPartition].isBlockIdValid === false)
    }
  }


  /**
   * Test the stream by sending data to a Kinesis stream and receiving from it.
   * This test is not run by default as it requires AWS credentials that the test
   * environment may not have. Even if there is AWS credentials available, the user
   * may not want to run these tests to avoid the Kinesis costs. To enable this test,
   * you must have AWS credentials available through the default AWS provider chain,
   * and you have to set the system environment variable RUN_KINESIS_TESTS=1 .
   */
  testIfEnabled("basic operation") {
    val awsCredentials = KinesisTestUtils.getAWSCredentials()
    val stream = KinesisUtils.createStream(ssc, appName, testUtils.streamName,
      testUtils.endpointUrl, testUtils.regionName, InitialPositionInStream.LATEST,
      Seconds(10), StorageLevel.MEMORY_ONLY,
      awsCredentials.getAWSAccessKeyId, awsCredentials.getAWSSecretKey)

    val collected = new mutable.HashSet[Int] with mutable.SynchronizedSet[Int]
    stream.map { bytes => new String(bytes).toInt }.foreachRDD { rdd =>
      collected ++= rdd.collect()
      logInfo("Collected = " + collected.mkString(", "))
    }
    ssc.start()

    val testData = 1 to 10
    eventually(timeout(120 seconds), interval(10 second)) {
      testUtils.pushData(testData, aggregateTestData)
      assert(collected === testData.toSet, "\nData received does not match data sent")
    }
    ssc.stop(stopSparkContext = false)
  }

  testIfEnabled("custom message handling") {
    val awsCredentials = KinesisTestUtils.getAWSCredentials()
    def addFive(r: Record): Int = JavaUtils.bytesToString(r.getData).toInt + 5
    val stream = KinesisUtils.createStream(ssc, appName, testUtils.streamName,
      testUtils.endpointUrl, testUtils.regionName, InitialPositionInStream.LATEST,
      Seconds(10), StorageLevel.MEMORY_ONLY, addFive,
      awsCredentials.getAWSAccessKeyId, awsCredentials.getAWSSecretKey)

    stream shouldBe a [ReceiverInputDStream[Int]]

    val collected = new mutable.HashSet[Int] with mutable.SynchronizedSet[Int]
    stream.foreachRDD { rdd =>
      collected ++= rdd.collect()
      logInfo("Collected = " + collected.mkString(", "))
    }
    ssc.start()

    val testData = 1 to 10
    eventually(timeout(120 seconds), interval(10 second)) {
      testUtils.pushData(testData, aggregateTestData)
      val modData = testData.map(_ + 5)
      assert(collected === modData.toSet, "\nData received does not match data sent")
    }
    ssc.stop(stopSparkContext = false)
  }

  testIfEnabled("failure recovery") {
    val sparkConf = new SparkConf().setMaster("local[4]").setAppName(this.getClass.getSimpleName)
    val checkpointDir = Utils.createTempDir().getAbsolutePath

    ssc = new StreamingContext(sc, Milliseconds(1000))
    ssc.checkpoint(checkpointDir)

    val awsCredentials = KinesisTestUtils.getAWSCredentials()
    val collectedData = new mutable.HashMap[Time, (Array[SequenceNumberRanges], Seq[Int])]
      with mutable.SynchronizedMap[Time, (Array[SequenceNumberRanges], Seq[Int])]

    val kinesisStream = KinesisUtils.createStream(ssc, appName, testUtils.streamName,
      testUtils.endpointUrl, testUtils.regionName, InitialPositionInStream.LATEST,
      Seconds(10), StorageLevel.MEMORY_ONLY,
      awsCredentials.getAWSAccessKeyId, awsCredentials.getAWSSecretKey)

    // Verify that the generated RDDs are KinesisBackedBlockRDDs, and collect the data in each batch
    kinesisStream.foreachRDD((rdd: RDD[Array[Byte]], time: Time) => {
      val kRdd = rdd.asInstanceOf[KinesisBackedBlockRDD[Array[Byte]]]
      val data = rdd.map { bytes => new String(bytes).toInt }.collect().toSeq
      collectedData(time) = (kRdd.arrayOfseqNumberRanges, data)
    })

    ssc.remember(Minutes(60)) // remember all the batches so that they are all saved in checkpoint
    ssc.start()

    def numBatchesWithData: Int = collectedData.count(_._2._2.nonEmpty)

    def isCheckpointPresent: Boolean = Checkpoint.getCheckpointFiles(checkpointDir).nonEmpty

    // Run until there are at least 10 batches with some data in them
    // If this times out because numBatchesWithData is empty, then its likely that foreachRDD
    // function failed with exceptions, and nothing got added to `collectedData`
    eventually(timeout(2 minutes), interval(1 seconds)) {
      testUtils.pushData(1 to 5, aggregateTestData)
      assert(isCheckpointPresent && numBatchesWithData > 10)
    }
    ssc.stop(stopSparkContext = true)  // stop the SparkContext so that the blocks are not reused

    // Restart the context from checkpoint and verify whether the
    logInfo("Restarting from checkpoint")
    ssc = new StreamingContext(checkpointDir)
    ssc.start()
    val recoveredKinesisStream = ssc.graph.getInputStreams().head

    // Verify that the recomputed RDDs are KinesisBackedBlockRDDs with the same sequence ranges
    // and return the same data
    val times = collectedData.keySet
    times.foreach { time =>
      val (arrayOfSeqNumRanges, data) = collectedData(time)
      val rdd = recoveredKinesisStream.getOrCompute(time).get.asInstanceOf[RDD[Array[Byte]]]
      rdd shouldBe a [KinesisBackedBlockRDD[Array[Byte]]]

      // Verify the recovered sequence ranges
      val kRdd = rdd.asInstanceOf[KinesisBackedBlockRDD[Array[Byte]]]
      assert(kRdd.arrayOfseqNumberRanges.size === arrayOfSeqNumRanges.size)
      arrayOfSeqNumRanges.zip(kRdd.arrayOfseqNumberRanges).foreach { case (expected, found) =>
        assert(expected.ranges.toSeq === found.ranges.toSeq)
      }

      // Verify the recovered data
      assert(rdd.map { bytes => new String(bytes).toInt }.collect().toSeq === data)
    }
    ssc.stop()
  }
}

class WithAggregationKinesisStreamSuite extends KinesisStreamTests(aggregateTestData = true)

class WithoutAggregationKinesisStreamSuite extends KinesisStreamTests(aggregateTestData = false)
