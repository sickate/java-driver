/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.cql;

import static com.datastax.oss.protocol.internal.FrameCodec.headerEncodedSize;
import static com.datastax.oss.protocol.internal.request.query.QueryOptions.queryFlagsSize;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigProfile;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchableStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.PrepareRequest;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.token.Token;
import com.datastax.oss.driver.api.core.servererrors.AlreadyExistsException;
import com.datastax.oss.driver.api.core.servererrors.BootstrappingException;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.servererrors.FunctionFailureException;
import com.datastax.oss.driver.api.core.servererrors.InvalidConfigurationInQueryException;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.OverloadedException;
import com.datastax.oss.driver.api.core.servererrors.ProtocolError;
import com.datastax.oss.driver.api.core.servererrors.ReadFailureException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.ServerError;
import com.datastax.oss.driver.api.core.servererrors.SyntaxError;
import com.datastax.oss.driver.api.core.servererrors.TruncateException;
import com.datastax.oss.driver.api.core.servererrors.UnauthorizedException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteFailureException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.type.codec.TypeCodecs;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.driver.internal.core.ProtocolFeature;
import com.datastax.oss.driver.internal.core.ProtocolVersionRegistry;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metadata.token.ByteOrderedToken;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3Token;
import com.datastax.oss.driver.internal.core.metadata.token.RandomToken;
import com.datastax.oss.driver.shaded.guava.common.base.Preconditions;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.PrimitiveSizes;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.request.Batch;
import com.datastax.oss.protocol.internal.request.Execute;
import com.datastax.oss.protocol.internal.request.Query;
import com.datastax.oss.protocol.internal.request.query.QueryOptions;
import com.datastax.oss.protocol.internal.request.query.Values;
import com.datastax.oss.protocol.internal.response.Error;
import com.datastax.oss.protocol.internal.response.Result;
import com.datastax.oss.protocol.internal.response.error.AlreadyExists;
import com.datastax.oss.protocol.internal.response.error.ReadFailure;
import com.datastax.oss.protocol.internal.response.error.ReadTimeout;
import com.datastax.oss.protocol.internal.response.error.Unavailable;
import com.datastax.oss.protocol.internal.response.error.WriteFailure;
import com.datastax.oss.protocol.internal.response.error.WriteTimeout;
import com.datastax.oss.protocol.internal.response.result.ColumnSpec;
import com.datastax.oss.protocol.internal.response.result.Prepared;
import com.datastax.oss.protocol.internal.response.result.Rows;
import com.datastax.oss.protocol.internal.response.result.RowsMetadata;
import com.datastax.oss.protocol.internal.util.Bytes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods to convert to/from protocol messages.
 *
 * <p>The main goal of this class is to move this code out of the request handlers.
 */
public class Conversions {

  public static Message toMessage(
      Statement<?> statement, DriverConfigProfile config, InternalDriverContext context) {
    int consistency =
        context
            .consistencyLevelRegistry()
            .fromName(config.getString(DefaultDriverOption.REQUEST_CONSISTENCY))
            .getProtocolCode();
    int pageSize = config.getInt(DefaultDriverOption.REQUEST_PAGE_SIZE);
    int serialConsistency =
        context
            .consistencyLevelRegistry()
            .fromName(config.getString(DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY))
            .getProtocolCode();
    long timestamp = statement.getTimestamp();
    if (timestamp == Long.MIN_VALUE) {
      timestamp = context.timestampGenerator().next();
    }
    CodecRegistry codecRegistry = context.codecRegistry();
    ProtocolVersion protocolVersion = context.protocolVersion();
    ProtocolVersionRegistry registry = context.protocolVersionRegistry();
    CqlIdentifier keyspace = statement.getKeyspace();
    if (statement instanceof SimpleStatement) {
      SimpleStatement simpleStatement = (SimpleStatement) statement;
      if (!simpleStatement.getPositionalValues().isEmpty()
          && !simpleStatement.getNamedValues().isEmpty()) {
        throw new IllegalArgumentException(
            "Can't have both positional and named values in a statement.");
      }
      if (keyspace != null
          && !registry.supports(protocolVersion, ProtocolFeature.PER_REQUEST_KEYSPACE)) {
        throw new IllegalArgumentException(
            "Can't use per-request keyspace with protocol " + protocolVersion);
      }
      QueryOptions queryOptions =
          new QueryOptions(
              consistency,
              encode(simpleStatement.getPositionalValues(), codecRegistry, protocolVersion),
              encode(simpleStatement.getNamedValues(), codecRegistry, protocolVersion),
              false,
              pageSize,
              statement.getPagingState(),
              serialConsistency,
              timestamp,
              (keyspace == null) ? null : keyspace.asInternal());
      return new Query(simpleStatement.getQuery(), queryOptions);
    } else if (statement instanceof BoundStatement) {
      BoundStatement boundStatement = (BoundStatement) statement;
      if (!registry.supports(protocolVersion, ProtocolFeature.UNSET_BOUND_VALUES)) {
        ensureAllSet(boundStatement);
      }
      boolean skipMetadata =
          boundStatement.getPreparedStatement().getResultSetDefinitions().size() > 0;
      QueryOptions queryOptions =
          new QueryOptions(
              consistency,
              boundStatement.getValues(),
              Collections.emptyMap(),
              skipMetadata,
              pageSize,
              statement.getPagingState(),
              serialConsistency,
              timestamp,
              null);
      PreparedStatement preparedStatement = boundStatement.getPreparedStatement();
      ByteBuffer id = preparedStatement.getId();
      ByteBuffer resultMetadataId = preparedStatement.getResultMetadataId();
      return new Execute(
          Bytes.getArray(id),
          (resultMetadataId == null) ? null : Bytes.getArray(resultMetadataId),
          queryOptions);
    } else if (statement instanceof BatchStatement) {
      BatchStatement batchStatement = (BatchStatement) statement;
      if (!registry.supports(protocolVersion, ProtocolFeature.UNSET_BOUND_VALUES)) {
        ensureAllSet(batchStatement);
      }
      if (keyspace != null
          && !registry.supports(protocolVersion, ProtocolFeature.PER_REQUEST_KEYSPACE)) {
        throw new IllegalArgumentException(
            "Can't use per-request keyspace with protocol " + protocolVersion);
      }
      List<Object> queriesOrIds = new ArrayList<>(batchStatement.size());
      List<List<ByteBuffer>> values = new ArrayList<>(batchStatement.size());
      for (BatchableStatement<?> child : batchStatement) {
        if (child instanceof SimpleStatement) {
          SimpleStatement simpleStatement = (SimpleStatement) child;
          if (simpleStatement.getNamedValues().size() > 0) {
            throw new IllegalArgumentException(
                String.format(
                    "Batch statements cannot contain simple statements with named values "
                        + "(offending statement: %s)",
                    simpleStatement.getQuery()));
          }
          queriesOrIds.add(simpleStatement.getQuery());
          values.add(encode(simpleStatement.getPositionalValues(), codecRegistry, protocolVersion));
        } else if (child instanceof BoundStatement) {
          BoundStatement boundStatement = (BoundStatement) child;
          queriesOrIds.add(Bytes.getArray(boundStatement.getPreparedStatement().getId()));
          values.add(boundStatement.getValues());
        } else {
          throw new IllegalArgumentException(
              "Unsupported child statement: " + child.getClass().getName());
        }
      }
      return new Batch(
          batchStatement.getBatchType().getProtocolCode(),
          queriesOrIds,
          values,
          consistency,
          serialConsistency,
          timestamp,
          (keyspace == null) ? null : keyspace.asInternal());
    } else {
      throw new IllegalArgumentException(
          "Unsupported statement type: " + statement.getClass().getName());
    }
  }

  public static List<ByteBuffer> encode(
      List<Object> values, CodecRegistry codecRegistry, ProtocolVersion protocolVersion) {
    if (values.isEmpty()) {
      return Collections.emptyList();
    } else {
      List<ByteBuffer> encodedValues = new ArrayList<>(values.size());
      for (Object value : values) {
        encodedValues.add(encode(value, codecRegistry, protocolVersion));
      }
      return encodedValues;
    }
  }

  public static Map<String, ByteBuffer> encode(
      Map<CqlIdentifier, Object> values,
      CodecRegistry codecRegistry,
      ProtocolVersion protocolVersion) {
    if (values.isEmpty()) {
      return Collections.emptyMap();
    } else {
      ImmutableMap.Builder<String, ByteBuffer> encodedValues = ImmutableMap.builder();
      for (Map.Entry<CqlIdentifier, Object> entry : values.entrySet()) {
        encodedValues.put(
            entry.getKey().asInternal(), encode(entry.getValue(), codecRegistry, protocolVersion));
      }
      return encodedValues.build();
    }
  }

  public static ByteBuffer encode(
      Object value, CodecRegistry codecRegistry, ProtocolVersion protocolVersion) {
    if (value instanceof Token) {
      if (value instanceof Murmur3Token) {
        return TypeCodecs.BIGINT.encode(((Murmur3Token) value).getValue(), protocolVersion);
      } else if (value instanceof ByteOrderedToken) {
        return TypeCodecs.BLOB.encode(((ByteOrderedToken) value).getValue(), protocolVersion);
      } else if (value instanceof RandomToken) {
        return TypeCodecs.VARINT.encode(((RandomToken) value).getValue(), protocolVersion);
      } else {
        throw new IllegalArgumentException("Unsupported token type " + value.getClass());
      }
    } else {
      return codecRegistry.codecFor(value).encode(value, protocolVersion);
    }
  }

  public static void ensureAllSet(BoundStatement boundStatement) {
    for (int i = 0; i < boundStatement.size(); i++) {
      if (!boundStatement.isSet(i)) {
        throw new IllegalStateException(
            "Unset value at index "
                + i
                + ". "
                + "If you want this value to be null, please set it to null explicitly.");
      }
    }
  }

  public static void ensureAllSet(BatchStatement batchStatement) {
    for (BatchableStatement<?> batchableStatement : batchStatement) {
      if (batchableStatement instanceof BoundStatement) {
        ensureAllSet(((BoundStatement) batchableStatement));
      }
    }
  }

  public static AsyncResultSet toResultSet(
      Result result,
      ExecutionInfo executionInfo,
      CqlSession session,
      InternalDriverContext context) {
    if (result instanceof Rows) {
      Rows rows = (Rows) result;
      Statement<?> statement = executionInfo.getStatement();
      ColumnDefinitions columnDefinitions = getResultDefinitions(rows, statement, context);
      return new DefaultAsyncResultSet(
          columnDefinitions, executionInfo, rows.getData(), session, context);
    } else if (result instanceof Prepared) {
      // This should never happen
      throw new IllegalArgumentException("Unexpected PREPARED response to a CQL query");
    } else {
      // Void, SetKeyspace, SchemaChange
      return DefaultAsyncResultSet.empty(executionInfo);
    }
  }

  public static ColumnDefinitions getResultDefinitions(
      Rows rows, Statement<?> statement, InternalDriverContext context) {
    RowsMetadata rowsMetadata = rows.getMetadata();
    if (rowsMetadata.columnSpecs.isEmpty()) {
      // If the response has no metadata, it means the request had SKIP_METADATA set, the driver
      // only ever does that for bound statements.
      BoundStatement boundStatement = (BoundStatement) statement;
      return boundStatement.getPreparedStatement().getResultSetDefinitions();
    } else {
      // The response has metadata, always use it above anything else we might have locally.
      ColumnDefinitions definitions = toColumnDefinitions(rowsMetadata, context);
      // In addition, if the server signaled a schema change (see CASSANDRA-10786), update the
      // prepared statement's copy of the metadata
      if (rowsMetadata.newResultMetadataId != null) {
        BoundStatement boundStatement = (BoundStatement) statement;
        PreparedStatement preparedStatement = boundStatement.getPreparedStatement();
        preparedStatement.setResultMetadata(
            ByteBuffer.wrap(rowsMetadata.newResultMetadataId).asReadOnlyBuffer(), definitions);
      }
      return definitions;
    }
  }

  public static DefaultPreparedStatement toPreparedStatement(
      Prepared response, PrepareRequest request, InternalDriverContext context) {
    return new DefaultPreparedStatement(
        ByteBuffer.wrap(response.preparedQueryId).asReadOnlyBuffer(),
        request.getQuery(),
        toColumnDefinitions(response.variablesMetadata, context),
        asList(response.variablesMetadata.pkIndices),
        (response.resultMetadataId == null)
            ? null
            : ByteBuffer.wrap(response.resultMetadataId).asReadOnlyBuffer(),
        toColumnDefinitions(response.resultMetadata, context),
        request.getConfigProfileNameForBoundStatements(),
        request.getConfigProfileForBoundStatements(),
        request.getKeyspace(),
        ImmutableMap.copyOf(request.getCustomPayloadForBoundStatements()),
        request.areBoundStatementsIdempotent(),
        context.codecRegistry(),
        context.protocolVersion(),
        ImmutableMap.copyOf(request.getCustomPayload()));
  }

  public static ColumnDefinitions toColumnDefinitions(
      RowsMetadata metadata, InternalDriverContext context) {
    ImmutableList.Builder<ColumnDefinition> definitions = ImmutableList.builder();
    for (ColumnSpec columnSpec : metadata.columnSpecs) {
      definitions.add(new DefaultColumnDefinition(columnSpec, context));
    }
    return DefaultColumnDefinitions.valueOf(definitions.build());
  }

  public static List<Integer> asList(int[] pkIndices) {
    if (pkIndices == null || pkIndices.length == 0) {
      return Collections.emptyList();
    } else {
      ImmutableList.Builder<Integer> builder = ImmutableList.builder();
      for (int pkIndex : pkIndices) {
        builder.add(pkIndex);
      }
      return builder.build();
    }
  }

  public static CoordinatorException toThrowable(
      Node node, Error errorMessage, InternalDriverContext context) {
    switch (errorMessage.code) {
      case ProtocolConstants.ErrorCode.UNPREPARED:
        throw new AssertionError(
            "UNPREPARED should be handled as a special case, not turned into an exception");
      case ProtocolConstants.ErrorCode.SERVER_ERROR:
        return new ServerError(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.PROTOCOL_ERROR:
        return new ProtocolError(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.AUTH_ERROR:
        // This method is used for query execution, authentication errors should only happen during
        // connection init
        return new ProtocolError(
            node, "Unexpected authentication error (" + errorMessage.message + ")");
      case ProtocolConstants.ErrorCode.UNAVAILABLE:
        Unavailable unavailable = (Unavailable) errorMessage;
        return new UnavailableException(
            node,
            context.consistencyLevelRegistry().fromCode(unavailable.consistencyLevel),
            unavailable.required,
            unavailable.alive);
      case ProtocolConstants.ErrorCode.OVERLOADED:
        return new OverloadedException(node);
      case ProtocolConstants.ErrorCode.IS_BOOTSTRAPPING:
        return new BootstrappingException(node);
      case ProtocolConstants.ErrorCode.TRUNCATE_ERROR:
        return new TruncateException(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.WRITE_TIMEOUT:
        WriteTimeout writeTimeout = (WriteTimeout) errorMessage;
        return new WriteTimeoutException(
            node,
            context.consistencyLevelRegistry().fromCode(writeTimeout.consistencyLevel),
            writeTimeout.received,
            writeTimeout.blockFor,
            context.writeTypeRegistry().fromName(writeTimeout.writeType));
      case ProtocolConstants.ErrorCode.READ_TIMEOUT:
        ReadTimeout readTimeout = (ReadTimeout) errorMessage;
        return new ReadTimeoutException(
            node,
            context.consistencyLevelRegistry().fromCode(readTimeout.consistencyLevel),
            readTimeout.received,
            readTimeout.blockFor,
            readTimeout.dataPresent);
      case ProtocolConstants.ErrorCode.READ_FAILURE:
        ReadFailure readFailure = (ReadFailure) errorMessage;
        return new ReadFailureException(
            node,
            context.consistencyLevelRegistry().fromCode(readFailure.consistencyLevel),
            readFailure.received,
            readFailure.blockFor,
            readFailure.numFailures,
            readFailure.dataPresent,
            readFailure.reasonMap);
      case ProtocolConstants.ErrorCode.FUNCTION_FAILURE:
        return new FunctionFailureException(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.WRITE_FAILURE:
        WriteFailure writeFailure = (WriteFailure) errorMessage;
        return new WriteFailureException(
            node,
            context.consistencyLevelRegistry().fromCode(writeFailure.consistencyLevel),
            writeFailure.received,
            writeFailure.blockFor,
            context.writeTypeRegistry().fromName(writeFailure.writeType),
            writeFailure.numFailures,
            writeFailure.reasonMap);
      case ProtocolConstants.ErrorCode.SYNTAX_ERROR:
        return new SyntaxError(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.UNAUTHORIZED:
        return new UnauthorizedException(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.INVALID:
        return new InvalidQueryException(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.CONFIG_ERROR:
        return new InvalidConfigurationInQueryException(node, errorMessage.message);
      case ProtocolConstants.ErrorCode.ALREADY_EXISTS:
        AlreadyExists alreadyExists = (AlreadyExists) errorMessage;
        return new AlreadyExistsException(node, alreadyExists.keyspace, alreadyExists.table);
      default:
        return new ProtocolError(node, "Unknown error code: " + errorMessage.code);
    }
  }

  /** Returns a common size for all kinds of Request implementations. */
  static int minimumRequestSize(Request statement, DriverContext context) {
    Preconditions.checkArgument(
        context instanceof InternalDriverContext,
        "DriverContext provided cannot be used to calculate statement's size");

    /* Header and payload are common inside a Frame at the protocol level */

    // Frame header has a fixed size of 9 for protocol version > V3, which includes Frame flags size
    int size = headerEncodedSize();

    if (!statement.getCustomPayload().isEmpty()
        && ((InternalDriverContext) context)
            .protocolVersionRegistry()
            .supports(context.protocolVersion(), ProtocolFeature.CUSTOM_PAYLOAD)) {
      size += PrimitiveSizes.sizeOfBytesMap(statement.getCustomPayload());
    }

    /* These are options in the protocol inside a frame that are common to all Statements */

    size += queryFlagsSize(context.protocolVersion().getCode());

    size += PrimitiveSizes.SHORT; // size of consistency level
    size += PrimitiveSizes.SHORT; // size of serial consistency level

    return size;
  }

  /**
   * Returns the size in bytes of a simple statement's values, depending on whether the values are
   * named or positional.
   */
  static int sizeOfSimpleStatementValues(
      SimpleStatement simpleStatement,
      ProtocolVersion protocolVersion,
      CodecRegistry codecRegistry) {
    int size = 0;

    if (!simpleStatement.getPositionalValues().isEmpty()) {

      List<ByteBuffer> positionalValues =
          new ArrayList<>(simpleStatement.getPositionalValues().size());
      for (Object value : simpleStatement.getPositionalValues()) {
        positionalValues.add(Conversions.encode(value, codecRegistry, protocolVersion));
      }

      size += Values.sizeOfPositionalValues(positionalValues);

    } else if (!simpleStatement.getNamedValues().isEmpty()) {

      Map<String, ByteBuffer> namedValues = new HashMap<>(simpleStatement.getNamedValues().size());
      for (Map.Entry<CqlIdentifier, Object> value : simpleStatement.getNamedValues().entrySet()) {
        namedValues.put(
            value.getKey().asInternal(),
            Conversions.encode(value.getValue(), codecRegistry, protocolVersion));
      }

      size += Values.sizeOfNamedValues(namedValues);
    }
    return size;
  }

  /** Return the size in bytes of a bound statement's values. */
  static int sizeOfBoundStatementValues(BoundStatement boundStatement) {
    return Values.sizeOfPositionalValues(boundStatement.getValues());
  }
}
