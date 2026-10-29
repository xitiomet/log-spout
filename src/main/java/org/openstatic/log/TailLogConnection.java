package org.openstatic.log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONObject;

public class TailLogConnection implements LogConnection
{
    private final JSONObject config;
    private final String file;
    private final long createdAt;
    private final CopyOnWriteArrayList<LogConnectionListener> listeners;
    private volatile Thread thread;

    public TailLogConnection(JSONObject config)
    {
        this.config = config;
        this.file = LogConnectionParser.replaceVariables(config.getString("_file"), config);
        if (this.file.isEmpty())
            throw new IllegalArgumentException("A tail source requires a non-empty file");
        this.createdAt = System.currentTimeMillis();
        this.listeners = new CopyOnWriteArrayList<LogConnectionListener>();
    }

    @Override
    public void addLogConnectionListener(LogConnectionListener listener)
    {
        this.listeners.addIfAbsent(listener);
    }

    @Override
    public void removeLogConnectionListener(LogConnectionListener listener)
    {
        this.listeners.remove(listener);
    }

    @Override
    public synchronized void connect()
    {
        if (this.thread != null && this.thread.isAlive())
            return;
        this.thread = new Thread(this::follow, "tail-" + this.file);
        this.thread.start();
    }

    @Override
    public void disconnect()
    {
        Thread previous;
        synchronized (this)
        {
            previous = this.thread;
            this.thread = null;
            if (previous != null)
                previous.interrupt();
        }
        if (previous != null && previous != Thread.currentThread())
        {
            try
            {
                previous.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                e.printStackTrace(System.err);
            }
        }
    }

    private boolean isActive()
    {
        return this.thread == Thread.currentThread() && !Thread.currentThread().isInterrupted();
    }

    private void follow()
    {
        try (RandomAccessFile input = new RandomAccessFile(this.file, "r"))
        {
            seekLastLines(input);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FollowingInputStream(input))))
            {
                while (isActive())
                {
                    String line = reader.readLine();
                    if (line == null || !isActive())
                        break;
                    String formatted = LogLineFormatter.format(line, this.config);
                    for (LogConnectionListener listener : this.listeners)
                    {
                        ArrayList<String> logPath = new ArrayList<String>();
                        logPath.add(getName());
                        try
                        {
                            listener.onLine(formatted, logPath, this);
                        } catch (RuntimeException e) {
                            e.printStackTrace(System.err);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            boolean notify;
            synchronized (this)
            {
                notify = isActive();
                if (notify)
                    this.thread = null;
            }
            if (notify)
            {
                e.printStackTrace(System.err);
                for (LogConnectionListener listener : this.listeners)
                {
                    try
                    {
                        listener.onLogDisconnectError(this, e.toString(), e);
                    } catch (RuntimeException listenerError) {
                        listenerError.printStackTrace(System.err);
                    }
                }
            }
        } finally {
            synchronized (this)
            {
                if (this.thread == Thread.currentThread())
                    this.thread = null;
            }
        }
    }

    private void seekLastLines(RandomAccessFile input) throws IOException
    {
        long end = input.length();
        long position = end;
        int newlines = 0;
        byte[] buffer = new byte[8192];
        while (position > 0 && isActive())
        {
            int length = (int) Math.min(buffer.length, position);
            position -= length;
            input.seek(position);
            input.readFully(buffer, 0, length);
            for (int i = length - 1; i >= 0; i--)
            {
                if (buffer[i] == '\n' && position + i != end - 1 && ++newlines == 10)
                {
                    input.seek(position + i + 1);
                    return;
                }
            }
        }
        input.seek(0);
    }

    private class FollowingInputStream extends InputStream
    {
        private final RandomAccessFile input;

        FollowingInputStream(RandomAccessFile input)
        {
            this.input = input;
        }

        @Override
        public int read() throws IOException
        {
            byte[] single = new byte[1];
            read(single, 0, 1);
            return single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException
        {
            if (length == 0)
                return 0;
            while (isActive())
            {
                if (this.input.length() < this.input.getFilePointer())
                    this.input.seek(0);
                int count = this.input.read(buffer, offset, length);
                if (count != -1)
                    return count;
                try
                {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Tail disconnected");
                }
            }
            throw new InterruptedIOException("Tail disconnected");
        }
    }

    @Override
    public void start()
    {
    }

    @Override
    public String getName()
    {
        return LogConnectionParser.replaceVariables(this.config.optString("_name", this.file), this.config);
    }

    @Override
    public Collection<String> getContainedNames()
    {
        return Collections.emptyList();
    }

    @Override
    public String getType()
    {
        return "tail";
    }

    @Override
    public boolean isConnected()
    {
        Thread current = this.thread;
        return current != null && current.isAlive();
    }

    @Override
    public long getAgeMillis()
    {
        return System.currentTimeMillis() - this.createdAt;
    }
}
