package zio

import zio.schema.Schema
import zio.stream.{ ZSink, ZStream }

package object dynamodb {
  // Filter expression is the same as a ConditionExpression but when used with Query but does not allow key attributes
  type FilterExpression[-From] = ConditionExpression[From]
  type LastEvaluatedKey        = Option[PrimaryKey]
  type PrimaryKey              = AttrMap
  val PrimaryKey = AttrMap
  type Item = AttrMap
  val Item = AttrMap

  type PkAndItem      = (PrimaryKey, Item)
  type TableNameAndPK = (String, String)

  type Encoder[A]  = A => AttributeValue
  type Decoder[+A] = AttributeValue => Either[DynamoDBError, A]

  private[dynamodb] def ddbExecute[A](query: DynamoDBQuery[_, A]): ZIO[DynamoDBExecutor, Throwable, A] =
    ZIO.serviceWithZIO[DynamoDBExecutor](_.execute(query))

  /**
   * Reads `stream` and uses function `f` for creating a BatchWrite request that is executes for side effects. Stream is batched into groups
   * of 25 items in a BatchWriteItem and executed using the `DynamoDBExecutor` service provided in the environment.
   * @param stream
   * @param mPar Level of parallelism for the stream processing
   * @param f Function that takes an `A` and returns a PutItem or WriteItem
   * @tparam R Environment
   * @tparam A
   * @tparam B Type of DynamoDBQuery returned by `f`
   * @return A stream of results from the `DynamoDBQuery` write's
   */
  def batchWriteFromStream[R, A, In, B](
    stream: ZStream[R, Throwable, A],
    mPar: Int = 10
  )(f: A => DynamoDBQuery[In, B]): ZStream[DynamoDBExecutor with R, Throwable, B] =
    stream
      .aggregateAsync(ZSink.collectAllN[A](25))
      .mapZIOPar(mPar) { chunk =>
        val batchWriteItem = DynamoDBQuery
          .forEach(chunk)(a => f(a))
          .map(Chunk.fromIterable)
        for {
          r <- ZIO.environment[DynamoDBExecutor]
          b <- batchWriteItem.execute.provideEnvironment(r)
        } yield b
      }
      .flattenChunks

  /**
   * Reads `stream` using function `pk` to determine the primary key which is then used to create a BatchGetItem request.
   * Stream is batched into groups of 100 items in a BatchGetItem and executed using the provided `DynamoDBExecutor` service
   * @param tableName
   * @param stream
   * @param mPar Level of parallelism for the stream processing
   * @param pk Function to determine the primary key
   * @tparam R Environment
   * @tparam A
   * @return A stream of (A, Item)
   */
  def batchReadItemFromStream[R, A](
    tableName: String,
    stream: ZStream[R, Throwable, A],
    mPar: Int = 10
  )(
    pk: A => PrimaryKey
  ): ZStream[R with DynamoDBExecutor, Throwable, (A, Item)] =
    stream
      .aggregateAsync(ZSink.collectAllN[A](100))
      .mapZIOPar(mPar) { chunk =>
        val batchGetItem: DynamoDBQuery[_, Chunk[Option[(A, Item)]]] = DynamoDBQuery
          .forEach(chunk)(a =>
            DynamoDBQuery.getItem(tableName, pk(a)).map {
              case Some(item) => Some((a, item))
              case None       => None
            }
          )
          .map(Chunk.fromIterable)
        for {
          r <- ZIO.environment[DynamoDBExecutor]
          list <- batchGetItem.execute.provideEnvironment(r)
        } yield list
      }
      .flattenChunks
      .collectSome

  /**
   * Reads `stream` using function `pk` to determine the primary key which is then used to create a BatchGetItem request.
   * Stream is batched into groups of 100 items in a BatchGetItem and executed using the provided `DynamoDBExecutor` service
   *
   * @param tableName
   * @param stream
   * @param mPar Level of parallelism for the stream processing
   * @param pk Function to determine the primary key
   * @tparam R Environment
   * @tparam A Input stream element type
   * @tparam B implicit Schema[B]
   * @return stream of (A, B), or fails on first error to convert an item to A
   */
  def batchReadFromStream[R, A, B: Schema](
    tableName: String,
    stream: ZStream[R, Throwable, A],
    mPar: Int = 10
  )(pk: A => PrimaryKey): ZStream[R with DynamoDBExecutor, Throwable, (A, B)] =
    batchReadItemFromStream(tableName, stream, mPar)(pk).mapZIO {
      case (a, item) =>
        DynamoDBQuery.fromItem(item) match {
          case Right(b) => ZIO.succeed((a, b))
          case Left(s)  => ZIO.fail(new IllegalStateException(s)) // TODO: think about error model
        }
    }

}
