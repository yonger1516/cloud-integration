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

package com.hortonworks.spark.cloud

import scala.reflect.ClassTag

import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{CommonConfigurationKeysPublic, FileSystem, LocatedFileStatus, Path, PathFilter, RemoteIterator}
import org.apache.hadoop.io.{NullWritable, Text}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql._

/**
 * Extra Hadoop operations for object store integration.
 */
private[cloud] trait ObjectStoreOperations extends CloudLogging {

  /**
   * Save this RDD as a text file, using string representations of elements.
   *
   * There's a bit of convoluted-ness here, as this supports writing to any Hadoop FS,
   * rather than the default one in the configuration ... this is addressed by creating a
   * new configuration. Spark makes a bit too much of the RDD private, so the key
   * and value of the RDD needs to be restated
   * @param rdd RDD to save
   * @param keyClass key of RDD
   * @param valueClass value of RDD
   * @param path path
   * @param conf config
   * @tparam T type of RDD
   */
  def saveAsTextFile[T](rdd: RDD[T],
      path: Path,
      conf: Configuration,
      keyClass: Class[_],
      valueClass: Class[_]): Unit = {
    val nullWritableClassTag = implicitly[ClassTag[NullWritable]]
    val textClassTag = implicitly[ClassTag[Text]]
    val r = rdd.mapPartitions { iter =>
      val text = new Text()
      iter.map { x =>
        text.set(x.toString)
        (NullWritable.get(), text)
      }
    }
    val pathFS = FileSystem.get(path.toUri, conf)
    val confWithTargetFS = new Configuration(conf)
    confWithTargetFS.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
      pathFS.getUri.toString)
    val pairOps = RDD.rddToPairRDDFunctions(r)(nullWritableClassTag, textClassTag, null)
    pairOps.saveAsNewAPIHadoopFile(path.toUri.toString,
      keyClass, valueClass,
      classOf[org.apache.hadoop.mapreduce.lib.output.TextOutputFormat[NullWritable, Text]],
      confWithTargetFS)
  }

  /**
   * Take a predicate, generate a path filter from it.
   * @param filterPredicate predicate
   * @return a filter which uses the predicate to decide whether to accept a file or not
   */
  def pathFilter(filterPredicate: Path => Boolean): PathFilter = {
    new PathFilter {
      def accept(path: Path): Boolean = filterPredicate(path)
    }
  }

  /**
   * List files in a filesystem.
   * @param fs filesystem
   * @param path path to list
   * @param recursive flag to request recursive listing
   * @return a sequence of all files (but not directories) underneath the path.
   */
  def listFiles(fs: FileSystem, path: Path, recursive: Boolean): Seq[LocatedFileStatus] = {
    remoteIteratorSequence(fs.listFiles(path, recursive))
  }

  /**
   * Take the output of a remote iterator and covert it to a scala sequence. Network
   * IO may take place during the operation, and changes to a remote FS may result in
   * a sequence which is not consistent with any single state of the FS.
   * @param source source
   * @tparam T type of source
   * @return a sequence
   */
  def remoteIteratorSequence[T](source: RemoteIterator[T]): Seq[T] = {
    new RemoteOutputIterator[T](source).toSeq
  }

  /**
   * Put a string to the destination
   * @param path path
   * @param conf configuration to use when requesting the filesystem
   * @param body string body
   */
  def put(path: Path, conf: Configuration, body: String): Unit = {
    val fs = FileSystem.get(path.toUri, conf)
    val out = fs.create(path, true)
    try {
      IOUtils.write(body, out)
    } finally {
      IOUtils.closeQuietly(out)
    }
  }

  /**
   * Save a dataframe in a specific format.
   * @param df dataframe
   * @param dest destination path
   * @param format format
   * @return the path the DF was saved to
   */
  def save(df: DataFrame, dest: Path, format: String): Path = {
    df.write.format(format).save(dest.toString)
    dest
  }

  /**
   * Load a dataframe.
   * @param spark spark session
   * @param source source path
   * @param srcFormat format
   * @return the loaded dataframe
   */
  def load(spark: SparkSession, source: Path, srcFormat: String,
           opts: Map[String, String] = Map()): DataFrame = {
    val reader = spark.read
    reader.options(opts)
    reader.format(srcFormat).load(source.toUri.toString)
  }

  def applyOrcSpeedupOptions(spark: SparkSession): Unit = {
    spark.read.option("mergeSchema", "false")
  }
}

/**
 * Iterator over remote output.
 * @param source source iterator
 * @tparam T type of response
 */
class RemoteOutputIterator[T](private val source: RemoteIterator[T]) extends Iterator[T] {
  def hasNext: Boolean = source.hasNext

  def next: T = source.next()
}
