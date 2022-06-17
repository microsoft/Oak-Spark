/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.spark.osdu

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.ArrayData
import org.apache.spark.sql.connector.write.{DataWriter, WriterCommitMessage}
import org.apache.spark.sql.types.{StringType, StructType}

import com.microsoft.osdu.client.model.{StorageAcl, StorageLegal, StorageRecord}
import com.microsoft.osdu.client.invoker.{ApiClient}

import scala.collection.JavaConverters._
import scala.collection.mutable.MutableList

/**
 *
 * @param osduApiEndpoint OSDU Api End point to post data to
 * @param partitionId - partition id where the data should be posted
 * @param bearerToken - OAuth Bearer token for authentication
 * @param schema - Data schema of the payload
 * @param useOSDUSDK - Boolean Flag on to use OSDU sdk vs http client for posting data
 */
class OSDUDataWriter(osduApiEndpoint: String, partitionId: String, bearerToken: String, schema: StructType, useOSDUSDK: Boolean = false)
  extends DataWriter[InternalRow] {

  private val idxId = if (schema.fieldNames contains "id") schema.fieldIndex("id") else -1
  private val idxKind = schema.fieldIndex("kind")
  private val idxAcl = schema.fieldIndex("acl")
  private val aclSchema = schema(idxAcl).dataType.asInstanceOf[StructType]
  private val idxAclViewers = aclSchema.fieldIndex("viewers")
  private val idxAclOwners = aclSchema.fieldIndex("owners")

  private val idxLegal = schema.fieldIndex("legal")
  private val legalSchema = schema(idxLegal).dataType.asInstanceOf[StructType]
  private val idxLegalTags = legalSchema.fieldIndex("legaltags")
  private val idxLegalOther = legalSchema.fieldIndex("otherRelevantDataCountries")

  private val idxData = schema.fieldIndex("data")
  private val dataSchema = schema.fields(idxData).dataType.asInstanceOf[StructType]
  private val dataSchemaNumFields = dataSchema.size

  private val converter = new OSDURecordConverter(dataSchema)
  private val recordBuffer = new MutableList[StorageRecord]
  // TODO: make configurable
  // private val forkJoinPool = ThreadUtils.newForkJoinPool("osdu-data-writer", 4)


  private def postRecordsInBatch(minimumBatchSize: Int): Unit = {
    if (recordBuffer.length >= minimumBatchSize) {

      // TODO: use async thread-pool
      // TODO: retry?
      // up to 500
      //TODO - Remove the if condition in future and use OSDUSDK to post data
      if (useOSDUSDK) {
        val client = new ApiClient()
        client.setBasePath(osduApiEndpoint)
        client.setBearerToken(bearerToken)

        import com.microsoft.osdu.client.api.StorageApi
        val storageApi = new StorageApi(client)

        val createOrUpdateRecord = storageApi.createOrUpdateRecords(
          partitionId, true, "", recordBuffer.asJava)

      } else {
        val osc = new OSDUStorageClient
        osc.postRecords(osduApiEndpoint, partitionId, bearerToken, recordBuffer)
      }

      recordBuffer.clear
    }
  }

  def write(record: InternalRow): Unit = {

    val storageRecord = new StorageRecord()
    // if the id is not provided, it's auto-generated by the server
    if (idxId != -1)
      storageRecord.setId(record.getString(idxId))

    storageRecord.setKind(record.getString(idxKind))

    def sparkArrayToJavaList(array: ArrayData): java.util.List[String] = {
      val list = new java.util.ArrayList[String]()
      for (j <- 0 until array.numElements())
        list.add(array.get(j, StringType).toString)

      list
    }

    def sparkArrayToJavaSet(array: ArrayData): java.util.Set[String] = {
      val list = new java.util.HashSet[String]()
      for (j <- 0 until array.numElements())
        list.add(array.get(j, StringType).toString)

      list
    }

    // ACL
    val acl = record.getStruct(idxAcl, 2)

    val owners =
      storageRecord.setAcl(
        new StorageAcl()
          .owners(sparkArrayToJavaList(acl.getArray(idxAclOwners)))
          .viewers(sparkArrayToJavaList(acl.getArray(idxAclViewers))))

    // Legal
    val legal = record.getStruct(idxLegal, 3)

    storageRecord.setLegal(
      new StorageLegal()
        .legaltags(sparkArrayToJavaSet(legal.getArray(idxLegalTags)))
        .otherRelevantDataCountries(sparkArrayToJavaSet(legal.getArray(idxLegalOther))))


    // convert record to data
    val dataStruct = record.getStruct(idxData, dataSchemaNumFields)
    val data = converter.toJava(dataStruct)
    data.forEach {
      case (k, v) => if (data.get(k).isInstanceOf[org.apache.spark.unsafe.types.UTF8String]) data.put(k, data.get(k).toString)
    }
    storageRecord.setData(data)


    // add to batch
    recordBuffer += storageRecord

    // post batch
    // TODO: parametrize batch size
    postRecordsInBatch(500)
  }

  def commit(): WriterCommitMessage = {
    // post final batch
    postRecordsInBatch(1)
    WriteSucceeded
  }

  def abort(): Unit = {
  }

  object WriteSucceeded extends WriterCommitMessage

  override def close(): Unit = {
  }
}