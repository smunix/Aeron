/*
 * Copyright 2014 - 2015 Real Logic Ltd.
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
package uk.co.real_logic.aeron;

import uk.co.real_logic.aeron.common.CncFileDescriptor;
import uk.co.real_logic.aeron.common.CommonContext;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.DataHandler;
import uk.co.real_logic.aeron.exceptions.DriverTimeoutException;
import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.IoUtil;
import uk.co.real_logic.agrona.TimerWheel;
import uk.co.real_logic.agrona.concurrent.AgentRunner;
import uk.co.real_logic.agrona.concurrent.BackoffIdleStrategy;
import uk.co.real_logic.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.agrona.concurrent.Signal;
import uk.co.real_logic.agrona.concurrent.broadcast.BroadcastReceiver;
import uk.co.real_logic.agrona.concurrent.broadcast.CopyBroadcastReceiver;
import uk.co.real_logic.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.agrona.concurrent.ringbuffer.RingBuffer;

import java.nio.MappedByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static uk.co.real_logic.agrona.IoUtil.mapExistingFile;

/**
 * Aeron entry point for communicating to the Media Driver for creating {@link Publication}s and {@link Subscription}s.
 * Use an {@link Aeron.Context} to configure the Aeron object.
 * <p>
 * A client application requires only one Aeron object per Media Driver.
 */
public final class Aeron implements AutoCloseable
{
    /**
     * The Default handler for Aeron runtime exceptions.
     * When a {@link uk.co.real_logic.aeron.exceptions.DriverTimeoutException} is encountered, this handler will
     * exit the program.
     * <p>
     * The error handler can be overridden by supplying an {@link Aeron.Context} with a custom handler.
     *
     * @see Aeron.Context#errorHandler(Consumer)
     */
    public static final Consumer<Throwable> DEFAULT_ERROR_HANDLER =
        (throwable) ->
        {
            throwable.printStackTrace();
            if (throwable instanceof DriverTimeoutException)
            {
                System.err.printf("\n***\n*** Timeout from the Media Driver - is it currently running? Exiting.\n***\n");
                System.exit(-1);
            }
        };

    private static final long IDLE_MAX_SPINS = 0;
    private static final long IDLE_MAX_YIELDS = 0;
    private static final long IDLE_MIN_PARK_NS = TimeUnit.NANOSECONDS.toNanos(1);
    private static final long IDLE_MAX_PARK_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private static final int CONDUCTOR_TICKS_PER_WHEEL = 1024;
    private static final int CONDUCTOR_TICK_DURATION_US = 10_000;

    private static final long NULL_TIMEOUT = -1;
    private static final long DEFAULT_MEDIA_DRIVER_TIMEOUT_MS = 10_000;

    private final ClientConductor conductor;
    private final AgentRunner conductorRunner;
    private final Context ctx;

    Aeron(final Context ctx)
    {
        ctx.conclude();
        this.ctx = ctx;

        conductor = new ClientConductor(
            ctx.toClientBuffer,
            ctx.logBuffersFactory,
            ctx.countersBuffer(),
            new DriverProxy(ctx.toDriverBuffer),
            new Signal(),
            new TimerWheel(CONDUCTOR_TICK_DURATION_US, TimeUnit.MICROSECONDS, CONDUCTOR_TICKS_PER_WHEEL),
            ctx.errorHandler,
            ctx.newConnectionHandler,
            ctx.inactiveConnectionHandler,
            ctx.mediaDriverTimeout());

        conductorRunner = new AgentRunner(ctx.idleStrategy, ctx.errorHandler, null, conductor);
    }

    /**
     * Create an Aeron instance and connect to the media driver.
     * <p>
     * Threads required for interacting with the media driver are created and managed within the Aeron instance.
     *
     * @param ctx for configuration of the client.
     * @return the new {@link Aeron} instance connected to the Media Driver.
     */
    public static Aeron connect(final Context ctx)
    {
        return new Aeron(ctx).start();
    }

    /**
     * Create an Aeron instance and connect to the media driver.
     * <p>
     * Threads for interacting with the media driver are run via the the provided {@link Executor}.
     *
     * @param ctx      for configuration of the client.
     * @param executor to run the internal threads for communicating with the media driver.
     * @return the new {@link Aeron} instance connected to the Media Driver.
     */
    public static Aeron connect(final Context ctx, final Executor executor)
    {
        return new Aeron(ctx).start(executor);
    }

    /**
     * Clean up and release all Aeron internal resources and shutdown threads.
     */
    public void close()
    {
        conductorRunner.close();
        ctx.close();
    }

    /**
     * Add a {@link Publication} for publishing messages to subscribers.
     * <p>
     * A session id will be generated for this publication.
     *
     * @param channel  for receiving the messages known to the media layer.
     * @param streamId within the channel scope.
     * @return the new Publication.
     */
    public Publication addPublication(final String channel, final int streamId)
    {
        return addPublication(channel, streamId, 0);
    }

    /**
     * Add a {@link Publication} for publishing messages to subscribers.
     * <p>
     * If the sessionId is 0, then a random one will be generated.
     *
     * @param channel   for receiving the messages known to the media layer.
     * @param streamId  within the channel scope.
     * @param sessionId To identify the Publication instance within a channel and streamId pair.
     * @return the new Publication.
     */
    public Publication addPublication(final String channel, final int streamId, final int sessionId)
    {
        int sessionIdToRequest = sessionId;

        if (0 == sessionId)
        {
            sessionIdToRequest = BitUtil.generateRandomisedId();
        }

        return conductor.addPublication(channel, streamId, sessionIdToRequest);
    }

    /**
     * Add a new {@link Subscription} for subscribing to messages from publishers.
     *
     * @param channel  for receiving the messages known to the media layer.
     * @param streamId within the channel scope.
     * @param handler  to be called back for each message received.
     * @return the {@link Subscription} for the channel and streamId pair.
     */
    public Subscription addSubscription(final String channel, final int streamId, final DataHandler handler)
    {
        return conductor.addSubscription(channel, streamId, handler);
    }

    private Aeron start()
    {
        final Thread thread = new Thread(conductorRunner);
        thread.setName("aeron-client-conductor");
        thread.start();

        return this;
    }

    private Aeron start(final Executor executor)
    {
        executor.execute(conductorRunner);

        return this;
    }

    /**
     * This class provides configuration for the {@link Aeron} class via the {@link Aeron#connect(Aeron.Context)}
     * method and its overloads. It gives applications some control over the interactions with the Aeron Media Driver.
     * It can also set up error handling as well as application callbacks for connection information from the
     * Media Driver.
     */
    public static class Context extends CommonContext
    {
        private IdleStrategy idleStrategy;
        private CopyBroadcastReceiver toClientBuffer;
        private RingBuffer toDriverBuffer;
        private MappedByteBuffer cncByteBuffer;
        private DirectBuffer cncMetaDataBuffer;

        private LogBuffersFactory logBuffersFactory;

        private Consumer<Throwable> errorHandler;
        private NewConnectionHandler newConnectionHandler;
        private InactiveConnectionHandler inactiveConnectionHandler;
        private long mediaDriverTimeoutMs = NULL_TIMEOUT;

        /**
         * This is called automatically by {@link Aeron#connect(Aeron.Context)} and its overloads.
         * There is no need to call it from a client application. It is responsible for providing default
         * values for options that are not individually changed through field setters.
         * @return this Aeron.Context for method chaining.
         */
        public Context conclude()
        {
            super.conclude();

            try
            {
                if (mediaDriverTimeoutMs == NULL_TIMEOUT)
                {
                    mediaDriverTimeoutMs = DEFAULT_MEDIA_DRIVER_TIMEOUT_MS;
                }

                if (null == idleStrategy)
                {
                    idleStrategy = new BackoffIdleStrategy(IDLE_MAX_SPINS, IDLE_MAX_YIELDS, IDLE_MIN_PARK_NS, IDLE_MAX_PARK_NS);
                }

                if (cncFile() != null)
                {
                    cncByteBuffer = mapExistingFile(cncFile(), CncFileDescriptor.CNC_FILE);
                    cncMetaDataBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer);

                    final int cncVersion = cncMetaDataBuffer.getInt(CncFileDescriptor.cncVersionOffset(0));

                    if (CncFileDescriptor.CNC_VERSION != cncVersion)
                    {
                        throw new IllegalStateException("aeron cnc file version not understood: version=" + cncVersion);
                    }
                }

                if (null == toClientBuffer)
                {
                    final BroadcastReceiver receiver = new BroadcastReceiver(
                        CncFileDescriptor.createToClientsBuffer(cncByteBuffer, cncMetaDataBuffer));
                    toClientBuffer = new CopyBroadcastReceiver(receiver);
                }

                if (null == toDriverBuffer)
                {
                    toDriverBuffer = new ManyToOneRingBuffer(
                        CncFileDescriptor.createToDriverBuffer(cncByteBuffer, cncMetaDataBuffer));
                }

                if (counterLabelsBuffer() == null)
                {
                    counterLabelsBuffer(CncFileDescriptor.createCounterLabelsBuffer(cncByteBuffer, cncMetaDataBuffer));
                }

                if (countersBuffer() == null)
                {
                    countersBuffer(CncFileDescriptor.createCounterValuesBuffer(cncByteBuffer, cncMetaDataBuffer));
                }

                if (null == logBuffersFactory)
                {
                    logBuffersFactory = new MappedLogBuffersFactory();
                }

                if (null == errorHandler)
                {
                    errorHandler = DEFAULT_ERROR_HANDLER;
                }
            }
            catch (final Exception ex)
            {
                System.err.printf("\n***\n*** Failed to connect to the Media Driver - is it currently running?\n***\n");

                throw new IllegalStateException("Could not initialise communication buffers", ex);
            }

            return this;
        }

        /**
         * Provides an IdleStrategy for the thread responsible for communicating with the Aeron Media Driver.
         * @param idleStrategy Thread idle strategy for communication with the Media Driver.
         * @return this Aeron.Context for method chaining.
         */
        public Context idleStrategy(final IdleStrategy idleStrategy)
        {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * This method is used for testing and debugging.
         * @param toClientBuffer Injected CopyBroadcastReceiver
         * @return this Aeron.Context for method chaining.
         */
        public Context toClientBuffer(final CopyBroadcastReceiver toClientBuffer)
        {
            this.toClientBuffer = toClientBuffer;
            return this;
        }

        /**
         * This method is used for testing and debugging.
         * @param toDriverBuffer Injected RingBuffer.
         * @return this Aeron.Context for method chaining.
         */
        public Context toDriverBuffer(final RingBuffer toDriverBuffer)
        {
            this.toDriverBuffer = toDriverBuffer;
            return this;
        }

        /**
         * This method is used for testing and debugging.
         * @param logBuffersFactory Injected LogBuffersFactory
         * @return this Aeron.Context for method chaining.
         */
        public Context bufferManager(final LogBuffersFactory logBuffersFactory)
        {
            this.logBuffersFactory = logBuffersFactory;
            return this;
        }

        /**
         * Handle Aeron exceptions in a callback method. The default behavior is defined by
         * {@link Aeron#DEFAULT_ERROR_HANDLER}.
         * @see uk.co.real_logic.aeron.exceptions.DriverTimeoutException
         * @see uk.co.real_logic.aeron.exceptions.RegistrationException
         * @param handler Method to handle objects of type Throwable.
         * @return this Aeron.Context for method chaining.
         */
        public Context errorHandler(final Consumer<Throwable> handler)
        {
            this.errorHandler = handler;
            return this;
        }

        /**
         * Set up a callback for when a new connection is created.
         * @param handler Callback method for handling new connection notifications.
         * @return this Aeron.Context for method chaining.
         */
        public Context newConnectionHandler(final NewConnectionHandler handler)
        {
            this.newConnectionHandler = handler;
            return this;
        }

        /**
         * Set up a callback for when a connection determined to be inactive.
         * @param handler Callback method for handling inactive connection notifications.
         * @return this Aeron.Context for method chaining.
         */
        public Context inactiveConnectionHandler(final InactiveConnectionHandler handler)
        {
            this.inactiveConnectionHandler = handler;
            return this;
        }

        /**
         * Set the amount of time, in milliseconds, that this client will wait until it determines the
         * Media Driver is unavailable. When this happens a
         * {@link uk.co.real_logic.aeron.exceptions.DriverTimeoutException} will be generated for the error handler.
         * @see #errorHandler(Consumer)
         * @param value Number of milliseconds.
         * @return this Aeron.Context for method chaining.
         */
        public Context mediaDriverTimeout(final long value)
        {
            this.mediaDriverTimeoutMs = value;
            return this;
        }

        /**
         * Get the amount of time, in milliseconds, that this client will wait until it determines the
         * Media Driver is unavailable.
         * @return Number of milliseconds.
         */
        public long mediaDriverTimeout()
        {
            return mediaDriverTimeoutMs;
        }

        /**
         * Clean up all resources that the client uses to communicate with the Media Driver.
         */
        public void close()
        {
            IoUtil.unmap(cncByteBuffer);

            super.close();
        }
    }
}
