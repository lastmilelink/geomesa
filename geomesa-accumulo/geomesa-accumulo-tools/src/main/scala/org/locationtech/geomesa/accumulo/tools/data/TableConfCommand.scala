/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.tools.data

import java.util.regex.Pattern

import com.beust.jcommander.{JCommander, Parameter, Parameters}
import org.apache.accumulo.core.client.TableNotFoundException
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.accumulo.index.AccumuloFeatureIndex
import org.locationtech.geomesa.accumulo.tools.{AccumuloDataStoreCommand, AccumuloDataStoreParams}
import org.locationtech.geomesa.tools.{Command, CommandWithSubCommands, RequiredTypeNameParam, Runner}
import org.locationtech.geomesa.utils.index.IndexMode
import org.opengis.feature.simple.SimpleFeatureType

import scala.collection.JavaConversions._

class TableConfCommand(val runner: Runner, val jc: JCommander) extends CommandWithSubCommands {

  import TableConfCommand._

  override val name = "configure-table"
  override val params = new TableConfParams()

  override val subCommands: Seq[Command] = Seq(new TableConfListCommand, new TableConfUpdateCommand)
}

class TableConfListCommand extends AccumuloDataStoreCommand {

  import TableConfCommand._

  override val name = "list"
  override val params = new ListParams

  override def execute(): Unit = {
    Command.user.info(s"Reading configuration parameters for index '${params.index}'")
    withDataStore { ds =>
      val table = getTableName(ds, ds.getSchema(params.featureName), params.index)
      val properties = getProperties(ds, table)
      val pattern = Option(params.key).map(Pattern.compile)
      properties.filterKeys(k => pattern.forall(_.matcher(k).matches)).toSeq.sorted.foreach { case (k, v) =>
        Command.output.info(s"  $k=$v")
      }
    }
  }
}

class TableConfUpdateCommand extends AccumuloDataStoreCommand {

  import TableConfCommand._

  override val name = "update"
  override val params = new UpdateParams

  override def execute(): Unit = {
    withDataStore { ds =>
      Command.user.info(s"Reading configuration parameters for index '${params.index}'")
      val table = getTableName(ds, ds.getSchema(params.featureName), params.index)
      val value = getProp(ds, table, params.key)
      Command.user.info(s"  current value: ${params.key}=$value")

      if (params.newValue != value) {
        Command.user.info(s"Updating configuration parameter to '${params.newValue}'...")
        setValue(ds, table, params.key, params.newValue)
        val updated = getProp(ds, table, params.key)
        Command.user.info(s"  updated value: ${params.key}=$updated")
      } else {
        Command.user.info(s"'${params.key}' already set to '${params.newValue}'.")
      }
    }
  }
}

object TableConfCommand {

  def getProp(ds: AccumuloDataStore, table: String, key: String): String =
    getProperties(ds, table).getOrElse(key, throw new RuntimeException(s"Parameter '$key' not found in table '$table'"))

  def setValue(ds: AccumuloDataStore, table: String, key: String, value: String): Unit = {
    try {
      ds.connector.tableOperations.setProperty(table, key, value)
    } catch {
      case e: Exception => throw new RuntimeException(s"Error updating table property: ${e.getMessage}", e)
    }
  }

  def getProperties(ds: AccumuloDataStore, table: String): Map[String, String] = {
    try {
      ds.connector.tableOperations.getProperties(table).map(e => (e.getKey, e.getValue)).toMap
    } catch {
      case e: TableNotFoundException =>
        throw new RuntimeException(s"Error: table $table does not exist: ${e.getMessage}", e)
    }
  }

  def getTableName(ds: AccumuloDataStore, sft: SimpleFeatureType, index: String): String = {
    val indices = AccumuloFeatureIndex.indices(sft, IndexMode.Any)
    indices.find(_.name.equalsIgnoreCase(index)).map(_.getTableName(sft.getTypeName, ds)).getOrElse {
      throw new IllegalArgumentException(s"Index '$index' does not exist for schema '${sft.getTypeName}'. " +
          s"Available indices: ${indices.map(_.name).mkString(", ")}")
    }
  }

  @Parameters(commandDescription = "Perform table configuration operations")
  class TableConfParams {}

  @Parameters(commandDescription = "List the configuration parameters for a geomesa table")
  class ListParams extends AccumuloDataStoreParams with RequiredTypeNameParam {
    @Parameter(names = Array("--index"), description = "Index to operate on (z2, z3, etc)", required = true)
    var index: String = _

    @Parameter(names = Array("-k", "--key"), description = "Table configuration key regex (e.g. table\\.bloom.*)")
    var key: String = _
  }

  @Parameters(commandDescription = "Update a given table configuration parameter")
  class UpdateParams extends AccumuloDataStoreParams with RequiredTypeNameParam {
    @Parameter(names = Array("--index"), description = "Index to operate on (z2, z3, etc)", required = true)
    var index: String = _

    @Parameter(names = Array("-k", "--key"), description = "Table configuration parameter key (e.g. table.bloom.enabled)", required = true)
    var key: String = _

    @Parameter(names = Array("-v", "--value"), description = "Value to set", required = true)
    var newValue: String = _
  }
}
