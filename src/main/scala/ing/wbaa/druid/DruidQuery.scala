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

package ing.wbaa.druid

import java.time.ZonedDateTime

import akka.NotUsed
import akka.stream.scaladsl.Source
import ca.mrvisser.sealerate
import ing.wbaa.druid.definitions.QueryContext.{ QueryContextParam, QueryContextValue }
import ing.wbaa.druid.definitions._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe._

import scala.concurrent.{ ExecutionContext, Future }

sealed trait QueryType extends Enum with CamelCaseEnumStringEncoder
object QueryType extends EnumCodec[QueryType] {
  case object TopN       extends QueryType
  case object GroupBy    extends QueryType
  case object Timeseries extends QueryType
  case object Scan       extends QueryType
  case object Select     extends QueryType
  case object Search     extends QueryType
  val values: Set[QueryType] = sealerate.values[QueryType]
}

sealed trait DruidQuery {

  val queryType: QueryType
  val dataSource: String
  val context: Map[String, String]

  /**
    * Utility method that converts the query to the corresponding native Druid JSON request
    *
    * @return corresponding JSON representation of the query
    */
  def toDebugString: String = this.asInstanceOf[DruidQuery].asJson.toString()

}

object DruidQuery {

  implicit val encoder: Encoder[DruidQuery] = new Encoder[DruidQuery] {
    final def apply(query: DruidQuery): Json =
      (query match {
        case x: GroupByQuery    => x.asJsonObject
        case x: TimeSeriesQuery => x.asJsonObject
        case x: TopNQuery       => x.asJsonObject
        case x: ScanQuery       => x.asJsonObject
        case x: SelectQuery     => x.asJsonObject
        case x: SearchQuery     => x.asJsonObject
      }).add("queryType", query.queryType.asJson)
        .add("dataSource", query.dataSource.asJson)
        .asJson
  }
}

sealed trait DruidQueryFunctions {
  this: DruidQuery =>

  def execute()(implicit config: DruidConfig = DruidConfig.DefaultConfig): Future[DruidResponse] =
    config.client.doQuery(this)

  def stream()(
      implicit config: DruidConfig = DruidConfig.DefaultConfig
  ): Source[BaseResult, NotUsed] =
    config.client.doQueryAsStream(this)

  def streamAs[T]()(
      implicit config: DruidConfig = DruidConfig.DefaultConfig,
      decoder: Decoder[T]
  ): Source[T, NotUsed] = {

    val source = this.stream()
    queryType match {
      case QueryType.TopN => source.mapConcat(result => result.as[List[T]])
      case _              => source.map(result => result.as[T])
    }
  }

  def streamSeriesAs[T]()(
      implicit config: DruidConfig = DruidConfig.DefaultConfig,
      decoder: Decoder[T]
  ): Source[(ZonedDateTime, T), NotUsed] = {

    val source = this.stream()

    queryType match {
      case QueryType.TopN =>
        source.mapConcat(result => result.as[List[T]].map(entry => (result.timestamp, entry)))
      case _ => source.map(result => (result.timestamp, result.as[T]))
    }
  }
}

case class GroupByQuery(
    aggregations: Iterable[Aggregation],
    intervals: Iterable[String],
    filter: Option[Filter] = None,
    dimensions: Iterable[Dimension] = Iterable.empty,
    granularity: Granularity = GranularityType.All,
    having: Option[Having] = None,
    limitSpec: Option[LimitSpec] = None,
    postAggregations: Iterable[PostAggregation] = Iterable.empty,
    context: Map[QueryContextParam, QueryContextValue] = Map.empty
)(implicit val config: DruidConfig = DruidConfig.DefaultConfig)
    extends DruidQuery
    with DruidQueryFunctions {
  val queryType          = QueryType.GroupBy
  val dataSource: String = config.datasource
}

case class LimitSpec(limit: Int, columns: Iterable[OrderByColumnSpec]) {
  val `type` = "default"
}

object LimitSpec {
  implicit val encoder: Encoder[LimitSpec] = new Encoder[LimitSpec] {
    override def apply(a: LimitSpec): Json = a.asJsonObject.add("type", a.`type`.asJson).asJson
  }
}

case class OrderByColumnSpec(
    dimension: String,
    direction: Direction = Direction.Ascending,
    dimensionOrder: DimensionOrder = DimensionOrder()
)
case class DimensionOrder(`type`: DimensionOrderType = DimensionOrderType.Lexicographic)

sealed trait Direction extends Enum with LowerCaseEnumStringEncoder
object Direction extends EnumCodec[Direction] {
  case object Ascending  extends Direction
  case object Descending extends Direction
  val values: Set[Direction] = sealerate.values[Direction]
}

sealed trait DimensionOrderType extends Enum with LowerCaseEnumStringEncoder
object DimensionOrderType extends EnumCodec[DimensionOrderType] {
  case object Lexicographic extends DimensionOrderType
  case object Alphanumeric  extends DimensionOrderType
  case object Strlen        extends DimensionOrderType
  case object Numeric       extends DimensionOrderType
  val values: Set[DimensionOrderType] = sealerate.values[DimensionOrderType]

}

case class TimeSeriesQuery(
    aggregations: Iterable[Aggregation],
    intervals: Iterable[String],
    filter: Option[Filter] = None,
    granularity: Granularity = GranularityType.Week,
    descending: String = "true",
    postAggregations: Iterable[PostAggregation] = Iterable.empty,
    context: Map[QueryContextParam, QueryContextValue] = Map.empty
)(implicit val config: DruidConfig = DruidConfig.DefaultConfig)
    extends DruidQuery
    with DruidQueryFunctions {
  val queryType          = QueryType.Timeseries
  val dataSource: String = config.datasource
}

case class TopNQuery(
    dimension: Dimension,
    threshold: Int,
    metric: String,
    aggregations: Iterable[Aggregation],
    intervals: Iterable[String],
    granularity: Granularity = GranularityType.All,
    filter: Option[Filter] = None,
    postAggregations: Iterable[PostAggregation] = Iterable.empty,
    context: Map[QueryContextParam, QueryContextValue] = Map.empty
)(implicit val config: DruidConfig = DruidConfig.DefaultConfig)
    extends DruidQuery
    with DruidQueryFunctions {
  val queryType          = QueryType.TopN
  val dataSource: String = config.datasource

}

case class ScanQuery private (
    granularity: Granularity,
    intervals: Iterable[String],
    filter: Option[Filter],
    columns: Iterable[String],
    batchSize: Option[Int],
    limit: Option[Int],
    order: Option[Order],
    legacy: Option[Boolean],
    context: Map[QueryContextParam, QueryContextValue]
)(implicit val config: DruidConfig)
    extends DruidQuery
    with DruidQueryFunctions {

  val queryType: QueryType = QueryType.Scan
  val dataSource: String   = config.datasource
  val resultFormat: String = "list"
}

object ScanQuery {

  def apply(
      granularity: Granularity,
      intervals: Iterable[String],
      columns: Iterable[String] = Iterable.empty,
      filter: Option[Filter] = None,
      batchSize: Option[Int] = None,
      limit: Option[Int] = None,
      order: Order = OrderType.None,
      context: Map[QueryContextParam, QueryContextValue] = Map.empty
  )(implicit config: DruidConfig = DruidConfig.DefaultConfig): ScanQuery = {

    // Depending on the mode (legacy or not) the name of the time dimension is either named as 'timestamp' or '__time'
    val timeDimensionName = if (config.scanQueryLegacyMode) "timestamp" else "__time"

    // When specific columns and metrics are defined, then we have to make sure that the time dimension is
    // also included. In any other case, we simply passthrough the specified columns --- as the columns are either
    // empty (meaning that all dimensions and metrics will be returned, including the time dimension) or the time
    // dimension is already included in `columns`. Please note that we need the time dimension, since it is a
    // mandatory field of [[ing.wbaa.druid.BaseResult]] and it is used by the implementations
    // of [[ing.wbaa.druid.DruidResponse.series]]
    val resultingColumns: Iterable[String] =
      if (columns.isEmpty || columns.exists(_ == timeDimensionName)) columns
      else timeDimensionName :: (columns.toList)

    new ScanQuery(granularity,
                  intervals,
                  filter,
                  resultingColumns,
                  batchSize,
                  limit,
                  Option(order),
                  Option(config.scanQueryLegacyMode),
                  context)
  }
}

case class SelectQuery(
    granularity: Granularity,
    intervals: Iterable[String],
    pagingSpec: PagingSpec,
    filter: Option[Filter] = None,
    descending: Boolean = false,
    dimensions: Iterable[Dimension] = Iterable.empty,
    metrics: Iterable[String] = Iterable.empty,
    context: Map[QueryContextParam, QueryContextValue] = Map.empty
)(implicit val config: DruidConfig = DruidConfig.DefaultConfig)
    extends DruidQuery
    with DruidQueryFunctions {
  val queryType          = QueryType.Select
  val dataSource: String = config.datasource
}

case class PagingSpec(
    threshold: Int,
    fromNext: Boolean = true,
    pagingIdentifiers: Map[String, Int] = Map.empty
)

object PagingSpec {
  def legacy(threshold: Int, pagingIdentifiers: Map[String, Int] = Map.empty): PagingSpec =
    new PagingSpec(threshold, false, pagingIdentifiers)
}

case class SearchQuery(
    granularity: Granularity,
    intervals: Iterable[String],
    query: SearchQuerySpec,
    filter: Option[Filter] = None,
    limit: Option[Int] = None,
    searchDimensions: Iterable[String] = Iterable.empty,
    sort: Option[DimensionOrder] = None,
    context: Map[QueryContextParam, QueryContextValue] = Map.empty
)(implicit val config: DruidConfig = DruidConfig.DefaultConfig)
    extends DruidQuery {

  val queryType          = QueryType.Search
  val dataSource: String = config.datasource

  def execute()(
      implicit config: DruidConfig = DruidConfig.DefaultConfig,
      ec: ExecutionContext = config.client.actorSystem.dispatcher
  ): Future[DruidResponseSearch] =
    config.client.doQuery(this).map(DruidResponseSearch)

  def stream()(implicit config: DruidConfig): Source[DruidSearchResult, NotUsed] =
    config.client.doQueryAsStream(this).mapConcat(_.as[List[DruidSearchResult]])

  def streamSeries()(
      implicit config: DruidConfig
  ): Source[(ZonedDateTime, DruidSearchResult), NotUsed] =
    config.client
      .doQueryAsStream(this)
      .mapConcat { response =>
        response
          .as[List[DruidSearchResult]]
          .map(result => response.timestamp -> result)
      }
}
