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

package com.hortonworks.spark.cloud.s3

import java.io.FileNotFoundException

import scala.collection.JavaConverters._

import com.hortonworks.spark.cloud.ObjectStoreOperations
import com.hortonworks.spark.cloud.persist.SuccessData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.s3a.S3AFileSystem
import org.apache.hadoop.fs.{FileSystem, Path, StorageStatistics}
import org.scalatest.Assertions

/**
 * General S3A operations against a filesystem.
 */
class S3AOperations(sourceFs: FileSystem)
  extends ObjectStoreOperations with Assertions {

  /**
   * S3A Filesystem.
   */
  private val fs = sourceFs.asInstanceOf[S3AFileSystem]

  /**
   * Get a sorted list of the FS statistics.
   */
  def getStorageStatistics(): List[StorageStatistics.LongStatistic] = {
    fs.getStorageStatistics.asScala.toList
      .sortWith((left, right) => left.getName > right.getName)
  }

  /**
   * Verify that an S3A committer was used
   *
   * @param destDir destination directory of work
   * @param committer commiter name, if known
   * @param fileCount expected number of files
   * @param text message to include in all assertions
   */
  def verifyS3Committer(
      destDir: Path,
      committer: Option[String],
      fileCount: Option[Integer],
      text: String,
      requireNonEmpty: Boolean = true): Option[SuccessData] = {

    val successFile = new Path(destDir, SUCCESS_FILE_NAME)

    var status = try {
      eventuallyGetFileStatus(fs, successFile)
    } catch {
      case _: FileNotFoundException =>
        throw new FileNotFoundException(
          "No commit success file: " + successFile)
    }
    if (status.getLen == 0) {
      if (requireNonEmpty) {
        fail(
          s"$text 0-byte $successFile implies that the S3A committer was not used" +
            s" to commit work to $destDir with committer $committer")
      }
      return None
    }
    val successData = SuccessData.load(fs, successFile)
    logInfo(s"success data at $successFile : ${successData.toString}")
    logInfo("Metrics:\n" + successData.dumpMetrics("  ", " = ", "\n"))
    logInfo("Diagnostics:\n" + successData.dumpDiagnostics("  ", " = ", "\n"))
    committer.foreach(n =>
      assert(n === successData.getCommitter, s"in $successData"))
    val files = successData.getFilenames
    assert(files != null,
      s"$text No 'filenames' in $successData")
    fileCount.foreach(expected =>
      assert(expected === files.size(),
        s"$text Not enough files in $successData."))
    val listing = files.asScala
      .map(p => fs.makeQualified(new Path(p)))
      .map(fs.getFileStatus)
      .map(st => s"${st.getPath} size=${st.getLen}")
      .mkString("  ","\n  ", "")
    logInfo(s"Files:\n$listing")
    Some(successData)
  }

  /**
   * If the committer is flagged as enabled, verify that it was used; return
   * the success data.
   *
   * @param destDir destination
   * @param committerImplName Committer name to look for in data
   * @param conf conf to query
   * @param fileCount expected number of files
   * @param text message to include in all assertions
   * @return any loaded success data
   */
  def maybeVerifyCommitter(
      destDir: Path,
      committerName: Option[String],
      committerImplName: Option[String],
      conf: Configuration,
      fileCount: Option[Integer],
      text: String = ""): Option[SuccessData] = {
    committerName match {
      case Some(S3ACommitterConstants.DEFAULT_RENAME) =>
        verifyS3Committer(destDir, None, fileCount, text, false)

      case Some(c) => verifyS3Committer(destDir,
        committerImplName, fileCount, text, true)

      case None =>
        verifyS3Committer(destDir, None, fileCount, text, false)

    }

  }

}
