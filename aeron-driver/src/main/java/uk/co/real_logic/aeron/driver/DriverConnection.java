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
package uk.co.real_logic.aeron.driver;

import uk.co.real_logic.aeron.common.TermHelper;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBuffer;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.LogRebuilder;
import uk.co.real_logic.aeron.common.event.EventCode;
import uk.co.real_logic.aeron.common.event.EventLogger;
import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.common.status.PositionIndicator;
import uk.co.real_logic.aeron.common.status.PositionReporter;
import uk.co.real_logic.aeron.driver.buffer.TermBuffers;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static uk.co.real_logic.aeron.common.TermHelper.*;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.IN_CLEANING;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.NEEDS_CLEANING;

/**
 * State maintained for active sessionIds within a channel for receiver processing
 */
public class DriverConnection implements AutoCloseable
{
    /** connection is active */
    public static final int ACTIVE = 1;

    /** connection is inactive. Publication side has timed out. */
    public static final int INACTIVE = 2;

    /** connection has been drained or timeout has occurred and is being lingered */
    public static final int LINGER = 3;

    private final ReceiveChannelEndpoint receiveChannelEndpoint;
    private final int sessionId;
    private final int streamId;
    private final TermBuffers termBuffers;
    private final PositionIndicator subscriberPosition;
    private final LongSupplier clock;
    private final PositionReporter contiguousReceivedPosition;
    private final PositionReporter highestReceivedPosition;
    private final SystemCounters systemCounters;
    private final EventLogger logger;

    private final AtomicInteger activeTermId = new AtomicInteger();
    private final AtomicLong timeOfLastFrame = new AtomicLong();
    private int activeIndex;
    private int hwmTermId;
    private int hwmIndex;
    private int status;
    private long timeOfLastStatusChange;

    private final LogRebuilder[] rebuilders;
    private final LossHandler lossHandler;
    private final StatusMessageSender statusMessageSender;

    private final int positionBitsToShift;
    private final int initialTermId;
    private final long statusMessageTimeout;

    private long lastSmPosition;
    private long lastSmTimestamp;
    private int lastSmTermId;
    private int currentWindowSize;
    private int currentGain;

    private volatile boolean statusMessagesEnabled = false;

    public DriverConnection(final ReceiveChannelEndpoint receiveChannelEndpoint,
                            final int sessionId,
                            final int streamId,
                            final int initialTermId,
                            final int initialWindowSize,
                            final long statusMessageTimeout,
                            final TermBuffers termBuffers,
                            final LossHandler lossHandler,
                            final StatusMessageSender statusMessageSender,
                            final PositionIndicator subscriberPosition,
                            final PositionReporter contiguousReceivedPosition,
                            final PositionReporter highestReceivedPosition,
                            final LongSupplier clock,
                            final SystemCounters systemCounters,
                            final EventLogger logger)
    {
        this.receiveChannelEndpoint = receiveChannelEndpoint;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.termBuffers = termBuffers;
        this.subscriberPosition = subscriberPosition;
        this.contiguousReceivedPosition = contiguousReceivedPosition;
        this.highestReceivedPosition = highestReceivedPosition;
        this.systemCounters = systemCounters;
        this.logger = logger;
        this.status = ACTIVE;
        this.timeOfLastStatusChange = clock.getAsLong();

        this.clock = clock;
        activeTermId.lazySet(initialTermId);
        timeOfLastFrame.lazySet(clock.getAsLong());
        this.hwmIndex = this.activeIndex = termIdToBufferIndex(initialTermId);
        this.hwmTermId = initialTermId;

        rebuilders = termBuffers.stream()
                                .map((rawLog) -> new LogRebuilder(rawLog.logBuffer(), rawLog.stateBuffer()))
                                .toArray(LogRebuilder[]::new);
        this.lossHandler = lossHandler;
        this.statusMessageSender = statusMessageSender;
        this.statusMessageTimeout = statusMessageTimeout;

        // attaching this term buffer will send an SM, so save the params set for comparison
        this.lastSmTermId = initialTermId;
        this.lastSmTimestamp = 0;

        final int termCapacity = rebuilders[0].capacity();

        // how big of a window to advertise to the sender
        this.currentWindowSize = Math.min(termCapacity, initialWindowSize);

        // trip of sending an SM as messages come in
        this.currentGain = Math.min(currentWindowSize / 4, termCapacity / 4);

        this.positionBitsToShift = Integer.numberOfTrailingZeros(termCapacity);
        this.initialTermId = initialTermId;
        this.lastSmPosition = TermHelper.calculatePosition(initialTermId, 0, positionBitsToShift, initialTermId);
    }

    public ReceiveChannelEndpoint receiveChannelEndpoint()
    {
        return receiveChannelEndpoint;
    }

    public int sessionId()
    {
        return sessionId;
    }

    public int streamId()
    {
        return streamId;
    }

    /**
     * Return status of the connection. Retrieved by {@link DriverConductor}.
     *
     * @return status of the connection
     */
    public int status()
    {
        return status;
    }

    /**
     * Set status of the connection. Set by {@link DriverConductor}.
     *
     * @param status of the connection
     */
    public void status(final int status)
    {
        this.status = status;
    }

    /**
     * Return time of last status change. Retrieved by {@link DriverConductor}.
     *
     * @return time of last status change
     */
    public long timeOfLastStatusChange()
    {
        return timeOfLastStatusChange;
    }

    /**
     * Set time of last status change. Set by {@link DriverConductor}.
     *
     * @param now timestamp to use for time
     */
    public void timeOfLastStatusChange(final long now)
    {
        timeOfLastStatusChange = now;
    }

    /** {@inheritDoc} */
    public void close()
    {
        contiguousReceivedPosition.close();
        highestReceivedPosition.close();
        termBuffers.close();
        subscriberPosition.close();
    }

    /**
     * Called from the {@link DriverConductor}.
     *
     * @return if work has been done or not
     */
    public int cleanLogBuffer()
    {
        for (final LogBuffer logBuffer : rebuilders)
        {
            if (logBuffer.status() == NEEDS_CLEANING && logBuffer.compareAndSetStatus(NEEDS_CLEANING, IN_CLEANING))
            {
                logBuffer.clean();

                return 1;
            }
        }

        return 0;
    }

    /**
     * Called from the {@link DriverConductor}.
     *
     * @return if work has been done or not
     */
    public int scanForGaps()
    {
        return lossHandler.scan();
    }

    /**
     * Called from the {@link DriverConductor} to determine what is remaining for the subscriber to drain.
     *
     * @return remaining bytes to drain
     */
    public long remaining()
    {
        // TODO: needs to account for multiple subscriberPosition values (multiple subscribers) when needed
        final long contiguousReceivedPosition = lossHandler.tailPosition();
        return Math.max(contiguousReceivedPosition - subscriberPosition.position(), 0);
    }

    /**
     * Insert frame into term buffer.
     *
     * @param header for the data frame
     * @param buffer for the data frame
     * @param length of the data frame on the wire
     */
    public void insertIntoTerm(final DataHeaderFlyweight header, final AtomicBuffer buffer, final int length)
    {
        final LogRebuilder currentRebuilder = rebuilders[activeIndex];
        final int termId = header.termId();
        final int activeTermId = this.activeTermId.get();

        final int packetTail = header.termOffset();
        final long packetPosition = calculatePosition(termId, packetTail);
        final long currentPosition = position(currentRebuilder.tail());
        final long proposedPosition = packetPosition + length;

        if (isFlowControlUnderRun(packetPosition, currentPosition) || isFlowControlOverRun(proposedPosition))
        {
            return;
        }

        if (termId == activeTermId)
        {
            currentRebuilder.insert(buffer, 0, length);
            contiguousReceivedPosition.position(lossHandler.tailPosition());

            if (currentRebuilder.isComplete())
            {
                activeIndex = hwmIndex = prepareForRotation();
                this.activeTermId.lazySet(activeTermId + 1);
            }
        }
        else if (termId == (activeTermId + 1))
        {
            if (termId != hwmTermId)
            {
                hwmIndex = prepareForRotation();
                hwmTermId = termId;
            }

            rebuilders[hwmIndex].insert(buffer, 0, length);
        }

        highestPositionCandidate(proposedPosition);
    }

    /**
     * Inform the loss handler that a potentially new high position in the stream has been reached.
     *
     * @param termId of the proposed position
     * @param termOffset of the proposed position
     */
    public void highestPositionCandidate(final int termId, final int termOffset)
    {
        highestPositionCandidate(calculatePosition(termId, termOffset));
    }

    /**
     * Inform the loss handler that a potentially new high position in the stream has been reached.
     *
     * @param proposedPosition for the new candidate high position.
     */
    private void highestPositionCandidate(final long proposedPosition)
    {
        timeOfLastFrame.lazySet(clock.getAsLong());
        highestReceivedPosition.position(lossHandler.highestPositionCandidate(proposedPosition));
    }

    /**
     * Called from the {@link DriverConductor}.
     *
     * @param now time in nanoseconds
     * @return number of work items processed.
     */
    public int sendPendingStatusMessages(final long now)
    {
        // not able to send yet because not added to dispatcher, anything received will be dropped (in progress)
        if (!statusMessagesEnabled)
        {
            return 0;
        }

        /* General approach is to check subscriber position and see if it has moved enough to warrant sending an SM.
         * - send SM when termId has moved (i.e. buffer rotation)
         * - send SM when subscriber position has moved more than the gain (min of term or window)
         * - send SM when haven't sent an SM in status message timeout
         */

        final long position = subscriberPosition.position();
        final int currentSmTermId = TermHelper.calculateTermIdFromPosition(position, positionBitsToShift, initialTermId);
        final int currentSmTail = TermHelper.calculateTermOffsetFromPosition(position, positionBitsToShift);

        if (0 == lastSmTimestamp ||  currentSmTermId != lastSmTermId ||
            (position - lastSmPosition) > currentGain || (lastSmTimestamp + statusMessageTimeout) < now)
        {
            return sendStatusMessage(currentSmTermId, currentSmTail, position, currentWindowSize, now);
        }

        // invert the work count logic. We want to appear to be less busy once we send an SM
        return 1;
    }

    /**
     * Called from the {@link Receiver} thread once added to dispatcher
     */
    public void enableStatusMessages()
    {
        statusMessagesEnabled = true;
    }

    /**
     * Called from the {@link Receiver} thread once removed from dispatcher to stop sending SMs
     */
    public void disableStatusMessages()
    {
        statusMessagesEnabled = false;
    }

    /**
     * Called from the {@link DriverConductor} thread to grab the time of the last frame for liveness
     * @return time of last frame from the source
     */
    public long timeOfLastFrame()
    {
        return timeOfLastFrame.get();
    }

    private int sendStatusMessage(final int termId,
                                  final int termOffset,
                                  final long position,
                                  final int windowSize,
                                  final long now)
    {
        statusMessageSender.send(termId, termOffset, windowSize);

        systemCounters.statusMessagesSent().orderedIncrement();
        lastSmTermId = termId;
        lastSmTimestamp = now;
        lastSmPosition = position;

        return 0;
    }

    private long position(final int currentTail)
    {
        return calculatePosition(activeTermId.get(), currentTail);
    }

    private long calculatePosition(final int termId, final int tail)
    {
        return TermHelper.calculatePosition(termId, tail, positionBitsToShift, initialTermId);
    }

    private boolean isFlowControlUnderRun(final long packetPosition, final long position)
    {
        final boolean isFlowControlUnderRun = packetPosition < position;

        if (isFlowControlUnderRun)
        {
            systemCounters.flowControlUnderRuns().orderedIncrement();
        }

        return isFlowControlUnderRun;
    }

    private boolean isFlowControlOverRun(final long proposedPosition)
    {
        final boolean isFlowControlOverRun = proposedPosition > (subscriberPosition.position() + currentWindowSize);

        if (isFlowControlOverRun)
        {
            logger.log(EventCode.FLOW_CONTROL_OVERRUN,
                       "overrun %x > %x + %d", proposedPosition, subscriberPosition.position(), currentWindowSize);
            systemCounters.flowControlOverRuns().orderedIncrement();
        }

        return isFlowControlOverRun;
    }

    private int prepareForRotation()
    {
        final int nextIndex = TermHelper.rotateNext(activeIndex);
        final LogRebuilder rebuilder = rebuilders[nextIndex];

        if (nextIndex != hwmIndex)
        {
            if (!ensureClean(rebuilder))
            {
                systemCounters.subscriptionCleaningLate().orderedIncrement();
            }
        }

        rebuilders[rotatePrevious(activeIndex)].statusOrdered(NEEDS_CLEANING);

        return nextIndex;
    }
}
