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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

/**
 * Wraps several {@link NodeQueue} instances (per transaction).
 * See {@link TransactionNodeQueueWorker} for usage.
 * @author mattias
 */
public class TransactionNodeQueue
{
	public static enum QueueRelTypes implements RelationshipType
	{
		UPDATE_QUEUE,
		INTERNAL_QUEUE,
	}
	
	private static final String INDEX_TX_ID = "txid";
	
	private static Map<Integer, TxQueue> queueNodes =
		Collections.synchronizedMap( new HashMap<Integer, TxQueue>() );
	
	private final Node rootNode;
	
	public TransactionNodeQueue( Node rootNode )
	{
		this.rootNode = rootNode;
		initialize();
	}
	
	private void initialize()
	{
		Collection<Relationship> toDelete = new ArrayList<Relationship>();
		for ( Relationship rel : getRefNode().getRelationships(
			QueueRelTypes.UPDATE_QUEUE, Direction.OUTGOING ) )
		{
			TxQueue queue = new TxQueue( rel.getEndNode() );
			if ( queue.peek() != null )
			{
				queueNodes.put( queue.getTxId(), queue );
			}
			else
			{
				toDelete.add( rel );
			}
		}

		for ( Relationship rel : toDelete )
		{
			Node node = rel.getEndNode();
			rel.delete();
			node.delete();
		}
	}
	
	public void add( int txId, Map<String, Object> values )
	{
		// We must be in a transaction, else the calling code isn't right
		TxQueue queue = findQueue( txId, true );
		queue.add( values );
	}
	
	private void remove( TxQueue queue )
	{
		int txId = queue.getTxId();
		queueNodes.remove( txId );
	}
	
	protected Node getRefNode()
	{
		return this.rootNode;
	}
	
	private TxQueue findQueue( int txId, boolean allowCreate )
	{
		TxQueue queue = queueNodes.get( txId );
		if ( queue != null )
		{
			return queue;
		}
		
		if ( allowCreate )
		{
			Node queueNode = rootNode.getGraphDatabase().createNode();
			queueNode.setProperty( INDEX_TX_ID, txId );
			getRefNode().createRelationshipTo( queueNode,
				QueueRelTypes.UPDATE_QUEUE );
			queue = new TxQueue( queueNode );
			queueNodes.put( txId, queue );
			return queue;
		}
		return null;
	}
	
	public Map<Integer, TxQueue> getQueues()
	{
		Map<Integer, TxQueue> map = new HashMap<Integer, TxQueue>();
		Map.Entry<Integer, TxQueue>[] entries = queueNodes.entrySet().
			toArray( new Map.Entry[ queueNodes.size() ] );
		for ( Map.Entry<Integer, TxQueue> entry : entries )
		{
			TxQueue queue = entry.getValue();
			try
			{
				if ( queue.peek() != null )
				{
					map.put( queue.getTxId(), queue );
				}
			}
			catch ( NotFoundException e )
			{
				// Since queueNodes is a cache which is filled inside
				// transactions it may be the case that a queue is created
				// and then the transaction is rolled back... then the
				// queueNodes map would still have a queue instance for
				// the node which was created in the transaction, but
				// not committed.
				queueNodes.remove( entry.getKey() );
			}
		}
		return Collections.unmodifiableMap( map );
	}
	
	public class TxQueue
	{
		private final NodeQueue queue;
		private final Node node;
		private boolean deleted;
		
		public TxQueue( Node rootNode )
		{
			queue = new NodeQueue( rootNode, QueueRelTypes.INTERNAL_QUEUE );
			node = rootNode;
		}
		
		Node getRootNode()
		{
			return node;
		}
		
		public int getTxId()
		{
			return ( Integer ) node.getProperty( INDEX_TX_ID );
		}
		
		private void add( Map<String, Object> values )
		{
			Node node = queue.add();
			for ( Map.Entry<String, Object> entry : values.entrySet() )
			{
				node.setProperty( entry.getKey(), entry.getValue() );
			}
		}
		
		public Map<String, Object> peek()
		{
		    Collection<Map<String, Object>> result = peek( 1 );
		    return result.isEmpty() ? null : result.iterator().next();
		}
		
		public Collection<Map<String, Object>> peek( int max )
		{
            if ( deleted )
            {
                return null;
            }
            
            Collection<Map<String, Object>> result =
                new ArrayList<Map<String,Object>>( max );
            Node[] nodes = queue.peek( max );
            for ( Node node : nodes )
            {
                result.add( readEntry( node ) );
            }
            return result;
		}
		
		private Map<String, Object> readEntry( Node node )
		{
			Map<String, Object> result = new HashMap<String, Object>();
			for ( String key : node.getPropertyKeys() )
			{
				result.put( key, node.getProperty( key ) );
			}
			return result;
		}
		
		public void remove()
		{
		    remove( 1 );
		}
		
		public void remove( int max )
		{
			if ( deleted )
			{
				throw new IllegalStateException( "Deleted" );
			}
			
			queue.remove( max );
			if ( queue.peek() == null )
			{
				TransactionNodeQueue.this.remove( this );
				deleted = true;
			}
		}
	}
}
