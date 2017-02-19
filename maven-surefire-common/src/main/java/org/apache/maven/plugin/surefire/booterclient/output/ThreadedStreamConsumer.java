package org.apache.maven.plugin.surefire.booterclient.output;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.shared.utils.cli.StreamConsumer;
import org.apache.maven.surefire.util.internal.DaemonThreadFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static java.lang.Thread.currentThread;

/**
 * Knows how to reconstruct *all* the state transmitted over stdout by the forked process.
 *
 * @author Kristian Rosenvold
 */
public final class ThreadedStreamConsumer
        implements StreamConsumer, Closeable
{
    private static final String END_ITEM = "";

    private static final int ITEM_LIMIT_BEFORE_SLEEP = 10 * 1000;

    private static final long CLOSE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final BlockingQueue<String> items = new ArrayBlockingQueue<String>( ITEM_LIMIT_BEFORE_SLEEP );

    private final Thread thread;

    private final Pumper pumper;

    private volatile boolean stop;

    final class Pumper
            implements Runnable
    {
        private final StreamConsumer target;

        private final MultipleFailureException errors = new MultipleFailureException();

        Pumper( StreamConsumer target )
        {
            this.target = target;
        }

        /**
         * Calls {@link ForkClient#consumeLine(String)} which may throw any {@link RuntimeException}.<p/>
         * Even if {@link ForkClient} is not fault-tolerant, this method MUST be fault-tolerant and thus the
         * try-catch block must be inside of the loop which prevents from loosing events from {@link StreamConsumer}.
         * <p/>
         * If {@link org.apache.maven.plugin.surefire.report.ConsoleOutputFileReporter#writeTestOutput} throws
         * {@link java.io.IOException} and then <em>target.consumeLine()</em> throws any RuntimeException, this method
         * MUST NOT skip reading the events from the forked JVM; otherwise we could simply lost events
         * e.g. acquire-next-test which means that {@link ForkClient} could hang on waiting for old test to complete
         * and therefore the plugin could be permanently in progress.
         */
        public void run()
        {
            while ( !ThreadedStreamConsumer.this.stop )
            {
                try
                {
                    String item = ThreadedStreamConsumer.this.items.take();
                    System.out.println( ThreadedStreamConsumer.class.getSimpleName()
                                                + "#" + ThreadedStreamConsumer.this.hashCode()
                                                + " run() :: items :: "
                                                + item );
                    if ( shouldStopQueueing( item ) )
                    {
                        break;
                    }
                    target.consumeLine( item );
                }
                catch ( Throwable t )
                {
                    errors.addException( t );
                }
            }
        }

        boolean hasErrors()
        {
            return errors.hasNestedExceptions();
        }

        void throwErrors() throws IOException
        {
            throw errors;
        }
    }

    public ThreadedStreamConsumer( StreamConsumer target )
    {
        pumper = new Pumper( target );
        thread = DaemonThreadFactory.newDaemonThread( pumper, "ThreadedStreamConsumer" );
        thread.start();
    }

    public void consumeLine( String s )
    {
        if ( stop && !thread.isAlive() )
        {
            System.out.println( getClass().getSimpleName() + "#" + hashCode()
                                        + " consumeLine() :: items.clear()." );
            items.clear();
            return;
        }

        try
        {
            System.out.println( getClass().getSimpleName() + "#" + hashCode()
                                        + " consumeLine() :: items.put( s ) :: " + s );
            items.put( s );
        }
        catch ( InterruptedException e )
        {
            currentThread().interrupt();
            throw new IllegalStateException( e );
        }
    }

    public void close()
            throws IOException
    {
        System.out.println( getClass().getSimpleName() + "#" + hashCode()
                                    + " close()" );
        if ( stop )
        {
            return;
        }

        try
        {
            System.out.println( getClass().getSimpleName() + "#" + hashCode()
                                        + " close() :: END_ITEM" );
            items.put( END_ITEM );
            thread.join( CLOSE_TIMEOUT_MILLIS );
        }
        catch ( InterruptedException e )
        {
            currentThread().interrupt();
            throw new IOException( e );
        }
        finally
        {
            stop = true;
        }

        if ( pumper.hasErrors() )
        {
            pumper.throwErrors();
        }
    }

    /**
     * Compared item with {@link #END_ITEM} by identity.
     *
     * @param item    element from <code>items</code>
     * @return <tt>true</tt> if tail of the queue
     */
    private boolean shouldStopQueueing( String item )
    {
        return item == END_ITEM;
    }
}
