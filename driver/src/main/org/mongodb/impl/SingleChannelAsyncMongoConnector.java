/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.impl;

import org.mongodb.Codec;
import org.mongodb.Decoder;
import org.mongodb.Document;
import org.mongodb.Encoder;
import org.mongodb.MongoClientOptions;
import org.mongodb.MongoCredential;
import org.mongodb.MongoCursorNotFoundException;
import org.mongodb.MongoException;
import org.mongodb.MongoInternalException;
import org.mongodb.MongoInterruptedException;
import org.mongodb.MongoNamespace;
import org.mongodb.MongoQueryFailureException;
import org.mongodb.ServerAddress;
import org.mongodb.async.SingleResultCallback;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.command.GetLastError;
import org.mongodb.command.MongoCommand;
import org.mongodb.command.MongoCommandFailureException;
import org.mongodb.io.BufferPool;
import org.mongodb.io.CachingAuthenticator;
import org.mongodb.io.PooledByteBufferOutputBuffer;
import org.mongodb.io.ResponseBuffers;
import org.mongodb.io.async.MongoAsynchronousSocketChannelGateway;
import org.mongodb.operation.MongoFind;
import org.mongodb.operation.MongoGetMore;
import org.mongodb.operation.MongoInsert;
import org.mongodb.operation.MongoKillCursor;
import org.mongodb.operation.MongoRemove;
import org.mongodb.operation.MongoReplace;
import org.mongodb.operation.MongoUpdate;
import org.mongodb.operation.MongoWrite;
import org.mongodb.pool.SimplePool;
import org.mongodb.protocol.MongoCommandMessage;
import org.mongodb.protocol.MongoDeleteMessage;
import org.mongodb.protocol.MongoGetMoreMessage;
import org.mongodb.protocol.MongoInsertMessage;
import org.mongodb.protocol.MongoKillCursorsMessage;
import org.mongodb.protocol.MongoQueryMessage;
import org.mongodb.protocol.MongoReplaceMessage;
import org.mongodb.protocol.MongoReplyMessage;
import org.mongodb.protocol.MongoRequestMessage;
import org.mongodb.protocol.MongoUpdateMessage;
import org.mongodb.result.CommandResult;
import org.mongodb.result.QueryResult;
import org.mongodb.result.ServerCursor;
import org.mongodb.result.WriteResult;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class SingleChannelAsyncMongoConnector implements MongoPoolableConnector {
    private final ServerAddress serverAddress;
    private final SimplePool<MongoPoolableConnector> channelPool;
    private final BufferPool<ByteBuffer> bufferPool;
    private final MongoClientOptions options;
    private volatile MongoAsynchronousSocketChannelGateway channel;
    private volatile boolean activeAsyncCall;
    private volatile boolean releasePending;

    SingleChannelAsyncMongoConnector(final ServerAddress serverAddress, final List<MongoCredential> credentialList,
                                     final SimplePool<MongoPoolableConnector> channelPool, final BufferPool<ByteBuffer> bufferPool,
                                     final MongoClientOptions options) {
        this.serverAddress = serverAddress;
        this.channelPool = channelPool;
        this.bufferPool = bufferPool;
        this.options = options;
        this.channel = new MongoAsynchronousSocketChannelGateway(serverAddress,
                new CachingAuthenticator(new MongoCredentialsStore(credentialList), this), bufferPool);
    }

    public ServerAddress getServerAddress() {
        return serverAddress;
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    @Override
    public synchronized void release() {
        if (channel == null) {
            throw new IllegalStateException("Can not release a channel that's already closed");
        }
        if (channelPool == null) {
            throw new IllegalStateException("Can not release a channel not associated with a pool");
        }

        if (activeAsyncCall) {
            releasePending = true;
        }
        else {
            releasePending = false;
            channelPool.done(this);
        }
    }

    @Override
    public List<ServerAddress> getServerAddressList() {
        return Arrays.asList(channel.getAddress());
    }

    private Codec<Document> withDocumentCodec(final Codec<Document> codec) {
        if (codec != null) {
            return codec;
        }
        return new DocumentCodec(options.getPrimitiveCodecs());
    }

    private Encoder<Document> withDocumentEncoder(final Encoder<Document> encoder) {
        if (encoder != null) {
            return encoder;
        }
        return new DocumentCodec(options.getPrimitiveCodecs());
    }

    private Decoder<Document> getDocumentDecoder() {
        return new DocumentCodec(options.getPrimitiveCodecs());
    }

    private synchronized void releaseIfPending() {
        activeAsyncCall = false;
        if (releasePending) {
            release();
        }
    }

    @Override
    public CommandResult command(final String database, final MongoCommand commandOperation,
                                 final Codec<Document> codec) {
        try {
            CommandResult result = asyncCommand(database, commandOperation, codec).get();
            if (result == null) {
                throw new MongoInternalException("Command result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public <T> WriteResult insert(final MongoNamespace namespace, final MongoInsert<T> insert,
                                  final Encoder<T> encoder) {
        try {
            WriteResult result = asyncInsert(namespace, insert, encoder).get();
            if (result == null) {
                throw new MongoInternalException("Command result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public <T> QueryResult<T> query(final MongoNamespace namespace, final MongoFind find,
                                    final Encoder<Document> queryEncoder, final Decoder<T> resultDecoder) {
        try {
            QueryResult<T> result = asyncQuery(namespace, find, queryEncoder, resultDecoder).get();
            if (result == null) {
                throw new MongoInternalException("Query result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public <T> QueryResult<T> getMore(final MongoNamespace namespace, final MongoGetMore getMore,
                                      final Decoder<T> resultDecoder) {
        try {
            QueryResult<T> result = asyncGetMore(namespace, getMore, resultDecoder).get();
            if (result == null) {
                throw new MongoInternalException("Query result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public WriteResult update(final MongoNamespace namespace, final MongoUpdate update, final Encoder<Document> queryEncoder) {
        try {
            WriteResult result = asyncUpdate(namespace, update, queryEncoder).get();
            if (result == null) {
                throw new MongoInternalException("Command result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public <T> WriteResult replace(final MongoNamespace namespace, final MongoReplace<T> replace,
                                   final Encoder<Document> queryEncoder, final Encoder<T> encoder) {

        try {
            WriteResult result = asyncReplace(namespace, replace, queryEncoder, encoder).get();
            if (result == null) {
                throw new MongoInternalException("Command result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public WriteResult remove(final MongoNamespace namespace, final MongoRemove remove,
                              final Encoder<Document> queryEncoder) {
        try {
            WriteResult result = asyncRemove(namespace, remove, queryEncoder).get();
            if (result == null) {
                throw new MongoInternalException("Command result should not be null", null);
            }
            return result;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        }
        throw new IllegalStateException("Should be unreachable");
    }

    @Override
    public void killCursors(final MongoKillCursor killCursor) {
        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferPool);
        final MongoKillCursorsMessage message = new MongoKillCursorsMessage(killCursor);
        encodeMessageToBuffer(message, buffer);
        channel.sendMessage(buffer);
    }

    @Override
    public Future<CommandResult> asyncCommand(final String database, final MongoCommand commandOperation,
                                              final Codec<Document> codec) {
        final SingleResultFuture<CommandResult> retVal = new SingleResultFuture<CommandResult>();

        asyncCommand(database, commandOperation, codec, new SingleResultFutureCallback<CommandResult>(retVal));

        return retVal;
    }

    @Override
    public void asyncCommand(final String database, final MongoCommand commandOperation, final Codec<Document> codec,
                             final SingleResultCallback<CommandResult> callback) {
        commandOperation.readPreferenceIfAbsent(options.getReadPreference());
        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferPool);
        final MongoCommandMessage message = new MongoCommandMessage(database + ".$cmd", commandOperation,
                withDocumentEncoder(codec));
        encodeMessageToBuffer(message, buffer);
        channel.sendAndReceiveMessage(buffer, new MongoCommandResultCallback(callback, commandOperation, this,
                withDocumentCodec(codec)));
    }

    @Override
    public <T> Future<QueryResult<T>> asyncQuery(final MongoNamespace namespace, final MongoFind find,
                                                 final Encoder<Document> queryEncoder, final Decoder<T> resultDecoder) {
        final SingleResultFuture<QueryResult<T>> retVal = new SingleResultFuture<QueryResult<T>>();

        asyncQuery(namespace, find, queryEncoder, resultDecoder, new SingleResultFutureCallback<QueryResult<T>>(retVal));

        return retVal;
    }

    @Override
    public <T> void asyncQuery(final MongoNamespace namespace, final MongoFind find, final Encoder<Document> queryEncoder,
                               final Decoder<T> resultDecoder, final SingleResultCallback<QueryResult<T>> callback) {
        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferPool);
        final MongoQueryMessage message = new MongoQueryMessage(namespace.getFullName(), find, withDocumentEncoder(queryEncoder));
        encodeMessageToBuffer(message, buffer);
        channel.sendAndReceiveMessage(buffer, new MongoQueryResultCallback<T>(callback, this, resultDecoder));
    }

    @Override
    public <T> Future<QueryResult<T>> asyncGetMore(final MongoNamespace namespace, final MongoGetMore getMore,
                                                   final Decoder<T> resultDecoder) {
        final SingleResultFuture<QueryResult<T>> retVal = new SingleResultFuture<QueryResult<T>>();

        asyncGetMore(namespace, getMore, resultDecoder, new SingleResultFutureCallback<QueryResult<T>>(retVal));

        return retVal;
    }

    @Override
    public <T> void asyncGetMore(final MongoNamespace namespace, final MongoGetMore getMore, final Decoder<T> resultDecoder,
                                 final SingleResultCallback<QueryResult<T>> callback) {
        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferPool);
        final MongoGetMoreMessage message = new MongoGetMoreMessage(namespace.getFullName(), getMore);
        encodeMessageToBuffer(message, buffer);
        channel.sendAndReceiveMessage(buffer, new MongoGetMoreResultCallback<T>(callback, this, resultDecoder,
                getMore.getServerCursor().getId()));
    }

    @Override
    public <T> Future<WriteResult> asyncInsert(final MongoNamespace namespace, final MongoInsert<T> insert,
                                               final Encoder<T> encoder) {
        final SingleResultFuture<WriteResult> retVal = new SingleResultFuture<WriteResult>();

        asyncInsert(namespace, insert, encoder, new SingleResultFutureCallback<WriteResult>(retVal));

        return retVal;
    }

    @Override
    public <T> void asyncInsert(final MongoNamespace namespace, final MongoInsert<T> insert, final Encoder<T> encoder,
                                final SingleResultCallback<WriteResult> callback) {
        insert.writeConcernIfAbsent(options.getWriteConcern());
        final MongoInsertMessage<T> message = new MongoInsertMessage<T>(namespace.getFullName(), insert, encoder);
        sendAsyncWriteMessage(namespace, insert, withDocumentCodec(null), callback, message);
    }

    @Override
    public Future<WriteResult> asyncUpdate(final MongoNamespace namespace, final MongoUpdate update,
                                           final Encoder<Document> queryEncoder) {
        final SingleResultFuture<WriteResult> retVal = new SingleResultFuture<WriteResult>();

        asyncUpdate(namespace, update, queryEncoder, new SingleResultFutureCallback<WriteResult>(retVal));

        return retVal;
    }

    @Override
    public void asyncUpdate(final MongoNamespace namespace, final MongoUpdate replace, final Encoder<Document> queryEncoder,
                            final SingleResultCallback<WriteResult> callback) {
        replace.writeConcernIfAbsent(options.getWriteConcern());
        final MongoUpdateMessage message = new MongoUpdateMessage(namespace.getFullName(), replace, withDocumentEncoder(queryEncoder));
        sendAsyncWriteMessage(namespace, replace, queryEncoder, callback, message);
    }

    @Override
    public <T> Future<WriteResult> asyncReplace(final MongoNamespace namespace, final MongoReplace<T> replace,
                                                final Encoder<Document> queryEncoder, final Encoder<T> encoder) {
        final SingleResultFuture<WriteResult> retVal = new SingleResultFuture<WriteResult>();

        asyncReplace(namespace, replace, queryEncoder, encoder, new SingleResultFutureCallback<WriteResult>(retVal));

        return retVal;
    }

    @Override
    public <T> void asyncReplace(final MongoNamespace namespace, final MongoReplace<T> replace,
                                 final Encoder<Document> queryEncoder, final Encoder<T> encoder,
                                 final SingleResultCallback<WriteResult> callback) {
        replace.writeConcernIfAbsent(options.getWriteConcern());
        final MongoReplaceMessage<T> message = new MongoReplaceMessage<T>(namespace.getFullName(), replace,
                withDocumentEncoder(queryEncoder), encoder);
        sendAsyncWriteMessage(namespace, replace, queryEncoder, callback, message);
    }

    @Override
    public Future<WriteResult> asyncRemove(final MongoNamespace namespace, final MongoRemove remove,
                                           final Encoder<Document> queryEncoder) {
        final SingleResultFuture<WriteResult> retVal = new SingleResultFuture<WriteResult>();

        asyncRemove(namespace, remove, queryEncoder, new SingleResultFutureCallback<WriteResult>(retVal));

        return retVal;
    }

    @Override
    public void asyncRemove(final MongoNamespace namespace, final MongoRemove remove, final Encoder<Document> queryEncoder,
                            final SingleResultCallback<WriteResult> callback) {
        remove.writeConcernIfAbsent(options.getWriteConcern());
        final MongoDeleteMessage message = new MongoDeleteMessage(namespace.getFullName(), remove, withDocumentEncoder(queryEncoder));
        sendAsyncWriteMessage(namespace, remove, queryEncoder, callback, message);
    }

    private void sendAsyncWriteMessage(final MongoNamespace namespace, final MongoWrite write, final Encoder<Document> encoder,
                                       final SingleResultCallback<WriteResult> callback, final MongoRequestMessage message) {

        PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferPool);
        encodeMessageToBuffer(message, buffer);
        if (write.getWriteConcern().callGetLastError()) {
            final GetLastError getLastError = new GetLastError(write.getWriteConcern());
            MongoCommandMessage getLastErrorMessage =
                    new MongoCommandMessage(namespace.getDatabaseName() + ".$cmd", getLastError, withDocumentEncoder(encoder));
            encodeMessageToBuffer(getLastErrorMessage, buffer);
            channel.sendAndReceiveMessage(buffer, new MongoWriteResultCallback(callback, write, getLastError, this,
                    getDocumentDecoder()));
        }
        else {
            channel.sendMessage(buffer, new MongoWriteResultCallback(callback, write, null, this, getDocumentDecoder()));
        }
    }

    private void encodeMessageToBuffer(final MongoRequestMessage message, final PooledByteBufferOutputBuffer buffer) {
        try {
            message.encode(buffer);
        } catch (RuntimeException e) {
            buffer.close();
            throw e;
        } catch (Error e) {
            buffer.close();
            throw e;
        }
    }

    private void handleExecutionException(final ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException) e.getCause();
        }
        else if (e.getCause() instanceof Error) {
            throw (Error) e.getCause();
        }
        else {
            throw new MongoException("", e.getCause());
        }
    }


    private abstract static class MongoResponseCallback implements SingleResultCallback<ResponseBuffers> {
        private final SingleChannelAsyncMongoConnector connection;
        private volatile boolean closed;

        public MongoResponseCallback(final SingleChannelAsyncMongoConnector connection) {
            this.connection = connection;
            this.connection.activeAsyncCall = true;
        }

        @Override
        public void onResult(final ResponseBuffers responseBuffers, final MongoException e) {
            if (closed) {
                throw new MongoInternalException("This should not happen", null);
            }
            closed = true;
            final ServerAddress address = connection.getServerAddress();
            connection.releaseIfPending();
            if (responseBuffers != null) {
                callCallback(address, responseBuffers, e);
            }
            else {
                callCallback(address, null, e);
            }
        }

        public SingleChannelAsyncMongoConnector getConnection() {
            return connection;
        }

        protected abstract void callCallback(ServerAddress serverAddress, ResponseBuffers responseBuffers, MongoException e);
    }

    private static class MongoQueryResultCallback<T> extends MongoResponseCallback {
        private final SingleResultCallback<QueryResult<T>> callback;
        private final Decoder<T> decoder;

        public MongoQueryResultCallback(final SingleResultCallback<QueryResult<T>> callback,
                                        final SingleChannelAsyncMongoConnector connection,
                                        final Decoder<T> decoder) {
            super(connection);
            this.callback = callback;
            this.decoder = decoder;
        }

        @Override
        protected void callCallback(final ServerAddress serverAddress, final ResponseBuffers responseBuffers, final MongoException e) {
            try {
                if (e != null) {
                    callback.onResult(null, e);
                }
                else if (responseBuffers.getReplyHeader().isQueryFailure()) {
                    final Document errorDocument = new MongoReplyMessage<Document>(responseBuffers,
                            getConnection().withDocumentCodec(null)).getDocuments().get(0);
                    callback.onResult(null, new MongoQueryFailureException(getConnection().channel.getAddress(), errorDocument));
                }
                else {
                    callback.onResult(new QueryResult<T>(new MongoReplyMessage<T>(responseBuffers, decoder), serverAddress), null);
                }
            } finally {
                if (responseBuffers != null) {
                    responseBuffers.close();
                }
            }
        }
    }

    private static class MongoGetMoreResultCallback<T> extends MongoResponseCallback {
        private final SingleResultCallback<QueryResult<T>> callback;
        private final Decoder<T> decoder;
        private final long cursorId;

        public MongoGetMoreResultCallback(final SingleResultCallback<QueryResult<T>> callback,
                                          final SingleChannelAsyncMongoConnector connection,
                                          final Decoder<T> decoder,
                                          final long cursorId) {
            super(connection);
            this.callback = callback;
            this.decoder = decoder;
            this.cursorId = cursorId;
        }

        @Override
        protected void callCallback(final ServerAddress serverAddress, final ResponseBuffers responseBuffers, final MongoException e) {
            try {
                if (e != null) {
                    callback.onResult(null, e);
                }
                else if (responseBuffers.getReplyHeader().isCursorNotFound()) {
                    callback.onResult(null, new MongoCursorNotFoundException(
                            new ServerCursor(cursorId, getConnection().channel.getAddress())));
                }
                else {
                    callback.onResult(new QueryResult<T>(new MongoReplyMessage<T>(responseBuffers, decoder), serverAddress), null);
                }
            } finally {
                if (responseBuffers != null) {
                    responseBuffers.close();
                }
            }
        }
    }

    private abstract static class MongoCommandResultBaseCallback extends MongoResponseCallback {
        private final MongoCommand commandOperation;
        private final Decoder<Document> decoder;

        public MongoCommandResultBaseCallback(final MongoCommand commandOperation,
                                              final SingleChannelAsyncMongoConnector client, final Decoder<Document> decoder) {
            super(client);
            this.commandOperation = commandOperation;
            this.decoder = decoder;
        }

        protected void callCallback(final ServerAddress serverAddress, final ResponseBuffers responseBuffers,
                                    final MongoException e) {
            try {
            if (responseBuffers != null) {
                MongoReplyMessage<Document> replyMessage = new MongoReplyMessage<Document>(responseBuffers, decoder);
                callCallback(new CommandResult(commandOperation.toDocument(), serverAddress,
                        replyMessage.getDocuments().get(0), replyMessage.getElapsedNanoseconds()), e);
            }
            else {
                callCallback(null, e);
            }
            } finally {
                if (responseBuffers != null) {
                    responseBuffers.close();
                }
            }
        }

        protected abstract void callCallback(final CommandResult commandResult, final MongoException e);
    }

    private static class MongoCommandResultCallback extends MongoCommandResultBaseCallback {
        private final SingleResultCallback<CommandResult> callback;

        public MongoCommandResultCallback(final SingleResultCallback<CommandResult> callback,
                                          final MongoCommand commandOperation, final SingleChannelAsyncMongoConnector client,
                                          final Decoder<Document> decoder) {
            super(commandOperation, client, decoder);
            this.callback = callback;
        }

        @Override
        protected void callCallback(final CommandResult commandResult, final MongoException e) {
            if (e != null) {
                callback.onResult(null, e);
            }
            else if (!commandResult.isOk()) {
                callback.onResult(null, new MongoCommandFailureException(commandResult));
            }
            else {
                callback.onResult(commandResult, null);
            }
        }
    }


    private static class MongoWriteResultCallback extends MongoCommandResultBaseCallback {
        private final SingleResultCallback<WriteResult> callback;
        private final MongoWrite writeOperation;
        private final GetLastError getLastError;

        public MongoWriteResultCallback(final SingleResultCallback<WriteResult> callback,
                                        final MongoWrite writeOperation, final GetLastError getLastError,
                                        final SingleChannelAsyncMongoConnector client,
                                        final Decoder<Document> decoder) {
            super(getLastError, client, decoder);
            this.callback = callback;
            this.writeOperation = writeOperation;
            this.getLastError = getLastError;
        }

        @Override
        protected void callCallback(final CommandResult commandResult, final MongoException e) {
            if (e != null) {
                callback.onResult(null, e);
            }
            else if (getLastError != null && !commandResult.isOk()) {
                callback.onResult(null, new MongoCommandFailureException(commandResult));
            }
            else {
                MongoCommandFailureException commandException = null;
                if (getLastError != null) {
                    commandException = getLastError.getCommandException(commandResult);
                }

                if (commandException != null) {
                    callback.onResult(null, commandException);
                }
                else {
                    callback.onResult(new WriteResult(writeOperation, commandResult), null);
                }
            }
        }
    }
}