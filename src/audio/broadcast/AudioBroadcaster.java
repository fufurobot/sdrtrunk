/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2016 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 ******************************************************************************/
package audio.broadcast;

import audio.AudioPacket;
import audio.IAudioPacketListener;
import audio.metadata.AudioMetadata;
import controller.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import properties.SystemProperties;
import sample.Listener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AudioBroadcaster implements IAudioPacketListener
{
    private final static Logger mLog = LoggerFactory.getLogger( AudioBroadcaster.class );

    public static final int PROCESSOR_RUN_INTERVAL_MS = 1000;

    private ThreadPoolManager mThreadPoolManager;
    private ScheduledFuture mScheduledTask;

    private RecordingQueueProcessor mRecordingQueueProcessor = new RecordingQueueProcessor();
    private LinkedTransferQueue<StreamableAudioRecording> mAudioRecordingQueue = new LinkedTransferQueue<>();

    private byte[] mSilenceFrame;

    private Listener<BroadcastEvent> mBroadcastEventListener;
    private BroadcastState mBroadcastState = BroadcastState.READY;

    private StreamManager mStreamManager;
    private int mStreamedAudioCount = 0;
    private BroadcastConfiguration mBroadcastConfiguration;
    private long mDelay;
    private AtomicBoolean mStreaming = new AtomicBoolean();

    /**
     * AudioBroadcaster for streaming audio recordings to a remote streaming audio server.  Audio recordings are
     * generated by an internal StreamManager that converts an inbound stream of AudioPackets into a recording of the
     * desired audio format (e.g. MP3) and nominates the recording to an internal recording queue for streaming.  The
     * broadcaster supports receiving audio packets from multiple audio sources.  Each audio packet's internal audio
     * metadata source string is used to reassemble each packet stream.  Recordings are capped at 30 seconds length.
     * If a source audio packet stream exceeds 30 seconds in length, it will be chunked into 30 second recordings.
     *
     * This broadcaster supports a time delay setting for delaying broadcast of audio recordings.  The delay setting is
     * defined in the broadcast configuration.  When this delay is greater than zero, the recording will remain in the
     * audio broadcaster queue until the recording start time + delay elapses.  Audio recordings are processed in a FIFO
     * manner.
     *
     * Use the start() and stop() methods to connect to/disconnect from the remote server.  Audio recordings will be
     * streamed to the remote server when available.  One second silence frames will be broadcast to the server when
     * there are no recordings available, in order to maintain a connection with the remote server.  Any audio packet
     * streams received while the broadcaster is stopped will be ignored.
     *
     * The last audio packet's metadata is automatically attached to the closed audio recording when it is enqueued for
     * broadcast.  That metadata will be updated on the remote server once the audio recording is opened for streaming.
     */
    public AudioBroadcaster(ThreadPoolManager threadPoolManager, BroadcastConfiguration broadcastConfiguration)
    {
        mThreadPoolManager = threadPoolManager;
        mBroadcastConfiguration = broadcastConfiguration;
        mDelay = mBroadcastConfiguration.getDelay();

        //Create a 1 second silence frame - from 1200 millis of silence
        mSilenceFrame = BroadcastFactory.getSilenceFrame(getBroadcastConfiguration().getBroadcastFormat(), 1200);

        mStreamManager = new StreamManager(threadPoolManager, this,
                SystemProperties.getInstance().getApplicationFolder(BroadcastModel.TEMPORARY_STREAM_DIRECTORY));
    }

    /**
     * Connects to the remote server specified by the broadcast configuration and starts audio streaming.
     */
    public void start()
    {
        if(mStreaming.compareAndSet(false, true))
        {
            mStreamManager.start();

            if(mScheduledTask == null)
            {
                if(mThreadPoolManager != null)
                {
                    mScheduledTask = mThreadPoolManager.scheduleFixedRate(ThreadPoolManager.ThreadType.AUDIO_PROCESSING,
                            mRecordingQueueProcessor, PROCESSOR_RUN_INTERVAL_MS, TimeUnit.MILLISECONDS );
                }
            }
        }
    }

    /**
     * Disconnects from the remote server.
     */
    public void stop()
    {
        if(mStreaming.compareAndSet(true, false))
        {
            mStreamManager.stop();

            if(mThreadPoolManager != null && mScheduledTask != null)
            {
                mThreadPoolManager.cancel(mScheduledTask);
            }

            disconnect();
        }
    }

    /**
     * Disconnects the broadcaster from the remote server for a reset or final stop.
     */
    protected abstract void disconnect();

    /**
     * Size of recording queue for recordings awaiting streaming
     */
    public int getQueueSize()
    {
        return mAudioRecordingQueue.size();
    }

    /**
     * Number of audio recordings streamed to remote server
     */
    public int getStreamedAudioCount()
    {
        return mStreamedAudioCount;
    }

    /**
     * Primary insert method for the stream manager to nominate completed audio recordings for broadcast.
     *
     * @param recording to queue for broadcasting
     */
    public void receive(StreamableAudioRecording recording)
    {
        mAudioRecordingQueue.add(recording);
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
    }

    /**
     * Cleanup method to remove a temporary recording file from disk.
     *
     * @param recording to remove
     */
    private void removeRecording(StreamableAudioRecording recording)
    {
        try
        {
            Files.delete(recording.getPath());
        }
        catch(IOException ioe)
        {
            mLog.error("Error deleting temporary internet recording file: " + recording.getPath().toString());
        }
    }

    /**
     * Broadcast configuration used by this broadcaster
     */
    public BroadcastConfiguration getBroadcastConfiguration()
    {
        return mBroadcastConfiguration;
    }

    /**
     * IAudioPacketListener interface method to gain access to the internal stream manager for enqueuing audio packets.
     */
    @Override
    public Listener<AudioPacket> getAudioPacketListener()
    {
        return mStreamManager;
    }

    /**
     * Broadcast binary audio data frames or sequences.
     */
    protected abstract void broadcastAudio(byte[] audio);

    /**
     * Broadcasts the next song's audio metadata prior to streaming the next song.
     * @param metadata
     */
    protected abstract void broadcastMetadata(AudioMetadata metadata);

    /**
     * Registers the listener to receive broadcastAudio state changes
     */
    public void setListener(Listener<BroadcastEvent> listener)
    {
        mBroadcastEventListener = listener;
    }

    /**
     * Removes the listener from receiving broadcastAudio state changes
     */
    public void removeListener()
    {
        mBroadcastEventListener = null;
    }

    /**
     * Broadcasts the event to any registered listener
     */
    public void broadcast(BroadcastEvent event)
    {
        if(mBroadcastEventListener != null)
        {
            mBroadcastEventListener.receive(event);
        }
    }

    /**
     * Sets the state of the broadcastAudio connection
     */
    protected void setBroadcastState(BroadcastState state)
    {
        if(mBroadcastState != state)
        {
            mLog.debug("Changing State to: " + state);
            mBroadcastState = state;

            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STATE_CHANGE));

            if(mBroadcastState.isErrorState())
            {
                stop();
            }
        }
    }

    /**
     * Current state of the broadcastAudio connection
     */
    public BroadcastState getBroadcastState()
    {
        return mBroadcastState;
    }

    /**
     * Indicates if the broadcaster is currently connected to the remote server
     */
    protected boolean connected()
    {
        return getBroadcastState() == BroadcastState.CONNECTED;
    }

    /**
     * Indicates if this broadcaster can connect and is not currently in an error state or a connected state.
     */
    public boolean canConnect()
    {
        BroadcastState state = getBroadcastState();

        return state != BroadcastState.CONNECTED && !state.isErrorState();
    }

    /**
     * Indicates if the current broadcast state is an error state, meaning that it cannot recover or connect using the
     * current configuration.
     */
    protected boolean isErrorState()
    {
        return getBroadcastState().isErrorState();
    }


    /**
     * Audio recording queue processor.  Fetches recordings from the queue and chunks the recording byte content
     * to subclass implementations for broadcast in the appropriate manner.
     */
    public class RecordingQueueProcessor implements Runnable
    {
        private AtomicBoolean mProcessing = new AtomicBoolean();
        private ByteArrayInputStream mInputStream;
        private int mChunkSize;

        @Override
        public void run()
        {
            if(mProcessing.compareAndSet(false, true))
            {
                if(mInputStream == null || mInputStream.available() <= 0)
                {
                    nextRecording();
                }

                if(mInputStream != null)
                {
                    int length = Math.min(mChunkSize, mInputStream.available());

                    byte[] audio = new byte[length];

                    try
                    {
                        mInputStream.read(audio);

                        broadcastAudio(audio);
                    }
                    catch(IOException ioe)
                    {
                        mLog.error("Error reading from in-memory audio recording input stream", ioe);
                    }
                }
                else
                {
                    broadcastAudio(mSilenceFrame);
                }

                mProcessing.set(false);
            }
        }

        /**
         * Loads the next recording for broadcast
         */
        private void nextRecording()
        {
            boolean metadataUpdateRequired = false;

            if(mInputStream != null)
            {
                mStreamedAudioCount++;
                broadcast(new BroadcastEvent(AudioBroadcaster.this,
                        BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
                metadataUpdateRequired = true;
            }

            mInputStream = null;

            StreamableAudioRecording nextRecording = mAudioRecordingQueue.peek();

            if(nextRecording != null && nextRecording.getStartTime() + mDelay <= System.currentTimeMillis())
            {
                StreamableAudioRecording recording = mAudioRecordingQueue.poll();

                try
                {
                    byte[] audio = Files.readAllBytes(recording.getPath());

                    if(audio != null && audio.length > 0)
                    {
                        mInputStream = new ByteArrayInputStream(audio);

                        int wholeIntervalChunks = (int)(recording.getRecordingLength() / PROCESSOR_RUN_INTERVAL_MS);

                        //Check for divide by zero situation
                        if(wholeIntervalChunks == 0)
                        {
                            wholeIntervalChunks = 1;
                        }

                        mChunkSize = (int)(mInputStream.available() / wholeIntervalChunks) + 1;

                        broadcastMetadata(recording.getAudioMetadata());
                        metadataUpdateRequired = false;
                    }
                }
                catch(IOException ioe)
                {
                    mLog.error("Error reading temporary audio stream recording [" + recording.getPath().toString() +
                            "] - skipping recording");

                    mInputStream = null;
                }

                removeRecording(recording);

                broadcast(new BroadcastEvent(AudioBroadcaster.this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
            }

            //If we closed out a recording and don't have anything new, send an empty metadata update
            if(metadataUpdateRequired)
            {
                broadcastMetadata(null);
            }
        }
    }
}
