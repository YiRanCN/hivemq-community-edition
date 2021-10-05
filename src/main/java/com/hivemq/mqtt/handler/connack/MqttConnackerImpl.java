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
package com.hivemq.mqtt.handler.connack;

import com.google.common.base.Preconditions;
import com.hivemq.bootstrap.ClientConnection;
import com.hivemq.bootstrap.ClientState;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.extension.sdk.api.packets.general.DisconnectedReasonCode;
import com.hivemq.extensions.events.OnAuthFailedEvent;
import com.hivemq.extensions.events.OnServerDisconnectEvent;
import com.hivemq.logging.EventLog;
import com.hivemq.mqtt.message.ProtocolVersion;
import com.hivemq.mqtt.message.connack.CONNACK;
import com.hivemq.mqtt.message.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.mqtt.message.mqtt5.Mqtt5UserProperties;
import com.hivemq.mqtt.message.reason.Mqtt5ConnAckReasonCode;
import com.hivemq.util.Bytes;
import com.hivemq.util.ChannelAttributes;
import com.hivemq.util.ThreadPreConditions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.ByteBuffer;

import static com.hivemq.util.ChannelUtils.getChannelIP;

/**
 * @author Florian Limpöck
 */
@Singleton
public class MqttConnackerImpl implements MqttConnacker {

    private static final @NotNull Logger log = LoggerFactory.getLogger(MqttConnackerImpl.class);

    private final boolean connackWithReasonCode;
    private final boolean connackWithReasonString;

    private final @NotNull EventLog eventLog;

    @Inject
    public MqttConnackerImpl(final @NotNull EventLog eventLog) {
        this.connackWithReasonCode = InternalConfigurations.CONNACK_WITH_REASON_CODE.get();
        this.connackWithReasonString = InternalConfigurations.CONNACK_WITH_REASON_STRING.get();
        this.eventLog = eventLog;
    }

    @Override
    public @NotNull ChannelFuture connackSuccess(final @NotNull ChannelHandlerContext ctx,
                                                 final @NotNull CONNACK connack) {

        Preconditions.checkNotNull(ctx, "ChannelHandlerContext must never be null");
        Preconditions.checkNotNull(connack, "CONNACK must never be null");
        Preconditions.checkArgument(connack.getReasonCode() == Mqtt5ConnAckReasonCode.SUCCESS, "Error is no success");
        ThreadPreConditions.inNettyChildEventloop();

        final ChannelFuture channelFuture = ctx.writeAndFlush(connack);

        //for preventing success, when a connack will be prevented by an extension
        channelFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                ctx.channel().attr(ChannelAttributes.CLIENT_CONNECTION).get().proposeClientState(ClientState.AUTHENTICATED);
                eventLog.clientConnected(future.channel());
            }
        });

        return channelFuture;
    }

    public void connackError(
            final @NotNull Channel channel,
            final @Nullable String logMessage,
            final @Nullable String eventLogMessage,
            final @Nullable Mqtt5ConnAckReasonCode reasonCode,
            final @Nullable String reasonString) {

        connackError(channel, logMessage, eventLogMessage, reasonCode, reasonString, Mqtt5UserProperties.NO_USER_PROPERTIES, false);
    }

    public void connackError(
            final @NotNull Channel channel,
            final @Nullable String logMessage,
            final @Nullable String eventLogMessage,
            final @Nullable Mqtt5ConnAckReasonCode reasonCode,
            final @Nullable String reasonString,
            final @NotNull Mqtt5UserProperties userProperties,
            final boolean isAuthentication) {

        Preconditions.checkNotNull(channel, "Channel must never be null");
        Preconditions.checkArgument(reasonCode != Mqtt5ConnAckReasonCode.SUCCESS, "Success is no error");
        ThreadPreConditions.inNettyChildEventloop();

        final ClientConnection clientConnection = channel.attr(ChannelAttributes.CLIENT_CONNECTION).get();

        // Avoid calling disconnect multiple times.
        if (clientConnection.getClientState().disconnected()) {
            return;
        }

        final ProtocolVersion protocolVersion = clientConnection.getProtocolVersion();

        logConnack(channel, logMessage, eventLogMessage);
        if (protocolVersion == null) {
            clientConnection.proposeClientState(ClientState.DISCONNECTED_UNGRACEFULLY);
            channel.close();
        } else if (ProtocolVersion.MQTTv3_1 == protocolVersion || ProtocolVersion.MQTTv3_1_1 == protocolVersion) {
            connackError3(clientConnection, connackWithReasonCode, reasonCode, reasonString, isAuthentication);
        } else {
            connackError5(clientConnection, connackWithReasonCode, connackWithReasonString, reasonCode, reasonString, userProperties, isAuthentication);
        }
    }

    private void logConnack(
            final @NotNull Channel channel,
            final @Nullable String logMessage,
            final @Nullable String eventLogMessage) {

        if (log.isDebugEnabled() && logMessage != null && !logMessage.isEmpty()) {
            log.debug(logMessage, getChannelIP(channel).or("UNKNOWN"));
        }

        if (eventLogMessage != null && !eventLogMessage.isEmpty()) {
            eventLog.clientWasDisconnected(channel, eventLogMessage);
        }
    }

    private void connackError3(
            final @NotNull ClientConnection clientConnection,
            final boolean withReasonCode,
            final @Nullable Mqtt5ConnAckReasonCode reasonCode,
            final @Nullable String reasonString,
            final boolean isAuthentication) {

        fireEvents(clientConnection, reasonCode, reasonString, Mqtt5UserProperties.NO_USER_PROPERTIES, isAuthentication);
        final Mqtt3ConnAckReturnCode returnCode = transformReasonCode(reasonCode);

        if (returnCode != null && withReasonCode) {
            clientConnection.proposeClientState(ClientState.DISCONNECTED_GRACEFULLY);
            clientConnection.getChannel().writeAndFlush(new CONNACK(returnCode)).addListener(ChannelFutureListener.CLOSE);
        } else {
            clientConnection.proposeClientState(ClientState.DISCONNECTED_UNGRACEFULLY);
            //Do not send connack to not let the client know its an mqtt server
            clientConnection.getChannel().close();
        }
    }

    private void connackError5(
            final @NotNull ClientConnection clientConnection,
            final boolean withReasonCode,
            final boolean withReasonString,
            @Nullable Mqtt5ConnAckReasonCode reasonCode,
            @Nullable String reasonString,
            final @NotNull Mqtt5UserProperties userProperties,
            final boolean isAuthentication) {

        fireEvents(clientConnection, reasonCode, reasonString, userProperties, isAuthentication);

        if (!withReasonCode) {
            reasonCode = null;
            reasonString = null;
        } else {
            Preconditions.checkNotNull(reasonCode, "Reason code must never be null for Mqtt 5");
            if (!withReasonString) {
                reasonString = null;
            }
        }

        if (reasonCode != null) {
            final CONNACK.Mqtt5Builder connackBuilder = new CONNACK.Mqtt5Builder()
                    .withReasonCode(reasonCode)
                    .withReasonString(reasonString)
                    .withUserProperties(userProperties);

            // set auth method if present
            final String authMethod = clientConnection.getAuthMethod();
            if (authMethod != null) {
                connackBuilder.withAuthMethod(authMethod);

                // set auth data
                final ByteBuffer authData = clientConnection.getAuthData();
                if (authData != null) {
                    clientConnection.setAuthData(null);
                    connackBuilder.withAuthData(Bytes.fromReadOnlyBuffer(authData));
                }
            }

            clientConnection.proposeClientState(ClientState.DISCONNECTED_GRACEFULLY);
            clientConnection.getChannel().writeAndFlush(connackBuilder.build()).addListener(ChannelFutureListener.CLOSE);
        } else {
            clientConnection.proposeClientState(ClientState.DISCONNECTED_UNGRACEFULLY);
            //Do not send connack to not let the client know its an mqtt server
            clientConnection.getChannel().close();
        }
    }

    private void fireEvents(
            final @NotNull ClientConnection clientConnection,
            final @Nullable Mqtt5ConnAckReasonCode reasonCode,
            final @Nullable String reasonString,
            final @NotNull Mqtt5UserProperties userProperties,
            final boolean isAuthentication) {

        if (clientConnection.getClientState() != ClientState.CONNECTING) {

            final DisconnectedReasonCode disconnectedReasonCode =
                    (reasonCode == null) ? null : reasonCode.toDisconnectedReasonCode();
            clientConnection.getChannel().pipeline().fireUserEventTriggered(isAuthentication ?
                    new OnAuthFailedEvent(disconnectedReasonCode, reasonString, userProperties) :
                    new OnServerDisconnectEvent(disconnectedReasonCode, reasonString, userProperties));
        }
    }

    @Nullable
    private Mqtt3ConnAckReturnCode transformReasonCode(final @Nullable Mqtt5ConnAckReasonCode reasonCode) {
        if (reasonCode == null) {
            return null;
        }
        switch (reasonCode) {
            case UNSPECIFIED_ERROR:
            case MALFORMED_PACKET:
            case PROTOCOL_ERROR:
            case IMPLEMENTATION_SPECIFIC_ERROR:
                //no reason code for mqtt 3 available for these cases
                return null;
            default:
                return Mqtt3ConnAckReturnCode.fromReasonCode(reasonCode);
        }
    }
}
