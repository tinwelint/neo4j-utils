/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

public abstract class NodeQueueWorker extends Thread
{
    private final GraphDatabaseService graphDb;
    private final NodeQueue queue;
    private boolean halted;
    private boolean requestedToPause;
    private boolean paused;
    private int batchSize;
    
    public NodeQueueWorker( GraphDatabaseService graphDb, NodeQueue queue, int batchSize,
        String name )
    {
        super( name );
        this.graphDb = graphDb;
        this.queue = queue;
        this.batchSize = batchSize;
    }
    
    public NodeQueueWorker( GraphDatabaseService graphDb, NodeQueue queue, int batchSize )
    {
        this( graphDb, queue, batchSize, "NodeQueueWorker" );
    }
    
    public NodeQueue getQueue()
    {
        return this.queue;
    }
    
    public void setPaused( boolean paused )
    {
        if ( this.paused == paused )
        {
            return;
        }
        
        if ( paused && this.requestedToPause )
        {
            waitUntilReallyPaused();
            return;
        }
        
        this.requestedToPause = paused;
        if ( paused )
        {
            waitUntilReallyPaused();
        }
        else
        {
            this.paused = false;
        }
    }
    
    private void waitUntilReallyPaused()
    {
        while ( !this.paused )
        {
            try
            {
                Thread.sleep( 100 );
            }
            catch ( InterruptedException e )
            { // OK
            }
        }
    }

    public boolean isPaused()
    {
        return this.paused;
    }
    
    private void sleepQuiet( long millis )
    {
        try
        {
            Thread.sleep( millis );
        }
        catch ( InterruptedException e )
        {
            // Ok
        }
    }
    
    @Override
    public void run()
    {
        while ( !this.halted )
        {
            if ( this.requestedToPause || this.paused )
            {
                this.paused = true;
                this.requestedToPause = false;
                sleepQuiet( 1000 );
                continue;
            }
            
            if ( !executeOneBatch() )
            {
                sleepQuiet( 100 );
            }
        }
    }
    
    public void add( Map<String, Object> values )
    {
        Node entry = this.queue.add();
        for ( Map.Entry<String, Object> value : values.entrySet() )
        {
            entry.setProperty( value.getKey(), value.getValue() );
        }
    }
    
    protected void beforeBatch()
    {
    }
    
    protected void afterBatch()
    {
    }
    
    private boolean executeOneBatch()
    {
        int entrySize = 0;
        Collection<Map<String, Object>> entries = null;
        Transaction tx = graphDb.beginTx();
        try
        {
            Node[] nodes = this.queue.peek( batchSize );
            if ( nodes.length == 0 )
            {
                return false;
            }
            entrySize = nodes.length;
            
            entries = new ArrayList<Map<String,Object>>( entrySize );
            for ( Node node : nodes )
            {
                entries.add( readNode( node ) );
            }

            beforeBatch();
            try
            {
                for ( Map<String, Object> entry : entries )
                {
                    doOne( entry );
                }
                
                final int size = entrySize;
                new DeadlockCapsule<Object>( "remover" )
                {
                    @Override
                    public Object tryOnce()
                    {
                        queue.remove( size );
                        return null;
                    }
                }.run();
            }
            catch ( Exception e )
            {
                // We got an exception, just do nothing and the tx will roll
                // back so that we can try next time instead.
            }
            finally
            {
                afterBatch();
            }
            
            tx.success();
        }
        finally
        {
            tx.finish();
        }
        return true;
    }
    
    private Map<String, Object> readNode( Node node )
    {
        Map<String, Object> result = new HashMap<String, Object>();
        for ( String key : node.getPropertyKeys() )
        {
            result.put( key, node.getProperty( key ) );
        }
        return result;
    }

    private void doOne( Map<String, Object> entry ) throws Exception
    {
        // Try a max of ten times if it fails.
        Exception exception = null;
        for ( int i = 0; !this.halted && i < 10; i++ )
        {
            try
            {
                doHandleEntry( entry );
                return;
            }
            catch ( Exception e )
            {
                exception = e;
            }
            sleepQuiet( 500 );
        }
        handleEntryError( entry, exception );
    }
    
    protected void handleEntryError( Map<String, Object> entry,
        Exception exception ) throws Exception
    {
        // Add it to the end of the queue
        add( entry );
    }
    
    private void doHandleEntry( Map<String, Object> entry )
    {
        handleEntry( entry );
    }
    
    protected abstract void handleEntry( Map<String, Object> entry );
    
    public void startUp()
    {
        this.start();
    }

    public void shutDown()
    {
        this.halted = true;
        while ( this.isAlive() )
        {
            sleepQuiet( 200 );
        }
    }
}
