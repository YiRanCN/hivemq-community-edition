/*
 * Copyright 2019-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hivemq.persistence.clientsession;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.hivemq.bootstrap.ClientConnection;
import com.hivemq.bootstrap.ioc.lazysingleton.LazySingleton;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.extensions.iteration.ChunkCursor;
import com.hivemq.extensions.iteration.Chunker;
import com.hivemq.extensions.iteration.MultipleChunkResult;
import com.hivemq.logging.EventLog;
import com.hivemq.mqtt.handler.disconnect.MqttServerDisconnector;
import com.hivemq.mqtt.message.connect.MqttWillPublish;
import com.hivemq.mqtt.message.reason.Mqtt5DisconnectReasonCode;
import com.hivemq.persistence.AbstractPersistence;
import com.hivemq.persistence.ConnectionPersistence;
import com.hivemq.persistence.ProducerQueues;
import com.hivemq.persistence.SingleWriterService;
import com.hivemq.persistence.clientqueue.ClientQueuePersistence;
import com.hivemq.persistence.clientsession.task.ClientSessionCleanUpTask;
import com.hivemq.persistence.local.ClientSessionLocalPersistence;
import com.hivemq.persistence.payload.PublishPayloadPersistenceImpl;
import com.hivemq.persistence.util.FutureUtils;
import com.hivemq.util.ClientSessions;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.hivemq.mqtt.message.connect.Mqtt5CONNECT.SESSION_EXPIRE_ON_DISCONNECT;
import static com.hivemq.mqtt.message.disconnect.DISCONNECT.SESSION_EXPIRY_NOT_SET;

/**
 * @author Lukas Brandl
 */
@LazySingleton
public class ClientSessionPersistenceImpl extends AbstractPersistence implements ClientSessionPersistence {

    private static final Logger log = LoggerFactory.getLogger(ClientSessionPersistenceImpl.class);

    private final @NotNull ClientSessionLocalPersistence localPersistence;
    private final @NotNull ClientSessionSubscriptionPersistence subscriptionPersistence;
    private final @NotNull ClientQueuePersistence clientQueuePersistence;
    private final @NotNull ProducerQueues singleWriter;
    private final @NotNull ConnectionPersistence connectionPersistence;
    private final @NotNull EventLog eventLog;
    private final @NotNull PendingWillMessages pendingWillMessages;
    private final @NotNull MqttServerDisconnector mqttServerDisconnector;
    private final @NotNull Chunker chunker;

    @Inject
    public ClientSessionPersistenceImpl(
            final @NotNull ClientSessionLocalPersistence localPersistence,
            final @NotNull ClientSessionSubscriptionPersistence sessionSubscriptionPersistence,
            final @NotNull ClientQueuePersistence clientQueuePersistence,
            final @NotNull SingleWriterService singleWriterService,
            final @NotNull ConnectionPersistence connectionPersistence,
            final @NotNull EventLog eventLog,
            final @NotNull PendingWillMessages pendingWillMessages,
            final @NotNull MqttServerDisconnector mqttServerDisconnector,
            final @NotNull Chunker chunker) {

        this.localPersistence = localPersistence;
        this.clientQueuePersistence = clientQueuePersistence;
        this.connectionPersistence = connectionPersistence;
        this.eventLog = eventLog;
        this.pendingWillMessages = pendingWillMessages;
        this.mqttServerDisconnector = mqttServerDisconnector;
        this.chunker = chunker;
        subscriptionPersistence = sessionSubscriptionPersistence;
        singleWriter = singleWriterService.getClientSessionQueue();
    }

    @Override
    public boolean isExistent(final @NotNull String client) {
        checkNotNull(client, "Client id must not be null");

        return isExistent(getSession(client, false));
    }

    @Override
    public @NotNull Map<String, Boolean> isExistent(final @NotNull Set<String> clients) {
        final ImmutableMap.Builder<String, Boolean> builder = ImmutableMap.builder();

        for (final String client : clients) {
            builder.put(client, isExistent(client));
        }

        return builder.build();
    }

    @Override
    public @NotNull ListenableFuture<Void> clientDisconnected(
            final @NotNull String client, final boolean sendWill, final long sessionExpiry) {

        checkNotNull(client, "Client id must not be null");

        final long timestamp = System.currentTimeMillis();
        final SettableFuture<Void> resultFuture = SettableFuture.create();
        singleWriter.submit(client, (SingleWriterService.Task<Void>) (bucketIndex) -> {
            final ClientSession disconnectSession = localPersistence.disconnect(client, timestamp, sendWill, bucketIndex, sessionExpiry);
            if (sendWill) {
                pendingWillMessages.addWill(client, disconnectSession);
            }

            final ListenableFuture<Void> removeQos0Future = clientQueuePersistence.removeAllQos0Messages(client, false);
            if (disconnectSession.getSessionExpiryInterval() == SESSION_EXPIRE_ON_DISCONNECT) {
                final ListenableFuture<Void> removeSubFuture = subscriptionPersistence.removeAll(client);
                resultFuture.setFuture(FutureUtils.mergeVoidFutures(removeQos0Future, removeSubFuture));
                return null;
            }
            resultFuture.setFuture(removeQos0Future);
            return null;
        });
        return resultFuture;
    }

    @Override
    public @NotNull ListenableFuture<Void> clientConnected(
            final @NotNull String client,
            final boolean cleanStart,
            final long clientSessionExpiryInterval,
            final @Nullable MqttWillPublish willPublish,
            final @Nullable Long queueLimit) {

        checkNotNull(client, "Client id must not be null");

        pendingWillMessages.cancelWill(client);
        final long timestamp = System.currentTimeMillis();

        ClientSessionWill sessionWill = null;
        if (willPublish != null) {
            final long publishId = PublishPayloadPersistenceImpl.createId();
            sessionWill = new ClientSessionWill(willPublish, publishId);
        }
        final ClientSession clientSession = new ClientSession(true, clientSessionExpiryInterval, sessionWill, queueLimit);

        final ListenableFuture<ConnectResult> submitFuture =
                singleWriter.submit(client, (bucketIndex) -> {
                    final Long previousTimestamp = localPersistence.getTimestamp(client, bucketIndex);
                    final ClientSession previousClientSession = localPersistence.getSession(client, bucketIndex, false);
                    localPersistence.put(client, clientSession, timestamp, bucketIndex);
                    return new ConnectResult(previousTimestamp, previousClientSession);
                });

        final SettableFuture<Void> resultFuture = SettableFuture.create();
        FutureUtils.addPersistenceCallback(submitFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(final ConnectResult connectResult) {
                final Long previousTimestamp = connectResult.getPreviousTimestamp();
                final ClientSession previousClientSession = connectResult.getPreviousClientSession();

                final ListenableFuture<Void> cleanupFuture;

                // CleanUp the client session if the session is expired OR the client is clean start client.
                if (cleanStart) {
                    cleanupFuture = cleanClientData(client);
                } else {
                    final boolean expired = previousTimestamp != null &&
                            ClientSessions.isExpired(previousClientSession, System.currentTimeMillis() - previousTimestamp);
                    if (expired) {
                        // timestamp in milliseconds + session expiry in seconds * 1000 = milliseconds
                        eventLog.clientSessionExpired(previousTimestamp + previousClientSession.getSessionExpiryInterval() * 1000, client);
                        cleanupFuture = cleanClientData(client);
                    } else {
                        cleanupFuture = Futures.immediateFuture(null);
                    }
                }
                resultFuture.setFuture(cleanupFuture);
            }

            @Override
            public void onFailure(final @NotNull Throwable t) {
                resultFuture.setException(t);
            }
        });
        return resultFuture;
    }

    @Override
    public @NotNull ListenableFuture<Boolean> forceDisconnectClient(
            final @NotNull String clientId,
            final boolean preventLwtMessage,
            final @NotNull DisconnectSource source,
            final @Nullable Mqtt5DisconnectReasonCode reasonCode,
            final @Nullable String reasonString) {

        checkNotNull(clientId, "Parameter clientId cannot be null");
        checkNotNull(source, "Disconnect source cannot be null");

        final ClientSession session = getSession(clientId, false);
        if (session == null) {
            log.trace("Ignoring forced client disconnect request for client '{}', because client is not connected.", clientId);
            return Futures.immediateFuture(false);
        }
        if (preventLwtMessage) {
            pendingWillMessages.cancelWill(clientId);
        }

        log.debug("Request forced client disconnect for client {}.", clientId);
        final ClientConnection clientConnection = connectionPersistence.get(clientId);

        if (clientConnection == null) {
            log.trace("Ignoring forced client disconnect request for client '{}', because client is not connected.", clientId);
            return Futures.immediateFuture(false);
        }
        clientConnection.setPreventLwt(preventLwtMessage);
        if (session.getSessionExpiryInterval() != SESSION_EXPIRY_NOT_SET) {
            clientConnection.setClientSessionExpiryInterval(session.getSessionExpiryInterval());
        }

        final String logMessage = String.format("Disconnecting client with clientId '%s' forcibly via extension system.", clientId);
        final String eventLogMessage = "Disconnected via extension system";

        final Mqtt5DisconnectReasonCode usedReasonCode =
                reasonCode == null ? Mqtt5DisconnectReasonCode.ADMINISTRATIVE_ACTION : Mqtt5DisconnectReasonCode.valueOf(reasonCode.name());

        mqttServerDisconnector.disconnect(clientConnection.getChannel(), logMessage, eventLogMessage, usedReasonCode, reasonString);

        final SettableFuture<Boolean> resultFuture = SettableFuture.create();
        clientConnection.getChannel().closeFuture().addListener((ChannelFutureListener) future -> {
            resultFuture.set(true);
        });
        return resultFuture;
    }

    @Override
    public @NotNull ListenableFuture<Boolean> forceDisconnectClient(
            final @NotNull String clientId, final boolean preventLwtMessage, final @NotNull DisconnectSource source) {
        return forceDisconnectClient(clientId, preventLwtMessage, source, null, null);
    }

    @Override
    public @NotNull ListenableFuture<Void> cleanClientData(final @NotNull String clientId) {

        final ImmutableList.Builder<ListenableFuture<Void>> builder = ImmutableList.builder();
        builder.add(subscriptionPersistence.removeAll(clientId));
        builder.add(clientQueuePersistence.clear(clientId, false));

        return FutureUtils.voidFutureFromList(builder.build());
    }

    @Override
    public @NotNull ListenableFuture<Set<String>> getAllClients() {
        final List<ListenableFuture<Set<String>>> futures = singleWriter.submitToAllBucketsParallel((bucketIndex) -> {
            final Set<String> clientSessions = new HashSet<>();
            clientSessions.addAll(localPersistence.getAllClients(bucketIndex));
            return clientSessions;
        });
        return FutureUtils.combineSetResults(Futures.allAsList(futures));
    }

    @Override
    public @Nullable ClientSession getSession(final @NotNull String clientId, final boolean includeWill) {
        checkNotNull(clientId, "Client id must not be null");

        return localPersistence.getSession(clientId, true, includeWill);
    }

    @Override
    public @NotNull ListenableFuture<Boolean> setSessionExpiryInterval(
            final @NotNull String clientId, final long sessionExpiryInterval) {

        checkNotNull(clientId, "Client id must not be null");

        final ListenableFuture<Boolean> setTTlFuture =
                singleWriter.submit(clientId, (bucketIndex) -> {

                    final boolean clientSessionExists = localPersistence.getSession(clientId) != null;

                    if (!clientSessionExists) {
                        return false;
                    }

                    localPersistence.setSessionExpiryInterval(clientId, sessionExpiryInterval, bucketIndex);
                    return true;
                });

        final SettableFuture<Boolean> settableFuture = SettableFuture.create();

        FutureUtils.addPersistenceCallback(setTTlFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(final @Nullable Boolean sessionExists) {
                if (sessionExpiryInterval == SESSION_EXPIRE_ON_DISCONNECT) {

                    final ListenableFuture<Void> removeAllFuture = subscriptionPersistence.removeAll(clientId);

                    FutureUtils.addPersistenceCallback(removeAllFuture, new FutureCallback<>() {
                        @Override
                        public void onSuccess(final @Nullable Void result) {
                            settableFuture.set(sessionExists);
                        }

                        @Override
                        public void onFailure(final @NotNull Throwable t) {
                            settableFuture.setException(t);
                        }
                    });
                } else {
                    settableFuture.set(sessionExists);
                }
            }

            @Override
            public void onFailure(final @NotNull Throwable t) {
                settableFuture.setException(t);
            }
        });

        return settableFuture;
    }

    @Override
    public @Nullable Long getSessionExpiryInterval(final @NotNull String clientId) {

        final ClientSession session = getSession(clientId, false);
        if (session == null) {
            return null;
        }
        return session.getSessionExpiryInterval();
    }

    @Override
    public @NotNull ListenableFuture<Void> cleanUp(final int bucketIndex) {
        return singleWriter.submit(bucketIndex, new ClientSessionCleanUpTask(localPersistence, this));
    }

    @Override
    public @NotNull ListenableFuture<Void> closeDB() {
        return closeDB(localPersistence, singleWriter);
    }

    @Override
    public @NotNull ListenableFuture<Boolean> invalidateSession(
            final @NotNull String clientId, final @NotNull DisconnectSource disconnectSource) {

        checkNotNull(clientId, "ClientId cannot be null");
        checkNotNull(disconnectSource, "Disconnect source cannot be null");

        final ListenableFuture<Boolean> setTTLFuture = setSessionExpiryInterval(clientId, 0);
        final SettableFuture<Boolean> resultFuture = SettableFuture.create();

        FutureUtils.addPersistenceCallback(setTTLFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(final Boolean sessionExists) {

                if (sessionExists) {
                    final ListenableFuture<Boolean> disconnectClientFuture = forceDisconnectClient(clientId, false, disconnectSource);
                    resultFuture.setFuture(disconnectClientFuture);
                } else {
                    resultFuture.set(null);
                }
            }

            @Override
            public void onFailure(final @NotNull Throwable throwable) {
                resultFuture.setException(throwable);
            }
        });

        return resultFuture;
    }

    @Override
    public @NotNull ListenableFuture<Map<String, PendingWillMessages.PendingWill>> pendingWills() {

        final List<ListenableFuture<Map<String, PendingWillMessages.PendingWill>>> futureList =
                singleWriter.submitToAllBucketsParallel(localPersistence::getPendingWills);

        final SettableFuture<Map<String, PendingWillMessages.PendingWill>> settableFuture = SettableFuture.create();
        FutureUtils.addPersistenceCallback(Futures.allAsList(futureList), new FutureCallback<>() {
            @Override
            public void onSuccess(final @Nullable List<Map<String, PendingWillMessages.PendingWill>> result) {

                if (result == null) {
                    settableFuture.set(ImmutableMap.of());
                    return;
                }

                final ImmutableMap.Builder<String, PendingWillMessages.PendingWill> resultMap = ImmutableMap.builder();
                for (final Map<String, PendingWillMessages.PendingWill> map : result) {
                    resultMap.putAll(map);
                }
                settableFuture.set(resultMap.build());
            }

            @Override
            public void onFailure(final Throwable t) {
                settableFuture.setException(t);
            }
        });
        return settableFuture;
    }

    @Override
    public @NotNull ListenableFuture<Void> deleteWill(final @NotNull String clientId) {
        checkNotNull(clientId, "Client id must not be null");
        return singleWriter.submit(clientId, (bucketIndex) -> {
            localPersistence.deleteWill(clientId, bucketIndex);
            return null;
        });
    }

    @Override
    public @NotNull ListenableFuture<MultipleChunkResult<Map<String, ClientSession>>> getAllLocalClientsChunk(
            final @NotNull ChunkCursor cursor) {

        return chunker.getAllLocalChunk(cursor, InternalConfigurations.PERSISTENCE_CLIENT_SESSIONS_MAX_CHUNK_SIZE,
                // Chunker.SingleWriterCall interface
                (bucket, lastKey, maxResults) -> singleWriter.submit(bucket,
                        // actual single writer call
                        (bucketIndex) ->
                                localPersistence.getAllClientsChunk(
                                        bucketIndex,
                                        lastKey,
                                        maxResults)));
    }

    private static boolean isExistent(final @Nullable ClientSession clientSession) {
        return (clientSession != null) && (clientSession.getSessionExpiryInterval() > 0 || clientSession.isConnected());
    }

    public enum DisconnectSource {

        /**
         * Extension system disconnected the client
         */
        EXTENSION(0);

        final int number;

        DisconnectSource(final int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }

        public static @NotNull DisconnectSource ofNumber(final int number) {

            for (final DisconnectSource value : values()) {
                if (value.number == number) {
                    return value;
                }
            }

            throw new IllegalArgumentException("No disconnect source found for number: " + number);
        }
    }
}
