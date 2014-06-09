/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.mediadriver;

import uk.co.real_logic.aeron.util.command.QualifiedMessageFlyweight;
import uk.co.real_logic.aeron.util.command.SubscriberMessageFlyweight;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBuffer;

import java.nio.ByteBuffer;

import static uk.co.real_logic.aeron.util.command.ControlProtocolEvents.*;

/**
 * Proxy for writing into the Receiver Thread's command buffer.
 */
public class ReceiverProxy
{
    private static final int WRITE_BUFFER_CAPACITY = 256;

    private final RingBuffer commandBuffer;
    private final NioSelector selector;
    private final AtomicBuffer writeBuffer = new AtomicBuffer(ByteBuffer.allocate(WRITE_BUFFER_CAPACITY));
    private final SubscriberMessageFlyweight receiverMessage = new SubscriberMessageFlyweight();
    private final QualifiedMessageFlyweight addTermBufferMessage = new QualifiedMessageFlyweight();

    public ReceiverProxy(final RingBuffer commandBuffer, final NioSelector selector)
    {
        this.commandBuffer = commandBuffer;
        this.selector = selector;

        receiverMessage.wrap(writeBuffer, 0);
        addTermBufferMessage.wrap(writeBuffer, 0);  // TODO: is this safe on the same buffer???
    }

    public void addNewSubscriberEvent(final String destination, final long[] channelIdList)
    {
        addReceiverEvent(ADD_SUBSCRIBER, destination, channelIdList);
    }

    public void addRemoveSubscriberEvent(final String destination, final long[] channelIdList)
    {
        addReceiverEvent(REMOVE_SUBSCRIBER, destination, channelIdList);
    }

    private void addReceiverEvent(final int msgTypeId, final String destination, final long[] channelIdList)
    {
        receiverMessage.channelIds(channelIdList);
        receiverMessage.destination(destination);
        commandBuffer.write(msgTypeId, writeBuffer, 0, receiverMessage.length());
        selector.wakeup();
    }

    public void addTermBufferCreatedEvent(final String destination,
                                          final long sessionId,
                                          final long channelId,
                                          final long termId)
    {
        addTermBufferMessage.sessionId(sessionId);
        addTermBufferMessage.channelId(channelId);
        addTermBufferMessage.termId(termId);
        addTermBufferMessage.destination(destination);
        commandBuffer.write(NEW_RECEIVE_BUFFER_NOTIFICATION, writeBuffer, 0, addTermBufferMessage.length());
        selector.wakeup();
    }
}